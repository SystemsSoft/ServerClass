package routes

import com.class_erp.schemas.Acessos
import com.class_erp.schemas.AcessosService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing

fun Application.acessosRouting(serviceAcesso: AcessosService) {
    routing {
        post("/acessos") {
            try {
                val user = call.receive<Acessos>()
                val id = serviceAcesso.create(user)
                call.respond(HttpStatusCode.Created, id)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.BadRequest, "Erro ao processar JSON: ${e.message}")
            }
        }

        // Read user
        get("/acessos/{id}") {

        }

        // Update user
        put("/acessos/{id}") {

        }

        // Delete user
        delete("/acessos/{id}") {

        }
    }
}