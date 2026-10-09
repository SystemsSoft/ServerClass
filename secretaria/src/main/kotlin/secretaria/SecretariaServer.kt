package secretaria

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import routes.secretaria.configureSecretaria
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
import services.secretaria.SecretariaCallHandler
import services.secretaria.SecretariaCallRegistry
import services.secretaria.SecretariaLiveBridge
import services.secretaria.HttpStripeGateway
import services.secretaria.StripeConfig
import schemas.secretaria.SecretariaBillingService
import schemas.secretaria.SecretariaAffiliateService
import services.secretaria.SecretariaLiveConfig
import services.secretaria.SecretariaToolExecutor
import java.io.File
import java.sql.DriverManager
import java.util.Properties

/**
 * Serviço da SecretárIA em processo próprio (deploy separado do servidor principal). Fica atrás do nginx, que encaminha
 * `/secretaria/` e `/ws/secretaria/` para cá.
 *
 *   java -jar secretaria-0.0.1.jar        porta 8081 em 127.0.0.1 (secretaria.port / secretaria.host para mudar)
 *
 * Configuração na pasta de onde o processo roda (ou variáveis de ambiente): `gemini-credentials.properties` (chaves do
 * Gemini) e `secretaria-credentials.properties` (secretaria.* e o banco: db.host, db.user, db.password — sem valor padrão).
 * Para desenvolver sem MySQL: `-Dsecretaria.db.url=jdbc:h2:mem:secretaria;MODE=MySQL;DB_CLOSE_DELAY=-1`.
 */
fun main() {
    loadLocalSecrets("gemini-credentials.properties")
    loadLocalSecrets("secretaria-credentials.properties")
    val port = config("secretaria.port", "SECRETARIA_PORT")?.toIntOrNull() ?: 8081
    val host = config("secretaria.host", "SECRETARIA_HOST") ?: "127.0.0.1"
    println("[Secretaria] Subindo em http://$host:$port")
    embeddedServer(Netty, port = port, host = host, module = { secretariaService() }).start(wait = true)
}

/** Tudo o que o serviço precisa: JSON, CORS, WebSocket, injeção de dependências e as rotas da SecretárIA. */
fun Application.secretariaService(database: Database = connectDatabase()) {
    // mesmas regras de JSON do servidor principal (o painel e o PWA já dependem delas)
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }
    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        // igual ao servidor principal hoje; a chamada de voz é restringida por secretaria.allowedOrigins
        anyHost()
        allowCredentials = true
    }
    install(WebSockets) {
        pingPeriodMillis = 15_000
        timeoutMillis = 60_000
        maxFrameSize = 1L shl 20 // 1 MB: um pedaço de áudio do PWA tem poucos KB
        masking = false
    }
    install(Koin) {
        slf4jLogger()
        modules(secretariaModule(database))
    }
    routing {
        // usado pelo deploy (e por quem monitora): 200 só se o banco responde
        get("/secretaria/health") {
            val dbOk = runCatching { transaction(database) { exec("SELECT 1") } }.isSuccess
            call.respondText(if (dbOk) "ok" else "banco indisponível", ContentType.Text.Plain, if (dbOk) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable)
        }
    }
    configureSecretaria()
}

/** Serviços da SecretárIA (antes no Databases.kt do servidor principal). */
fun secretariaModule(database: Database): Module = module {
    single { SecretariaTokens.fromConfig() }
    single { SecretariaAuthService(database, get()) }
    single { SecretariaClinicService(database) }
    single { SecretariaAppointmentService(database) }
    single { SecretariaCallService(database) }
    single { SecretariaSettingsService(database, geminiConfigured = { SecretariaLiveConfig.keysFromConfig().isNotEmpty() }) }
    single { SecretariaTeamService(database) }
    single { SecretariaCatalogService(database) }
    single { SecretariaPatientService(database, get()) }
    single { StripeConfig.fromConfig() }
    single { SecretariaAffiliateService(database) }
    single { SecretariaBillingService(database, get(), get<StripeConfig>().takeIf { it.enabled }?.let { HttpStripeGateway(it) }, get()) }
    single { SecretariaPlanService(database, get(), billing = { get<SecretariaBillingService>().info() }) }
    single { SecretariaReportService(database) }
    single { SecretariaProfileService(database) }
    single { SecretariaDashboardService(get(), get(), get(), get()) }
    single { SecretariaCallRegistry() }
    single { SecretariaToolExecutor(get(), get(), get()) }
    single { SecretariaLiveBridge(get(), get()) }
    single {
        SecretariaCallHandler(
            calls = get(), clinics = get(), settings = get(), bridge = get(), registry = get(), profiles = get(),
            model = SecretariaLiveConfig().model,
            maxConcurrentCalls = config("secretaria.maxConcurrentCalls", "SECRETARIA_MAX_CONCURRENT_CALLS")?.toIntOrNull() ?: 5,
        )
    }
}

// ── banco ────────────────────────────────────────────────────────────────────

private const val DB_NAME = "secretaria_db"

/** Abre o banco (MySQL, ou o que vier em secretaria.db.url) e cria as tabelas que faltarem. */
private fun connectDatabase(): Database {
    val url = config("secretaria.db.url", "SECRETARIA_DB_URL")?.takeIf { it.isNotBlank() }
    val database = if (url != null) Database.connect(url) else connectMySql()
    SecretariaSchema.create(database)
    return database
}

private fun connectMySql(): Database {
    val host = required("db.host", "DB_HOST")
    val user = required("db.user", "DB_USER")
    val password = required("db.password", "DB_PASSWORD")

    // mesmo comportamento do servidor principal: cria o banco se ainda não existir.
    // Carrega o driver explicitamente (como o servidor principal): não depende do registro automático do jar.
    Class.forName("com.mysql.cj.jdbc.Driver")
    DriverManager.getConnection("jdbc:mysql://$host:3306/", user, password).use { connection ->
        connection.createStatement().use {
            it.executeUpdate("CREATE DATABASE IF NOT EXISTS `$DB_NAME` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
        }
    }
    val pool = HikariConfig().apply {
        jdbcUrl = "jdbc:mysql://$host:3306/$DB_NAME"
        username = user
        this.password = password
        driverClassName = "com.mysql.cj.jdbc.Driver"
        maximumPoolSize = 10
        minimumIdle = 0
        idleTimeout = 300_000
        connectionTimeout = 10_000
        maxLifetime = 1_800_000
        isAutoCommit = false
        transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        validate()
    }
    return Database.connect(HikariDataSource(pool))
}

// ── configuração ─────────────────────────────────────────────────────────────

private fun config(property: String, env: String): String? = System.getProperty(property) ?: System.getenv(env)

private fun required(property: String, env: String): String =
    config(property, env)?.takeIf { it.isNotBlank() }
        ?: error("Configure $property (ou a variável $env) em secretaria-credentials.properties para o serviço da SecretárIA acessar o banco.")

/** Carrega um .properties local (nunca versionado) como propriedades do sistema; sem o arquivo, segue com o ambiente. */
private fun loadLocalSecrets(fileName: String) {
    val file = File(fileName)
    if (!file.exists()) {
        println("[Config] $fileName não encontrado, usando variáveis de ambiente")
        return
    }
    runCatching {
        val properties = Properties()
        file.inputStream().use { properties.load(it) }
        properties.forEach { (key, value) -> System.setProperty(key.toString(), value.toString()) }
    }.onSuccess { println("[Config] Credenciais carregadas de $fileName") }
        .onFailure { println("[Config] Erro ao carregar $fileName: ${it.message}") }
}
