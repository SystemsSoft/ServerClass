package com.class_erp

import com.google.zxing.BarcodeFormat
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import com.stripe.Stripe
import com.stripe.net.Webhook
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.koin.ktor.ext.inject
import schemas.estrelasLeiria.Indicado
import schemas.estrelasLeiria.IndicadoService
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.concurrent.thread

// --- DTOs ---
@Serializable
data class InscricaoDTO(
    val nome: String,
    val instagram: String,
    val categoriaId: String,
    val descricaoDetalhada: String,
    val imageData: String,
    val desejaParticiparVotacao: Boolean
)

@Serializable
data class InscricaoResposta(
    val id: Int,
    val status: String
)

// --- TABELA TEMPORÁRIA (Nome alterado para forçar recriação correta) ---
object PreInscricoesTable : Table("pre_inscricoes_v2") {
    val id = integer("id").autoIncrement()
    val nome = varchar("nome", 255)
    val instagram = varchar("instagram", 100)
    val categoriaId = varchar("categoria_id", 50)
    val descricao = text("descricao")

    // Agora vai criar como LONGTEXT corretamente na nova tabela
    val imageData = largeText("image_data")

    val desejaParticiparVotacao = bool("deseja_participar_votacao").default(false)
    val status = varchar("status", 20).default("AGUARDANDO")

    override val primaryKey = PrimaryKey(id)
}

// Função auxiliar (Declarada apenas uma vez aqui fora)
fun gerarQrCodeBytes(conteudo: String): ByteArray {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(conteudo, BarcodeFormat.QR_CODE, 500, 500)
        val outputStream = ByteArrayOutputStream()
        MatrixToImageWriter.writeToStream(bitMatrix, "PNG", outputStream)
        outputStream.toByteArray()
    } catch (e: Exception) {
        ByteArray(0)
    }
}

