package routes.inovacloudRoute

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import schemas.inovacloudSchema.VideoService
import schemas.inovacloudSchema.VideoUpdateRequest
import services.InovaCloudS3Client
import java.util.UUID

fun Application.videoRouting(videoService: VideoService) {
    routing {

        route("/videos") {

            // ── POST /videos ──────────────────────────────────────────────
            // Multipart: field "email" + file field "file"
            post {
                try {
                    var email: String? = null
                    var fileName: String? = null
                    var mimeType: String = "video/mp4"
                    var fileBytes: ByteArray? = null

                    call.receiveMultipart().forEachPart { part ->
                        when (part) {
                            is PartData.FormItem -> {
                                if (part.name == "email") email = part.value
                            }
                            is PartData.FileItem -> {
                                fileName  = part.originalFileName ?: "video"
                                mimeType  = part.contentType?.toString() ?: "video/mp4"
                                fileBytes = part.streamProvider().readBytes()
                            }
                            else -> Unit
                        }
                        part.dispose()
                    }

                    val resolvedEmail = email
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("message" to "Campo 'email' é obrigatório.")
                        )

                    val bytes = fileBytes
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("message" to "Nenhum arquivo enviado.")
                        )

                    val id = UUID.randomUUID().toString()
                    val url = InovaCloudS3Client.uploadVideoFile(
                        fileId = id,
                        bytes = bytes,
                        mimeType = mimeType,
                        originalFileName = fileName ?: "video",
                        userEmail = resolvedEmail,
                    )

                    val bucketBase = "https://inova-cloud.s3.us-east-2.amazonaws.com/"
                    val s3Key = url.removePrefix(bucketBase)

                    val dto = videoService.create(
                        id        = id,
                        email     = resolvedEmail,
                        name      = fileName ?: "video",
                        sizeBytes = bytes.size.toLong(),
                        mimeType  = mimeType,
                        s3Key     = s3Key,
                        url       = url,
                    )

                    call.respond(HttpStatusCode.Created, mapOf("data" to dto))
                } catch (e: Exception) {
                    println("[VIDEOS] ❌ Erro no upload: ${e.message}")
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("message" to "Erro ao enviar vídeo: ${e.message}")
                    )
                }
            }

            // ── GET /videos?email=... ────────────────────────────────────
            get {
                try {
                    val email = call.request.queryParameters["email"]
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("message" to "Parâmetro 'email' é obrigatório.")
                        )

                    val videos = videoService.readByEmail(email)
                    call.respond(HttpStatusCode.OK, mapOf("data" to videos))
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("message" to "Erro ao listar vídeos: ${e.message}")
                    )
                }
            }

            // ── GET /videos/{id} ─────────────────────────────────────────
            get("/{id}") {
                try {
                    val id = call.parameters["id"]
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("message" to "ID não informado.")
                        )

                    val dto = videoService.readById(id)
                        ?: return@get call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("message" to "Vídeo $id não encontrado.")
                        )

                    call.respond(HttpStatusCode.OK, mapOf("data" to dto))
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("message" to "Erro ao buscar vídeo: ${e.message}")
                    )
                }
            }

            // ── PUT /videos/{id} ─────────────────────────────────────────
            // Body JSON: { "email": "...", "name": "..." }
            put("/{id}") {
                try {
                    val id = call.parameters["id"]
                        ?: return@put call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("message" to "ID não informado.")
                        )

                    val body = call.receive<VideoUpdateRequest>()
                    val newName = body.name
                        ?: return@put call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("message" to "Campo 'name' é obrigatório.")
                        )

                    val dto = videoService.update(id, newName)
                        ?: return@put call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("message" to "Vídeo $id não encontrado.")
                        )

                    call.respond(HttpStatusCode.OK, mapOf("data" to dto))
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("message" to "Erro ao atualizar vídeo: ${e.message}")
                    )
                }
            }

            // ── DELETE /videos/{id}?email=... ────────────────────────────
            delete("/{id}") {
                try {
                    val id = call.parameters["id"]
                        ?: return@delete call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("message" to "ID não informado.")
                        )

                    val email = call.request.queryParameters["email"]
                        ?: return@delete call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("message" to "Parâmetro 'email' é obrigatório.")
                        )

                    val s3Key = videoService.delete(id, email)
                        ?: return@delete call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("message" to "Vídeo $id não encontrado ou não pertence a este usuário.")
                        )

                    runCatching { InovaCloudS3Client.deleteVideoObject(s3Key, email) }
                        .onFailure { println("[VIDEOS] ⚠️ Falha ao remover S3 key=$s3Key: ${it.message}") }

                    call.respond(HttpStatusCode.OK, mapOf("message" to "Vídeo $id removido com sucesso."))
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("message" to "Erro ao remover vídeo: ${e.message}")
                    )
                }
            }
        }
    }
}

