package routes.`class` // Keeping the same package as your example, but consider 'routes.mec' for better organization

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import schemas.mec.ClientMec
import schemas.mec.ClientMecDto
import schemas.mec.ClientMecService

fun Application.clientMecRouting(clientMecService: ClientMecService) {
    routing {
        post("/clients/mec") {
            try {
                val client = call.receive<ClientMec>()
                val id = clientMecService.create(client)
                call.respond(HttpStatusCode.Created, id) // Use HttpStatusCode.Created for successful creation
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.BadRequest, "Erro ao processar JSON para cliente MEC: ${e.message}")
            }
        }

        // Route to read all clients
        get("/clients/mec") {
            try {
                val clients = clientMecService.readAll()
                call.respond(HttpStatusCode.OK, clients)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar clientes MEC: ${e.message}")
            }
        }

        // Route to update an existing client
        put("/clients/mec") {
            try {
                val client = call.receive<ClientMecDto>()
                // The update function in ClientMecService takes id and ClientMecDto
                clientMecService.update(client.id, client)
                call.respond(HttpStatusCode.OK, "Cliente MEC atualizado com sucesso!")
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao atualizar cliente MEC: ${e.message}")
            }
        }

        // Route to delete a client
        delete("/clients/mec") {
            try {
                val client = call.receive<ClientMecDto>() // Assuming you send the DTO with the ID to delete
                clientMecService.delete(client.id)
                call.respond(HttpStatusCode.OK, "Cliente MEC excluído com sucesso!")
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao excluir cliente MEC: ${e.message}")
            }
        }
    }
}