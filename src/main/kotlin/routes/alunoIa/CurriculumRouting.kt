package routes.alunoIa

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import schemas.alunoIa.IdiomaCurriculum

/**
 * Exposição pública do currículo (por idioma) para o front-end montar telas de
 * grade/trilha do curso (ex: "o que eu vou aprender"). Não expõe o conversationSeed
 * de cada dia — ver MissionDayPublicDto. Idioma vem da query param `idioma` (ex:
 * "espanhol", "alemao", "frances", "holandes"); se ausente ou desconhecido, cai no
 * currículo de inglês (ver Idioma.DEFAULT).
 */
fun Application.curriculumRouting() {
    routing {

        // ── GET /curriculo?idioma=... ────────────────────────────────────────
        // Lista todos os módulos, na ordem de progressão, com seus dias/tópicos.
        get("/curriculo") {
            val curriculo = IdiomaCurriculum.forCodigo(call.request.queryParameters["idioma"])
            call.respond(HttpStatusCode.OK, curriculo.allModulesPublic())
        }

        // ── GET /curriculo/{moduleId}?idioma=... ─────────────────────────────
        // Dias de um módulo específico (aceita "module1" ou só "1", por exemplo).
        get("/curriculo/{moduleId}") {
            val moduleId = call.parameters["moduleId"]
            if (moduleId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "Parâmetro 'moduleId' é obrigatório.")
                return@get
            }

            val curriculo = IdiomaCurriculum.forCodigo(call.request.queryParameters["idioma"])
            val dias = curriculo.publicDaysOf(moduleId)
            if (dias == null) {
                call.respond(HttpStatusCode.NotFound, "Módulo não encontrado.")
            } else {
                call.respond(HttpStatusCode.OK, dias)
            }
        }
    }
}
