package routes.estrelasLeiria

import schemas.estrelasLeiria.Categoria

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import schemas.estrelasLeiria.CategoriaService
import java.util.UUID // Import necessário para gerar IDs únicos

fun Application.categoriaRouting(categoriaService: CategoriaService) {
    routing {

        // Agrupa todas as rotas de Categoria sob o prefixo /categorias
        route("/categorias") {

            // Rota POST: Criar uma nova categoria
            // Endpoint: POST /categorias
            post {
                try {
                    val categoria = call.receive<Categoria>()
                    // Simula a geração de um ID único (UUID) para a String ID
                    val generatedId = UUID.randomUUID().toString()

                    val id = categoriaService.create(categoria, generatedId)

                    // Retorna o ID gerado com status 201 Created
                    call.respond(HttpStatusCode.Created, id)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, "Erro ao criar categoria: ${e.message}")
                }
            }

            // Rota GET: Listar todas as categorias
            // Endpoint: GET /categorias
            get {
                try {
                    val categorias = categoriaService.readAll()
                    // Retorna a lista de categorias com status 200 OK
                    call.respond(HttpStatusCode.OK, categorias)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar categorias: ${e.message}")
                }
            }


            put("/{id}") {
                try {
                    val id = call.parameters["id"] ?: return@put call.respond(
                        HttpStatusCode.BadRequest,
                        "ID da categoria não fornecido"
                    )

                    // Recebe o objeto Categoria com os novos dados
                    val categoria = call.receive<Categoria>()
                    categoriaService.update(id, categoria)

                    call.respond(HttpStatusCode.OK, "Categoria $id atualizada com sucesso!")
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Erro ao atualizar categoria: ${e.message}")
                }
            }

            // Rota DELETE: Excluir uma categoria
            // Endpoint: DELETE /categorias/{id}
            delete("/{id}") {
                try {
                    val id = call.parameters["id"] ?: return@delete call.respond(
                        HttpStatusCode.BadRequest,
                        "ID da categoria não fornecido"
                    )

                    categoriaService.delete(id)

                    call.respond(HttpStatusCode.OK, "Categoria $id excluída com sucesso!")
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Erro ao excluir categoria: ${e.message}")
                }
            }
        }
    }
}