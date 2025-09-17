package routes.mec

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import schemas.mec.Vehicle
import schemas.mec.VehicleDto
import schemas.mec.VehicleService

fun Application.vehicleRouting(vehicleService: VehicleService) {
    routing {
        post("/vehicles") {
            try {
                val vehicle = call.receive<Vehicle>()
                val id = vehicleService.create(vehicle)
                call.respond(HttpStatusCode.Created, id)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.BadRequest, "Erro ao processar a requisição: ${e.message}")
            }
        }

        get("/vehicles/by-client") {
            try {
                val userId = call.request.headers["Authorization"]
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, "Token de autorização não fornecido.")

                val clientId = call.request.queryParameters["clientId"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "clientId não fornecido ou inválido")

                val vehicles = vehicleService.readByClientId(clientId, userId)
                call.respond(HttpStatusCode.OK, vehicles)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar veículos do cliente: ${e.message}")
            }
        }

        get("/vehicles") {
            try {
                val userId = call.request.queryParameters["userId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "userId não fornecido")
                val vehicles = vehicleService.readAll(userId)
                call.respond(HttpStatusCode.OK, vehicles)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar veículos: ${e.message}")
            }
        }

        put("/vehicles/{id}") {
            try {
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, "ID do veículo inválido ou não fornecido.")
                val vehicleDto = call.receive<VehicleDto>()
                vehicleService.update(id, vehicleDto)
                call.respond(HttpStatusCode.OK, "Veículo atualizado com sucesso!")
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao atualizar veículo: ${e.message}")
            }
        }

        delete("/vehicles/{id}") {
            try {
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, "ID do veículo inválido ou não fornecido.")
                val userId = call.request.queryParameters["userId"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, "userId não fornecido")
                vehicleService.delete(id, userId)
                call.respond(HttpStatusCode.OK, "Veículo excluído com sucesso!")
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao excluir veículo: ${e.message}")
            }
        }
    }
}