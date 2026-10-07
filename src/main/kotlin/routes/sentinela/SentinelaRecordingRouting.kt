package routes.sentinela

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import schemas.sentinela.SentinelaRecordingDetailDto
import schemas.sentinela.SentinelaRecordingOwnerDto
import schemas.sentinela.SentinelaRecordingService
import schemas.sentinela.SentinelaRecordingStatus
import schemas.sentinela.SentinelaRecordingWithLocations
import schemas.sentinela.SentinelaShareService
import schemas.sentinela.SentinelaStoredReport
import schemas.sentinela.SentinelaTranscriptDto
import schemas.sentinela.SentinelaTranscriptStatus
import schemas.sentinela.isReusable
import schemas.sentinela.SentinelaUserService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import services.SentinelaReportPdf
import services.SentinelaS3Uploader
import services.SentinelaTranscriptionService
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

private suspend fun ApplicationCall.respondPdf(bytes: ByteArray) {
    response.header(
        HttpHeaders.ContentDisposition,
        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "sentinela-relatorio.pdf").toString(),
    )
    respondBytes(bytes, ContentType.Application.Pdf)
}

/** Endereço público da API, usado nos links que saem do servidor (QR code do relatório). */
private fun publicBaseUrl(): String =
    (System.getProperty("sentinela.publicBaseUrl") ?: System.getenv("SENTINELA_PUBLIC_BASE_URL"))
        ?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        ?: "https://api.effectiveenglishcourse.com"

/** Por quanto tempo o link público do vídeo continua valendo. */
private val SHARE_LINK_VALIDITY: java.time.Duration = java.time.Duration.ofDays(7)

/**
 * Consulta das gravações do Modo Sentinela. Todas as rotas exigem o ID Token do
 * Firebase e só retornam gravações do próprio usuário ou de quem compartilhou a
 * conta com ele (ver [SentinelaShareService]).
 */
