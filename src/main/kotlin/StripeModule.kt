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
import java.util.UUID

// --- DTOs (Comunicação com Frontend) ---
@Serializable
data class InscricaoDTO(
    val nome: String,
    val instagram: String, // NOVO CAMPO
    val categoriaId: String,
    val descricaoDetalhada: String,
    val imageData: String // Base64
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
    val instagram = varchar("instagram", 100) // NOVO CAMPO NO BANCO
    val categoriaId = varchar("categoria_id", 50)
    val descricao = text("descricao")
    val imageData = text("image_data")
    val status = varchar("status", 20).default("AGUARDANDO")

    override val primaryKey = PrimaryKey(id)
}

fun Application.configureStripeModule() {

    val indicadoService by inject<IndicadoService>()

    // Cria a tabela temporária ao iniciar (se não existir)
    val db = org.jetbrains.exposed.sql.transactions.TransactionManager.defaultDatabase
    if (db != null) {
        transaction(db) {
            // Nota: Se a tabela já existir no Heroku sem a coluna instagram,
            // você pode precisar dropar a tabela manualmente ou adicionar a coluna via SQL.
            SchemaUtils.create(PreInscricoesTable)

            // Tenta adicionar a coluna se ela faltar (Migration simples para Exposed)
            try {
                SchemaUtils.createMissingTablesAndColumns(PreInscricoesTable)
            } catch (e: Exception) {
                println("Aviso: Tentativa de migração de colunas: ${e.message}")
            }
        }
    }

    val stripeKey = System.getenv("STRIPE_API_KEY")
    val webhookSecret = System.getenv("STRIPE_WEBHOOK_SECRET")

    if (stripeKey != null) Stripe.apiKey = stripeKey

    routing {

        // --------------------------------------------------
        // 1. SALVAR PRÉ-INSCRIÇÃO
        // --------------------------------------------------
        post("/inscricoes") {
            try {
                val dto = call.receive<InscricaoDTO>()

                val novoId = newSuspendedTransaction(Dispatchers.IO) {
                    PreInscricoesTable.insert {
                        it[nome] = dto.nome
                        it[instagram] = dto.instagram // SALVA O INSTAGRAM
                        it[categoriaId] = dto.categoriaId
                        it[descricao] = dto.descricaoDetalhada
                        it[imageData] = dto.imageData
                        it[status] = "AGUARDANDO"
                    }[PreInscricoesTable.id]
                }

                println("Pré-inscrição salva: ID $novoId - Insta: ${dto.instagram}")
                call.respond(HttpStatusCode.Created, mapOf("id" to novoId))

            } catch (e: Exception) {
                e.printStackTrace()
                call.respond(HttpStatusCode.BadRequest, "Erro ao salvar: ${e.message}")
            }
        }

        // --------------------------------------------------
        // 2. POLLING
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
                    println("Pagamento recebido. Processando...")

                    val regex = """"client_reference_id":\s*"(\d+)"""".toRegex()
                    val match = regex.find(payload)

                    if (match != null) {
                        val idTemp = match.groupValues[1].toInt()

                        newSuspendedTransaction(Dispatchers.IO) {

                            val row = PreInscricoesTable.selectAll()
                                .where { PreInscricoesTable.id eq idTemp }
                                .singleOrNull()

                            if (row != null) {
                                val statusAtual = row[PreInscricoesTable.status]

                                if (statusAtual != "PAGO") {
                                    PreInscricoesTable.update({ PreInscricoesTable.id eq idTemp }) {
                                        it[status] = "PAGO"
                                    }

                                    // Transfere para a tabela OFICIAL
                                    // ATENÇÃO: A classe Indicado (no outro arquivo) precisa ter o campo instagram também!
                                    val novoIndicado = Indicado(
                                        categoriaId = row[PreInscricoesTable.categoriaId],
                                        nome = row[PreInscricoesTable.nome],
                                        instagram = row[PreInscricoesTable.instagram], // TRANSFERE O INSTAGRAM
                                        imageData = row[PreInscricoesTable.imageData],
                                        descricaoDetalhada = row[PreInscricoesTable.descricao]
                                    )

                                    val uuidIndicado = UUID.randomUUID().toString()
                                    indicadoService.create(novoIndicado, uuidIndicado)

                                    println(">>> SUCESSO! Inscrição $idTemp (Insta: ${novoIndicado.instagram}) promovida a Indicado Oficial <<<")
                                } else {
                                    println("Aviso: Inscrição $idTemp já estava paga. Ignorando duplicidade.")
                                }
                            } else {
                                println("ERRO CRÍTICO: ID $idTemp veio da Stripe mas não existe na tabela temporária.")
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