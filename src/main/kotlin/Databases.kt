package com.class_erp

import schemas.classes.UploadService
import com.class_erp.schemas.AccessService
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.koin.core.qualifier.named
import org.koin.dsl.module
import schemas.alunoIa.AlunoIaService
import schemas.classes.ClassesListService
import schemas.classes.FlashcardService
import schemas.classes.estrelasLeiria.CategoriaService
import schemas.classes.estrelasLeiria.EbookPaidSessionService
import schemas.classes.estrelasLeiria.IndicadoService
import schemas.classes.estrelasLeiria.VotoService
import schemas.sentinela.SentinelaBillingService
import schemas.sentinela.SentinelaPushTokenService
import schemas.sentinela.SentinelaRecordingService
import schemas.sentinela.SentinelaShareService
import schemas.sentinela.SentinelaUserService
import schemas.secretaria.SecretariaAppointmentService
import schemas.secretaria.SecretariaAuthService
import schemas.secretaria.SecretariaCallService
import schemas.secretaria.SecretariaCatalogService
import schemas.secretaria.SecretariaClinicService
import schemas.secretaria.SecretariaPatientService
import schemas.secretaria.SecretariaPlanService
import schemas.secretaria.SecretariaReportService
import schemas.secretaria.SecretariaSettingsService
import schemas.secretaria.SecretariaTeamService
import schemas.secretaria.SecretariaDashboardService
import schemas.secretaria.SecretariaSchema
import schemas.secretaria.SecretariaTokens
import schemas.users.ClientService
import services.secretaria.SecretariaCallHandler
import services.secretaria.SecretariaCallRegistry
import services.secretaria.SecretariaLiveBridge
import services.secretaria.SecretariaLiveConfig
import services.secretaria.SecretariaToolExecutor
import services.GeminiLiveBridge
import services.GeminiAudioTranscriber
import services.GeminiTranslationService
import services.SentinelaPasswordReset
import services.SentinelaStripe
import services.SentinelaTranscriptionService


object DatabaseConfig {

    private val host = System.getProperty("db.host") ?: System.getenv("DB_HOST") ?: "ls-2604b71534d5a90611ca87c04b8512b860560ff4.cwtokk0amd7p.us-east-1.rds.amazonaws.com"
    private val dbUser = System.getProperty("db.user") ?: System.getenv("DB_USER") ?: "dbmasteruser"
    private val dbPassword = System.getProperty("db.password") ?: System.getenv("DB_PASSWORD") ?: "dbmasteruser"

