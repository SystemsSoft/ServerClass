package routes.estrelasLeiria


import com.class_erp.EmailService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import schemas.estrelasLeiria.BilheteManualDTO
import schemas.estrelasLeiria.Indicado
import schemas.estrelasLeiria.IndicadoService
import java.util.UUID
import kotlin.concurrent.thread

fun Application.adminTicketRouting(
    indicadoService: IndicadoService,
    database: Database,
    emailService: EmailService
) {
    routing {
        staticResources(remotePath = "/painel", basePackage = "static")

        post("/admin/criar-bilhete-manual") {
            try {
                val dados = call.receive<BilheteManualDTO>()
                val codigoBilhete = "MANUAL_" + UUID.randomUUID().toString().substring(0, 8).uppercase()

                if (dados.desejaParticiparVotacao) {
                    val novoIndicado = Indicado(
                        categoriaId = dados.categoriaId,
                        nome = dados.nome,
                        instagram = dados.instagram,
                        imageData = dados.fotoUrl,
                        descricaoDetalhada = dados.descricao,
                        desejaParticiparVotacao = true,
                        stripeId = codigoBilhete,
                        email = dados.email,
                        quantidade = dados.quantidade
                    )

                    indicadoService.create(novoIndicado, UUID.randomUUID().toString())

                } else {
                    newSuspendedTransaction(Dispatchers.IO, db = database) {
                        InscritosTable.insert {
                            it[id] = UUID.randomUUID().toString()
                            it[nome] = dados.nome
                            it[instagram] = dados.instagram
                            it[categoriaId] = dados.categoriaId
                            it[descricao] = dados.descricao
                            it[imageData] = dados.fotoUrl
                            it[desejaParticiparVotacao] = false
                            it[stripeId] = codigoBilhete
                            it[email] = dados.email
                            it[quantidade] = dados.quantidade
                            it[checkIn] = false
                        }
                    }
                }

                thread {
                    try {
                        val qrBytes = gerarQrCodeBytes(codigoBilhete)
                        emailService.enviarBilhete(
                            destinatario = dados.email,
                            nomeParticipante = dados.nome,
                            qrCodeBytes = qrBytes,
                            quantidade = dados.quantidade
                        )
                        println(">>> E-mail manual enviado para ${dados.email}")
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                call.response.status(HttpStatusCode.Created)
                call.respond(mapOf(
                    "status" to "SUCESSO",
                    "codigo" to codigoBilhete
                ))

            } catch (e: Exception) {
                e.printStackTrace()
                call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Erro: ${e.message}"))
            }
        }
    }
}