import ClientMec
import ClientMecDto
import ClientMecService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.request.queryString // Importação necessária para pegar parâmetros de URL
import io.ktor.server.application.call

fun Application.clientMecRouting(clientMecService: ClientMecService) {
    routing {
        // Rota para CRIAR um novo cliente
        post("/clients/mec") {
            try {
                val client = call.receive<ClientMec>()
                val id = clientMecService.create(client)
                call.respond(HttpStatusCode.Created, id)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.BadRequest, "Erro ao processar JSON para cliente MEC: ${e.message}")
            }
        }

        // Rota para LER todos os clientes de um usuário específico
        get("/clients/mec") {
            try {
                // Tenta obter o userId da query string. Se não houver, retorna erro.
                val userIdString = call.request.queryParameters["userId"] ?: return@get call.respond(HttpStatusCode.BadRequest, "userId não fornecido")
                val userId = userIdString.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, "userId inválido")

                val clients = clientMecService.readAll(userId)
                call.respond(HttpStatusCode.OK, clients)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar clientes MEC: ${e.message}")
            }
        }

        // Rota para ATUALIZAR um cliente existente, garantindo que ele pertença ao userId
        put("/clients/mec") {
            try {
                val client = call.receive<ClientMecDto>()
                // A função de atualização agora recebe o ID e o DTO, que já contém o userId
                clientMecService.update(client.id, client)
                call.respond(HttpStatusCode.OK, "Cliente MEC atualizado com sucesso!")
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao atualizar cliente MEC: ${e.message}")
            }
        }

        // Rota para EXCLUIR um cliente, garantindo que ele pertença ao userId
        delete("/clients/mec") {
            try {
                val client = call.receive<ClientMecDto>()
                // A função de exclusão agora recebe o ID e o userId para garantir a segurança
                clientMecService.delete(client.id, client.userId)
                call.respond(HttpStatusCode.OK, "Cliente MEC excluído com sucesso!")
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao excluir cliente MEC: ${e.message}")
            }
        }
    }
}