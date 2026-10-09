import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import schemas.secretaria.BootstrapDoctor
import schemas.secretaria.BootstrapRequest
import schemas.secretaria.BootstrapResponse
import schemas.secretaria.ClinicInfo
import schemas.secretaria.SecretariaAppointmentService
import schemas.secretaria.SecretariaAuthService
import schemas.secretaria.SecretariaCallService
import schemas.secretaria.SecretariaCatalogService
import schemas.secretaria.SecretariaClinicService
import schemas.secretaria.SecretariaDashboardService
import schemas.secretaria.SecretariaPatientService
import schemas.secretaria.SecretariaPlanService
import schemas.secretaria.SecretariaProfileService
import schemas.secretaria.SecretariaReportService
import schemas.secretaria.SecretariaSchema
import schemas.secretaria.SecretariaSettingsService
import schemas.secretaria.SecretariaTeamService
import schemas.secretaria.SecretariaTokens
import services.secretaria.SecretariaCallRegistry
import services.secretaria.SecretariaToolExecutor
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import routes.secretaria.configureSecretaria
import schemas.secretaria.SecretariaAffiliateService
import schemas.secretaria.SecretariaBillingService
import services.secretaria.StripeConfig
import services.secretaria.StripeGateway
import services.secretaria.StripeSubscription
import services.secretaria.GeminiKey
import services.secretaria.SecretariaCallHandler
import services.secretaria.SecretariaLiveBridge
import services.secretaria.SecretariaLiveConfig
import java.time.Instant
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import java.util.concurrent.atomic.AtomicLong

/** Serviços da SecretárIA sobre um H2 em memória (modo MySQL) e um relógio controlável. */
class SecretariaFixture(
    start: Instant = Instant.parse("2026-10-05T12:00:00Z"), // segunda 09:00 em Brasília
    database: Database? = null,
) {
    private val nowMs = AtomicLong(start.toEpochMilli())
    val clock: () -> Long = { nowMs.get() }
    fun advanceSeconds(seconds: Long) { nowMs.addAndGet(seconds * 1000) }

    val db: Database = (database ?: Database.connect("jdbc:h2:mem:sec${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1", "org.h2.Driver"))
        .also { SecretariaSchema.create(it) }

    val tokens = SecretariaTokens("segredo-de-teste-com-mais-de-32-caracteres!!")
    val auth = SecretariaAuthService(db, tokens)
    val clinics = SecretariaClinicService(db, clock)
    val appointments = SecretariaAppointmentService(db, clock)
    val calls = SecretariaCallService(db, clock)
    var geminiConfigured = true
    val settings = SecretariaSettingsService(db, clock, geminiConfigured = { geminiConfigured })
    val team = SecretariaTeamService(db, clock)
    val catalog = SecretariaCatalogService(db, clock)
    val patients = SecretariaPatientService(db, appointments, clock)
    val stripe = FakeStripe()
    val stripeConfig = StripeConfig(apiKey = "sk_test_x", webhookSecret = "whsec_teste", priceId = "price_pacote", dashboardUrl = "https://painel.test")
    val billing = SecretariaBillingService(db, clinics, stripe, stripeConfig, clock)
    val affiliates = SecretariaAffiliateService(db, clock, linkBase = "https://painel.test")
    val plans = SecretariaPlanService(db, clinics, clock, billing = { billing.info() })
    val reports = SecretariaReportService(db)
    val profiles = SecretariaProfileService(db, clock)
    val dashboard = SecretariaDashboardService(clinics, calls, appointments, settings, clock)
    val registry = SecretariaCallRegistry()
    val tools = SecretariaToolExecutor(clinics, appointments, calls, clock)

    fun bootstrap(
        email: String = "maria@clinica.test",
        clinicName: String = "Clínica Teste",
        doctors: List<BootstrapDoctor> = listOf(
            BootstrapDoctor("Dr. Henrique Martins", "Consulta geral"),
            BootstrapDoctor("Dra. Camila Torres", "Cardiologia"),
        ),
    ): BootstrapResponse = runBlocking {
        clinics.bootstrap(BootstrapRequest(clinicName, "Dr. Henrique Martins", "America/Sao_Paulo", "Maria Silva", email, "senha-segura-123", doctors))
    }

    fun clinic(id: Long): ClinicInfo = runBlocking { clinics.clinic(id)!! }
}

