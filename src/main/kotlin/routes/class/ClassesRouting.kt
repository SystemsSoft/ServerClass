package routes.`class`

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import schemas.classes.ClassesList
import schemas.classes.ClassDto
import schemas.classes.ClassesListService

fun Application.classesRouting(classesListService: ClassesListService) {
    routing {

        post("/classes") {
            try {
                val classe = call.receive<ClassesList>()
                val id = classesListService.create(classe)
                call.respond(HttpStatusCode.OK, id)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.BadRequest, "Erro ao processar JSON: ${e.message}")
            }
        }

        get("/classes") {
            try {
                val classes = classesListService.readAll()
                call.respond(HttpStatusCode.OK, classes)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar classes: ${e.message}")
            }
        }

        put("/classes") {
            try {
                val classe = call.receive<ClassDto>()
                call.respond(
                    HttpStatusCode.OK, classesListService.update(
                        classe.id,
                        classe
                    )
                )
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar classes: ${e.message}")
            }
        }

        delete("/classes") {
            try {
                val classe = call.receive<ClassDto>()
                call.respond(HttpStatusCode.OK, classesListService.delete(classe.id))
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar classes: ${e.message}")
            }
        }
    }
}