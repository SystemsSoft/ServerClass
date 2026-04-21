package routes.`class`

import schemas.classes.UploadList
import schemas.classes.UploadDeleteRequest
import schemas.classes.UploadService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.Application
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
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
                call.respond(HttpStatusCode.OK, id)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.BadRequest, "Erro ao processar JSON: ${e.message}")
            }
        }

        get("/upload") {
            try {
                call.respond(HttpStatusCode.OK, uploadsService.readAll())
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar classes: ${e.message}")
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

                val filteredUploads = uploadsService.readFiltered(classCode, active)
                call.respond(HttpStatusCode.OK, filteredUploads)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar uploads filtrados: ${e.message}")
            }
        }


        put("/upload") {
            try {
                val upload = call.receive<UploadList>()
                val id = upload.id
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, "Campo 'id' é obrigatório para update.")
                    return@put
                }

                call.respond(
                    HttpStatusCode.OK, uploadsService.update(
                        id,
                        upload
                    )
                )
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar classes: ${e.message}")
            }
        }

        delete("/upload") {
            try {
                val classe = call.receive<UploadDeleteRequest>()
                call.respond(HttpStatusCode.OK, uploadsService.delete(classe.id))
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar classes: ${e.message}")
            }
        }

        // ── Vídeo ────────────────────────────────────────────────────────────

        /**
         * POST /upload/video/{id}
         * Aceita dois formatos:
         *   1. multipart/form-data com campo "video" (recomendado para Flutter Web / browsers)
         *   2. Raw bytes no body (application/octet-stream ou video/mp4)
         *
         * O multipart/form-data é a forma correta para Flutter Web pois evita o problema de
         * "Invalid array length" ao tentar alocar arquivos grandes como Uint8List em memória.
         */
        post("/upload/video/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, "ID inválido.")

            println("[VIDEO] ▶ Iniciando upload para aula id=$id")
            println("[VIDEO]   Content-Type: ${call.request.contentType()}")
            println("[VIDEO]   Content-Length: ${call.request.headers["Content-Length"] ?: "não informado"} bytes")

            try {
                val contentType = call.request.contentType()
                val videoBytes: ByteArray

                if (contentType.match(ContentType.MultiPart.FormData)) {
                    // ── Modo multipart/form-data (Flutter Web, browsers) ──────────────
                    println("[VIDEO]   Modo: multipart/form-data")
                    var collected: ByteArray? = null
                    var partCount = 0

                    val multipart = call.receiveMultipart(formFieldLimit = 600 * 1024 * 1024L)
                    multipart.forEachPart { part ->
                        partCount++
                        println("[VIDEO]   Part #$partCount — tipo=${part::class.simpleName} nome='${part.name}'")
                        if (part is PartData.FileItem && collected == null) {
                            println("[VIDEO]   Lendo bytes da part '${part.name}'...")
                            try {
                                collected = part.streamProvider().readBytes()
                                println("[VIDEO]   Part lida: ${collected!!.size} bytes (%.1f MB)".format(collected!!.size / 1_048_576.0))
                            } catch (e: Exception) {
                                println("[VIDEO] ❌ Erro ao ler bytes da part: ${e::class.simpleName}: ${e.message}")
                                e.printStackTrace()
                            }
                        }
                        part.dispose()
                    }
                    println("[VIDEO]   Total de parts recebidas: $partCount")

                    if (collected == null || collected!!.isEmpty()) {
                        println("[VIDEO] ❌ Nenhum arquivo encontrado nas parts.")
                        call.respond(HttpStatusCode.BadRequest, "Nenhum arquivo enviado. Use o campo 'video' no form-data.")
                        return@post
                    }
                    videoBytes = collected!!

                } else {
                    // ── Modo raw bytes (application/octet-stream / video/mp4) ─────────
                    println("[VIDEO]   Modo: raw bytes")
                    videoBytes = call.receive<ByteArray>()
                    if (videoBytes.isEmpty()) {
                        println("[VIDEO] ❌ Body vazio recebido.")
                        call.respond(HttpStatusCode.BadRequest, "Body vazio: nenhum byte recebido.")
                        return@post
                    }
                }

                println("[VIDEO]   Total recebido: ${videoBytes.size} bytes (%.1f MB)".format(videoBytes.size / 1_048_576.0))
                println("[VIDEO]   Primeiros 4 bytes (magic): ${videoBytes.take(4).map { "0x%02X".format(it) }}")

                println("[VIDEO]   Salvando no banco...")
                val saved = uploadsService.saveVideo(id, videoBytes)

                if (saved) {
                    println("[VIDEO] ✅ Vídeo salvo com sucesso para id=$id")
                    call.respond(HttpStatusCode.OK, "Vídeo salvo com sucesso (${videoBytes.size} bytes).")
                } else {
                    println("[VIDEO] ❌ Aula id=$id não encontrada no banco.")
                    call.respond(HttpStatusCode.NotFound, "Aula com id=$id não encontrada.")
                }
            } catch (e: Throwable) {
                println("[VIDEO] ❌ Exceção ao processar upload para id=$id: ${e::class.simpleName}: ${e.message}")
                e.printStackTrace()
                call.respond(HttpStatusCode.InternalServerError, "Erro ao salvar vídeo: ${e.message}")
            }
        }

        /**
         * GET /upload/video/{id}
         * Devolve os bytes do vídeo com Content-Type: video/mp4 para reprodução direta.
         */
        get("/upload/video/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "ID inválido.")

            println("[VIDEO-GET] ▶ Buscando vídeo para aula id=$id")
            try {
                val videoBytes = uploadsService.getVideo(id)
                if (videoBytes != null) {
                    println("[VIDEO-GET] ✅ Vídeo encontrado para id=$id — ${videoBytes.size} bytes (%.1f MB)".format(videoBytes.size / 1_048_576.0))
                    call.respondBytes(videoBytes, ContentType.parse("video/mp4"))
                } else {
                    println("[VIDEO-GET] ❌ Vídeo não encontrado no banco para id=$id")
                    call.respond(HttpStatusCode.NotFound, "Vídeo não encontrado para id=$id.")
                }
            } catch (e: Throwable) {
                println("[VIDEO-GET] ❌ Exceção ao buscar vídeo id=$id: ${e::class.simpleName}: ${e.message}")
                e.printStackTrace()
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar vídeo: ${e.message}")
            }
        }
    }
}