package com.class_erp

import com.stripe.Stripe
import com.stripe.model.Event
import com.stripe.model.checkout.Session
import com.stripe.net.Webhook
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.Optional // Importante para o Optional do Java

// --- MODELOS ---
@Serializable
data class InscricaoDTO(
    val nome: String,
    val categoriaId: String,
    val descricaoDetalhada: String,
    val imageData: String?
)

@Serializable
data class InscricaoResposta(
    val id: Int,
    val status: String
)

// --- BANCO DE DADOS SIMULADO ---
data class InscricaoEntidade(
    val id: Int,
    val dados: InscricaoDTO,
    var status: String
)

val bancoDeDados = mutableListOf<InscricaoEntidade>()
var ultimoId = 0

fun Application.configureStripeModule() {

    // 1. CARREGAR VARIÁVEIS (Com proteção)
    val stripeKey = System.getenv("STRIPE_API_KEY")
    val webhookSecret = System.getenv("STRIPE_WEBHOOK_SECRET")

    if (stripeKey != null) {
        Stripe.apiKey = stripeKey
    } else {
        println("ERRO: STRIPE_API_KEY não encontrada.")
    }

    routing {

        // ROTA 1: CRIAR INSCRIÇÃO
        post("/inscricoes") {
            try {
                val dados = call.receive<InscricaoDTO>()

                ultimoId++
                val novaInscricao = InscricaoEntidade(
                    id = ultimoId,
                    dados = dados,
                    status = "AGUARDANDO"
                )
                bancoDeDados.add(novaInscricao)

                println("Inscrição criada: ID $ultimoId - Status: AGUARDANDO")
                call.respond(HttpStatusCode.Created, mapOf("id" to ultimoId))

            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Erro ao salvar: ${e.message}")
            }
        }

        // ROTA 2: VERIFICAR STATUS (Polling)
        get("/inscricoes/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            val inscricao = bancoDeDados.find { it.id == id }

            if (inscricao != null) {
                // Retorna o status atual ("AGUARDANDO" ou "PAGO")
                call.respond(InscricaoResposta(inscricao.id, inscricao.status))
            } else {
                // Retorna 404 para o Flutter saber que os dados sumiram
                call.respond(HttpStatusCode.NotFound, "Inscrição não encontrada")
            }
        }

        // ROTA 3: WEBHOOK (ATUALIZADA E SEGURA)
        post("/stripe-webhook") {
            val payload = call.receiveText()
            val sigHeader = call.request.header("Stripe-Signature")

            if (webhookSecret == null) {
                call.respond(HttpStatusCode.InternalServerError, "Erro de configuração do servidor")
                return@post
            }

            try {
                val event = Webhook.constructEvent(payload, sigHeader, webhookSecret)

                if (event.type == "checkout.session.completed") {

                    var session: Session? = null

                    // --- CORREÇÃO DE SEGURANÇA ---
                    // Verifica se o objeto existe antes de dar .get()
                    if (event.dataObjectDeserializer.`object`.isPresent) {
                        session = event.dataObjectDeserializer.`object`.get() as Session
                    } else {
                        println("ERRO GRAVE: Versão da Lib incompatível! O objeto veio vazio.")
                        println("Evento recebido: ${event.toJson()}")
                    }

                    if (session != null) {
                        val idReferencia = session.clientReferenceId

                        if (idReferencia != null) {
                            val id = idReferencia.toIntOrNull()
                            if (id != null) {
                                val inscricao = bancoDeDados.find { it.id == id }
                                if (inscricao != null) {
                                    inscricao.status = "PAGO"
                                    println(">>> PAGAMENTO CONFIRMADO PARA ID $id <<<")
                                } else {
                                    println("ERRO: ID $id não encontrado no banco (Reinício do servidor?)")
                                }
                            }
                        } else {
                            println("Aviso: Pagamento sem ID de referência.")
                        }
                    }
                }

                call.respond(HttpStatusCode.OK)

            } catch (e: Exception) {
                println("Erro no Webhook: ${e.message}")
                call.respond(HttpStatusCode.BadRequest, "Erro: ${e.message}")
            }
        }
    }
}