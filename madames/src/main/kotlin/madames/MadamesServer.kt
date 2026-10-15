package madames

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
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import routes.madamesFormosas.madamesEquipeRouting
import routes.madamesFormosas.madamesProdutosRouting
import schemas.madamesFormosas.EquipeService
import schemas.madamesFormosas.ProdutoService
import java.io.File
import java.sql.DriverManager
import java.util.Properties

/**
 * Serviço da Madames Formosas (catálogo de produtos) em processo próprio, com deploy separado do servidor principal.
 * Fica atrás do nginx, que encaminha `/madames/` para cá.
 *
 *   java -jar madames-0.0.1.jar        porta 8082 em 127.0.0.1 (madames.port / madames.host para mudar)
 *
 * Configuração em `madames-credentials.properties` na pasta de onde o processo roda (ou variáveis de ambiente):
 * o banco (db.host, db.user, db.password — sem valor padrão), madames.adminToken e o primeiro login da equipe
 * (madames.adminLogin / madames.adminPassword, usado só enquanto não existe ninguém cadastrado).
 * Para desenvolver sem MySQL: `-Dmadames.db.url=jdbc:h2:mem:madames;MODE=MySQL;DB_CLOSE_DELAY=-1`.
 */
fun main() {
    loadLocalSecrets("madames-credentials.properties")
    val port = config("madames.port", "MADAMES_PORT")?.toIntOrNull() ?: 8082
    val host = config("madames.host", "MADAMES_HOST") ?: "127.0.0.1"
    println("[Madames] Subindo em http://$host:$port")
    embeddedServer(Netty, port = port, host = host, module = { madamesService() }).start(wait = true)
}

/** Tudo o que o serviço precisa: JSON, CORS e as rotas do catálogo. */
fun Application.madamesService(
    database: Database = connectDatabase(),
    adminInicial: Pair<String, String>? = adminInicialDaConfig(),
) {
    // mesmas regras de JSON do servidor principal
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
        // catálogo público; a escrita é protegida pelo token de administração
        anyHost()
    }
    routing {
        // usado pelo deploy (e por quem monitora): 200 só se o banco responde
        get("/madames/health") {
            val dbOk = runCatching { transaction(database) { exec("SELECT 1") } }.isSuccess
            call.respondText(if (dbOk) "ok" else "banco indisponível", ContentType.Text.Plain, if (dbOk) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable)
        }
    }
    val produtos = ProdutoService(database)
    madamesProdutosRouting(produtos)
    madamesEquipeRouting(EquipeService(database, adminInicial), produtos)
}

// ── banco ────────────────────────────────────────────────────────────────────

private const val DB_NAME = "madames_formosas"

/** Abre o banco (MySQL, ou o que vier em madames.db.url). As tabelas são criadas pelo ProdutoService. */
private fun connectDatabase(): Database {
    val url = config("madames.db.url", "MADAMES_DB_URL")?.takeIf { it.isNotBlank() }
    return if (url != null) Database.connect(url) else connectMySql()
}

private fun connectMySql(): Database {
    val host = required("db.host", "DB_HOST")
    val user = required("db.user", "DB_USER")
    val password = required("db.password", "DB_PASSWORD")

    // mesmo comportamento do servidor principal: cria o banco se ainda não existir.
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
        maximumPoolSize = 3
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

private fun adminInicialDaConfig(): Pair<String, String>? {
    val login = config("madames.adminLogin", "MADAMES_ADMIN_LOGIN")?.takeIf { it.isNotBlank() } ?: return null
    val senha = config("madames.adminPassword", "MADAMES_ADMIN_PASSWORD")?.takeIf { it.isNotBlank() } ?: return null
    return login to senha
}

private fun required(property: String, env: String): String =
    config(property, env)?.takeIf { it.isNotBlank() }
        ?: error("Configure $property (ou a variável $env) em madames-credentials.properties para o serviço da Madames acessar o banco.")

/** Carrega um .properties local (nunca versionado) como propriedades do sistema; sem o arquivo, segue com o ambiente. */
private fun loadLocalSecrets(fileName: String) {
    val file = File(fileName)
    if (!file.exists()) {
        println("[Config] $fileName não encontrado, usando variáveis de ambiente")
        return
    }
    val properties = Properties()
    file.inputStream().use { properties.load(it) }
    properties.forEach { (key, value) -> System.setProperty(key.toString(), value.toString()) }
    println("[Config] Credenciais carregadas de $fileName")
}
