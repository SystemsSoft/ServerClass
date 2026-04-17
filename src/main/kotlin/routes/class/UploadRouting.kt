package routes.`class`

import UploadList
import UploadDeleteRequest
import UploadService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
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
         * Corpo: bytes brutos do MP4 (Content-Type: video/mp4 ou application/octet-stream)
         * Salva o vídeo direto na coluna videoData da aula {id}.
         */
        post("/upload/video/{id}") {
            try {
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, "ID inválido.")

                // receive<ByteArray>() lê o body completo de forma correta no Ktor 3
                val videoBytes = call.receive<ByteArray>()

                if (videoBytes.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, "Body vazio: nenhum byte recebido.")
                    return@post
                }

                println("Upload vídeo id=$id — ${videoBytes.size} bytes recebidos")

                val saved = uploadsService.saveVideo(id, videoBytes)
                if (saved) {
                    call.respond(HttpStatusCode.OK, "Vídeo salvo com sucesso (${videoBytes.size} bytes).")
                } else {
                    call.respond(HttpStatusCode.NotFound, "Aula com id=$id não encontrada.")
                }
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao salvar vídeo: ${e.message}")
            }
        }

        /**
         * GET /upload/video/{id}
         * Devolve os bytes do vídeo com Content-Type: video/mp4 para reprodução direta.
         */
        get("/upload/video/{id}") {
            try {
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "ID inválido.")

                val videoBytes = uploadsService.getVideo(id)
                if (videoBytes != null) {
                    call.respondBytes(videoBytes, ContentType.parse("video/mp4"))
                } else {
                    call.respond(HttpStatusCode.NotFound, "Vídeo não encontrado para id=$id.")
                }
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar vídeo: ${e.message}")
            }
        }
    }
}