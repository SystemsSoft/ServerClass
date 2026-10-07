package routes.sentinela

import com.stripe.net.Webhook
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import schemas.sentinela.SentinelaBillingService
import schemas.sentinela.SentinelaPaidStatus
import schemas.sentinela.SentinelaPlan
import services.SentinelaStripe
import java.time.Instant

/**
 * Webhook da conta Stripe do Sentinela (`POST /webhook/stripe/sentinela`). Só a assinatura do
 * corpo é verificada aqui; o conteúdo é lido do JSON cru porque o formato de `data.object` muda
 * conforme a versão da API configurada na conta.
 */
fun Application.sentinelaStripeWebhookRouting(billing: SentinelaBillingService, stripe: SentinelaStripe) {
    val handler = SentinelaStripeEvents(billing, stripe)

    routing {
        post("/webhook/stripe/sentinela") {
            val payload = call.receiveText()
            val signature = call.request.header("Stripe-Signature")
            if (signature.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "Missing Stripe-Signature header")
                return@post
            }
            val secret = stripe.webhookSecret
            if (secret == null) {
                println("[SentinelaStripe] ERRO: segredo do webhook não configurado (stripe.sentinela.webhookSecret)")
                call.respond(HttpStatusCode.InternalServerError, "Webhook secret not configured")
                return@post
            }
            val valid = runCatching { Webhook.Signature.verifyHeader(payload, signature, secret, 300L) }.getOrDefault(false)
            if (!valid) {
                println("[SentinelaStripe] Assinatura inválida")
                call.respond(HttpStatusCode.BadRequest, "Invalid signature")
                return@post
            }

            try {
                handler.handle(payload)
                call.respond(HttpStatusCode.OK)
            } catch (e: Exception) {
                println("[SentinelaStripe] Erro ao processar evento: ${e.message}")
                call.respond(HttpStatusCode.InternalServerError, "Erro ao processar evento")
            }
        }
    }
}

/** Regras de cada evento da Stripe sobre o plano do usuário. */
class SentinelaStripeEvents(private val billing: SentinelaBillingService, private val stripe: SentinelaStripe) {

    suspend fun handle(payload: String) {
        val event = Json.parseToJsonElement(payload).jsonObject
        val eventId = event.string("id") ?: error("Evento sem id")
        val type = event.string("type").orEmpty()
        val obj = event["data"]?.jsonObject?.get("object")?.jsonObject ?: return

        // A Stripe reenvia eventos; cada um só vale uma vez. Se o tratamento falhar, libera para o reenvio.
        if (!billing.markProcessed("event:$eventId")) {
            println("[SentinelaStripe] Evento $eventId ($type) já processado, ignorando.")
            return
        }
        try {
            when (type) {
                "checkout.session.completed" -> onCheckoutCompleted(obj)
                "checkout.session.async_payment_succeeded" -> grantAvulsoIfPayment(obj, requirePaid = false)
                "checkout.session.async_payment_failed" ->
                    println("[SentinelaStripe] Pagamento assíncrono falhou (sessão ${obj.string("id")}); nada a liberar.")
                "invoice.paid" -> onInvoicePaid(obj)
                "invoice.payment_failed" -> obj.string("customer")?.let { billing.setStatusByCustomer(it, SentinelaPaidStatus.PAST_DUE) }
                "customer.subscription.updated" -> onSubscriptionUpdated(obj)
                "customer.subscription.deleted" -> obj.string("customer")?.let { billing.endSubscription(it) }
                else -> println("[SentinelaStripe] Evento $type recebido mas não tratado, ignorando.")
            }
        } catch (e: Exception) {
            billing.forget("event:$eventId")
            throw e
        }
    }

    private suspend fun onCheckoutCompleted(session: JsonObject) {
        val uid = uidOf(session)
        val customer = session.string("customer")
        if (uid != null && customer != null) billing.linkCustomer(uid, customer)

        // Assinatura: quem libera o acesso é o invoice.paid (traz o fim exato do período).
        if (session.string("mode") == "payment") grantAvulsoIfPayment(session, requirePaid = true)
    }