fun Application.sentinelaRecordingRouting(
    recordingService: SentinelaRecordingService,
    shareService: SentinelaShareService,
    userService: SentinelaUserService,
    transcriptionService: SentinelaTranscriptionService,
    reportPdf: SentinelaReportPdf = SentinelaReportPdf(),
) {
    suspend fun ownerInfo(ownerUid: String): SentinelaRecordingOwnerDto? =
        userService.findByUid(ownerUid)?.let { SentinelaRecordingOwnerDto(name = it.name, email = it.email) }

    /** A gravação, se existir e o usuário for o dono ou tiver acesso compartilhado; senão null (404 não revela que existe). */
    suspend fun accessibleRecording(recordingId: String?, uid: String): SentinelaRecordingWithLocations? {
        if (recordingId.isNullOrBlank()) return null
        val recording = recordingService.findById(recordingId) ?: return null
        val allowed = recording.ownerUid == uid ||
            shareService.exists(ownerUid = recording.ownerUid, granteeUid = uid)
        return recording.takeIf { allowed }
    }

    routing {
        route("/sentinela/recordings") {
            get {
                val verifiedUser = call.requireFirebaseUser() ?: return@get

                val own = recordingService.listByUid(verifiedUser.uid)
                val shared = shareService.ownerUidsSharedWith(verifiedUser.uid).flatMap { ownerUid ->
                    val owner = ownerInfo(ownerUid)
                    recordingService.listByUid(ownerUid, viewerIsOwner = false).map { it.copy(sharedBy = owner) }
                }

                // startedAt é ISO-8601 em UTC, então a ordem lexicográfica é a cronológica.
                call.respond(HttpStatusCode.OK, (own + shared).sortedByDescending { it.startedAt })
            }

            get("/{id}") {
                val verifiedUser = call.requireFirebaseUser() ?: return@get
                val recording = accessibleRecording(call.parameters["id"], verifiedUser.uid)
                if (recording == null) {
                    call.respond(HttpStatusCode.NotFound, "Gravação não encontrada")
                    return@get
                }

                val videoUrl = recording.s3Key?.let { key ->
                    runCatching { SentinelaS3Uploader.presignedGetUrl(key) }
                        .onFailure { println("[Sentinela] Falha ao assinar URL da gravação ${recording.summary.id}: ${it.message}") }
                        .getOrNull()
                }

                val isOwner = recording.ownerUid == verifiedUser.uid
                val storedReport = if (isOwner) recording.report else recording.sharedReport
                val summary = if (isOwner) {
                    recording.summary
                } else {
                    recording.summary.copy(sharedBy = ownerInfo(recording.ownerUid), hasReport = storedReport != null)
                }
                call.respond(
                    HttpStatusCode.OK,
                    SentinelaRecordingDetailDto(
                        recording = summary,
                        locations = recording.locations,
                        videoUrl = videoUrl,
                        transcript = recording.transcript,
                        transcriptStatus = recording.transcriptStatus,
                        reportGeneratedAt = storedReport?.generatedAt?.toString(),
                    ),
                )
            }

            // Pede a transcrição: enfileira se ela nunca foi gerada ou falhou; se já está em andamento
            // ou pronta, só devolve o estado atual. O app consulta o GET acima até terminar.
            post("/{id}/transcript") {
                val verifiedUser = call.requireFirebaseUser() ?: return@post
                val recording = accessibleRecording(call.parameters["id"], verifiedUser.uid)
                if (recording == null) {
                    call.respond(HttpStatusCode.NotFound, "Gravação não encontrada")
                    return@post
                }
                val s3Key = recording.s3Key
                if (s3Key == null) {
                    call.respond(HttpStatusCode.Conflict, "Esta gravação não tem vídeo para transcrever")
                    return@post
                }

                val recordingId = recording.summary.id
                if (recordingService.requestTranscription(recordingId)) {
                    transcriptionService.enqueue(recordingId, s3Key)
                    call.respond(HttpStatusCode.OK, SentinelaTranscriptDto(status = SentinelaTranscriptStatus.PENDING.name, transcript = null))
                    return@post
                }
                val current = recordingService.findById(recordingId) ?: recording
                call.respond(HttpStatusCode.OK, SentinelaTranscriptDto(status = current.transcriptStatus, transcript = current.transcript))
            }

            // O dono gera (ou recupera) o link público do vídeo para compartilhar com quem não tem conta.
            post("/{id}/share-link") {
                val verifiedUser = call.requireFirebaseUser() ?: return@post
                val recording = accessibleRecording(call.parameters["id"], verifiedUser.uid)
                if (recording == null) {
                    call.respond(HttpStatusCode.NotFound, "Gravação não encontrada")
                    return@post
                }
                if (recording.ownerUid != verifiedUser.uid) {
                    call.respond(HttpStatusCode.Forbidden, "Só o dono da gravação pode gerar o link do vídeo")
                    return@post
                }
                if (recording.s3Key == null) {
                    call.respond(HttpStatusCode.Conflict, "Esta gravação não tem vídeo para compartilhar")
                    return@post
                }
                call.respond(
                    HttpStatusCode.OK,
                    recordingService.ensureShareLink(recording.summary.id, SHARE_LINK_VALIDITY),
                )
            }

            // Relatório em PDF da gravação, guardado no S3 e reaproveitado nas próximas vezes. O QR code aponta para
            // o link público do vídeo, que só o dono pode gerar; por isso quem vê a gravação por compartilhamento de
            // conta tem um relatório guardado à parte, sem QR, em vez de receber o do dono.
            get("/{id}/report.pdf") {
                val verifiedUser = call.requireFirebaseUser() ?: return@get
                val recording = accessibleRecording(call.parameters["id"], verifiedUser.uid)
                if (recording == null) {
                    call.respond(HttpStatusCode.NotFound, "Gravação não encontrada")
                    return@get
                }
                val recordingId = recording.summary.id
                val isOwner = recording.ownerUid == verifiedUser.uid

                // Já existe um relatório desta gravação? Então abre o mesmo, em vez de gerar outro.
                val stored = if (isOwner) recording.report else recording.sharedReport
                if (stored != null && stored.isReusable(transcriptReadyNow = !recording.transcript.isNullOrBlank())) {
                    val cached = runCatching { SentinelaS3Uploader.getObjectBytes(stored.s3Key) }
                        .onFailure { println("[Sentinela] Não foi possível ler o relatório guardado de $recordingId: ${it.message}") }
                        .getOrNull()
                    if (cached != null) {
                        call.respondPdf(cached)
                        return@get
                    }
                }

                val shareLink = if (isOwner && recording.s3Key != null) {
                    recordingService.ensureShareLink(recordingId, SHARE_LINK_VALIDITY)
                } else {
                    null
                }
                // O app manda o deslocamento do fuso do usuário (em minutos) para os horários saírem na hora local dele.
                val zone: ZoneId = call.request.queryParameters["tzOffset"]?.toIntOrNull()
                    ?.takeIf { it in -840..840 }
                    ?.let { ZoneOffset.ofTotalSeconds(it * 60) }
                    ?: ZoneId.of("America/Sao_Paulo")

                val linkExpiresAt = shareLink?.let { Instant.parse(it.expiresAt) }
                val pdf = try {
                    withContext(Dispatchers.IO) {
                        reportPdf.generate(
                            SentinelaReportPdf.Input(
                                recording = recording,
                                ownerName = ownerInfo(recording.ownerUid)?.name,
                                videoLink = shareLink?.let { "${publicBaseUrl()}/sentinela/v/${it.token}" },
                                videoLinkExpiresAt = linkExpiresAt,
                                zone = zone,
                            ),
                        )
                    }
                } catch (e: Exception) {
                    println("[Sentinela] Falha ao gerar o PDF da gravação $recordingId: ${e.message}")
                    call.respond(HttpStatusCode.InternalServerError, "Não foi possível gerar o PDF")
                    return@get
                }

                // Gravação ainda em andamento gera um relatório parcial: não guarda, para o próximo refletir o final.
                if (recording.summary.status != SentinelaRecordingStatus.RECORDING.name) {
                    runCatching {
                        val suffix = if (isOwner) "relatorio" else "relatorio-compartilhado"
                        val key = "sentinela/${recording.ownerUid}/$recordingId-$suffix.pdf"
                        SentinelaS3Uploader.putObject(key, pdf, "application/pdf")
                        recordingService.saveReport(
                            recordingId,
                            SentinelaStoredReport(
                                s3Key = key,
                                generatedAt = Instant.now(),
                                hasTranscript = !recording.transcript.isNullOrBlank(),
                                linkExpiresAt = linkExpiresAt,
                            ),
                            forOwner = isOwner,
                        )
                    }.onFailure { println("[Sentinela] Não foi possível guardar o relatório de $recordingId: ${it.message}") }
                }
                call.respondPdf(pdf)
            }

            // Só a transcrição (e o andamento dela), sem assinar URL de vídeo nem devolver o trajeto.
            get("/{id}/transcript") {
                val verifiedUser = call.requireFirebaseUser() ?: return@get
                val recording = accessibleRecording(call.parameters["id"], verifiedUser.uid)
                if (recording == null) {
                    call.respond(HttpStatusCode.NotFound, "Gravação não encontrada")
                    return@get
                }
                call.respond(
                    HttpStatusCode.OK,
                    SentinelaTranscriptDto(status = recording.transcriptStatus, transcript = recording.transcript),
                )
            }
        }
    }
}
