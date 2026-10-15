package routes.madamesFormosas

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import schemas.madamesFormosas.Produto
import schemas.madamesFormosas.ProdutoService
import java.security.MessageDigest

/**
 * Token de administração do catálogo (criar/editar/excluir produtos). Vem de
 * madames-credentials.properties (madames.adminToken) ou da variável MADAMES_ADMIN_TOKEN.
 * Sem token configurado, as rotas de escrita ficam desligadas.
 */
private fun adminToken(): String? =
    (System.getProperty("madames.adminToken") ?: System.getenv("MADAMES_ADMIN_TOKEN"))?.takeIf { it.isNotBlank() }

/** Responde e retorna false quando a chamada não traz o token de administração válido. */
private suspend fun ApplicationCall.autorizarAdmin(): Boolean {
    val esperado = adminToken() ?: run {
        respond(HttpStatusCode.ServiceUnavailable, "Administração do catálogo não configurada")
        return false
    }
    val recebido = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim().orEmpty()
    if (!MessageDigest.isEqual(recebido.toByteArray(), esperado.toByteArray())) {
        respond(HttpStatusCode.Unauthorized, "Token inválido")
        return false
    }
    return true
}

/** Mesmo formato de código para a API e para a gestão de produtos da equipe. */
internal fun Produto.normalizado(): Produto = copy(
    codigo = codigo.trim().uppercase(),
    nome = nome.trim(),
    categoria = categoria.trim(),
    fotos = fotos.map { it.trim() }.filter { it.isNotEmpty() },
)

internal fun Produto.validar(): String? = when {
    codigo.isBlank() -> "Código obrigatório"
    !Regex("[A-Za-z0-9_-]{1,32}").matches(codigo) -> "Código deve ter até 32 letras, números, hífen ou _"
    nome.isBlank() -> "Nome obrigatório"
    categoria.isBlank() -> "Categoria obrigatória"
    preco < 0 -> "Preço inválido"
    fotos.isEmpty() -> "Informe ao menos uma foto"
    else -> null
}

fun Application.madamesProdutosRouting(produtoService: ProdutoService) {
    routing {
        route("/madames/produtos") {

            // Catálogo público: GET /madames/produtos[?categoria=Brincos]
            get {
                try {
                    val categoria = call.request.queryParameters["categoria"]?.takeIf { it.isNotBlank() }
                    call.respond(HttpStatusCode.OK, produtoService.readAll(categoria))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar produtos: ${e.message}")
                }
            }

            // Inclui inativos — para a administração.
            get("/todos") {
                if (!call.autorizarAdmin()) return@get
                call.respond(HttpStatusCode.OK, produtoService.readAll(incluirInativos = true))
            }

            get("/{codigo}") {
                val codigo = call.parameters["codigo"]!!
                val produto = produtoService.read(codigo)
                if (produto == null || !produto.ativo) {
                    call.respond(HttpStatusCode.NotFound, "Produto $codigo não encontrado")
                } else {
                    call.respond(HttpStatusCode.OK, produto)
                }
            }

            post {
                if (!call.autorizarAdmin()) return@post
                try {
                    val produto = call.receive<Produto>().normalizado()
                    produto.validar()?.let { return@post call.respond(HttpStatusCode.BadRequest, it) }

                    if (produtoService.create(produto)) {
                        call.respond(HttpStatusCode.Created, produto.codigo)
                    } else {
                        call.respond(HttpStatusCode.Conflict, "Já existe um produto com o código ${produto.codigo}")
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, "Erro ao criar produto: ${e.message}")
                }
            }

            put("/{codigo}") {
                if (!call.autorizarAdmin()) return@put
                try {
                    val codigo = call.parameters["codigo"]!!
                    val produto = call.receive<Produto>().copy(codigo = codigo)
                    produto.validar()?.let { return@put call.respond(HttpStatusCode.BadRequest, it) }

                    if (produtoService.update(codigo, produto)) {
                        call.respond(HttpStatusCode.OK, "Produto $codigo atualizado com sucesso!")
                    } else {
                        call.respond(HttpStatusCode.NotFound, "Produto $codigo não encontrado")
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, "Erro ao atualizar produto: ${e.message}")
                }
            }

            delete("/{codigo}") {
                if (!call.autorizarAdmin()) return@delete
                val codigo = call.parameters["codigo"]!!
                if (produtoService.delete(codigo)) {
                    call.respond(HttpStatusCode.OK, "Produto $codigo excluído com sucesso!")
                } else {
                    call.respond(HttpStatusCode.NotFound, "Produto $codigo não encontrado")
                }
            }
        }
    }
}
