package routes.alunoIa

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import schemas.alunoIa.AlunoIaDto
import schemas.alunoIa.AlunoIaService
import schemas.alunoIa.MissionFluencyCurriculum
import schemas.alunoIa.StatusAssinatura
import java.time.Instant

@Serializable
data class AssinaturaStatusDto(
    val assinante: Boolean,
    val ativa: Boolean,
    val cancelada: Boolean,
    val pagamentoFalhou: Boolean,
    val status: String,
    val atualizadoEm: String,
)

fun Application.alunoIaRouting(alunoIaService: AlunoIaService) {
    routing {

        // ── GET /aluno-ia ────────────────────────────────────────────────────
        get("/aluno-ia") {
            try {
                val alunos = alunoIaService.readAll()
                call.respond(HttpStatusCode.OK, alunos)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar alunos: ${e.message}")
            }
        }

        // ── GET /aluno-ia/{userId} ───────────────────────────────────────────
        get("/aluno-ia/{userId}") {
            val userId = call.parameters["userId"]
            if (userId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "Parâmetro 'userId' é obrigatório.")
                return@get
            }

            try {
                val aluno = alunoIaService.readByUserId(userId)
                if (aluno == null) {
                    call.respond(HttpStatusCode.NotFound, "Aluno não encontrado.")
                } else {
                    call.respond(HttpStatusCode.OK, aluno)
                }
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar aluno: ${e.message}")
            }
        }

        // ── POST /aluno-ia ───────────────────────────────────────────────────
        post("/aluno-ia") {
            try {
                val aluno = call.receive<AlunoIaDto>()
                val created = alunoIaService.create(aluno)
                call.respond(HttpStatusCode.Created, created)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.BadRequest, "Erro ao criar aluno: ${e.message}")
            }
        }

        // ── PUT /aluno-ia/{userId} ───────────────────────────────────────────
        put("/aluno-ia/{userId}") {
            val userId = call.parameters["userId"]
            if (userId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "Parâmetro 'userId' é obrigatório.")
                return@put
            }

            try {
                val aluno = call.receive<AlunoIaDto>()
                val rowsUpdated = alunoIaService.update(userId, aluno)
                if (rowsUpdated == 0) {
                    call.respond(HttpStatusCode.NotFound, "Aluno não encontrado.")
                } else {
                    call.respond(HttpStatusCode.OK, aluno.copy(userId = userId))
                }
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao atualizar aluno: ${e.message}")
            }
        }

        // ── POST /aluno-ia/{userId}/avancar-missao ───────────────────────────
        // Chamado pelo front-end quando o aluno sinaliza que assistiu/concluiu
        // o conteúdo da missão do dia, liberando a próxima missão.
        post("/aluno-ia/{userId}/avancar-missao") {
            val userId = call.parameters["userId"]
            if (userId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "Parâmetro 'userId' é obrigatório.")
                return@post
            }

            try {
                val aluno = alunoIaService.readByUserId(userId)
                if (aluno == null) {
                    call.respond(HttpStatusCode.NotFound, "Aluno não encontrado.")
                    return@post
                }

                val moduloAtual = aluno.moduloAtual.ifBlank { "module1" }
                val diaAtual = aluno.missaoAtual.toIntOrNull() ?: 1
                val proximoDia = (diaAtual + 1).coerceAtMost(MissionFluencyCurriculum.sizeOf(moduloAtual))

                val atualizado = aluno.copy(
                    moduloAtual = moduloAtual,
                    missaoAtual = proximoDia.toString(),
                    ultimaSessao = Instant.now().toString(),
                )
                alunoIaService.update(userId, atualizado)
                call.respond(HttpStatusCode.OK, atualizado)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao avançar missão: ${e.message}")
            }
        }

        // ── GET /aluno-ia/{userId}/assinatura ────────────────────────────────
        // Consumido pelo front-end para saber se o aluno é assinante, se a
        // assinatura está ativa, foi cancelada, ou se algum pagamento falhou.
        get("/aluno-ia/{userId}/assinatura") {
            val userId = call.parameters["userId"]
            if (userId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "Parâmetro 'userId' é obrigatório.")
                return@get
            }

            try {
                val aluno = alunoIaService.readByUserId(userId)
                if (aluno == null) {
                    call.respond(HttpStatusCode.NotFound, "Aluno não encontrado.")
                    return@get
                }

                val status = aluno.statusAssinatura.ifBlank { StatusAssinatura.INATIVA }
                call.respond(HttpStatusCode.OK, AssinaturaStatusDto(
                    assinante = status != StatusAssinatura.INATIVA,
                    ativa = status == StatusAssinatura.ATIVA,
                    cancelada = status == StatusAssinatura.CANCELADA,
                    pagamentoFalhou = status == StatusAssinatura.PAGAMENTO_FALHOU,
                    status = status,
                    atualizadoEm = aluno.assinaturaAtualizadaEm,
                ))
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao buscar assinatura: ${e.message}")
            }
        }

        // ── DELETE /aluno-ia/{userId} ────────────────────────────────────────
        delete("/aluno-ia/{userId}") {
            val userId = call.parameters["userId"]
            if (userId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "Parâmetro 'userId' é obrigatório.")
                return@delete
            }

            try {
                alunoIaService.delete(userId)
                call.respond(HttpStatusCode.OK, "Aluno removido.")
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Erro ao remover aluno: ${e.message}")
            }
        }
    }
}
