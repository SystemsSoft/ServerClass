package routes.users

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import schemas.users.Client
import schemas.users.ClientService
import schemas.users.LoginResponse
import schemas.users.User


fun Application.clientRouting(clientService: ClientService) {
    routing {
        post("/client") {
            try {
                val id = clientService.create(call.receive<Client>())
                call.respond(HttpStatusCode.OK, id)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.BadRequest, "Erro ao processar JSON: ${e.message}")
            }
        }

        post("/auth") {
            try {
                val user = call.receive<User>()
                var isAuthenticated = false
                var idLicense: String? = null
                val userList = clientService.readAll()
                val isUser = userList.filter { it.password == user.password && it.name == user.name }

                if (isUser.isNotEmpty()) {
                    val userFound = isUser.first()
                    if (userFound.license) {
                        isAuthenticated = true
                        idLicense = userFound.idLicense
                    }
                }

                call.respond(HttpStatusCode.OK, LoginResponse(isAuthenticated, idLicense))

            } catch (e: Throwable) {
                call.respond(HttpStatusCode.BadRequest, "Erro ao processar JSON: ${e.message}")
            }
        }


        staticResources("/", "") {
            default("index.html")
        }
    }
}
