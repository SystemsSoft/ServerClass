package routes.classes // Consider changing to 'routes.financial' or 'routes.revenue' for better organization

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

fun Application.revenueRouting(revenueService: RevenueService) { // Renamed parameter for clarity
    routing {
        post("/revenues") {
            try {
                val revenue = call.receive<Revenue>()
                val id = revenueService.create(revenue)
                call.respond(HttpStatusCode.Created, id) // Use HttpStatusCode.Created for successful creation
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.BadRequest, "Error processing JSON for revenue: ${e.message}")
            }
        }

        // Route to read all revenue records
        get("/revenues") {
            try {
                val revenues = revenueService.readAll()
                call.respond(HttpStatusCode.OK, revenues)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Error fetching revenues: ${e.message}")
            }
        }

        // Route to update an existing revenue record
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
                val id = call.parameters["id"]?.toIntOrNull()
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid expense ID")
                    return@delete
                }
                revenueService.delete(id)
                call.respond(HttpStatusCode.OK, "Expense deleted successfully!")
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Error deleting expense: ${e.message}")
            }
        }
    }
}