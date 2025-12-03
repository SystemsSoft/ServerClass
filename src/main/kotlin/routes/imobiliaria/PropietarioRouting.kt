package routes.imobiliaria

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import schemas.imobiliaria.Proprietario
import schemas.imobiliaria.ProprietarioService

fun Application.proprietarioRouting(proprietarioService: ProprietarioService) {
    routing {

        // Agrupa todas as rotas de Proprietario sob o prefixo /proprietarios
        route("/proprietarios") {

            // Rota POST: Criar um novo proprietário
            // Endpoint: POST /proprietarios
            post {
                try {
                    val proprietario = call.receive<Proprietario>()

                    // O create retorna o ID (Int) gerado pelo banco
                    val generatedId = proprietarioService.create(proprietario)

                    // Retorna o ID gerado com status 201 Created
                    call.respond(HttpStatusCode.Created, mapOf("id" to generatedId))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, "Erro ao criar proprietário: ${e.message}")
                }
            }

            // Rota GET: Listar todos os proprietários
            // Endpoint: GET /proprietarios
            get {
                try {
                    val lista = proprietarioService.readAll()
                    // Retorna a lista com status 200 OK
                    call.respond(HttpStatusCode.OK, lista)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar proprietários: ${e.message}")
                }
            }

            // Rota PUT: Atualizar um proprietário
            // Endpoint: PUT /proprietarios/{id}
            put("/{id}") {
                try {
                    val id = call.parameters["id"]?.toIntOrNull()

                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, "ID inválido")
                        return@put
                    }

                    // Recebe o objeto Proprietario com os novos dados
                    val proprietarioAtualizado = call.receive<Proprietario>()

                    val atualizou = proprietarioService.update(id, proprietarioAtualizado)

                    if (atualizou) {
                        call.respond(HttpStatusCode.OK, "Proprietário $id atualizado com sucesso!")
                    } else {
                        call.respond(HttpStatusCode.NotFound, "Proprietário não encontrado para atualização")
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Erro ao atualizar proprietário: ${e.message}")
                }
            }

            // Rota DELETE: Excluir um proprietário
            // Endpoint: DELETE /proprietarios/{id}
            delete("/{id}") {
                try {
                    val id = call.parameters["id"]?.toIntOrNull()

                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, "ID inválido")
                        return@delete
                    }

                    val deletou = proprietarioService.delete(id)

                    if (deletou) {
                        call.respond(HttpStatusCode.OK, "Proprietário $id excluído com sucesso!")
                    } else {
                        call.respond(HttpStatusCode.NotFound, "Proprietário não encontrado para exclusão")
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Erro ao excluir proprietário: ${e.message}")
                }
            }
        }
    }
}