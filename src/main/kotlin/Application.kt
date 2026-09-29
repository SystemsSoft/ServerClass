package com.class_erp


import schemas.classes.UploadService
import com.class_erp.DatabaseConfig.classModule
import com.class_erp.DatabaseConfig.clientModule
import com.class_erp.DatabaseConfig.estrelasLeiria
import com.class_erp.DatabaseConfig.resolvebr
import com.class_erp.DatabaseConfig.inovaCloud
import com.class_erp.DatabaseConfig.sentinela
import com.class_erp.schemas.AccessService
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import routes.`class`.accessRouting
import routes.`class`.classesRouting
import routes.users.clientRouting
import routes.`class`.flashcardsRouting
import routes.`class`.uploadRouting
import routes.alunoIa.alunoIaRouting
import routes.alunoIa.assinaturaWebhookRouting
import routes.alunoIa.curriculumRouting
import routes.alunoIa.meganRouting
import routes.alunoIa.translateRouting
import services.GeminiLiveBridge
import routes.estrelasLeiria.categoriaRouting
import routes.estrelasLeiria.indicadoRouting
import routes.estrelasLeiria.stripeRouting
import routes.estrelasLeiria.votoRouting
import schemas.alunoIa.AlunoIaService
import schemas.classes.ClassesListService
import schemas.classes.FlashcardService
import schemas.classes.estrelasLeiria.CategoriaService
import schemas.classes.estrelasLeiria.IndicadoService
import schemas.classes.estrelasLeiria.VotoService
import schemas.sentinela.SentinelaBillingService
import schemas.sentinela.SentinelaPushTokenService
import schemas.sentinela.SentinelaRecordingService
import schemas.sentinela.SentinelaShareService
import schemas.sentinela.SentinelaUserService
import services.SentinelaStripe
import services.SentinelaTranscriptionService
import schemas.users.ClientService
import routes.sentinela.sentinelaLiveRouting
import routes.sentinela.sentinelaBillingRouting
import routes.sentinela.sentinelaPublicVideoRouting
import routes.sentinela.sentinelaRecordingRouting
import routes.sentinela.sentinelaShareRouting
import routes.sentinela.sentinelaStreamRouting
import routes.sentinela.sentinelaStripeWebhookRouting
import routes.sentinela.sentinelaUserRouting
import kotlin.getValue
import org.koin.core.qualifier.named
import routes.estrelasLeiria.adminTicketRouting
import routes.estrelasLeiria.cortesiaRouting
import routes.estrelasLeiria.ebookWebhookRouting
import java.io.File
import java.util.Properties


/**
 * Carrega um arquivo .properties local (nunca versionado) e define suas chaves como
 * propriedades do sistema. Usado em desenvolvimento; em produção as mesmas chaves
 * chegam via variável de ambiente, sem precisar deste arquivo.
 */
fun loadLocalSecrets(fileName: String) {
    val propertiesFile = File(fileName)
    if (propertiesFile.exists()) {
        try {
            val properties = Properties()
            propertiesFile.inputStream().use { properties.load(it) }

            properties.forEach { (key, value) ->
                System.setProperty(key.toString(), value.toString())
            }

            println("[Config] Credenciais carregadas do arquivo $fileName")
        } catch (e: Exception) {
            println("[Config] Erro ao carregar $fileName: ${e.message}")
        }
    } else {
        println("[Config] Arquivo $fileName não encontrado, usando variáveis de ambiente ou valores padrão")
    }
}

fun Application.module() {
    loadLocalSecrets("aws-credentials.properties")
    loadLocalSecrets("gemini-credentials.properties")
    loadLocalSecrets("stripe-credentials.properties")
    (System.getProperty("stripe.apiKey") ?: System.getenv("STRIPE_API_KEY"))?.let {
        com.stripe.Stripe.apiKey = it
    }
    configureHTTP()
    configureSockets()
    configureContentNegotiation()
    configureDependencyInjection()
    configureRouting()
    configureRoutingEstrelasLeiria()
}

fun Application.configureContentNegotiation() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }
}

private fun Application.configureDependencyInjection() {
    install(Koin) {
        slf4jLogger()
        modules(
            classModule,
            clientModule,
            estrelasLeiria,
            resolvebr,
            inovaCloud,
            sentinela,
        )
    }
}

private fun Application.configureRouting() {
    val serviceAccess by inject<AccessService>()
    val classesListService by inject<ClassesListService>()
    val uploadListService by inject<UploadService>()
    val clientService: ClientService by inject<ClientService>()
    val flashcardService by inject<FlashcardService>()
    val alunoIaService by inject<AlunoIaService>()
    val geminiLiveBridge by inject<GeminiLiveBridge>()
    val sentinelaUserService by inject<SentinelaUserService>()
    val sentinelaRecordingService by inject<SentinelaRecordingService>()
    val sentinelaShareService by inject<SentinelaShareService>()
    val sentinelaPushTokenService by inject<SentinelaPushTokenService>()
    val sentinelaTranscriptionService by inject<SentinelaTranscriptionService>()
    val sentinelaBillingService by inject<SentinelaBillingService>()
    val sentinelaStripe by inject<SentinelaStripe>()

    clientRouting(clientService)
    sentinelaUserRouting(sentinelaUserService)
    sentinelaStreamRouting(sentinelaRecordingService, sentinelaShareService, sentinelaUserService, sentinelaPushTokenService, sentinelaTranscriptionService, sentinelaBillingService)
    sentinelaTranscriptionService.resumePending()
    sentinelaLiveRouting(sentinelaShareService, sentinelaUserService, sentinelaPushTokenService)
    sentinelaRecordingRouting(sentinelaRecordingService, sentinelaShareService, sentinelaUserService, sentinelaTranscriptionService)
    sentinelaPublicVideoRouting(sentinelaRecordingService)
    sentinelaBillingRouting(sentinelaBillingService, sentinelaStripe)
    sentinelaStripeWebhookRouting(sentinelaBillingService, sentinelaStripe)
    sentinelaShareRouting(sentinelaShareService, sentinelaUserService)
    accessRouting(serviceAccess)
    classesRouting(classesListService)
    uploadRouting(uploadListService)
    flashcardsRouting(flashcardService)
    alunoIaRouting(alunoIaService)
    meganRouting(alunoIaService, geminiLiveBridge)
    translateRouting()
    assinaturaWebhookRouting(alunoIaService)
    curriculumRouting()
}



private fun Application.configureRoutingEstrelasLeiria() {
    val categorias by inject<CategoriaService>()
    val indicados by inject<IndicadoService>()
    val votos by inject<VotoService>()

    val databaseEstrelas by inject<Database>(named("EstrelasLeiriaDB"))

    val emailService = EmailService()

    categoriaRouting(categorias)
    indicadoRouting(indicados)
    votoRouting(votos)
    stripeRouting(indicados)
    cortesiaRouting(database = databaseEstrelas)
    ebookWebhookRouting() // Register Stripe ebook webhook endpoint
    adminTicketRouting(
        indicadoService = indicados,
        database = databaseEstrelas,
        emailService = emailService
    )
}








