package routes.resolvebr

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import schemas.resolvebr.Cadastro
import schemas.resolvebr.CadastroService
import schemas.resolvebr.CadastroPatch
import java.util.UUID

fun Application.cadastroRouting(cadastroService: CadastroService) {
    routing {

        route("/cadastros") {

            // ── POST /cadastros ──────────────────────────────────────
            // Cria um novo cadastro e retorna o objeto completo (201).
            post {
                try {
                    val body = call.receive<Cadastro>()
                    val id   = UUID.randomUUID().toString()
                    val dto  = cadastroService.create(body, id)
                    call.respond(HttpStatusCode.Created, dto)
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("message" to "Erro ao criar cadastro: ${e.message}")
                    )
                }
            }

            // ── GET /cadastros ───────────────────────────────────────
            // Lista com filtros opcionais: categoria, cidade, page, limit.
            get {
                try {
                    val categoria = call.request.queryParameters["categoria"]
                    val cidade    = call.request.queryParameters["cidade"]
                    val page      = call.request.queryParameters["page"]?.toIntOrNull()  ?: 1
                    val limit     = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20

                    val list = cadastroService.readAll(
                        categoria = categoria,
                        cidade    = cidade,
                        page      = page,
                        limit     = limit,
                    )
                    call.respond(HttpStatusCode.OK, list)
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("message" to "Erro ao listar cadastros: ${e.message}")
                    )
                }
            }

            // ── GET /cadastros/email/:email ──────────────────────────
            // Deve ficar ANTES de /{id} para não ser capturado como id.
            get("/email/{email}") {
                try {
                    val email = call.parameters["email"]
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("message" to "E-mail não informado")
                        )

                    val dto = cadastroService.readByEmail(email)
                        ?: return@get call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("message" to "Cadastro não encontrado para o e-mail: $email")
                        )

                    call.respond(HttpStatusCode.OK, dto)
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("message" to "Erro ao buscar por e-mail: ${e.message}")
                    )
                }
            }

            // ── GET /cadastros/:id ───────────────────────────────────
            get("/{id}") {
                try {
                    val id = call.parameters["id"]
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("message" to "ID não informado")
                        )

                    val dto = cadastroService.readById(id)
                        ?: return@get call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("message" to "Cadastro $id não encontrado")
                        )

                    call.respond(HttpStatusCode.OK, dto)
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("message" to "Erro ao buscar cadastro: ${e.message}")
                    )
                }
            }

            // ── PUT /cadastros/:id ───────────────────────────────────
            // Substituição completa do cadastro.
            put("/{id}") {
                try {
                    val id = call.parameters["id"]
                        ?: return@put call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("message" to "ID não informado")
                        )

                    val body = call.receive<Cadastro>()
                    val dto  = cadastroService.update(id, body)
                        ?: return@put call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("message" to "Cadastro $id não encontrado")
                        )

                    call.respond(HttpStatusCode.OK, dto)
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("message" to "Erro ao atualizar cadastro: ${e.message}")
                    )
                }
            }

            // ── PATCH /cadastros/:id ─────────────────────────────────
            // Atualização parcial — apenas os campos enviados são alterados.
            patch("/{id}") {
                try {
                    val id = call.parameters["id"]
                        ?: return@patch call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("message" to "ID não informado")
                        )

                    val fields = call.receive<CadastroPatch>()
                    val dto    = cadastroService.patch(id, fields)
                        ?: return@patch call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("message" to "Cadastro $id não encontrado")
                        )

                    call.respond(HttpStatusCode.OK, dto)
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("message" to "Erro ao aplicar patch: ${e.message}")
                    )
                }
            }

            // ── DELETE /cadastros/:id ────────────────────────────────
            delete("/{id}") {
                try {
                    val id = call.parameters["id"]
                        ?: return@delete call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("message" to "ID não informado")
                        )

                    val deleted = cadastroService.delete(id)
                    if (!deleted) {
                        return@delete call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("message" to "Cadastro $id não encontrado")
                        )
                    }

                    call.respond(HttpStatusCode.OK, mapOf("message" to "Cadastro $id removido com sucesso"))
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("message" to "Erro ao remover cadastro: ${e.message}")
                    )
                }
            }
        }
    }
}

