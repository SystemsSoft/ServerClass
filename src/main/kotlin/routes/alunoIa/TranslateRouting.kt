package routes.alunoIa

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
data class TranslateRequest(val text: String, val targetLang: String = "pt-BR")

@Serializable
data class TranslateResponse(val translation: String)

/**
 * POST /translate — mantém o contrato esperado pelo frontend (o app mostra só o texto em
 * inglês que a Megan falou), mas a tradução em si está desativada por ora: nenhum provedor
 * (Gemini/LibreTranslate) é chamado, a resposta sempre vem com `translation` vazio.
 * Reative chamando um serviço de tradução aqui quando houver uma opção definida.
 */
fun Application.translateRouting() {
    routing {
        post("/translate") {
            val request = try {
                call.receive<TranslateRequest>()
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    "Corpo inválido: esperado {\"text\": \"...\", \"targetLang\": \"...\"}."
                )
                return@post
            }

            if (request.text.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, "Campo 'text' é obrigatório.")
                return@post
            }

            call.respond(HttpStatusCode.OK, TranslateResponse(translation = ""))
        }
    }
}
