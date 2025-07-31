package routes.users

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import schemas.users.Client
import schemas.users.ClientService
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
                val userList = clientService.readAll()
                val authenticatedUser = userList.firstOrNull { it.name == user.name && it.password == user.password }

                if (authenticatedUser != null) {
                    call.respond(HttpStatusCode.OK, authenticatedUser)
                } else {
                    call.respond(HttpStatusCode.Unauthorized, "Usuário ou senha incorretos.")
                }

            } catch (e: Throwable) {
                call.respond(HttpStatusCode.BadRequest, "Erro ao processar JSON: ${e.message}")
            }
        }

        get("/") {
            val resourceStream = this::class.java.classLoader.getResourceAsStream("client_form.html")
            if (resourceStream != null) {
                val htmlContent = resourceStream.bufferedReader().use { it.readText() }
                call.respondText(htmlContent, ContentType.Text.Html)
            } else {
                call.respond(HttpStatusCode.NotFound, "Página HTML não encontrada.")
            }
        }
    }
}
