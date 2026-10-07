package routes.sentinela

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import services.FirebaseTokenVerifier
import services.FirebaseVerifiedUser

/**
 * Extrai o "Authorization: Bearer <idToken>", verifica o ID Token do Firebase e
 * retorna o usuário autenticado. Se o token estiver ausente ou inválido, já
 * responde 401 e retorna null — o chamador deve encerrar o handler.
 */
internal suspend fun ApplicationCall.requireFirebaseUser(): FirebaseVerifiedUser? {
    val header = request.headers[HttpHeaders.Authorization]
    val token = header?.removePrefix("Bearer ")?.trim()

    if (token.isNullOrBlank()) {
        respond(HttpStatusCode.Unauthorized, "Token de autenticação ausente")
        return null
    }

    return try {
        FirebaseTokenVerifier.verifyIdToken(token)
    } catch (e: IllegalArgumentException) {
        respond(HttpStatusCode.Unauthorized, e.message ?: "Token inválido")
        null
    }
}
