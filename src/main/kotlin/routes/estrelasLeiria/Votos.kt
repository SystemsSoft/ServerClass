package routes.estrelasLeiria

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import schemas.estrelasLeiria.VotoService
import schemas.estrelasLeiria.Votos
import java.util.UUID

fun Application.votoRouting(votoService: VotoService) {

    routing {
        route("/votos") {

            post {
                try {
                    val voto = call.receive<Votos>()

                    if (voto.categoriaId.isBlank() || voto.indicadoId.isBlank()) {
                        return@post call.respond(HttpStatusCode.BadRequest, "É necessário informar a Categoria e o Indicado.")
                    }

                    val generatedId = UUID.randomUUID().toString()

                    val id = votoService.create(voto, generatedId)

                    call.respond(HttpStatusCode.Created, "Voto registrado com sucesso! ID: $id")

                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, "Erro ao registrar voto: ${e.localizedMessage}")
                }
            }

            get {
                try {
                    val listaVotos = votoService.readAll()
                    call.respond(HttpStatusCode.OK, listaVotos)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar votos: ${e.localizedMessage}")
                }
            }


            delete("/{id}") {
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "ID do voto não fornecido")

                try {
                    votoService.delete(id)
                    call.respond(HttpStatusCode.OK, "Voto $id excluído com sucesso!")
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Erro ao excluir voto: ${e.localizedMessage}")
                }
            }
        }
    }
}