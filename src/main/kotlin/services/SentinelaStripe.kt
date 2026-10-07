package services

import com.stripe.model.Customer
import com.stripe.model.Price
import com.stripe.model.Product
import com.stripe.model.billingportal.Session as PortalSession
import com.stripe.model.checkout.Session as CheckoutSession
import com.stripe.net.RequestOptions
import com.stripe.param.CustomerCreateParams
import com.stripe.param.billingportal.SessionCreateParams as PortalSessionParams
import com.stripe.param.checkout.SessionCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import schemas.sentinela.SentinelaBillingService
import schemas.sentinela.SentinelaPlan
import java.time.Instant

/** Preço de um plano como está cadastrado na Stripe (os valores em reais ficam só lá). */
@Serializable
data class SentinelaPlanPriceDto(
    val amountCents: Long,
    val currency: String,
    /** "month" ou "year" para assinaturas; nulo em compra única. */
    val interval: String?,
    val intervalCount: Int?,
)

@Serializable
data class SentinelaPlanOfferDto(
    val id: String,
    val title: String,
    val hours: Int,
    val retentionDays: Int,
    val recurring: Boolean,
    /** Nulo se o preço do plano não estiver configurado ou a Stripe não respondeu. */
    val price: SentinelaPlanPriceDto?,
)

/**
 * Cobrança do Sentinela na Stripe. O Sentinela tem conta própria, então a chave é a dele
 * (`stripe.sentinela.apiKey`) e vai em cada chamada; a `Stripe.apiKey` global das outras funcionalidades
 * não é usada nem alterada.
 *
 * Configuração (propriedade do sistema ou variável de ambiente):
 * - `stripe.sentinela.apiKey` / `STRIPE_SENTINELA_API_KEY` — chave secreta da conta do Sentinela
 * - `stripe.sentinela.webhookSecret` / `STRIPE_SENTINELA_WEBHOOK_SECRET` — segredo do webhook
 * - `stripe.sentinela.price.<plano>` / `STRIPE_SENTINELA_PRICE_<PLANO>` — ID do preço de cada plano
 * - `sentinela.appUrl` / `SENTINELA_APP_URL` — endereço do app web, para voltar do checkout quando o
 *   navegador não informa a origem
 */
class SentinelaStripe(private val billing: SentinelaBillingService) {

    private companion object {
        const val PRICE_CACHE_MILLIS = 10 * 60 * 1000L

        fun config(property: String, env: String): String? =
            (System.getProperty(property) ?: System.getenv(env))?.trim()?.takeIf { it.isNotEmpty() }
    }

    private val apiKey: String
        get() = config("stripe.sentinela.apiKey", "STRIPE_SENTINELA_API_KEY")
            ?: error("Chave da Stripe do Sentinela não configurada (stripe.sentinela.apiKey)")

    private val options: RequestOptions get() = RequestOptions.builder().setApiKey(apiKey).build()

    val webhookSecret: String?
        get() = config("stripe.sentinela.webhookSecret", "STRIPE_SENTINELA_WEBHOOK_SECRET")

    val defaultAppUrl: String?
        get() = config("sentinela.appUrl", "SENTINELA_APP_URL")?.trimEnd('/')

    /** O que está configurado para o plano: um ID de preço (`price_…`) ou de produto (`prod_…`). */
    fun configuredIdFor(plan: SentinelaPlan): String? =
        config("stripe.sentinela.price.${plan.name.lowercase()}", "STRIPE_SENTINELA_PRICE_${plan.name}")

    /**
     * Plano que corresponde a um preço/produto de um evento da Stripe, ou null se não for de nenhum plano
     * do Sentinela. Vale o ID configurado ser o do preço ou o do produto.
     */
    fun planFor(priceId: String?, productId: String?): SentinelaPlan? =
        SentinelaPlan.entries.firstOrNull { plan ->
            val configured = configuredIdFor(plan)
            configured != null && (configured == priceId || configured == productId)
        }

    private val resolvedPrices = HashMap<String, String>()

    /**
     * ID do preço (`price_…`) que o checkout precisa. Se o configurado for um produto, usa o preço padrão
     * dele; produtos com mais de um preço devem ter o ID do preço configurado diretamente.
     */
    private suspend fun resolvePriceId(plan: SentinelaPlan): String? {
        val configured = configuredIdFor(plan) ?: return null
        if (configured.startsWith("price_")) return configured
        synchronized(resolvedPrices) { resolvedPrices[configured] }?.let { return it }
        val price = withContext(Dispatchers.IO) { Product.retrieve(configured, options).defaultPrice }
            ?: error("O produto $configured não tem preço padrão; configure o ID do preço (price_…) no lugar dele")
        synchronized(resolvedPrices) { resolvedPrices[configured] = price }
        return price
    }

    private val priceCache = HashMap<SentinelaPlan, Pair<Long, SentinelaPlanPriceDto?>>()

