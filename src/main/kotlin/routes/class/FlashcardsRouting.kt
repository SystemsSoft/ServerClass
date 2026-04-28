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
import schemas.classes.FlashcardDto
import schemas.classes.FlashcardService

fun Application.flashcardsRouting(flashcardService: FlashcardService) {
    routing {

        // ── GET /flashcards?studentName=&className= ───────────────────────────
        get("/flashcards") {
            val studentName = call.request.queryParameters["studentName"]
            val className   = call.request.queryParameters["className"]

            if (studentName.isNullOrBlank() || className.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "Parâmetros 'studentName' e 'className' são obrigatórios.")
                return@get
            }

            try {
                val cards = flashcardService.readAll(studentName, className)
                call.respond(HttpStatusCode.OK, cards)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar flashcards: ${e.message}")
            }
        }

        // ── POST /flashcards ─────────────────────────────────────────────────
        post("/flashcards") {
            try {
                val card = call.receive<FlashcardDto>()
                val created = flashcardService.create(card)
                call.respond(HttpStatusCode.Created, created)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.BadRequest, "Erro ao criar flashcard: ${e.message}")
            }
        }

        // ── PUT /flashcards/{id}?studentName=&className= ─────────────────────
        put("/flashcards/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, "ID inválido.")
                return@put
            }

            val studentName = call.request.queryParameters["studentName"]
            val className   = call.request.queryParameters["className"]

            if (studentName.isNullOrBlank() || className.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "Parâmetros 'studentName' e 'className' são obrigatórios.")
                return@put
            }

            try {
                val card = call.receive<FlashcardDto>()
                val updated = flashcardService.update(id, card)
                call.respond(HttpStatusCode.OK, updated)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao atualizar flashcard: ${e.message}")
            }
        }

        // ── DELETE /flashcards/{id}?studentName=&className= ──────────────────
        delete("/flashcards/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, "ID inválido.")
                return@delete
            }

            try {
                flashcardService.delete(id)
                call.respond(HttpStatusCode.OK, "Flashcard removido.")
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao remover flashcard: ${e.message}")
            }
        }

        // ── DELETE /flashcards?studentName=&className= (delete all) ──────────
        delete("/flashcards") {
            val studentName = call.request.queryParameters["studentName"]
            val className   = call.request.queryParameters["className"]

            if (studentName.isNullOrBlank() || className.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "Parâmetros 'studentName' e 'className' são obrigatórios.")
                return@delete
            }

            try {
                flashcardService.deleteAll(studentName, className)
                call.respond(HttpStatusCode.OK, "Todos os flashcards removidos.")
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao remover flashcards: ${e.message}")
            }
        }
    }
}

