import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import routes.sentinela.SentinelaStripeEvents
import routes.sentinela.sentinelaStripeWebhookRouting
import schemas.sentinela.SENTINELA_TRIAL_SECONDS
import schemas.sentinela.SentinelaBillingMode
import schemas.sentinela.SentinelaBillingService
import schemas.sentinela.SentinelaPlan
import services.SentinelaStripe
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SentinelaBillingTest {

    private val uid = "uid-1"
    private val customer = "cus_123"

    @BeforeTest
    fun configure() {
        SentinelaPlan.entries.forEach { System.setProperty("stripe.sentinela.price.${it.name.lowercase()}", "price_${it.name.lowercase()}") }
        System.setProperty("stripe.sentinela.webhookSecret", "whsec_teste")
    }

    @AfterTest
    fun cleanup() {
        SentinelaPlan.entries.forEach { System.clearProperty("stripe.sentinela.price.${it.name.lowercase()}") }
        System.clearProperty("stripe.sentinela.webhookSecret")
    }

    private fun newBilling() =
        SentinelaBillingService(Database.connect("jdbc:h2:mem:${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1", "org.h2.Driver"))

    private fun events(billing: SentinelaBillingService) = SentinelaStripeEvents(billing, SentinelaStripe(billing))

    // ── saldo ────────────────────────────────────────────────────────────────

    @Test
    fun `new account has 30 free minutes and they are consumed first come first served`() = runBlocking {
        val billing = newBilling()

        val fresh = billing.availability(uid)
        assertEquals(SentinelaBillingMode.TRIAL, fresh.mode)
        assertEquals(1800, fresh.remainingSeconds)
        assertTrue(fresh.canRecord)

        billing.consume(uid, SentinelaBillingMode.TRIAL, 600)
        assertEquals(1200, billing.availability(uid).remainingSeconds)

        billing.consume(uid, SentinelaBillingMode.TRIAL, 5000) // passou do limite por causa do atraso da rede
        val exhausted = billing.availability(uid)
        assertEquals(SentinelaBillingMode.NONE, exhausted.mode)
        assertFalse(exhausted.canRecord)
        assertEquals(SENTINELA_TRIAL_SECONDS, billing.status(uid).trialUsedSeconds) // nunca mostra mais que o total
    }

    @Test
    fun `an active plan takes priority over the trial and its usage is separate`() = runBlocking {
        val billing = newBilling()
        billing.linkCustomer(uid, customer)
        billing.activateSubscription(uid, SentinelaPlan.MENSAL, Instant.now().plus(Duration.ofDays(30)), "sub_1")

        val availability = billing.availability(uid)
        assertEquals(SentinelaBillingMode.PLAN, availability.mode)
        assertEquals(3 * 3600L, availability.remainingSeconds)

        billing.consume(uid, SentinelaBillingMode.PLAN, 3 * 3600)
        // acabou o plano do período: volta ao que sobrou do teste grátis (que continua intacto)
        val afterPlan = billing.availability(uid)
        assertEquals(SentinelaBillingMode.TRIAL, afterPlan.mode)
        assertEquals(1800, afterPlan.remainingSeconds)
    }

    @Test
    fun `an expired plan stops counting`() = runBlocking {
        val billing = newBilling()
        billing.activateSubscription(uid, SentinelaPlan.MENSAL, Instant.now().minusSeconds(60), "sub_1")
        billing.consume(uid, SentinelaBillingMode.TRIAL, 1800)

        assertEquals(SentinelaBillingMode.NONE, billing.availability(uid).mode)
    }

    @Test
    fun `renewal resets the period usage`() = runBlocking {
        val billing = newBilling()
        billing.activateSubscription(uid, SentinelaPlan.MENSAL, Instant.now().plus(Duration.ofDays(30)), "sub_1")
        billing.consume(uid, SentinelaBillingMode.PLAN, 7200)
        assertEquals(3600, billing.availability(uid).remainingSeconds)

        billing.activateSubscription(uid, SentinelaPlan.MENSAL, Instant.now().plus(Duration.ofDays(60)), "sub_1")
        assertEquals(3 * 3600L, billing.availability(uid).remainingSeconds)
    }

    @Test
    fun `single purchase adds one hour, stacks, and carries into a subscription`() = runBlocking {
        val billing = newBilling()
        billing.grantAvulso(uid)
        assertEquals(SentinelaPlan.AVULSO.name, billing.status(uid).plan)
        assertEquals(3600, billing.availability(uid).remainingSeconds)

        billing.grantAvulso(uid) // comprou outra antes de usar
        assertEquals(7200, billing.availability(uid).remainingSeconds)

        billing.consume(uid, SentinelaBillingMode.PLAN, 1800)
        billing.activateSubscription(uid, SentinelaPlan.MENSAL, Instant.now().plus(Duration.ofDays(30)), "sub_1")
        // mensal (3h) + o que sobrou das avulsas (1h30)
        assertEquals(3 * 3600L + 5400, billing.availability(uid).remainingSeconds)
    }

    @Test
    fun `single purchase during a subscription is added to it`() = runBlocking {
        val billing = newBilling()
        billing.activateSubscription(uid, SentinelaPlan.MENSAL, Instant.now().plus(Duration.ofDays(30)), "sub_1")
        billing.grantAvulso(uid)

        val status = billing.status(uid)
        assertEquals(SentinelaPlan.MENSAL.name, status.plan)
        assertEquals(4 * 3600L, status.planIncludedSeconds)
    }

    @Test
    fun `stripe events are processed only once`() = runBlocking {
        val billing = newBilling()
        assertTrue(billing.markProcessed("event:evt_1"))
        assertFalse(billing.markProcessed("event:evt_1"))
        billing.forget("event:evt_1")
        assertTrue(billing.markProcessed("event:evt_1"))
    }

    // ── eventos da Stripe ────────────────────────────────────────────────────

    private fun checkoutCompleted(id: String, mode: String, paymentStatus: String, event: String = "evt_$id") = """
        {"id":"$event","type":"checkout.session.completed","data":{"object":{"id":"cs_$id","object":"checkout.session",
        "mode":"$mode","payment_status":"$paymentStatus","client_reference_id":"$uid","customer":"$customer",
        "subscription":${if (mode == "subscription") "\"sub_1\"" else "null"},"metadata":{"uid":"$uid","plan":"${if (mode == "payment") "AVULSO" else "MENSAL"}"}}}}
    """.trimIndent()

    /** Fatura no formato das APIs novas (basil/dahlia): assinatura em parent.subscription_details, preço em pricing. */
    private fun invoicePaidNewApi(event: String, priceId: String, periodEnd: Long) = """
        {"id":"$event","type":"invoice.paid","data":{"object":{"id":"in_1","customer":"$customer",
        "parent":{"subscription_details":{"subscription":"sub_1","metadata":{"uid":"$uid","plan":"MENSAL"}}},
        "lines":{"data":[{"period":{"start":1,"end":$periodEnd},"pricing":{"price_details":{"price":"$priceId"}}}]}}}}
    """.trimIndent()

    /** Fatura no formato antigo: subscription na raiz e price.id na linha. */
    private fun invoicePaidOldApi(event: String, priceId: String, periodEnd: Long) = """
        {"id":"$event","type":"invoice.paid","data":{"object":{"id":"in_1","customer":"$customer","subscription":"sub_1",
        "lines":{"data":[{"period":{"start":1,"end":$periodEnd},"price":{"id":"$priceId"}}]}}}}
    """.trimIndent()

    private val futureEnd get() = Instant.now().plus(Duration.ofDays(30)).epochSecond

    @Test
    fun `subscription flow - checkout links the customer and invoice paid activates the plan`() = runBlocking {
        val billing = newBilling()
        val events = events(billing)

        events.handle(checkoutCompleted("1", "subscription", "paid"))
        assertEquals(SentinelaBillingMode.TRIAL, billing.availability(uid).mode) // ainda não liberou: quem libera é a fatura
        assertEquals(customer, billing.customerIdOf(uid))

        events.handle(invoicePaidNewApi("evt_i1", "price_mensal", futureEnd))
        val status = billing.status(uid)
        assertEquals("MENSAL", status.plan)
        assertEquals("ACTIVE", status.planStatus)
        assertEquals(SentinelaBillingMode.PLAN.name, status.mode)
        assertEquals(futureEnd, Instant.parse(status.planActiveUntil).epochSecond)
    }

    @Test
    fun `invoice paid works for the older api shape and before the checkout event`() = runBlocking {
        val billing = newBilling()
        billing.linkCustomer(uid, customer) // o vínculo nasce quando o checkout é criado
        events(billing).handle(invoicePaidOldApi("evt_i2", "price_trimestral", futureEnd))

        assertEquals("TRIMESTRAL", billing.status(uid).plan)
        assertEquals(10 * 3600L, billing.availability(uid).remainingSeconds)
    }

    @Test
    fun `the same event delivered twice does not renew twice`() = runBlocking {
        val billing = newBilling()
        billing.linkCustomer(uid, customer)
        val events = events(billing)
        events.handle(invoicePaidNewApi("evt_i3", "price_mensal", futureEnd))
        billing.consume(uid, SentinelaBillingMode.PLAN, 3600)

        events.handle(invoicePaidNewApi("evt_i3", "price_mensal", futureEnd)) // reenvio da Stripe
        assertEquals(2 * 3600L, billing.availability(uid).remainingSeconds)
    }

    @Test
    fun `single purchase is credited once even when two events arrive for the same session`() = runBlocking {
        val billing = newBilling()
        val events = events(billing)

        // Pix: a sessão conclui sem pagamento; o crédito só vem quando o pagamento é confirmado
        events.handle(checkoutCompleted("2", "payment", "unpaid"))
        assertEquals(SentinelaBillingMode.TRIAL, billing.availability(uid).mode)

        val asyncSucceeded = checkoutCompleted("2", "payment", "paid", event = "evt_async")
            .replace("checkout.session.completed", "checkout.session.async_payment_succeeded")
        events.handle(asyncSucceeded)
        assertEquals(SentinelaBillingMode.PLAN, billing.availability(uid).mode)
        assertEquals(3600, billing.availability(uid).remainingSeconds)

        events.handle(checkoutCompleted("2", "payment", "paid", event = "evt_other")) // mesma sessão, outro evento
        assertEquals(3600, billing.availability(uid).remainingSeconds)
    }

    @Test
    fun `payment failure blocks the plan, recovery and cancellation are tracked, deletion ends it`() = runBlocking {
        val billing = newBilling()
        billing.linkCustomer(uid, customer)
        val events = events(billing)
        events.handle(invoicePaidNewApi("evt_a", "price_mensal", futureEnd))
        billing.consume(uid, SentinelaBillingMode.TRIAL, 1800)

        events.handle("""{"id":"evt_b","type":"invoice.payment_failed","data":{"object":{"customer":"$customer"}}}""")
        assertEquals("PAST_DUE", billing.status(uid).planStatus)
        assertFalse(billing.availability(uid).canRecord)

        events.handle("""{"id":"evt_c","type":"customer.subscription.updated","data":{"object":{"customer":"$customer","status":"active","cancel_at_period_end":true}}}""")
        assertEquals("ACTIVE", billing.status(uid).planStatus)
        assertTrue(billing.status(uid).cancelAtPeriodEnd)
        assertTrue(billing.availability(uid).canRecord)

        events.handle("""{"id":"evt_d","type":"customer.subscription.deleted","data":{"object":{"customer":"$customer"}}}""")
        assertEquals("CANCELED", billing.status(uid).planStatus)
        assertFalse(billing.availability(uid).canRecord)
    }

    @Test
    fun `a plan configured with a product id is matched by the product of the invoice line`() = runBlocking {
        System.setProperty("stripe.sentinela.price.semestral", "prod_semestral")
        val billing = newBilling()
        billing.linkCustomer(uid, customer)
        val payload = invoicePaidNewApi("evt_p", "price_qualquer", futureEnd)
            .replace("\"price\":\"price_qualquer\"", "\"price\":\"price_qualquer\",\"product\":\"prod_semestral\"")

        events(billing).handle(payload)
        assertEquals("SEMESTRAL", billing.status(uid).plan)
    }

    @Test
    fun `unknown price and unrelated events are ignored`() = runBlocking {
        val billing = newBilling()
        billing.linkCustomer(uid, customer)
        val events = events(billing)

        events.handle(invoicePaidNewApi("evt_x", "price_de_outro_produto", futureEnd).replace("\"plan\":\"MENSAL\"", "\"plan\":\"NADA\""))
        events.handle("""{"id":"evt_y","type":"charge.refunded","data":{"object":{"id":"ch_1"}}}""")

        assertEquals(null, billing.status(uid).plan)
    }

    // ── assinatura do webhook ────────────────────────────────────────────────

    private fun signature(payload: String, secret: String, timestamp: Long = Instant.now().epochSecond): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        val hex = mac.doFinal("$timestamp.$payload".toByteArray()).joinToString("") { "%02x".format(it) }
        return "t=$timestamp,v1=$hex"
    }

    @Test
    fun `webhook rejects a missing or wrong signature and accepts a valid one`() = testApplication {
        val billing = newBilling()
        val stripe = SentinelaStripe(billing)
        application { sentinelaStripeWebhookRouting(billing, stripe) }
        billing.linkCustomer(uid, customer)
        val payload = invoicePaidNewApi("evt_w", "price_anual", futureEnd)

        assertEquals(HttpStatusCode.BadRequest, client.post("/webhook/stripe/sentinela") { setBody(payload) }.status)
        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/webhook/stripe/sentinela") {
                header("Stripe-Signature", signature(payload, "whsec_outro_segredo"))
                setBody(payload)
            }.status,
        )
        assertEquals(null, billing.status(uid).plan) // nada foi aplicado

        val ok = client.post("/webhook/stripe/sentinela") {
            header("Stripe-Signature", signature(payload, "whsec_teste"))
            setBody(payload)
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("ANUAL", billing.status(uid).plan)
    }
}