    private fun criarBancoSeNaoExistir(dbName: String) {
        Class.forName("com.mysql.cj.jdbc.Driver")
        val url = "jdbc:mysql://$host:3306/"
        val conn = java.sql.DriverManager.getConnection(url, dbUser, dbPassword)
        conn.use { c ->
            c.createStatement().use { stmt ->
                stmt.executeUpdate("CREATE DATABASE IF NOT EXISTS `$dbName` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
                println("[DB] Banco '$dbName' verificado/criado com sucesso.")
            }
        }
    }

    private fun conectarBanco(dbName: String, maxConexoes: Int): Database {
        criarBancoSeNaoExistir(dbName)

        val config = HikariConfig().apply {
            // maxAllowedPacket=629145600 (~600 MB) na URL para garantir que o driver negocie corretamente
            jdbcUrl = "jdbc:mysql://$host:3306/$dbName?maxAllowedPacket=629145600"
            username = dbUser
            password = dbPassword
            driverClassName = "com.mysql.cj.jdbc.Driver"

            // CONFIGURAÇÕES DE LIMITE
            maximumPoolSize = maxConexoes
            minimumIdle = 0
            idleTimeout = 300000
            connectionTimeout = 10000
            maxLifetime = 1800000

            // Permite BLOBs grandes (600 MB) — resolve "Packet too large" do MySQL
            addDataSourceProperty("maxAllowedPacket", "629145600")


            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        }

        val dataSource = HikariDataSource(config)
        return Database.connect(dataSource)
    }


    val classModule = module {
        single(named("MainDB")) {
            // Limite: 2 conexões
            conectarBanco("effective_english_course", maxConexoes = 2)
        }

        single { AccessService(get(named("MainDB"))) }
        single { ClassesListService(get(named("MainDB"))) }
        single { UploadService(get(named("MainDB"))) }
        single(createdAtStart = true) { FlashcardService(get(named("MainDB"))) }
        single { AlunoIaService(get(named("MainDB"))) }
        single { GeminiLiveBridge() }
        single { GeminiTranslationService() }
    }

    val clientModule = module {
        single { ClientService(get(named("MainDB"))) }
    }


    val estrelasLeiria = module {
        single(named("EstrelasLeiriaDB")) {
            conectarBanco("estrelas", maxConexoes = 15)
        }

        single { CategoriaService(get(named("EstrelasLeiriaDB"))) }
        single { IndicadoService(get(named("EstrelasLeiriaDB"))) }
        single { VotoService(get(named("EstrelasLeiriaDB"))) }
        single { EbookPaidSessionService(get(named("EstrelasLeiriaDB"))) }
    }

    val resolvebr = module {}

    val inovaCloud = module {}

    val sentinela = module {
        single(named("SentinelaDB")) {
            // Mesmo host/usuário/senha do banco principal, banco próprio do Sentinela.
            conectarBanco("sentinela_db", maxConexoes = 10)
        }

        single { SentinelaUserService(get(named("SentinelaDB"))) }
        single { SentinelaRecordingService(get(named("SentinelaDB"))) }
        single { SentinelaShareService(get(named("SentinelaDB"))) }
        single { SentinelaPushTokenService(get(named("SentinelaDB"))) }
        single { SentinelaBillingService(get(named("SentinelaDB"))) }
        single { SentinelaStripe(get()) }
        single { SentinelaPasswordReset(get()) }
        single { GeminiAudioTranscriber() }
        single { SentinelaTranscriptionService(get(), get()) }
    }

    val secretaria = module {
        single(named("SecretariaDB")) {
            // Banco próprio da SecretárIA (criado se não existir); as tabelas são criadas na partida.
            conectarBanco("secretaria_db", maxConexoes = 10).also { SecretariaSchema.create(it) }
        }

        single { SecretariaTokens.fromConfig() }
        single { SecretariaAuthService(get(named("SecretariaDB")), get()) }
        single { SecretariaClinicService(get(named("SecretariaDB"))) }
        single { SecretariaAppointmentService(get(named("SecretariaDB"))) }
        single { SecretariaCallService(get(named("SecretariaDB"))) }
        single { SecretariaSettingsService(get(named("SecretariaDB")), geminiConfigured = { SecretariaLiveConfig.keysFromConfig().isNotEmpty() }) }
        single { SecretariaTeamService(get(named("SecretariaDB"))) }
        single { SecretariaCatalogService(get(named("SecretariaDB"))) }
        single { SecretariaPatientService(get(named("SecretariaDB")), get()) }
        single { SecretariaPlanService(get(named("SecretariaDB")), get()) }
        single { SecretariaReportService(get(named("SecretariaDB"))) }
        single { SecretariaDashboardService(get(), get(), get(), get()) }
        single { SecretariaCallRegistry() }
        single { SecretariaToolExecutor(get(), get(), get()) }
        single { SecretariaLiveBridge(get(), get()) }
        single {
            SecretariaCallHandler(
                calls = get(), clinics = get(), settings = get(), bridge = get(), registry = get(),
                model = SecretariaLiveConfig().model,
                maxConcurrentCalls = (System.getProperty("secretaria.maxConcurrentCalls") ?: System.getenv("SECRETARIA_MAX_CONCURRENT_CALLS"))
                    ?.toIntOrNull() ?: 5,
            )
        }
    }
}
