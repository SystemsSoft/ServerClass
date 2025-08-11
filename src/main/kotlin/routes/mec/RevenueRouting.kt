package routes.`class`

import ClientMecDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import schemas.mec.DeleteDto
import schemas.mec.Revenue
import schemas.mec.RevenueDto
import schemas.mec.RevenueService

fun Application.revenueRouting(revenueService: RevenueService) {
    routing {
        post("/revenues") {
            try {
                val revenue = call.receive<Revenue>()
                val id = revenueService.create(revenue)
                call.respond(HttpStatusCode.Created, id)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.BadRequest, "Error processing JSON for revenue: ${e.message}")
            }
        }

        get("/revenues") {
            try {
                val idLicense = call.request.queryParameters["idLicense"] ?: return@get call.respond(HttpStatusCode.BadRequest, "idLicense não fornecido")
                val revenues = revenueService.readAll(idLicense)
                call.respond(HttpStatusCode.OK, revenues)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Error fetching revenues: ${e.message}")
            }
        }

        put("/revenues") {
            try {
                val revenue = call.receive<RevenueDto>()
                revenueService.update(revenue.id, revenue)
                call.respond(HttpStatusCode.OK, "Revenue updated successfully!")
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Error updating revenue: ${e.message}")
            }
        }

        delete("/revenues") {
            try {
                val deleteDto = call.receive<DeleteDto>()

                revenueService.delete(deleteDto.id, deleteDto.userId)
                call.respond(HttpStatusCode.OK, "Revenue deleted successfully!")
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Error deleting revenue: ${e.message}")
            }
        }
    }
}