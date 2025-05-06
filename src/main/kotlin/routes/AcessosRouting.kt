package routes

import com.class_erp.schemas.Acessos
import com.class_erp.schemas.AcessosDto
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
                val id = serviceAcesso.create(call.receive<Acessos>())
                call.respond(HttpStatusCode.Created, id)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.BadRequest, "Erro ao processar JSON: ${e.message}")
            }
        }

        get("/acessos") {
            try {
                call.respond(HttpStatusCode.OK, serviceAcesso.readAll())
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar classes: ${e.message}")
            }
        }

        put("/acessos") {
            try {
                val acesso = call.receive<AcessosDto>()
                call.respond(
                    HttpStatusCode.OK, serviceAcesso.update(
                        acesso.id,
                        acesso
                    )
                )
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar classes: ${e.message}")
            }
        }

        delete("/acessos") {
            try {
                val acesso = call.receive<AcessosDto>()
                call.respond(HttpStatusCode.OK, serviceAcesso.delete(acesso.id))
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar classes: ${e.message}")
            }
        }
    }
}