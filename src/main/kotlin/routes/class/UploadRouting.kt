package routes.`class`

import schemas.classes.UploadList
import schemas.classes.UploadDeleteRequest
import schemas.classes.UploadService
import services.S3ApiClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.Application
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing


fun Application.uploadRouting(uploadsService: UploadService) {
    routing {

        post("/upload") {
            try {
                val id = uploadsService.create(call.receive<UploadList>())
                call.respondText(id.toString(), status = HttpStatusCode.OK)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.BadRequest, "Erro ao processar JSON: ${e.message}")
            }
        }

        get("/upload") {
            try {
                call.respond(HttpStatusCode.OK, uploadsService.readAll())
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar aulas: ${e.message}")
            }
        }

        get("/upload/filter") {
            try {
                val classCode = call.request.queryParameters["classCode"]
                val active = call.request.queryParameters["active"]?.toBooleanStrictOrNull()
                if (classCode == null) {
                    call.respond(HttpStatusCode.BadRequest, "Parâmetro 'classCode' é obrigatório.")
                    return@get
                }
                call.respond(HttpStatusCode.OK, uploadsService.readFiltered(classCode, active))
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar uploads filtrados: ${e.message}")
            }
        }

        put("/upload") {
            try {
                val upload = call.receive<UploadList>()
                val id = upload.id ?: return@put call.respond(HttpStatusCode.BadRequest, "Campo 'id' é obrigatório.")
                call.respond(HttpStatusCode.OK, uploadsService.update(id, upload))
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao atualizar: ${e.message}")
            }
        }

        delete("/upload") {
            try {
                val req = call.receive<UploadDeleteRequest>()
                call.respond(HttpStatusCode.OK, uploadsService.delete(req.id))
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao deletar: ${e.message}")
            }
        }

        // ── Vídeo ────────────────────────────────────────────────────────────

        /**
         * POST /upload/video/{id}
         * Recebe o vídeo (multipart ou raw), envia para S3 e salva a key no banco.
         */
        post("/upload/video/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, "ID inválido.")

            println("[VIDEO] ▶ Iniciando upload para aula id=$id")
            println("[VIDEO]   Content-Type: ${call.request.contentType()}")
            println("[VIDEO]   Content-Length: ${call.request.headers["Content-Length"] ?: "não informado"} bytes")

            // Busca o registro para montar a key com classCodes + videoName
            val record = uploadsService.getById(id)
                ?: return@post call.respond(HttpStatusCode.NotFound, "Aula com id=$id não encontrada.")

            // Ex: "ENG101-ENG102" ou "ENG101"
            val codesSegment = record.classCodes
                .filter { it.isNotBlank() }
                .joinToString("-") { it.trim() }
                .ifBlank { "sem-classe" }

            // Ex: "aula-introducao" (sanitiza espaços/caracteres especiais)
            val nameSegment = (record.videoName ?: record.title)
                .trim()
                .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
                .take(100)

            // Key final: lessons/ENG101-ENG102/aula-introducao.mp4
            val s3Key = "lessons/$codesSegment/$nameSegment.mp4"
            println("[VIDEO]   S3 key: $s3Key")

            try {
                if (call.request.contentType().match(ContentType.MultiPart.FormData)) {
                    println("[VIDEO]   Modo: multipart/form-data")
                    var uploaded = false

                    call.receiveMultipart(formFieldLimit = 600 * 1024 * 1024L).forEachPart { part ->
                        if (part is PartData.FileItem && !uploaded) {
                            println("[VIDEO]   Lendo part '${part.name}'...")
                            val contentLength = call.request.headers["Content-Length"]?.toLongOrNull() ?: -1L
                            val savedKey = S3ApiClient.uploadVideo(s3Key, part.streamProvider(), contentLength)
                            println("[VIDEO]   Salvando key no banco: $savedKey")
                            val saved = uploadsService.saveVideoKey(id, savedKey)
                            if (saved) {
                                println("[VIDEO] ✅ Vídeo salvo no S3 para id=$id: $savedKey")
                                call.respond(HttpStatusCode.OK, mapOf("key" to savedKey))
                            } else {
                                call.respond(HttpStatusCode.NotFound, "Aula com id=$id não encontrada.")
                            }
                            uploaded = true
                        }
                        part.dispose()
                    }

                    if (!uploaded) {
                        call.respond(HttpStatusCode.BadRequest, "Nenhum arquivo enviado.")
                        return@post
                    }
                } else {
                    println("[VIDEO]   Modo: raw bytes")
                    val stream = call.receiveStream()
                    val contentLength = call.request.headers["Content-Length"]?.toLongOrNull() ?: -1L
                    val savedKey = S3ApiClient.uploadVideo(s3Key, stream, contentLength)
                    println("[VIDEO]   Salvando key no banco: $savedKey")
                    val saved = uploadsService.saveVideoKey(id, savedKey)
                    if (saved) {
                        println("[VIDEO] ✅ Vídeo salvo no S3 para id=$id: $savedKey")
                        call.respond(HttpStatusCode.OK, mapOf("key" to savedKey))
                    } else {
                        call.respond(HttpStatusCode.NotFound, "Aula com id=$id não encontrada.")
                    }
                }
            } catch (e: Throwable) {
                println("[VIDEO] ❌ Exceção ao processar upload para id=$id: ${e::class.simpleName}: ${e.message}")
                e.printStackTrace()
                call.respond(HttpStatusCode.InternalServerError, "Erro ao salvar vídeo: ${e.message}")
            }
        }

        /**
         * GET /upload/video/{id}
         * Retorna uma URL pré-assinada do S3 válida por 1 hora.
         * O player faz streaming diretamente do S3 com suporte a seek (Range requests).
         */
        get("/upload/video/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "ID inválido.")

            println("[VIDEO-GET] ▶ Buscando URL para aula id=$id")
            try {
                val s3Key = uploadsService.getVideoKey(id)
                if (s3Key == null) {
                    call.respond(HttpStatusCode.NotFound, "Vídeo não encontrado para id=$id.")
                    return@get
                }
                val presignedUrl = S3ApiClient.generatePresignedUrl(s3Key)
                println("[VIDEO-GET] ✅ URL gerada para id=$id")
                call.respond(HttpStatusCode.OK, mapOf("url" to presignedUrl))
            } catch (e: Throwable) {
                println("[VIDEO-GET] ❌ Exceção id=$id: ${e::class.simpleName}: ${e.message}")
                e.printStackTrace()
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar vídeo: ${e.message}")
            }
        }
    }
}