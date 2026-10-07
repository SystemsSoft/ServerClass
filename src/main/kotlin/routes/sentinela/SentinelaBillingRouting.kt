package routes.sentinela

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import schemas.sentinela.SentinelaBillingService
import schemas.sentinela.SentinelaPaidStatus
import schemas.sentinela.SentinelaPlan
import services.SentinelaStripe
import java.time.Instant

@Serializable
data class SentinelaCheckoutRequest(val plan: String)

@Serializable
data class SentinelaUrlResponse(val url: String)

/**
 * Cobrança do Sentinela. Todas as rotas exigem o ID Token do Firebase.
 * - GET  /sentinela/billing          saldo do teste grátis e do plano
 * - GET  /sentinela/billing/plans    planos à venda (preços vindos da Stripe)
 * - POST /sentinela/billing/checkout {"plan":"MENSAL"}  → {"url": checkout da Stripe}
 * - POST /sentinela/billing/portal   → {"url": portal da Stripe para gerenciar a assinatura}
 */
fun Application.sentinelaBillingRouting(billing: SentinelaBillingService, stripe: SentinelaStripe) {
    routing {
        route("/sentinela/billing") {
            get {
                val user = call.requireFirebaseUser() ?: return@get
                call.respond(HttpStatusCode.OK, billing.status(user.uid))
            }

            get("/plans") {
                call.requireFirebaseUser() ?: return@get
                call.respond(HttpStatusCode.OK, stripe.offers())
            }

            post("/checkout") {
                val user = call.requireFirebaseUser() ?: return@post
                val plan = runCatching { call.receive<SentinelaCheckoutRequest>() }.getOrNull()
                    ?.let { request -> SentinelaPlan.entries.firstOrNull { it.name.equals(request.plan.trim(), ignoreCase = true) } }
                if (plan == null) {
                    call.respond(HttpStatusCode.BadRequest, "Plano inválido")
                    return@post
                }
                val appUrl = call.appUrl(stripe)
                if (appUrl == null) {
                    call.respond(HttpStatusCode.BadRequest, "Não foi possível identificar o endereço do app")
                    return@post
                }

                // Evita assinar duas vezes: quem já tem assinatura ativa troca ou cancela pelo portal.
                val current = billing.status(user.uid)
                val hasSubscription = SentinelaPlan.entries.firstOrNull { it.name == current.plan }?.recurring == true &&
                    current.planStatus == SentinelaPaidStatus.ACTIVE.name &&
                    current.planActiveUntil?.let { Instant.parse(it).isAfter(Instant.now()) } == true
                if (plan.recurring && hasSubscription) {
                    call.respond(HttpStatusCode.Conflict, "Você já tem uma assinatura ativa. Gerencie pelo portal.")
                    return@post
                }

                try {
                    call.respond(HttpStatusCode.OK, SentinelaUrlResponse(stripe.createCheckoutUrl(user.uid, user.email, plan, appUrl)))
                } catch (e: Exception) {
                    println("[SentinelaBilling] Falha ao criar o checkout (${plan.name}) de ${user.uid}: ${e.message}")
                    call.respond(HttpStatusCode.BadGateway, "Não foi possível iniciar o pagamento. Tente novamente.")
                }
            }

            post("/portal") {
                val user = call.requireFirebaseUser() ?: return@post
                if (billing.customerIdOf(user.uid) == null) {
                    call.respond(HttpStatusCode.Conflict, "Esta conta ainda não tem assinatura para gerenciar.")
                    return@post
                }
                val appUrl = call.appUrl(stripe)
                if (appUrl == null) {
                    call.respond(HttpStatusCode.BadRequest, "Não foi possível identificar o endereço do app")
                    return@post
                }
                try {
                    call.respond(HttpStatusCode.OK, SentinelaUrlResponse(stripe.createPortalUrl(user.uid, appUrl)))
                } catch (e: Exception) {
                    println("[SentinelaBilling] Falha ao abrir o portal de ${user.uid}: ${e.message}")
                    call.respond(HttpStatusCode.BadGateway, "Não foi possível abrir o gerenciamento da assinatura.")
                }
            }
        }
    }
}

/**
 * Endereço do app web para onde a Stripe devolve o usuário. Usa o configurado (`sentinela.appUrl`) e,
 * na falta dele, a origem que o navegador informa — só http(s), sem caminho.
 */
private fun ApplicationCall.appUrl(stripe: SentinelaStripe): String? {
    stripe.defaultAppUrl?.let { return it }
    val origin = request.header(HttpHeaders.Origin)?.trim()?.trimEnd('/') ?: return null
    return origin.takeIf { Regex("^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?$").matches(it) }
}
