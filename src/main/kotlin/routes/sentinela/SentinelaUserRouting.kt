package routes.sentinela

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import schemas.sentinela.SentinelaUser
import schemas.sentinela.SentinelaUserService

fun Application.sentinelaUserRouting(sentinelaUserService: SentinelaUserService) {
    routing {
        route("/sentinela/users") {

            // Chamado pelo app logo após o login/cadastro no Firebase, para
            // espelhar o usuário autenticado na tabela sentinela_users.
            // O uid/email usados são sempre os do token verificado — nunca do
            // corpo da requisição — para que ninguém grave dados em nome de
            // outro usuário.
            post {
                val verifiedUser = call.requireFirebaseUser() ?: return@post

                try {
                    val body = call.receive<SentinelaUser>()
                    if (body.uid != verifiedUser.uid) {
                        call.respond(HttpStatusCode.Forbidden, "uid não corresponde ao usuário autenticado")
                        return@post
                    }

                    val toSave = SentinelaUser(
                        uid = verifiedUser.uid,
                        name = body.name.ifBlank {
                            verifiedUser.name ?: verifiedUser.email?.substringBefore("@") ?: "Usuário"
                        },
                        email = verifiedUser.email?.takeIf { it.isNotBlank() } ?: body.email,
                        photoUrl = body.photoUrl ?: verifiedUser.picture,
                    )

                    val saved = sentinelaUserService.upsert(toSave)
                    call.respond(HttpStatusCode.OK, saved)
                } catch (e: Throwable) {
                    call.respond(HttpStatusCode.BadRequest, "Erro ao processar JSON: ${e.message}")
                }
            }

            get("/{uid}") {
                val verifiedUser = call.requireFirebaseUser() ?: return@get
                val uid = call.parameters["uid"]

                if (uid.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, "uid é obrigatório")
                    return@get
                }
                if (uid != verifiedUser.uid) {
                    call.respond(HttpStatusCode.Forbidden, "Não é possível acessar dados de outro usuário")
                    return@get
                }

                val user = sentinelaUserService.findByUid(uid)
                if (user == null) {
                    call.respond(HttpStatusCode.NotFound, "Usuário não encontrado")
                } else {
                    call.respond(HttpStatusCode.OK, user)
                }
            }

            delete("/{uid}") {
                val verifiedUser = call.requireFirebaseUser() ?: return@delete
                val uid = call.parameters["uid"]

                if (uid.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, "uid é obrigatório")
                    return@delete
                }
                if (uid != verifiedUser.uid) {
                    call.respond(HttpStatusCode.Forbidden, "Não é possível remover dados de outro usuário")
                    return@delete
                }

                sentinelaUserService.delete(uid)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
