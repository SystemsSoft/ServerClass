package com.class_erp


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
    val desejaParticiparVotacao: Boolean // 1. NOVO CAMPO RECEBIDO DO APP
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
    val imageData = largeText("image_data") // Usando largeText para garantir compatibilidade

    // 2. NOVA COLUNA NA TABELA TEMPORÁRIA
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
            // Garante que a coluna nova seja criada no banco se já existir a tabela
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

        // ... (Rota QR Code mantém igual) ...
        get("/ingresso/qrcode/{id}") {
            val idIndicado = call.parameters["id"]
            // ... (Lógica do QR Code igual ao anterior)
            if (idIndicado == null) { call.respond(HttpStatusCode.BadRequest); return@get }
            // ...
            call.respond(HttpStatusCode.NotFound) // Placeholder
        }

        // --------------------------------------------------
        // 1. SALVAR PRÉ-INSCRIÇÃO
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
                        // 3. SALVA O BOOLEAN NA TABELA TEMPORÁRIA
                        it[desejaParticiparVotacao] = dto.desejaParticiparVotacao
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

        // ... (Rota POLLING mantém igual) ...
        get("/inscricoes/{id}") {
            // ... mesma lógica anterior ...
            val idParam = call.parameters["id"]?.toIntOrNull()
            if(idParam != null) call.respond(InscricaoResposta(idParam, "AGUARDANDO")) // Placeholder para compilar no exemplo
        }

        // --------------------------------------------------
        // 3. WEBHOOK
        // --------------------------------------------------
        post("/stripe-webhook") {
            val payload = call.receiveText()
            val sigHeader = call.request.header("Stripe-Signature")

            if (webhookSecret == null) {
                call.respond(HttpStatusCode.InternalServerError); return@post
            }

            try {
                val event = Webhook.constructEvent(payload, sigHeader, webhookSecret)

                if (event.type == "checkout.session.completed") {
                    val regexRef = """"client_reference_id":\s*"(\d+)"""".toRegex()
                    val matchRef = regexRef.find(payload)

                    val regexStripeId = """"id":\s*"(cs_[a-zA-Z0-9_]+)"""".toRegex()
                    val matchStripe = regexStripeId.find(payload)
                    val stripeSessionId = matchStripe?.groupValues?.get(1)

                    if (matchRef != null && stripeSessionId != null) {
                        val idTemp = matchRef.groupValues[1].toInt()

                        newSuspendedTransaction(Dispatchers.IO) {
                            val row = PreInscricoesTable.selectAll()
                                .where { PreInscricoesTable.id eq idTemp }
                                .singleOrNull()

                            if (row != null) {
                                if (row[PreInscricoesTable.status] != "PAGO") {

                                    PreInscricoesTable.update({ PreInscricoesTable.id eq idTemp }) {
                                        it[status] = "PAGO"
                                    }

                                    // 4. TRANSFERE O DADO PARA A TABELA OFICIAL
                                    // Atenção: Você precisa adicionar este campo na classe Indicado (no outro arquivo)
                                    val novoIndicado = Indicado(
                                        categoriaId = row[PreInscricoesTable.categoriaId],
                                        nome = row[PreInscricoesTable.nome],
                                        instagram = row[PreInscricoesTable.instagram],
                                        imageData = row[PreInscricoesTable.imageData],
                                        descricaoDetalhada = row[PreInscricoesTable.descricao],
                                        stripeId = stripeSessionId,
                                        // Pega o valor do banco temporário
                                        desejaParticiparVotacao = row[PreInscricoesTable.desejaParticiparVotacao]
                                    )

                                    val uuidIndicado = UUID.randomUUID().toString()
                                    indicadoService.create(novoIndicado, uuidIndicado)

                                    println(">>> SUCESSO! ID $idTemp confirmado. Participa Votação: ${novoIndicado.desejaParticiparVotacao}")
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