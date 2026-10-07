package routes.sentinela

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import schemas.sentinela.SentinelaPushTokenService
import schemas.sentinela.SentinelaShareService
import schemas.sentinela.SentinelaUserService
import services.FcmResult
import services.FcmSender
import services.FirebaseTokenVerifier
import services.SentinelaLiveHub

private const val WATCH_START_TIMEOUT_MS = 10_000L
private const val MAX_TOKEN_LENGTH = 400

@Serializable
private data class PushTokenRequest(val token: String)

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.takeIf { it.isString }?.content

private suspend fun DefaultWebSocketServerSession.failWatch(message: String) {
    runCatching {
        send(Frame.Text(buildJsonObject {
            put("type", "error")
            put("message", message)
        }.toString()))
    }
    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, message))
}

/**
 * Avisa por push (FCM) cada convidado da conta de [ownerUid] que o Modo Sentinela foi ativado.
 * O clique na notificação abre o app em `/?live=<ownerUid>`, na tela do mapa ao vivo.
 * Tokens que o FCM informa como inexistentes são apagados.
 */
suspend fun notifySentinelaActivated(
    ownerUid: String,
    ownerName: String,
    shareService: SentinelaShareService,
    pushTokenService: SentinelaPushTokenService,
) {
    if (!FcmSender.isConfigured) return

    val displayName = ownerName.ifBlank { "Alguém" }
    val data = mapOf(
        "type" to "sentinela_active",
        "ownerUid" to ownerUid,
        "ownerName" to ownerName,
        "title" to "Modo Sentinela ativo",
        "body" to "$displayName ativou o Modo Sentinela. Toque para ver a localização ao vivo.",
        "url" to "/?live=$ownerUid",
    )

    for (granteeUid in shareService.granteeUidsOf(ownerUid)) {
        for (token in pushTokenService.tokensOf(granteeUid)) {
            when (FcmSender.sendData(token, data)) {
                FcmResult.INVALID_TOKEN -> pushTokenService.remove(token)
                FcmResult.SENT -> println("[Sentinela] Push enviado para $granteeUid (dono $ownerUid)")
                else -> Unit
            }
        }
    }
}

/**
 * - `POST /sentinela/push-tokens` e `/sentinela/push-tokens/unregister`: o app registra/remove
 *   o token FCM do navegador do usuário logado.
 * - `WS /ws/sentinela/live`: mapa ao vivo. Cliente envia
 *   `{"type":"watch","token":"<Firebase ID Token>","ownerUid":"..."}`; o servidor só aceita se o
 *   dono compartilhou a conta com o usuário, responde `snapshot` e depois retransmite
 *   `started`/`location`/`stopped` enquanto a conexão durar.
 */
fun Application.sentinelaLiveRouting(
    shareService: SentinelaShareService,
    userService: SentinelaUserService,
    pushTokenService: SentinelaPushTokenService,
) {
    routing {
        route("/sentinela/push-tokens") {
            post {
                val verifiedUser = call.requireFirebaseUser() ?: return@post
                val token = runCatching { call.receive<PushTokenRequest>().token.trim() }.getOrNull()
                if (token.isNullOrBlank() || token.length > MAX_TOKEN_LENGTH) {
                    call.respond(HttpStatusCode.BadRequest, "Token inválido")
                    return@post
                }
                pushTokenService.register(verifiedUser.uid, token)
                call.respond(HttpStatusCode.NoContent)
            }

            post("/unregister") {
                val verifiedUser = call.requireFirebaseUser() ?: return@post
                val token = runCatching { call.receive<PushTokenRequest>().token.trim() }.getOrNull()
                if (token.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, "Token inválido")
                    return@post
                }
                pushTokenService.unregister(verifiedUser.uid, token)
                call.respond(HttpStatusCode.NoContent)
            }
        }

        webSocket("/ws/sentinela/live") {
            val startFrame = withTimeoutOrNull(WATCH_START_TIMEOUT_MS) { incoming.receive() }
            val message = (startFrame as? Frame.Text)
                ?.let { runCatching { Json.parseToJsonElement(it.readText()).jsonObject }.getOrNull() }
            if (message?.string("type") != "watch") {
                failWatch("Primeira mensagem deve ser 'watch'")
                return@webSocket
            }

            val token = message.string("token")
            val ownerUid = message.string("ownerUid")
            if (token.isNullOrBlank() || ownerUid.isNullOrBlank()) {
                failWatch("Token e dono são obrigatórios")
                return@webSocket
            }

            val verifiedUser = try {
                FirebaseTokenVerifier.verifyIdToken(token)
            } catch (e: IllegalArgumentException) {
                failWatch(e.message ?: "Token inválido")
                return@webSocket
            }

            // Mesma regra das gravações: só quem recebeu o compartilhamento do dono acompanha.
            if (!shareService.exists(ownerUid = ownerUid, granteeUid = verifiedUser.uid)) {
                failWatch("Você não tem acesso à localização desta conta.")
                return@webSocket
            }

            val ownerName = userService.findByUid(ownerUid)?.name.orEmpty()
            val relay = launch {
                SentinelaLiveHub.events(ownerUid)
                    .onSubscription { emit(SentinelaLiveHub.snapshot(ownerUid, ownerName)) }
                    .collect { send(Frame.Text(it)) }
            }

            try {
                // O cliente não envia mais nada; o loop só detecta o fechamento da conexão.
                for (frame in incoming) Unit
            } finally {
                relay.cancel()
            }
        }
    }
}
