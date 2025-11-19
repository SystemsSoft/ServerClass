package routes.estrelasLeiria

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import schemas.estrelasLeiria.Indicado
import schemas.estrelasLeiria.IndicadoService
import schemas.estrelasLeiria.IndicadoUpdate
import java.util.UUID


fun Application.indicadoRouting(indicadoService: IndicadoService) {


    routing {

        route("/indicados") {

            post {
                val generatedId = UUID.randomUUID().toString()

                try {
                    val novoIndicado = call.receive<Indicado>()

                    if (novoIndicado.nome.isBlank() || novoIndicado.categoriaId.isBlank() || novoIndicado.imageData.isBlank()) {
                        return@post call.respond(HttpStatusCode.BadRequest, "Dados de Indicado incompletos ou imagem Base64 ausente.")
                    }

                    val id = indicadoService.create(novoIndicado, generatedId)
                    call.respond(HttpStatusCode.Created, id)

                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Erro no processamento da requisição: ${e.localizedMessage}")
                }
            }

            get {
                try {
                    val indicados = indicadoService.readAll()
                    call.respond(HttpStatusCode.OK, indicados)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar indicados: ${e.localizedMessage}")
                }
            }

            put("/{id}") {
                try {
                    val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, "ID não fornecido")

                    // RECEBE O DTO DE UPDATE
                    val indicado = call.receive<IndicadoUpdate>()

                    indicadoService.update(id, indicado)

                    call.respond(HttpStatusCode.OK, "Indicado $id atualizado com sucesso!")
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Erro ao atualizar indicado: ${e.localizedMessage}")
                }
            }

            // ------------------------------------------------------------
            // Rota DELETE: Excluir
            // ------------------------------------------------------------
            delete("/{id}") {
                try {
                    val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "ID não fornecido")

                    indicadoService.delete(id)

                    call.respond(HttpStatusCode.OK, "Indicado $id excluído com sucesso!")
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Erro ao excluir indicado: ${e.localizedMessage}")
                }
            }
        }
    }
}