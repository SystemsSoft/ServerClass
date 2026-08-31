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


fun main(args: Array<String>) {
    loadAwsCredentials()
    io.ktor.server.netty.EngineMain.main(args)
}

/**
 * Carrega as credenciais AWS do arquivo aws-credentials.properties e as define como propriedades do sistema.
 */
fun loadAwsCredentials() {
    val propertiesFile = File("aws-credentials.properties")
    if (propertiesFile.exists()) {
        try {
            val properties = Properties()
            propertiesFile.inputStream().use { properties.load(it) }

            properties.forEach { (key, value) ->
                System.setProperty(key.toString(), value.toString())
            }

            println("[AWS] Credenciais carregadas do arquivo aws-credentials.properties")
        } catch (e: Exception) {
            println("[AWS] Erro ao carregar credenciais: ${e.message}")
        }
    } else {
        println("[AWS] Arquivo aws-credentials.properties não encontrado, usando variáveis de ambiente ou valores padrão")
    }
}

fun Application.module() {
    configureHTTP()
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

    clientRouting(clientService)
    accessRouting(serviceAccess)
    classesRouting(classesListService)
    uploadRouting(uploadListService)
    flashcardsRouting(flashcardService)
    alunoIaRouting(alunoIaService)
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








