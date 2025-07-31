package routes.`class`

import UploadList
import UploadListDto
import UploadService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
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
                val fileType = call.request.queryParameters["fileType"]

                if (classCode == null || fileType == null) {
                    call.respond(HttpStatusCode.BadRequest, "Parâmetros 'classCode' e 'fileType' são obrigatórios.")
                    return@get
                }

                val filteredUploads = uploadsService.readFiltered(classCode, fileType)
                call.respond(HttpStatusCode.OK, filteredUploads)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar uploads filtrados: ${e.message}")
            }
        }


        put("/upload") {
            try {
                val upload = call.receive<UploadListDto>()
                call.respond(
                    HttpStatusCode.OK, uploadsService.update(
                        upload.id,
                        upload
                    )
                )
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar classes: ${e.message}")
            }
        }

        delete("/upload") {
            try {
                val classe = call.receive<UploadListDto>()
                call.respond(HttpStatusCode.OK, uploadsService.delete(classe.id))
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar classes: ${e.message}")
            }
        }
    }
}