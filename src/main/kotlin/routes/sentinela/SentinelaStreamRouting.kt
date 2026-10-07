package routes.sentinela

import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import schemas.sentinela.SentinelaRecordingService
import kotlinx.coroutines.launch
import schemas.sentinela.SentinelaBillingMode
import schemas.sentinela.SentinelaBillingService
import schemas.sentinela.SentinelaPushTokenService
import schemas.sentinela.SentinelaRecordingStatus
import schemas.sentinela.SentinelaShareService
import schemas.sentinela.SentinelaUserService
import services.FirebaseTokenVerifier
import services.LivePoint
import services.SentinelaLiveHub
import services.SentinelaS3Uploader
import services.SentinelaUsageMeter
import services.SentinelaTranscriptionService

private const val START_TIMEOUT_MS = 10_000L
private const val MAX_CHUNK_BYTES = 10 * 1024 * 1024

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.takeIf { it.isString }?.content

private fun JsonObject.double(name: String): Double? =
    this[name]?.jsonPrimitive?.doubleOrNull

private fun parseJson(text: String): JsonObject? =
    runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()

private suspend fun DefaultWebSocketServerSession.sendJson(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
    send(Frame.Text(buildJsonObject(block).toString()))
}

private suspend fun DefaultWebSocketServerSession.fail(message: String, code: String? = null) {
    runCatching {
        sendJson {
            put("type", "error")
            put("message", message)
            code?.let { put("code", it) }
        }
    }
    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, message))
}

/**
 * Streaming do Modo Sentinela. Protocolo:
 * 1. Cliente envia {"type":"start","token":"<Firebase ID Token>","mimeType":"video/webm..."}
 *    — o token vai na mensagem porque o WebSocket do browser não aceita header Authorization.
 * 2. Servidor responde {"type":"ready","recordingId":"..."}.
 * 3. Cliente envia frames binários (chunks do MediaRecorder) e {"type":"location",...}.
 * 4. Cliente envia {"type":"stop"}; servidor fecha o arquivo e responde {"type":"stopped",...}.
 * Se a conexão cair antes do stop, o que já chegou é salvo e a gravação fica INTERRUPTED.
 *
 * Limite de uso: o teste grátis (30 min) e as horas do plano são controlados aqui, no servidor.
 * Sem saldo, o start é recusado com {"type":"error","code":"payment_required"}. Ver [SentinelaUsageMeter]:
 * - passou dos 30 min grátis: a gravação NÃO para; o servidor manda {"type":"limit_warning","cutoffInSeconds":N}
 *   (encerra em N segundos, no teto de 1 hora grátis);
 * - o usuário comprou horas nesse meio tempo: {"type":"plan_active","remainingSeconds":N} e segue sem corte;
 * - o teto (ou as horas do plano) acaba: encerra normalmente com {"type":"stopped","reason":"limit_reached"}.
 */
