package com.class_erp


import schemas.classes.UploadService
import com.class_erp.DatabaseConfig.classModule
import com.class_erp.DatabaseConfig.clientModule
import com.class_erp.DatabaseConfig.estrelasLeiria
import com.class_erp.DatabaseConfig.resolvebr
import com.class_erp.DatabaseConfig.inovaCloud
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
import routes.alunoIa.meganRouting
import services.GeminiLiveBridge
import routes.estrelasLeiria.categoriaRouting
import routes.estrelasLeiria.indicadoRouting
import routes.estrelasLeiria.stripeRouting
import routes.estrelasLeiria.votoRouting
import schemas.alunoIa.AlunoIaService
import schemas.classes.ClassesListService
import schemas.classes.FlashcardService
import schemas.estrelasLeiria.CategoriaService
import schemas.estrelasLeiria.IndicadoService
import schemas.estrelasLeiria.VotoService
import schemas.users.ClientService
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

    clientRouting(clientService)
    accessRouting(serviceAccess)
    classesRouting(classesListService)
    uploadRouting(uploadListService)
    flashcardsRouting(flashcardService)
    alunoIaRouting(alunoIaService)
    meganRouting(alunoIaService, geminiLiveBridge)
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








