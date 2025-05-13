package routes

import com.class_erp.schemas.Access
import com.class_erp.schemas.AccessDto
import com.class_erp.schemas.AccessService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing

fun Application.accessRouting(accessService: AccessService) {
    routing {
        post("/access") {
            try {
                val id = accessService.create(call.receive<Access>())
                call.respond(HttpStatusCode.OK, id)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.BadRequest, "Erro ao processar JSON: ${e.message}")
            }
        }

        get("/access") {
            try {
                call.respond(HttpStatusCode.OK, accessService.readAll())
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar classes: ${e.message}")
            }
        }

        put("/access") {
            try {
                val access = call.receive<AccessDto>()
                call.respond(
                    HttpStatusCode.OK, accessService.update(
                        access.id,
                        access
                    )
                )
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar classes: ${e.message}")
            }
        }

        delete("/access") {
            try {
                val access = call.receive<AccessDto>()
                call.respond(HttpStatusCode.OK, accessService.delete(access.id))
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar classes: ${e.message}")
            }
        }
    }
}