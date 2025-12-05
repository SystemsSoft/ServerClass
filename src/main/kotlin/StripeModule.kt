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
// Esta tabela segura os dados enquanto o pagamento não é confirmado.
// Assim, se o Heroku reiniciar, você não perde o cliente que estava pagando.
object PreInscricoesTable : Table("pre_inscricoes") {
    val id = integer("id").autoIncrement()
    val nome = varchar("nome", 255)
    val categoriaId = varchar("categoria_id", 50)
    val descricao = text("descricao") // Texto longo
    val imageData = text("image_data") // Base64 é grande, use text
    val status = varchar("status", 20).default("AGUARDANDO") // AGUARDANDO, PAGO

    override val primaryKey = PrimaryKey(id)
}

fun Application.configureStripeModule() {

    // Injeção de Dependência do Serviço Principal
    val indicadoService by inject<IndicadoService>()

    // Injeção do Banco de Dados (MainDB ou EstrelasDB, onde você quiser salvar a temp)
    // Se não conseguir injetar o banco aqui, use o transaction normal se já estiver configurado globalmente
    // Mas o ideal é pegar o database configurado no Koin.
    // Vamos assumir que o Exposed já está conectado pelo DatabaseConfig.

    // Cria a tabela temporária ao iniciar (se não existir)
    // NOTA: Em produção, idealmente use Flyway/Liquibase, mas aqui funciona para o MVP
    val db = org.jetbrains.exposed.sql.transactions.TransactionManager.defaultDatabase
    if (db != null) {
        transaction(db) {
            SchemaUtils.create(PreInscricoesTable)
        }
    }

    // Configuração Stripe
    val stripeKey = System.getenv("STRIPE_API_KEY")
    val webhookSecret = System.getenv("STRIPE_WEBHOOK_SECRET")

    if (stripeKey != null) Stripe.apiKey = stripeKey

    routing {

        // --------------------------------------------------
        // 1. SALVAR PRÉ-INSCRIÇÃO (Banco SQL Temporário)
        // --------------------------------------------------
        post("/inscricoes") {
            try {
                val dto = call.receive<InscricaoDTO>()

                val novoId = newSuspendedTransaction(Dispatchers.IO) {
                    PreInscricoesTable.insert {
                        it[nome] = dto.nome
                        it[categoriaId] = dto.categoriaId
                        it[descricao] = dto.descricaoDetalhada
                        it[imageData] = dto.imageData
                        it[status] = "AGUARDANDO"
                    }[PreInscricoesTable.id]
                }

                println("Pré-inscrição salva no banco: ID $novoId")
                call.respond(HttpStatusCode.Created, mapOf("id" to novoId))

            } catch (e: Exception) {
                e.printStackTrace()
                call.respond(HttpStatusCode.BadRequest, "Erro ao salvar: ${e.message}")
            }
        }

        // --------------------------------------------------
        // 2. POLLING (Consultar Status no SQL Temporário)
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
        // 3. WEBHOOK (A Mágica da Transferência)
        // --------------------------------------------------
        post("/stripe-webhook") {
            val payload = call.receiveText()
            val sigHeader = call.request.header("Stripe-Signature")

            if (webhookSecret == null) {
                call.respond(HttpStatusCode.InternalServerError); return@post
            }

            try {
                // Valida assinatura
                val event = Webhook.constructEvent(payload, sigHeader, webhookSecret)

                if (event.type == "checkout.session.completed") {
                    println("Pagamento recebido. Processando...")

                    // REGEX para extrair ID (Robusto contra versões de lib)
                    val regex = """"client_reference_id":\s*"(\d+)"""".toRegex()
                    val match = regex.find(payload)

                    if (match != null) {
                        val idTemp = match.groupValues[1].toInt()

                        // TRANSAÇÃO DE CONFIRMAÇÃO
                        newSuspendedTransaction(Dispatchers.IO) {

                            // A. Busca na Tabela Temporária
                            val row = PreInscricoesTable.selectAll()
                                .where { PreInscricoesTable.id eq idTemp }
                                .singleOrNull()

                            if (row != null) {
                                val statusAtual = row[PreInscricoesTable.status]

                                if (statusAtual != "PAGO") {
                                    // B. Marca como PAGO na temporária (para o App saber)
                                    PreInscricoesTable.update({ PreInscricoesTable.id eq idTemp }) {
                                        it[status] = "PAGO"
                                    }

                                    // C. >>> MOMENTO CRUCIAL <<<
                                    // Transfere os dados para a tabela OFICIAL de Indicados
                                    val novoIndicado = Indicado(
                                        categoriaId = row[PreInscricoesTable.categoriaId],
                                        nome = row[PreInscricoesTable.nome],
                                        imageData = row[PreInscricoesTable.imageData], // Passa o Base64
                                        descricaoDetalhada = row[PreInscricoesTable.descricao]
                                    )

                                    // Gera um UUID para o indicado oficial
                                    val uuidIndicado = UUID.randomUUID().toString()

                                    // Chama o Service que você criou para salvar na tabela final
                                    indicadoService.create(novoIndicado, uuidIndicado)

                                    println(">>> SUCESSO! Inscrição $idTemp promovida a Indicado Oficial ($uuidIndicado) <<<")
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