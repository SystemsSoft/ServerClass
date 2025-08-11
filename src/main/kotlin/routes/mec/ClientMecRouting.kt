import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

fun Application.clientMecRouting(clientMecService: ClientMecService) {
    routing {
        post("/clients/mec") {
            try {
                val client = call.receive<ClientMec>()
                val id = clientMecService.create(client)
                call.respond(HttpStatusCode.Created, id)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.BadRequest, "Erro ao processar a requisição: ${e.message}")
            }
        }

        get("/clients/mec") {
            try {
                val idLicense = call.request.queryParameters["idLicense"] ?: return@get call.respond(HttpStatusCode.BadRequest, "idLicense não fornecido")
                val clients = clientMecService.readAll(idLicense)
                call.respond(HttpStatusCode.OK, clients)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar clientes MEC: ${e.message}")
            }
        }

        put("/clients/mec") {
            try {
                // Recebe o objeto ClientMecDto com o idLicense
                val client = call.receive<ClientMecDto>()
                clientMecService.update(client.id, client)
                call.respond(HttpStatusCode.OK, "Cliente MEC atualizado com sucesso!")
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao atualizar cliente MEC: ${e.message}")
            }
        }

        delete("/clients/mec") {
            try {
                val client = call.receive<ClientMecDto>()
                clientMecService.delete(client.id, client.userId)
                call.respond(HttpStatusCode.OK, "Cliente MEC excluído com sucesso!")
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao excluir cliente MEC: ${e.message}")
            }
        }
    }
}