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
import schemas.inovacloudSchema.PhotoService
import schemas.inovacloudSchema.PhotoUpdateRequest
import services.InovaCloudS3Client
import services.S3ApiClient
import java.util.UUID

fun Application.photoRouting(photoService: PhotoService) {
    routing {

        route("/photos") {

            // ── POST /photos ─────────────────────────────────────────────────
            // Multipart: field "email" + file field "file"
            post {
                try {
                    var email: String? = null
                    var fileName: String? = null
                    var mimeType: String = "image/jpeg"
                    var fileBytes: ByteArray? = null

                    call.receiveMultipart().forEachPart { part ->
                        when (part) {
                            is PartData.FormItem -> {
                                if (part.name == "email") email = part.value
                            }
                            is PartData.FileItem -> {
                                fileName  = part.originalFileName ?: "photo"
                                mimeType  = part.contentType?.toString() ?: "image/jpeg"
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
                    val url = InovaCloudS3Client.uploadPhotoFile(
                        fileId = id,
                        bytes = bytes,
                        mimeType = mimeType,
                        originalFileName = fileName ?: "photo",
                    )

                    // Derive the s3Key from the URL
                    val bucketBase = "https://repo-english-class.s3.us-east-2.amazonaws.com/"
                    val s3Key = url.removePrefix(bucketBase)

                    val dto = photoService.create(
                        id        = id,
                        email     = resolvedEmail,
                        name      = fileName ?: "photo",
                        sizeBytes = bytes.size.toLong(),
                        mimeType  = mimeType,
                        s3Key     = s3Key,
                        url       = url,
                    )

                    call.respond(HttpStatusCode.Created, mapOf("data" to dto))
                } catch (e: Exception) {
                    println("[PHOTOS] ❌ Erro no upload: ${e.message}")
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("message" to "Erro ao enviar foto: ${e.message}")
                    )
                }
            }

            // ── GET /photos?email=... ────────────────────────────────────────
            get {
                try {
                    val email = call.request.queryParameters["email"]
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("message" to "Parâmetro 'email' é obrigatório.")
                        )

                    val photos = photoService.readByEmail(email)
                    call.respond(HttpStatusCode.OK, mapOf("data" to photos))
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("message" to "Erro ao listar fotos: ${e.message}")
                    )
                }
            }

            // ── GET /photos/{id}?email=... ───────────────────────────────────
            get("/{id}") {
                try {
                    val id = call.parameters["id"]
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("message" to "ID não informado.")
                        )

                    val dto = photoService.readById(id)
                        ?: return@get call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("message" to "Foto $id não encontrada.")
                        )

                    call.respond(HttpStatusCode.OK, mapOf("data" to dto))
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("message" to "Erro ao buscar foto: ${e.message}")
                    )
                }
            }

            // ── PUT /photos/{id} ─────────────────────────────────────────────
            // Body JSON: { "email": "...", "name": "..." }
            put("/{id}") {
                try {
                    val id = call.parameters["id"]
                        ?: return@put call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("message" to "ID não informado.")
                        )

                    val body = call.receive<PhotoUpdateRequest>()
                    val newName = body.name
                        ?: return@put call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("message" to "Campo 'name' é obrigatório.")
                        )

                    val dto = photoService.update(id, newName)
                        ?: return@put call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("message" to "Foto $id não encontrada.")
                        )

                    call.respond(HttpStatusCode.OK, mapOf("data" to dto))
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("message" to "Erro ao atualizar foto: ${e.message}")
                    )
                }
            }

            // ── DELETE /photos/{id}?email=... ────────────────────────────────
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

                    val s3Key = photoService.delete(id, email)
                        ?: return@delete call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("message" to "Foto $id não encontrada ou não pertence a este usuário.")
                        )

                    runCatching { InovaCloudS3Client.deleteObject(s3Key) }
                        .onFailure { println("[PHOTOS] ⚠️ Falha ao remover S3 key=$s3Key: ${it.message}") }

                    call.respond(HttpStatusCode.OK, mapOf("message" to "Foto $id removida com sucesso."))
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("message" to "Erro ao remover foto: ${e.message}")
                    )
                }
            }
        }
    }
}

