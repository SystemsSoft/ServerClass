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
    val plans = SecretariaPlanService(db, clinics, clock)
    val reports = SecretariaReportService(db)
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
    val handler = SecretariaCallHandler(fx.calls, fx.clinics, fx.settings, bridge, fx.registry, "models/fake", maxConcurrentCalls = 2, clock = fx.clock)
    application {
        install(ServerWebSockets)
        install(ContentNegotiation) { json() }
        install(Koin) {
            modules(module {
                single { fx.auth }; single { fx.clinics }; single { fx.calls }; single { fx.appointments }; single { fx.dashboard }
                single { fx.registry }; single { handler }; single { fx.settings }; single { fx.team }; single { fx.catalog }
                single { fx.patients }; single { fx.plans }; single { fx.reports }
            })
        }
        configureSecretaria()
    }
}
