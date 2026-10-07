package routes.alunoIa

import com.stripe.net.Webhook
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import schemas.alunoIa.AlunoIaService
import schemas.alunoIa.StatusAssinatura
import java.time.Instant

/**
 * Webhook do Stripe para a assinatura do curso (endpoint configurado na Stripe como
 * https://api.effectiveenglishcourse.com/webhook/stripe), com os eventos:
 * checkout.session.completed, invoice.paid, invoice.payment_failed, customer.subscription.deleted.
 *
 * Os campos de cada evento são lidos do JSON cru (não dos objetos tipados do SDK) porque
 * o formato de `data.object` já muda conforme a API version configurada na conta Stripe —
 * mesmo padrão de robustez usado em routes/estrelasLeiria/EbookWebhook.kt.
 */
fun Application.assinaturaWebhookRouting(alunoIaService: AlunoIaService) {
    routing {
        post("/webhook/stripe") {
            val payload = call.receiveText()

            val sigHeader = call.request.header("Stripe-Signature")
                ?: run {
                    println("[AssinaturaWebhook] ERRO: header Stripe-Signature ausente")
                    return@post call.respond(HttpStatusCode.BadRequest, "Missing Stripe-Signature header")
                }

            val webhookSecret = System.getProperty("stripe.alunoIa.webhookSecret")
                ?: System.getenv("STRIPE_ALUNO_IA_WEBHOOK_SECRET")
                ?: run {
                    println("[AssinaturaWebhook] ERRO: segredo do webhook não configurado (stripe.alunoIa.webhookSecret / STRIPE_ALUNO_IA_WEBHOOK_SECRET)")
                    return@post call.respond(HttpStatusCode.InternalServerError, "Webhook secret not configured")
                }

            val event = try {
                Webhook.constructEvent(payload, sigHeader, webhookSecret)
            } catch (e: Exception) {
                println("[AssinaturaWebhook] Assinatura inválida: ${e.message}")
                return@post call.respond(HttpStatusCode.BadRequest, "Invalid signature")
            }

            val deveProcessar = alunoIaService.deveProcessarEventoStripe(event.id)
            if (!deveProcessar) {
                println("[AssinaturaWebhook] Evento ${event.id} (${event.type}) já processado, ignorando.")
                return@post call.respond(HttpStatusCode.OK, "Evento já processado")
            }

            try {
                val agora = Instant.now().toString()
                when (event.type) {
                    "checkout.session.completed" -> handleCheckoutSessionCompleted(payload, alunoIaService, agora)
                    "invoice.paid" -> handleInvoicePaid(payload, alunoIaService, agora)
                    "invoice.payment_failed" -> handleInvoicePaymentFailed(payload, alunoIaService, agora)
                    "customer.subscription.deleted" -> handleSubscriptionDeleted(payload, alunoIaService, agora)
                    else -> println("[AssinaturaWebhook] Evento ${event.type} recebido mas não tratado, ignorando.")
                }
                call.respond(HttpStatusCode.OK)
            } catch (e: Exception) {
                println("[AssinaturaWebhook] Erro ao processar evento ${event.type}: ${e.message}")
                call.respond(HttpStatusCode.InternalServerError, "Erro ao processar evento")
            }
        }
    }
}

private suspend fun handleCheckoutSessionCompleted(payload: String, alunoIaService: AlunoIaService, agora: String) {
    val obj = dataObjectOf(payload) ?: return

    // Só nos interessa quando o checkout resulta em uma assinatura (mode=subscription).
    if (obj.stringField("mode") != "subscription") {
        println("[AssinaturaWebhook] checkout.session.completed com mode != subscription, ignorando.")
        return
    }

    val stripeCustomerId = obj.stringField("customer")
    val stripeSubscriptionId = obj.stringField("subscription")
    val clientReferenceId = obj.stringField("client_reference_id")
    val email = obj.nestedStringField("customer_details", "email") ?: obj.stringField("customer_email")

    val aluno = when {
        !clientReferenceId.isNullOrBlank() -> alunoIaService.readByUserId(clientReferenceId)
        !email.isNullOrBlank() -> alunoIaService.readByEmail(email)
        else -> null
    }

    if (aluno == null) {
        println("[AssinaturaWebhook] checkout.session.completed: nenhum aluno encontrado (clientReferenceId=$clientReferenceId, email=$email)")
        return
    }

    alunoIaService.updateAssinaturaPorUserId(
        userId = aluno.userId,
        statusAssinatura = StatusAssinatura.ATIVA,
        atualizadoEm = agora,
        stripeCustomerId = stripeCustomerId,
        stripeSubscriptionId = stripeSubscriptionId,
    )
    println("[AssinaturaWebhook] Assinatura ATIVA para userId=${aluno.userId} (customer=$stripeCustomerId)")
}

private suspend fun handleInvoicePaid(payload: String, alunoIaService: AlunoIaService, agora: String) {
    val obj = dataObjectOf(payload) ?: return
    val stripeCustomerId = obj.stringField("customer") ?: run {
        println("[AssinaturaWebhook] invoice.paid sem customer, ignorando.")
        return
    }
    val stripeSubscriptionId = obj.stringField("subscription")

    val linhas = alunoIaService.updateAssinaturaPorStripeCustomerId(
        stripeCustomerId = stripeCustomerId,
        statusAssinatura = StatusAssinatura.ATIVA,
        atualizadoEm = agora,
        stripeSubscriptionId = stripeSubscriptionId,
    )
    if (linhas == 0) {
        println("[AssinaturaWebhook] invoice.paid: nenhum aluno com stripeCustomerId=$stripeCustomerId")
    }
}

private suspend fun handleInvoicePaymentFailed(payload: String, alunoIaService: AlunoIaService, agora: String) {
    val obj = dataObjectOf(payload) ?: return
    val stripeCustomerId = obj.stringField("customer") ?: run {
        println("[AssinaturaWebhook] invoice.payment_failed sem customer, ignorando.")
        return
    }

    val linhas = alunoIaService.updateAssinaturaPorStripeCustomerId(
        stripeCustomerId = stripeCustomerId,
        statusAssinatura = StatusAssinatura.PAGAMENTO_FALHOU,
        atualizadoEm = agora,
    )
    if (linhas == 0) {
        println("[AssinaturaWebhook] invoice.payment_failed: nenhum aluno com stripeCustomerId=$stripeCustomerId")
    }
}

private suspend fun handleSubscriptionDeleted(payload: String, alunoIaService: AlunoIaService, agora: String) {
    val obj = dataObjectOf(payload) ?: return
    val stripeCustomerId = obj.stringField("customer") ?: run {
        println("[AssinaturaWebhook] customer.subscription.deleted sem customer, ignorando.")
        return
    }

    val linhas = alunoIaService.updateAssinaturaPorStripeCustomerId(
        stripeCustomerId = stripeCustomerId,
        statusAssinatura = StatusAssinatura.CANCELADA,
        atualizadoEm = agora,
    )
    if (linhas == 0) {
        println("[AssinaturaWebhook] customer.subscription.deleted: nenhum aluno com stripeCustomerId=$stripeCustomerId")
    }
}

// ── Helpers de leitura do JSON cru do evento (data.object) ──────────────────

private fun dataObjectOf(payload: String): JsonObject? = runCatching {
    Json.parseToJsonElement(payload).jsonObject["data"]?.jsonObject?.get("object")?.jsonObject
}.getOrNull()

private fun JsonObject.stringField(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.nestedStringField(parent: String, name: String): String? =
    this[parent]?.jsonObject?.get(name)?.jsonPrimitive?.contentOrNull
