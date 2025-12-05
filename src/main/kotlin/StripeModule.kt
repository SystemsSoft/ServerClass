package com.class_erp

import com.stripe.Stripe
import com.stripe.model.checkout.Session
import com.stripe.net.Webhook
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

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

// Lista em memória (lembre-se: se o Heroku reiniciar, isso zera)
val bancoDeDados = mutableListOf<InscricaoEntidade>()
var ultimoId = 0

fun Application.configureStripeModule() {

    val stripeKey = System.getenv("STRIPE_API_KEY")
        ?: throw IllegalStateException("A variável STRIPE_API_KEY não foi configurada!")

    val webhookSecret = System.getenv("STRIPE_WEBHOOK_SECRET")
        ?: throw IllegalStateException("A variável STRIPE_WEBHOOK_SECRET não foi configurada!")

    // 2. CONFIGURAR STRIPE
    Stripe.apiKey = stripeKey
    val endpointSecret = webhookSecret

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

                println("Nova inscrição criada: ID $ultimoId - Status: AGUARDANDO")
                call.respond(HttpStatusCode.Created, mapOf("id" to ultimoId))

            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Erro ao salvar: ${e.message}")
            }
        }

        // ROTA 2: VERIFICAR STATUS
        get("/inscricoes/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            val inscricao = bancoDeDados.find { it.id == id }

            if (inscricao != null) {
                call.respond(InscricaoResposta(inscricao.id, inscricao.status))
            } else {
                call.respond(HttpStatusCode.NotFound, "Inscrição não encontrada")
            }
        }

        // ROTA 3: WEBHOOK
        post("/stripe-webhook") {
            val payload = call.receiveText()
            val sigHeader = call.request.header("Stripe-Signature")

            try {
                val event = Webhook.constructEvent(payload, sigHeader, endpointSecret)

                if (event.type == "checkout.session.completed") {

                    // CORREÇÃO AQUI: Uso das crases em `object`
                    val session = event.dataObjectDeserializer.`object`.get() as Session

                    val idReferencia = session.clientReferenceId

                    if (idReferencia != null) {
                        // CORREÇÃO AQUI: Uso de toIntOrNull para segurança
                        val id = idReferencia.toIntOrNull()

                        if (id != null) {
                            val inscricao = bancoDeDados.find { it.id == id }
                            if (inscricao != null) {
                                inscricao.status = "PAGO"
                                println(">>> PAGAMENTO CONFIRMADO PARA ID $id <<<")
                            } else {
                                println("ERRO: Inscrição ID $id não encontrada no banco.")
                            }
                        }
                    }
                }
                call.respond(HttpStatusCode.OK)

            } catch (e: Exception) {
                println("Erro no Webhook: ${e.message}")
                call.respond(HttpStatusCode.BadRequest, "Erro de assinatura ou parse")
            }
        }
    }
}