fun Application.sentinelaStreamRouting(
    recordingService: SentinelaRecordingService,
    shareService: SentinelaShareService,
    userService: SentinelaUserService,
    pushTokenService: SentinelaPushTokenService,
    transcriptionService: SentinelaTranscriptionService,
    billingService: SentinelaBillingService,
) {
    routing {
        webSocket("/ws/sentinela/stream") {
            val startFrame = withTimeoutOrNull(START_TIMEOUT_MS) { incoming.receive() }
            val startMessage = (startFrame as? Frame.Text)?.let { parseJson(it.readText()) }
            if (startMessage?.string("type") != "start") {
                fail("Primeira mensagem deve ser 'start'")
                return@webSocket
            }

            val token = startMessage.string("token")
            if (token.isNullOrBlank()) {
                fail("Token de autenticação ausente")
                return@webSocket
            }
            val verifiedUser = try {
                FirebaseTokenVerifier.verifyIdToken(token)
            } catch (e: IllegalArgumentException) {
                fail(e.message ?: "Token inválido")
                return@webSocket
            }

            val mimeType = startMessage.string("mimeType").orEmpty()
            val extension = when {
                mimeType.startsWith("video/webm") -> "webm"
                mimeType.startsWith("video/mp4") -> "mp4"
                else -> {
                    fail("Formato de vídeo não suportado")
                    return@webSocket
                }
            }

            val availability = billingService.availability(verifiedUser.uid)
            if (!availability.canRecord) {
                fail("Seu tempo de gravação acabou. Escolha um plano para continuar.", code = "payment_required")
                return@webSocket
            }

            val recordingId = recordingService.create(verifiedUser.uid, mimeType)
            val s3Key = "sentinela/${verifiedUser.uid}/$recordingId.$extension"
            val uploader = SentinelaS3Uploader(s3Key, mimeType.substringBefore(';'))
            var chunkCount = 0
            var finalized = false
            var charged = false
            val meter = SentinelaUsageMeter(availability)
            val recordingStartedAt = System.nanoTime()
            fun elapsedSeconds() = (System.nanoTime() - recordingStartedAt) / 1_000_000_000

            // Desconta do saldo (teste grátis ou plano) o tempo gravado; roda uma única vez por gravação.
            suspend fun chargeUsage() {
                if (charged) return
                charged = true
                val (mode, seconds) = meter.pendingCharge(elapsedSeconds()) ?: return
                runCatching { billingService.consume(verifiedUser.uid, mode, seconds) }
                    .onFailure { println("[Sentinela] Falha ao descontar o uso da gravação $recordingId: ${it.message}") }
            }

            // Fecha o arquivo e avisa o cliente; usado no "stop" pedido por ele e no fim do saldo.
            suspend fun completeRecording(reason: String?) {
                val savedKey = uploader.complete()
                recordingService.finalize(
                    recordingId,
                    SentinelaRecordingStatus.COMPLETED,
                    savedKey,
                    uploader.totalBytes,
                    chunkCount,
                )
                finalized = true
                chargeUsage()
                savedKey?.let { transcriptionService.enqueue(recordingId, it) }
                sendJson {
                    put("type", "stopped")
                    put("recordingId", recordingId)
                    put("sizeBytes", uploader.totalBytes)
                    reason?.let { put("reason", it) }
                }
                println("[Sentinela] Gravação $recordingId concluída (${uploader.totalBytes} bytes, $chunkCount chunks${reason?.let { ", $it" }.orEmpty()})")
                close(CloseReason(CloseReason.Codes.NORMAL, "Gravação encerrada"))
            }

            try {
                uploader.start()
                sendJson {
                    put("type", "ready")
                    put("recordingId", recordingId)
                }
                println("[Sentinela] Gravação $recordingId iniciada")

                // Avisa os convidados (push) e abre o canal do mapa ao vivo. Não bloqueia a gravação.
                val ownerName = userService.findByUid(verifiedUser.uid)?.name ?: verifiedUser.name.orEmpty()
                SentinelaLiveHub.start(verifiedUser.uid, recordingId, ownerName)
                launch {
                    runCatching {
                        notifySentinelaActivated(verifiedUser.uid, ownerName, shareService, pushTokenService)
                    }.onFailure { println("[Sentinela] Falha ao notificar convidados: ${it.message}") }
                }

                for (frame in incoming) {
                    when (frame) {
                        is Frame.Binary -> {
                            val bytes = frame.readBytes()
                            if (bytes.size > MAX_CHUNK_BYTES) {
                                fail("Chunk acima do limite de ${MAX_CHUNK_BYTES / 1024 / 1024} MB")
                                break
                            }
                            uploader.write(bytes)
                            chunkCount++
                            when (val decision = meter.tick(elapsedSeconds()) { billingService.availability(verifiedUser.uid) }) {
                                SentinelaUsageMeter.Decision.Continue -> Unit
                                is SentinelaUsageMeter.Decision.Warn -> sendJson {
                                    put("type", "limit_warning")
                                    put("cutoffInSeconds", decision.cutoffInSeconds)
                                }
                                is SentinelaUsageMeter.Decision.SwitchedToPlan -> {
                                    runCatching {
                                        billingService.consume(verifiedUser.uid, SentinelaBillingMode.TRIAL, decision.trialSecondsToCharge)
                                    }.onFailure { println("[Sentinela] Falha ao descontar o teste grátis de ${verifiedUser.uid}: ${it.message}") }
                                    sendJson {
                                        put("type", "plan_active")
                                        put("remainingSeconds", decision.planRemainingSeconds)
                                    }
                                }
                                SentinelaUsageMeter.Decision.Stop -> {
                                    completeRecording("limit_reached")
                                    break
                                }
                            }
                        }

                        is Frame.Text -> {
                            val message = parseJson(frame.readText()) ?: continue
                            when (message.string("type")) {
                                "location" -> {
                                    val lat = message.double("lat")
                                    val lng = message.double("lng")
                                    if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) continue
                                    recordingService.addLocation(
                                        recordingId = recordingId,
                                        lat = lat,
                                        lng = lng,
                                        accuracy = message.double("accuracy"),
                                        speed = message.double("speed"),
                                        capturedAt = message.string("capturedAt") ?: java.time.Instant.now().toString(),
                                    )
                                    SentinelaLiveHub.publishLocation(
                                        ownerUid = verifiedUser.uid,
                                        recordingId = recordingId,
                                        point = LivePoint(
                                            lat = lat,
                                            lng = lng,
                                            accuracy = message.double("accuracy"),
                                            speed = message.double("speed"),
                                            capturedAt = message.string("capturedAt") ?: java.time.Instant.now().toString(),
                                        ),
                                    )
                                }

                                "stop" -> {
                                    completeRecording(null)
                                    break
                                }
                            }
                        }

                        else -> Unit
                    }
                }
            } catch (e: Exception) {
                println("[Sentinela] Erro na gravação $recordingId: ${e.message}")
                runCatching { fail("Falha ao gravar no servidor") }
            } finally {
                // Qualquer fim (stop, queda, erro) encerra o mapa ao vivo dos convidados.
                SentinelaLiveHub.stop(verifiedUser.uid, recordingId)
                if (!finalized) {
                    val savedKey = runCatching { uploader.complete() }
                        .onFailure { runCatching { uploader.abort() } }
                        .getOrNull()
                    runCatching {
                        recordingService.finalize(
                            recordingId,
                            SentinelaRecordingStatus.INTERRUPTED,
                            savedKey,
                            uploader.totalBytes,
                            chunkCount,
                        )
                    }
                    chargeUsage()
                    savedKey?.let { transcriptionService.enqueue(recordingId, it) }
                    println("[Sentinela] Gravação $recordingId interrompida (${uploader.totalBytes} bytes salvos)")
                }
            }
        }
    }
}