    /** Compra avulsa paga: +1 hora. Pix e similares confirmam depois, pelo evento async_payment_succeeded. */
    private suspend fun grantAvulsoIfPayment(session: JsonObject, requirePaid: Boolean) {
        if (session.string("mode") != "payment") return
        if (requirePaid && session.string("payment_status") != "paid") {
            println("[SentinelaStripe] Sessão ${session.string("id")} ainda não paga; aguardando a confirmação.")
            return
        }
        val uid = uidOf(session) ?: run {
            println("[SentinelaStripe] Compra avulsa sem usuário identificável (sessão ${session.string("id")})")
            return
        }
        val sessionId = session.string("id") ?: return
        // Uma sessão só rende uma hora, mesmo que cheguem dois eventos dela (completed + async_payment_succeeded).
        if (!billing.markProcessed("grant:$sessionId")) return
        try {
            billing.grantAvulso(uid)
            println("[SentinelaStripe] +1h avulsa para uid=$uid (sessão $sessionId)")
        } catch (e: Exception) {
            billing.forget("grant:$sessionId")
            throw e
        }
    }

    private suspend fun onInvoicePaid(invoice: JsonObject) {
        val customer = invoice.string("customer") ?: return
        val line = invoice["lines"]?.jsonObject?.get("data")?.jsonArray?.firstOrNull()?.jsonObject
        val subscriptionDetails = invoice["parent"]?.jsonObject?.get("subscription_details")?.jsonObject
        val subscriptionId = subscriptionDetails?.string("subscription") ?: invoice.string("subscription")
        if (subscriptionId == null) return // fatura que não é de assinatura

        // APIs novas trazem o preço em pricing.price_details; as antigas, em price.
        val priceDetails = line?.get("pricing")?.jsonObject?.get("price_details")?.jsonObject
        val oldPrice = line?.get("price")?.jsonObject
        val priceId = priceDetails?.string("price") ?: oldPrice?.string("id")
        val productId = priceDetails?.string("product") ?: oldPrice?.string("product")
        val metadata = subscriptionDetails?.get("metadata")?.jsonObject ?: invoice["subscription_details"]?.jsonObject?.get("metadata")?.jsonObject
        val plan = stripe.planFor(priceId, productId)
            ?: metadata?.string("plan")?.let { name -> SentinelaPlan.entries.firstOrNull { it.name == name } }
            ?: run {
                println("[SentinelaStripe] invoice.paid com preço desconhecido ($priceId), ignorando.")
                return
            }
        val uid = billing.uidOfCustomer(customer) ?: metadata?.string("uid") ?: run {
            println("[SentinelaStripe] invoice.paid sem usuário para o cliente $customer")
            return
        }
        if (billing.uidOfCustomer(customer) == null) billing.linkCustomer(uid, customer)

        val periodEnd = line?.get("period")?.jsonObject?.get("end")?.jsonPrimitive?.longOrNull?.let(Instant::ofEpochSecond)
            ?: stripe.fallbackPeriodEnd(plan)
        billing.activateSubscription(uid, plan, periodEnd, subscriptionId)
        println("[SentinelaStripe] Plano ${plan.name} ativo para uid=$uid até $periodEnd")
    }

    private suspend fun onSubscriptionUpdated(subscription: JsonObject) {
        val customer = subscription.string("customer") ?: return
        billing.setCancelAtPeriodEnd(customer, subscription["cancel_at_period_end"]?.jsonPrimitive?.contentOrNull == "true")
        when (subscription.string("status")) {
            "active", "trialing" -> billing.setStatusByCustomer(customer, SentinelaPaidStatus.ACTIVE)
            "past_due", "unpaid" -> billing.setStatusByCustomer(customer, SentinelaPaidStatus.PAST_DUE)
        }
    }

    private suspend fun uidOf(session: JsonObject): String? =
        session.string("client_reference_id")
            ?: session["metadata"]?.jsonObject?.string("uid")
            ?: session.string("customer")?.let { billing.uidOfCustomer(it) }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonElement)?.let {
        runCatching { it.jsonPrimitive.contentOrNull }.getOrNull()
    }
}
