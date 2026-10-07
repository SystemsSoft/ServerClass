package routes.sentinela

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import services.SentinelaPasswordReset

private val RESET_EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

@Serializable
private data class PasswordResetRequest(val email: String)

@Serializable
private data class PasswordResetResponse(val message: String)

fun Application.sentinelaPasswordResetRouting(passwordReset: SentinelaPasswordReset) {
    routing {
        // Público (o usuário esqueceu a senha, então não tem token). Responde igual exista ou não
        // conta com o e-mail, para não revelar quem é cadastrado.
        post("/sentinela/auth/password-reset") {
            val email = try {
                call.receive<PasswordResetRequest>().email.trim()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, PasswordResetResponse("Informe o e-mail."))
                return@post
            }

            if (email.length > 200 || !RESET_EMAIL_REGEX.matches(email)) {
                call.respond(HttpStatusCode.BadRequest, PasswordResetResponse("Informe um e-mail válido."))
                return@post
            }
            if (!passwordReset.isConfigured) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    PasswordResetResponse("Redefinição de senha indisponível no momento."),
                )
                return@post
            }

            passwordReset.request(email)
            call.respond(
                HttpStatusCode.Accepted,
                PasswordResetResponse("Se houver uma conta com este e-mail, enviaremos um link para redefinir a senha."),
            )
        }
    }
}