    /** Planos à venda com o preço cadastrado na Stripe (guardado por 10 min para não consultar a cada tela). */
    suspend fun offers(): List<SentinelaPlanOfferDto> = SentinelaPlan.entries.map { plan ->
        SentinelaPlanOfferDto(
            id = plan.name,
            title = plan.title,
            hours = plan.hours,
            retentionDays = plan.retentionDays,
            recurring = plan.recurring,
            price = priceOf(plan),
        )
    }

    private suspend fun priceOf(plan: SentinelaPlan): SentinelaPlanPriceDto? {
        val cached = synchronized(priceCache) { priceCache[plan] }
        if (cached != null && System.currentTimeMillis() - cached.first < PRICE_CACHE_MILLIS) return cached.second

        val price = runCatching {
            val priceId = resolvePriceId(plan) ?: return null
            withContext(Dispatchers.IO) {
                val stripePrice = Price.retrieve(priceId, options)
                SentinelaPlanPriceDto(
                    amountCents = stripePrice.unitAmount ?: return@withContext null,
                    currency = stripePrice.currency,
                    interval = stripePrice.recurring?.interval,
                    intervalCount = stripePrice.recurring?.intervalCount?.toInt(),
                )
            }
        }.onFailure { println("[Sentinela] Falha ao buscar o preço do plano ${plan.name} na Stripe: ${it.message}") }
            .getOrNull()

        if (price != null) synchronized(priceCache) { priceCache[plan] = System.currentTimeMillis() to price }
        return price
    }

    /** Cliente da Stripe desta conta; cria na primeira vez, já marcando o UID, e guarda o vínculo. */
    private suspend fun ensureCustomer(uid: String, email: String?): String {
        billing.customerIdOf(uid)?.let { return it }
        val customer = withContext(Dispatchers.IO) {
            Customer.create(
                CustomerCreateParams.builder()
                    .apply { if (!email.isNullOrBlank()) setEmail(email) }
                    .putMetadata("uid", uid)
                    .build(),
                options,
            )
        }
        billing.linkCustomer(uid, customer.id)
        return customer.id
    }

    /** Abre um checkout hospedado pela Stripe para [plan] e devolve o endereço para redirecionar o usuário. */
    suspend fun createCheckoutUrl(uid: String, email: String?, plan: SentinelaPlan, appUrl: String): String {
        val priceId = resolvePriceId(plan) ?: error("Preço do plano ${plan.name} não configurado (stripe.sentinela.price.${plan.name.lowercase()})")
        val customerId = ensureCustomer(uid, email)

        val params = SessionCreateParams.builder()
            .setMode(if (plan.recurring) SessionCreateParams.Mode.SUBSCRIPTION else SessionCreateParams.Mode.PAYMENT)
            .setCustomer(customerId)
            .setClientReferenceId(uid)
            .setLocale(SessionCreateParams.Locale.PT_BR)
            .addLineItem(SessionCreateParams.LineItem.builder().setPrice(priceId).setQuantity(1L).build())
            .setSuccessUrl("$appUrl/?checkout=success")
            .setCancelUrl("$appUrl/?checkout=cancel")
            .putMetadata("uid", uid)
            .putMetadata("plan", plan.name)
            .apply {
                if (plan.recurring) {
                    setSubscriptionData(
                        SessionCreateParams.SubscriptionData.builder()
                            .putMetadata("uid", uid)
                            .putMetadata("plan", plan.name)
                            .build(),
                    )
                }
            }
            .build()

        val session = withContext(Dispatchers.IO) { CheckoutSession.create(params, options) }
        return session.url ?: error("A Stripe não devolveu o endereço do checkout")
    }

    /** Portal da Stripe para o usuário ver faturas, trocar o cartão e cancelar a assinatura. */
    suspend fun createPortalUrl(uid: String, returnUrl: String): String {
        val customerId = billing.customerIdOf(uid) ?: error("Esta conta ainda não tem cobrança na Stripe")
        val params = PortalSessionParams.builder().setCustomer(customerId).setReturnUrl(returnUrl).build()
        val session = withContext(Dispatchers.IO) { PortalSession.create(params, options) }
        return session.url ?: error("A Stripe não devolveu o endereço do portal")
    }

    /** Fim do período de uma assinatura a partir de "agora + intervalo" quando a Stripe ainda não informou a data. */
    fun fallbackPeriodEnd(plan: SentinelaPlan, now: Instant = Instant.now()): Instant = when (plan) {
        SentinelaPlan.MENSAL -> now.plusSeconds(31L * 24 * 3600)
        SentinelaPlan.TRIMESTRAL -> now.plusSeconds(93L * 24 * 3600)
        SentinelaPlan.SEMESTRAL -> now.plusSeconds(186L * 24 * 3600)
        SentinelaPlan.ANUAL -> now.plusSeconds(366L * 24 * 3600)
        SentinelaPlan.AVULSO -> now.plusSeconds(plan.validityDays * 24L * 3600)
    }
}
