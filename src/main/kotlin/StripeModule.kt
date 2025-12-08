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

// --- DTOs (Comunicação com Frontend) ---
@Serializable
data class InscricaoDTO(
    val nome: String,
    val instagram: String,
    val categoriaId: String,
    val descricaoDetalhada: String,
    val imageData: String,
    val desejaParticiparVotacao: Boolean // Recebe do Checkbox do App
)

@Serializable
data class InscricaoResposta(
    val id: Int,
    val status: String
)

// --- TABELA TEMPORÁRIA (PRÉ-INSCRIÇÃO) ---
object PreInscricoesTable : Table("pre_inscricoes") {
    val id = integer("id").autoIncrement()
    val nome = varchar("nome", 255)
    val instagram = varchar("instagram", 100)
    val categoriaId = varchar("categoria_id", 50)
    val descricao = text("descricao")
    val imageData = largeText("image_data")

    // Armazena a escolha da votação temporariamente até o pagamento
    val desejaParticiparVotacao = bool("deseja_participar_votacao").default(false)

    val status = varchar("status", 20).default("AGUARDANDO")

    override val primaryKey = PrimaryKey(id)
}

fun Application.configureStripeModule() {

    val indicadoService by inject<IndicadoService>()

    val db = org.jetbrains.exposed.sql.transactions.TransactionManager.defaultDatabase
    if (db != null) {
        transaction(db) {
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
        // 1. ROTA DE QR CODE (Gera imagem PNG com o StripeID)
        // --------------------------------------------------
        get("/ingresso/qrcode/{id}") {
            val idIndicado = call.parameters["id"]
            if (idIndicado == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }

            // Busca o stripeId na tabela oficial de Indicados
            val stripeId = newSuspendedTransaction(Dispatchers.IO) {
                IndicadoService.IndicadoTable
                    .selectAll() // 1. Seleciona tudo primeiro
                    .where { IndicadoService.IndicadoTable.id eq idIndicado } // 2. Aplica o filtro aqui
                    .map { it[IndicadoService.IndicadoTable.stripeId] }
                    .singleOrNull()
            }

            if (stripeId != null) {
                try {
                    val writer = QRCodeWriter()
                    // Gera o QR Code contendo o ID da Sessão da Stripe (Seguro e Único)
                    val bitMatrix = writer.encode(stripeId, BarcodeFormat.QR_CODE, 400, 400)

                    val outputStream = ByteArrayOutputStream()
                    MatrixToImageWriter.writeToStream(bitMatrix, "PNG", outputStream)

                    call.respondBytes(outputStream.toByteArray(), ContentType.Image.PNG)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Erro geração QR")
                }
            } else {
                call.respond(HttpStatusCode.NotFound, "Ingresso ainda não confirmado ou não encontrado")
            }
        }


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
                        it[desejaParticiparVotacao] = dto.desejaParticiparVotacao // Salva a escolha
                        it[status] = "AGUARDANDO"
                    }[PreInscricoesTable.id]
                }

                println("Pré-inscrição salva: ID $novoId - Votação: ${dto.desejaParticiparVotacao}")
                call.respond(HttpStatusCode.Created, mapOf("id" to novoId))

            } catch (e: Exception) {
                e.printStackTrace()
                call.respond(HttpStatusCode.BadRequest, "Erro ao salvar: ${e.message}")
            }
        }

        // --------------------------------------------------
        // 3. POLLING (Verificar status do pagamento)
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
                call.respond(HttpStatusCode.NotFound, "Inscrição não encontrada")
            }
        }

        // --------------------------------------------------
        // 4. WEBHOOK (Stripe -> Servidor)
        // --------------------------------------------------
        post("/stripe-webhook") {
            val payload = call.receiveText()
            val sigHeader = call.request.header("Stripe-Signature")

            if (webhookSecret == null) {
                call.respond(HttpStatusCode.InternalServerError, "Webhook Secret não configurado"); return@post
            }

            try {
                val event = Webhook.constructEvent(payload, sigHeader, webhookSecret)

                if (event.type == "checkout.session.completed") {
                    println("Webhook: Pagamento recebido. Processando dados...")

                    // 1. Extrai ID Temporário (Referência do nosso banco)
                    val regexRef = """"client_reference_id":\s*"(\d+)"""".toRegex()
                    val matchRef = regexRef.find(payload)

                    // 2. Extrai ID da Sessão Stripe (cs_test...) para o QR Code
                    val regexStripeId = """"id":\s*"(cs_[a-zA-Z0-9_]+)"""".toRegex()
                    val matchStripe = regexStripeId.find(payload)

                    // 3. Extrai Email do Cliente
                    val regexEmail = """"email":\s*"([^"]+)"""".toRegex()
                    val matchEmail = regexEmail.find(payload)

                    val stripeSessionId = matchStripe?.groupValues?.get(1)
                    val customerEmail = matchEmail?.groupValues?.get(1)

                    if (matchRef != null && stripeSessionId != null) {
                        val idTemp = matchRef.groupValues[1].toInt()

                        newSuspendedTransaction(Dispatchers.IO) {
                            val row = PreInscricoesTable.selectAll()
                                .where { PreInscricoesTable.id eq idTemp }
                                .singleOrNull()

                            if (row != null) {
                                // Só processa se ainda não foi pago para evitar duplicidade
                                if (row[PreInscricoesTable.status] != "PAGO") {

                                    // A. Atualiza status na tabela temporária
                                    PreInscricoesTable.update({ PreInscricoesTable.id eq idTemp }) {
                                        it[status] = "PAGO"
                                    }

                                    // B. Cria o registro definitivo na tabela de Indicados
                                    val novoIndicado = Indicado(
                                        categoriaId = row[PreInscricoesTable.categoriaId],
                                        nome = row[PreInscricoesTable.nome],
                                        instagram = row[PreInscricoesTable.instagram],
                                        imageData = row[PreInscricoesTable.imageData],
                                        descricaoDetalhada = row[PreInscricoesTable.descricao],

                                        // Dados recuperados
                                        desejaParticiparVotacao = row[PreInscricoesTable.desejaParticiparVotacao],
                                        stripeId = stripeSessionId,
                                        email = customerEmail
                                    )

                                    val uuidIndicado = UUID.randomUUID().toString()
                                    indicadoService.create(novoIndicado, uuidIndicado)

                                    println(">>> SUCESSO! Inscrição confirmada.")
                                    println("Nome: ${novoIndicado.nome}")
                                    println("Email: $customerEmail")
                                    println("Votação: ${novoIndicado.desejaParticiparVotacao}")
                                    println("StripeID: $stripeSessionId")
                                } else {
                                    println("Aviso: ID $idTemp já processado anteriormente.")
                                }
                            } else {
                                println("Erro: ID $idTemp não encontrado na tabela temporária.")
                            }
                        }
                    }
                }
                call.respond(HttpStatusCode.OK)
            } catch (e: Exception) {
                println("Erro CRÍTICO no Webhook: ${e.message}")
                e.printStackTrace()
                call.respond(HttpStatusCode.BadRequest)
            }
        }
    }
}