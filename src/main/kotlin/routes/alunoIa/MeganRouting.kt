package routes.alunoIa

import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import schemas.alunoIa.AlunoIaService
import schemas.alunoIa.MeganPersona
import schemas.alunoIa.MissionFluencyCurriculum
import services.GeminiLiveBridge
import java.time.Instant

/**
 * Sessão de voz ao vivo com a Megan: o app conecta em /ws/megan/{userId} e fala
 * diretamente o protocolo da Gemini Live API (áudio em tempo real). Este servidor
 * só injeta a persona/tema do dia no início e faz o relay — a chave da Gemini
 * nunca chega ao cliente.
 */
fun Application.meganRouting(alunoIaService: AlunoIaService, geminiLiveBridge: GeminiLiveBridge) {
    routing {
        webSocket("/ws/megan/{userId}") {
            val userId = call.parameters["userId"]
            if (userId.isNullOrBlank()) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Parâmetro 'userId' é obrigatório."))
                return@webSocket
            }

            val aluno = runCatching { alunoIaService.readByUserId(userId) }.getOrNull()
            val currentDay = aluno?.missaoAtual?.toIntOrNull() ?: 1
            val day = MissionFluencyCurriculum.dayOf(aluno?.moduloAtual ?: "module1", currentDay)
                ?: MissionFluencyCurriculum.module1.first()

            send(Frame.Text(buildJsonObject {
                put("type", "session_ready")
                put("day", day.day)
                put("topic", day.topic)
            }.toString()))

            try {
                val studentName = aluno?.nome?.takeIf { it.isNotBlank() } ?: "there"
                geminiLiveBridge.bridge(this, MeganPersona.systemInstructionFor(day, studentName))
            } catch (e: Exception) {
                println("[Megan] Erro na sessão de $userId: ${e.message}")
                runCatching {
                    send(Frame.Text(buildJsonObject {
                        put("type", "error")
                        put("message", "Não foi possível iniciar a chamada com a Megan agora.")
                    }.toString()))
                }
            } finally {
                if (aluno != null) {
                    // O avanço de missão não acontece mais aqui: o front-end chama
                    // POST /aluno-ia/{userId}/avancar-missao quando o aluno sinaliza que
                    // assistiu/concluiu o conteúdo do dia. Aqui só registramos o horário
                    // da última sessão de chamada.
                    runCatching {
                        alunoIaService.update(userId, aluno.copy(
                            ultimaSessao = Instant.now().toString(),
                        ))
                    }
                }
            }
        }
    }
}
