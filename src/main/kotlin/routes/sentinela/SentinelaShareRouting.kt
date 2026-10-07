package routes.sentinela

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import schemas.sentinela.SentinelaShareService
import schemas.sentinela.SentinelaUserDto
import schemas.sentinela.SentinelaUserService
import services.FirebaseVerifiedUser

/** Quantos usuários cada conta pode convidar para ver suas gravações. */
private const val MAX_SHARES_PER_ACCOUNT = 1

private val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

@Serializable
private data class CreateShareRequest(val email: String)

@Serializable
private data class ShareError(val message: String)

@Serializable
private data class ShareCandidate(val name: String, val email: String)

/**
 * Lê o e-mail do corpo e valida se ele pertence a um usuário cadastrado que pode
 * receber o compartilhamento. Em caso de problema, já responde com o erro e retorna null.
 */
private suspend fun ApplicationCall.resolveEligibleGrantee(
    verifiedUser: FirebaseVerifiedUser,
    shareService: SentinelaShareService,
    userService: SentinelaUserService,
): SentinelaUserDto? {
    val email = runCatching { receive<CreateShareRequest>().email.trim() }.getOrNull()
    if (email.isNullOrBlank() || email.length > 200 || !EMAIL_REGEX.matches(email)) {
        respond(HttpStatusCode.BadRequest, ShareError("Informe um e-mail válido."))
        return null
    }
    if (email.equals(verifiedUser.email, ignoreCase = true)) {
        respond(HttpStatusCode.BadRequest, ShareError("Você não pode compartilhar a conta com você mesmo."))
        return null
    }

    val grantee = userService.findByEmail(email)
    if (grantee == null || grantee.uid == verifiedUser.uid) {
        respond(
            HttpStatusCode.NotFound,
            ShareError("Nenhuma conta do Sentinela com esse e-mail. Peça para a pessoa se cadastrar primeiro."),
        )
        return null
    }
    if (shareService.exists(verifiedUser.uid, grantee.uid)) {
        respond(HttpStatusCode.Conflict, ShareError("A conta já está compartilhada com esse usuário."))
        return null
    }
    if (shareService.countByOwner(verifiedUser.uid) >= MAX_SHARES_PER_ACCOUNT) {
        respond(
            HttpStatusCode.Conflict,
            ShareError("Limite de $MAX_SHARES_PER_ACCOUNT usuário(s) atingido. Remova o compartilhamento atual para adicionar outro."),
        )
        return null
    }
    return grantee
}

/**
 * Compartilhamento de conta: o usuário autenticado informa o e-mail de outra
 * pessoa já cadastrada no Sentinela, que passa a ver (só leitura) todas as
 * gravações dele. Apenas o dono cria e remove os próprios compartilhamentos.
 */
fun Application.sentinelaShareRouting(
    shareService: SentinelaShareService,
    userService: SentinelaUserService,
) {
    routing {
        route("/sentinela/shares") {
            get {
                val verifiedUser = call.requireFirebaseUser() ?: return@get
                call.respond(HttpStatusCode.OK, shareService.overview(verifiedUser.uid, MAX_SHARES_PER_ACCOUNT))
            }

            // Etapa 1: confere se o e-mail tem cadastro e se pode receber o compartilhamento,
            // sem criar nada — o app mostra a pessoa encontrada para o dono confirmar.
            post("/lookup") {
                val verifiedUser = call.requireFirebaseUser() ?: return@post
                val grantee = call.resolveEligibleGrantee(verifiedUser, shareService, userService) ?: return@post
                call.respond(HttpStatusCode.OK, ShareCandidate(name = grantee.name, email = grantee.email))
            }

            // Etapa 2: cria o compartilhamento. Revalida tudo, pois o cadastro pode ter mudado entre as etapas.
            post {
                val verifiedUser = call.requireFirebaseUser() ?: return@post
                val grantee = call.resolveEligibleGrantee(verifiedUser, shareService, userService) ?: return@post

                shareService.create(ownerUid = verifiedUser.uid, granteeUid = grantee.uid)
                println("[Sentinela] Conta ${verifiedUser.uid} compartilhada com ${grantee.uid}")
                call.respond(HttpStatusCode.Created, shareService.overview(verifiedUser.uid, MAX_SHARES_PER_ACCOUNT))
            }

            delete("/{granteeUid}") {
                val verifiedUser = call.requireFirebaseUser() ?: return@delete
                val granteeUid = call.parameters["granteeUid"]
                if (granteeUid.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ShareError("Usuário é obrigatório."))
                    return@delete
                }

                if (!shareService.delete(ownerUid = verifiedUser.uid, granteeUid = granteeUid)) {
                    call.respond(HttpStatusCode.NotFound, ShareError("Compartilhamento não encontrado."))
                    return@delete
                }
                println("[Sentinela] Compartilhamento ${verifiedUser.uid} -> $granteeUid removido")
                call.respond(HttpStatusCode.OK, shareService.overview(verifiedUser.uid, MAX_SHARES_PER_ACCOUNT))
            }
        }
    }
}
