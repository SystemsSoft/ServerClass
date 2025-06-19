import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import org.slf4j.LoggerFactory

fun Application.configureSockets() {
    install(WebSockets) {
        pingPeriod = 30.seconds
        timeout = 30.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    val salas = mutableMapOf<String, MutableMap<String, DefaultWebSocketServerSession>>()
    val json = Json { ignoreUnknownKeys = true }
    val log = LoggerFactory.getLogger(Application::class.java)

    routing {
        webSocket("/signal") {
            var userId: String? = null
            var sala: String? = null

            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        val message = json.parseToJsonElement(text).jsonObject
                        val type = message["type"]?.jsonPrimitive?.content

                        when (type) {
                            "join" -> {
                                userId = message["id"]?.jsonPrimitive?.content ?: continue
                                sala = message["sala"]?.jsonPrimitive?.content ?: continue

                                val salaUsuarios = salas.getOrPut(sala) { mutableMapOf() }
                                salaUsuarios[userId!!] = this

                                // Enviar lista de peers existentes ao novo usuário
                                val peers = salaUsuarios.keys.filter { it != userId }
                                outgoing.send(Frame.Text(json.encodeToString(JsonObject(mapOf(
                                    "type" to JsonPrimitive("peers"),
                                    "peers" to JsonArray(peers.map { JsonPrimitive(it) })
                                )))))

                                // Avisar outros que um novo peer entrou
                                for ((id, session) in salaUsuarios) {
                                    if (id != userId) {
                                        session.outgoing.send(Frame.Text(json.encodeToString(JsonObject(mapOf(
                                            "type" to JsonPrimitive("new-peer"),
                                            "id" to JsonPrimitive(userId!!)
                                        )))))
                                    }
                                }
                            }

                            "signal" -> {
                                val to = message["to"]?.jsonPrimitive?.content ?: continue
                                val from = message["from"]?.jsonPrimitive?.content ?: continue
                                val payload = message["payload"] ?: continue

                                sala?.let { s ->
                                    salas[s]?.get(to)?.outgoing?.send(Frame.Text(
                                        json.encodeToString(JsonObject(mapOf(
                                            "type" to JsonPrimitive("signal"),
                                            "from" to JsonPrimitive(from),
                                            "payload" to payload
                                        )))
                                    ))
                                }
                            }
                            "chat" -> {
                                val from = message["from"]?.jsonPrimitive?.content ?: continue
                                val salaAtual = sala ?: continue
                                val chatMsg = message["message"]?.jsonPrimitive?.content ?: continue

                                // Montar JSON da mensagem que será enviada para os outros participantes
                                val chatResponse = JsonObject(mapOf(
                                    "type" to JsonPrimitive("chat"),
                                    "from" to JsonPrimitive(from),
                                    "message" to JsonPrimitive(chatMsg)
                                ))

                                salas[salaAtual]?.forEach { (id, session) ->
                                    if (id != from) {
                                        session.outgoing.send(Frame.Text(json.encodeToString(chatResponse)))
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                log.error("Erro no WebSocket para $userId: ${e.message}", e)
            } finally {
                // Ao desconectar, remover da sala e notificar os outros
                if (userId != null && sala != null) {
                    salas[sala]?.remove(userId)
                    salas[sala]?.values?.forEach {
                        it.outgoing.send(Frame.Text(json.encodeToString(JsonObject(mapOf(
                            "type" to JsonPrimitive("peer-left"),
                            "id" to JsonPrimitive(userId)
                        )))))
                    }
                }
            }
        }

        post("/ice-candidate-from-kurento") {
            val message = call.receive<JsonObject>() // Receber o JSON do ICE Candidate do serviço de gravação
            val targetUserId = message["targetUserId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("targetUserId faltando")
            val roomId = message["roomId"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("roomId faltando")

            salas[roomId]?.get(targetUserId)?.outgoing?.send(Frame.Text(json.encodeToString(JsonObject(mapOf(
                "type" to JsonPrimitive("ice-candidate-recording"),
                "candidate" to message["candidate"]!! // O objeto ICE Candidate completo
            )))))
            call.respond(io.ktor.http.HttpStatusCode.OK)
            log.info("ICE Candidate do Kurento retransmitido para $targetUserId na sala $roomId")
        }
    }
}