fun secretariaTestConfig(
    url: (String) -> String,
    keys: List<GeminiKey> = listOf(GeminiKey("t", "k")),
    maxCallMillis: Long = 60_000,
    idleMillis: Long = 30_000,
) = SecretariaLiveConfig(
    keys = { keys }, liveUrl = url, model = "models/fake", voice = "Aoede",
    maxCallMillis = maxCallMillis, idleMillis = idleMillis, watchdogIntervalMillis = 50,
)

/** Sobe o módulo (rotas + Koin) sobre os serviços do [SecretariaFixture]. */
fun ApplicationTestBuilder.startSecretaria(fx: SecretariaFixture, config: SecretariaLiveConfig = secretariaTestConfig({ "ws://x" })) {
    val bridge = SecretariaLiveBridge(fx.tools, fx.calls, config)
    val handler = SecretariaCallHandler(
        fx.calls, fx.clinics, fx.settings, bridge, fx.registry, fx.profiles, "models/fake", maxConcurrentCalls = 2, clock = fx.clock,
        helloTimeoutMillis = 100, // quem não manda hello (testes antigos) não espera 1,5 s
    )
    application {
        install(ServerWebSockets)
        install(ContentNegotiation) { json() }
        install(Koin) {
            modules(module {
                single { fx.auth }; single { fx.clinics }; single { fx.calls }; single { fx.appointments }; single { fx.dashboard }
                single { fx.registry }; single { handler }; single { fx.settings }; single { fx.team }; single { fx.catalog }
                single { fx.patients }; single { fx.plans }; single { fx.reports }; single { fx.profiles }; single { fx.billing }
                single { fx.affiliates }
            })
        }
        configureSecretaria()
    }
}

/**
 * Stripe falsa: guarda as assinaturas em memória. [completeCheckout] faz o papel da clínica pagando na página da
 * Stripe; [failNextCharge] simula um cartão recusado no próximo aumento.
 */
class FakeStripe : StripeGateway {
    val checkouts = mutableListOf<Map<String, Any?>>()
    val subscriptions = mutableMapOf<String, StripeSubscription>()
    val quantityCalls = mutableListOf<Pair<Int, Boolean>>()
    var failNextCharge = false
    private var seq = 0

    override suspend fun createCheckout(clinicId: Long, email: String, units: Int, successUrl: String, cancelUrl: String, customerId: String?): String {
        checkouts += mapOf("clinicId" to clinicId, "email" to email, "units" to units, "success" to successUrl, "cancel" to cancelUrl, "customer" to customerId)
        return "https://checkout.stripe.test/c/${checkouts.size}"
    }

    /** A clínica pagou: cria a assinatura (como a Stripe faria) e devolve o id. */
    fun completeCheckout(clinicId: Long, units: Int, periodStart: Long, periodEnd: Long, customerId: String = "cus_${clinicId}"): String {
        val id = "sub_${++seq}"
        subscriptions[id] = StripeSubscription(id, customerId, "active", "si_$seq", units, periodStart, periodEnd, clinicId)
        return id
    }

    override suspend fun subscription(id: String) = subscriptions[id] ?: throw services.secretaria.StripeException("No such subscription: $id")

    override suspend fun setQuantity(subscriptionId: String, itemId: String, units: Int, chargeNow: Boolean) {
        quantityCalls += units to chargeNow
        if (chargeNow && failNextCharge) { failNextCharge = false; return } // pending_if_incomplete: não muda
        subscriptions[subscriptionId] = subscriptions.getValue(subscriptionId).copy(quantity = units)
    }

    override suspend fun portal(customerId: String, returnUrl: String) = "https://billing.stripe.test/p/$customerId"

    fun update(id: String, change: (StripeSubscription) -> StripeSubscription) { subscriptions[id] = change(subscriptions.getValue(id)) }
}

/** Aviso da Stripe assinado como a Stripe assina (HMAC-SHA256 de "t.corpo"). */
fun signedStripeEvent(secret: String, payload: String, nowSeconds: Long): String {
    val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
    val signature = mac.doFinal("$nowSeconds.$payload".toByteArray()).joinToString("") { "%02x".format(it) }
    return "t=$nowSeconds,v1=$signature"
}
