package routes.estrelasLeiria

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import schemas.estrelasLeiria.Indicado
import schemas.estrelasLeiria.IndicadoService
import schemas.estrelasLeiria.IndicadoUpdate
import services.S3ApiClient // <--- Não esqueça de importar
import java.util.UUID

fun Application.indicadoRouting(indicadoService: IndicadoService) {

    routing {

        route("/indicados") {

            // =================================================================
            // POST: CRIAR INDICADO (ADMIN) - COM UPLOAD S3
            // =================================================================
            post {
                val generatedId = UUID.randomUUID().toString()

                try {
                    val novoIndicado = call.receive<Indicado>()

                    if (novoIndicado.nome.isBlank() || novoIndicado.imageData.isBlank()) {
                        return@post call.respond(HttpStatusCode.BadRequest, "Dados incompletos.")
                    }

                    // ---------------------------------------------------------
                    // 1. UPLOAD PARA O S3
                    // ---------------------------------------------------------
                    // Passamos o ID gerado e o Base64. A função retorna a URL pública.
                    val urlS3 = S3ApiClient.uploadImage(generatedId, novoIndicado.imageData)

                    // ---------------------------------------------------------
                    // 2. SUBSTITUI O BASE64 PELA URL
                    // ---------------------------------------------------------
                    val indicadoComUrl = novoIndicado.copy(imageData = urlS3)

                    // ---------------------------------------------------------
                    // 3. SALVA NO BANCO (Agora leve, só com texto)
                    // ---------------------------------------------------------
                    val id = indicadoService.create(indicadoComUrl, generatedId)

                    call.respond(HttpStatusCode.Created, id)

                } catch (e: Exception) {
                    e.printStackTrace() // Log no console do servidor
                    call.respond(HttpStatusCode.InternalServerError, "Erro: ${e.localizedMessage}")
                }
            }

            // =================================================================
            // GET: LISTAR TODOS
            // =================================================================
            get {
                try {
                    val indicados = indicadoService.readAll()
                    // Agora o campo 'imageData' retornará URLs (https://...)
                    // e não mais Base64
                    call.respond(HttpStatusCode.OK, indicados)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Erro: ${e.localizedMessage}")
                }
            }

            // =================================================================
            // PUT: ATUALIZAR
            // =================================================================
            put("/{id}") {
                try {
                    val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, "ID ausente")

                    // Nota: O seu DTO 'IndicadoUpdate' atual NÃO tem campo de imagem.
                    // Se você quiser permitir atualizar a foto no futuro, terá que:
                    // 1. Adicionar imageData no IndicadoUpdate
                    // 2. Chamar o S3ApiClient.uploadImage(id, novaImagem) aqui também.

                    val indicadoUpdate = call.receive<IndicadoUpdate>()
                    indicadoService.update(id, indicadoUpdate)

                    call.respond(HttpStatusCode.OK, "Atualizado com sucesso!")
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Erro: ${e.localizedMessage}")
                }
            }

            // =================================================================
            // DELETE: REMOVER
            // =================================================================
            delete("/{id}") {
                try {
                    val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)

                    // Opcional: Você poderia também deletar a imagem do S3 aqui
                    // Mas geralmente não faz mal deixar lá (soft delete) ou limpar depois.

                    indicadoService.delete(id)
                    call.respond(HttpStatusCode.OK, "Excluído com sucesso")
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Erro: ${e.localizedMessage}")
                }
            }
        }
    }
}