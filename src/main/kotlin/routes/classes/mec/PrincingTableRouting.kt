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
import schemas.mec.PriceTableMec // Import the PriceTableMec data class
import schemas.mec.PriceTableMecDto // Import the PriceTableMecDto data class
import schemas.mec.PriceTableMecService // Import the PriceTableMecService

fun Application.pricingTableRouting(priceTableMecService: PriceTableMecService) {
    routing {

        post("/pricing/mec") {
            try {
                val pricingEntry = call.receive<PriceTableMec>()
                val id = priceTableMecService.create(pricingEntry)
                call.respond(HttpStatusCode.Created, id)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.BadRequest, "Erro ao processar JSON para tabela de preços MEC: ${e.message}")
            }
        }

        get("/pricing/mec") {
            try {
                val pricingEntries = priceTableMecService.readAll()
                call.respond(HttpStatusCode.OK, pricingEntries)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar tabela de preços MEC: ${e.message}")
            }
        }

        put("/pricing/mec") {
            try {
                val pricingEntry = call.receive<PriceTableMecDto>()
                priceTableMecService.update(pricingEntry.id, pricingEntry)
                call.respond(HttpStatusCode.OK, "Tabela de preços MEC atualizada com sucesso!")
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao atualizar tabela de preços MEC: ${e.message}")
            }
        }

        delete("/pricing/mec") {
            try {
                val pricingEntry = call.receive<PriceTableMecDto>() // Assuming you send the DTO with the ID to delete
                priceTableMecService.delete(pricingEntry.id)
                call.respond(HttpStatusCode.OK, "Tabela de preços MEC excluída com sucesso!")
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao excluir tabela de preços MEC: ${e.message}")
            }
        }
    }
}