fun Application.configureStripeModule() {
    val emailService = EmailService()
    val indicadoService by inject<IndicadoService>()

    val db = org.jetbrains.exposed.sql.transactions.TransactionManager.defaultDatabase
    if (db != null) {
        transaction(db) {
            // Vai criar a tabela "pre_inscricoes_v2" do zero com as colunas certas
            SchemaUtils.create(PreInscricoesTable)
            try {
                SchemaUtils.createMissingTablesAndColumns(PreInscricoesTable)
            } catch (e: Exception) {
                println("Aviso migração: ${e.message}")
            }
        }
    }

    val stripeKey = System.getenv("STRIPE_API_KEY")
    val webhookSecret = System.getenv("STRIPE_WEBHOOK_SECRET")

    if (stripeKey != null) Stripe.apiKey = stripeKey

    routing {

        // --------------------------------------------------
        // 1. ROTA DE QR CODE
        // --------------------------------------------------
        get("/ingresso/qrcode/{id}") {
            val idIndicado = call.parameters["id"]
            if (idIndicado == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }

            val stripeId = newSuspendedTransaction(Dispatchers.IO) {
                schemas.estrelasLeiria.IndicadoService.IndicadoTable
                    .selectAll()
                    .where { schemas.estrelasLeiria.IndicadoService.IndicadoTable.id eq idIndicado }
                    .map { it[schemas.estrelasLeiria.IndicadoService.IndicadoTable.stripeId] }
                    .singleOrNull()
            }

            if (stripeId != null) {
                val bytes = gerarQrCodeBytes(stripeId)
                call.respondBytes(bytes, ContentType.Image.PNG)
            } else {
                call.respond(HttpStatusCode.NotFound, "Ingresso não encontrado")
            }
        }

        // --------------------------------------------------
        // 2. ADMIN: SINCRONIZAR
        // --------------------------------------------------
        get("/admin/sincronizar") {
            val listaBilhetes = newSuspendedTransaction(Dispatchers.IO) {
                IndicadoService.IndicadoTable
                    .selectAll()
                    .where { IndicadoService.IndicadoTable.stripeId.isNotNull() }
                    .map {
                        mapOf(
                            "stripeId" to it[IndicadoService.IndicadoTable.stripeId],
                            "nome" to it[IndicadoService.IndicadoTable.nome],
                            "categoria" to it[IndicadoService.IndicadoTable.categoriaId],
                            "status" to "VALIDO"
                        )
                    }
            }
            call.respond(listaBilhetes)
        }

        // --------------------------------------------------
        // 3. ADMIN: VALIDAR UM
        // --------------------------------------------------
        get("/admin/validar/{stripeId}") {
            val codigoLido = call.parameters["stripeId"]

            if (codigoLido == null) {
                call.respond(HttpStatusCode.BadRequest, "Código vazio")
                return@get
            }

            val indicadoEncontrado = newSuspendedTransaction(Dispatchers.IO) {
                IndicadoService.IndicadoTable
                    .selectAll()
                    .where { IndicadoService.IndicadoTable.stripeId eq codigoLido }
                    .map {
                        mapOf(
                            "nome" to it[IndicadoService.IndicadoTable.nome],
                            "categoriaId" to it[IndicadoService.IndicadoTable.categoriaId],
                            "foto" to it[IndicadoService.IndicadoTable.imageData],
                            "status" to "VALIDO"
                        )
                    }
                    .singleOrNull()
            }

            if (indicadoEncontrado != null) {
                call.respond(HttpStatusCode.OK, indicadoEncontrado)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("status" to "INVALIDO"))
            }
        }

        // --------------------------------------------------
        // 4. SALVAR PRÉ-INSCRIÇÃO (APP)
        // --------------------------------------------------
        post("/inscricoes") {
            try {
                val dto = call.receive<InscricaoDTO>()

                val novoId = newSuspendedTransaction(Dispatchers.IO) {
                    PreInscricoesTable.insert {
                        it[nome] = dto.nome
                        it[instagram] = dto.instagram
                        it[categoriaId] = dto.categoriaId
                        it[descricao] = dto.descricaoDetalhada
                        it[imageData] = dto.imageData
                        it[desejaParticiparVotacao] = dto.desejaParticiparVotacao
                        it[status] = "AGUARDANDO"
                    }[PreInscricoesTable.id]
                }

                println("Pré-inscrição salva: ID $novoId")
                call.respond(HttpStatusCode.Created, mapOf("id" to novoId))

            } catch (e: Exception) {
                e.printStackTrace()
                call.respond(HttpStatusCode.BadRequest, "Erro ao salvar: ${e.message}")
            }
        }

        // --------------------------------------------------
        // 5. POLLING STATUS
        // --------------------------------------------------
        get("/inscricoes/{id}") {
            val idParam = call.parameters["id"]?.toIntOrNull()
            if (idParam == null) {
                call.respond(HttpStatusCode.BadRequest); return@get
            }

            val statusAtual = newSuspendedTransaction(Dispatchers.IO) {
                PreInscricoesTable.selectAll()
                    .where { PreInscricoesTable.id eq idParam }
                    .map { it[PreInscricoesTable.status] }
                    .singleOrNull()
            }

            if (statusAtual != null) {
                call.respond(InscricaoResposta(idParam, statusAtual))
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        // --------------------------------------------------
        // 6. WEBHOOK STRIPE
        // --------------------------------------------------
        post("/stripe-webhook") {
            val payload = call.receiveText()
            val sigHeader = call.request.header("Stripe-Signature")
            val webhookSecret = System.getenv("STRIPE_WEBHOOK_SECRET")

            if (webhookSecret == null) {
                call.respond(HttpStatusCode.InternalServerError); return@post
            }

            try {
                val event = Webhook.constructEvent(payload, sigHeader, webhookSecret)

                if (event.type == "checkout.session.completed") {
                    val regexRef = """"client_reference_id":\s*"(\d+)"""".toRegex()
                    val regexStripeId = """"id":\s*"(cs_[a-zA-Z0-9_]+)"""".toRegex()
                    val regexEmail = """"email":\s*"([^"]+)"""".toRegex()

                    val matchRef = regexRef.find(payload)
                    val matchStripe = regexStripeId.find(payload)
                    val matchEmail = regexEmail.find(payload)

                    val stripeSessionId = matchStripe?.groupValues?.get(1)
                    val customerEmail = matchEmail?.groupValues?.get(1)
                    val idTemp = matchRef?.groupValues?.get(1)?.toIntOrNull()

                    if (idTemp != null && stripeSessionId != null) {

                        newSuspendedTransaction(Dispatchers.IO) {
                            val row = PreInscricoesTable.selectAll()
                                .where { PreInscricoesTable.id eq idTemp }
                                .singleOrNull()

                            if (row != null) {
                                if (row[PreInscricoesTable.status] != "PAGO") {

                                    PreInscricoesTable.update({ PreInscricoesTable.id eq idTemp }) {
                                        it[status] = "PAGO"
                                    }

                                    val novoIndicado = Indicado(
                                        categoriaId = row[PreInscricoesTable.categoriaId],
                                        nome = row[PreInscricoesTable.nome],
                                        instagram = row[PreInscricoesTable.instagram],
                                        imageData = row[PreInscricoesTable.imageData],
                                        descricaoDetalhada = row[PreInscricoesTable.descricao],
                                        desejaParticiparVotacao = row[PreInscricoesTable.desejaParticiparVotacao],
                                        stripeId = stripeSessionId,
                                        email = customerEmail
                                    )

                                    val uuidIndicado = UUID.randomUUID().toString()
                                    indicadoService.create(novoIndicado, uuidIndicado)

                                    println(">>> Pagamento Confirmado: ${novoIndicado.nome} <<<")

                                    if (customerEmail != null) {
                                        val qrBytes = gerarQrCodeBytes(stripeSessionId)
                                        thread {
                                            emailService.enviarBilhete(
                                                destinatario = customerEmail,
                                                nomeParticipante = novoIndicado.nome,
                                                qrCodeBytes = qrBytes
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                call.respond(HttpStatusCode.OK)
            } catch (e: Exception) {
                println("Erro Webhook: ${e.message}")
                call.respond(HttpStatusCode.BadRequest)
            }
        }
    }
}