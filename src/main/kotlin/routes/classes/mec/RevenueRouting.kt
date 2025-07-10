package routes.classes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
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
                val revenues = revenueService.readAll()
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

        delete("/revenues/{id}") {
            try {
                val id = call.parameters["id"]?.toIntOrNull()
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid revenue ID")
                    return@delete
                }
                revenueService.delete(id)
                call.respond(HttpStatusCode.OK, "Revenue deleted successfully!")
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Error deleting revenue: ${e.message}")
            }
        }
    }
}