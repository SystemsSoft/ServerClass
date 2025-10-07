package routes.mec

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import schemas.mec.ServiceOrder
import schemas.mec.ServiceOrderDto
import schemas.mec.ServiceOrderService

fun Application.serviceOrderRouting(serviceOrderService: ServiceOrderService) {
    routing {
        post("/service_orders") {
            try {
                val serviceOrder = call.receive<ServiceOrder>()
                val id = serviceOrderService.create(serviceOrder)
                call.respond(HttpStatusCode.Created, id)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.BadRequest, "Erro ao processar a requisição: ${e.message}")
            }
        }

        get("/service_orders") {
            try {
                val userId = call.request.queryParameters["userId"] ?: return@get call.respond(HttpStatusCode.BadRequest, "userId não fornecido")
                val serviceOrders = serviceOrderService.readAll(userId)
                call.respond(HttpStatusCode.OK, serviceOrders)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar ordens de serviço: ${e.message}")
            }
        }

        get("/service_orders/details") {
            try {
                val clientId = call.request.queryParameters["clientId"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "clientId inválido ou não fornecido.")
                val userId = call.request.queryParameters["userId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "userId não fornecido.")
                val idVeiculo = call.request.queryParameters["idVeiculo"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "idVeiculo inválido ou não fornecido.")

                val serviceOrders = serviceOrderService.readByClientAndVehicle(clientId, userId, idVeiculo)
                call.respond(HttpStatusCode.OK, serviceOrders)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar ordens de serviço detalhadas: ${e.message}")
            }
        }

        put("/service_orders") {
            try {
                val serviceOrder = call.receive<ServiceOrderDto>()
                serviceOrderService.update(serviceOrder.id, serviceOrder)
                call.respond(HttpStatusCode.OK, "Ordem de serviço atualizada com sucesso!")
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao atualizar ordem de serviço: ${e.message}")
            }
        }

        delete("/service_orders") {
            try {
                val serviceOrder = call.receive<ServiceOrderDto>()
                serviceOrderService.delete(serviceOrder.id, serviceOrder.userId)
                call.respond(HttpStatusCode.OK, "Ordem de serviço excluída com sucesso!")
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao excluir ordem de serviço: ${e.message}")
            }
        }
    }
}