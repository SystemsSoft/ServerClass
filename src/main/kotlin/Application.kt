package com.class_erp


import ClientMecService
import ExpenseService
import UploadService
import clientMecRouting
import com.class_erp.DatabaseConfig.classModule
import com.class_erp.DatabaseConfig.clientModule
import com.class_erp.DatabaseConfig.estrelasLeiria
import com.class_erp.DatabaseConfig.imobiliaria
import com.class_erp.DatabaseConfig.mecModule
import com.class_erp.schemas.AccessService
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import routes.`class`.accessRouting
import routes.`class`.classesRouting
import routes.users.clientRouting
import routes.`class`.expensesRouting
import routes.`class`.pricingTableRouting
import routes.`class`.revenueRouting
import routes.`class`.uploadRouting
import routes.estrelasLeiria.InscritosTable
import routes.estrelasLeiria.categoriaRouting
import routes.estrelasLeiria.gerarQrCodeBytes
import routes.estrelasLeiria.indicadoRouting
import routes.estrelasLeiria.stripeRouting
import routes.estrelasLeiria.votoRouting
import routes.imobiliaria.proprietarioRouting
import routes.mec.serviceOrderRouting
import routes.mec.vehicleRouting
import schemas.classes.ClassesListService
import schemas.estrelasLeiria.BilheteManualDTO
import schemas.estrelasLeiria.CategoriaService
import schemas.estrelasLeiria.Indicado
import schemas.estrelasLeiria.IndicadoService
import schemas.estrelasLeiria.VotoService
import schemas.imobiliaria.ProprietarioService
import schemas.users.ClientService
import schemas.mec.PriceTableMecService
import schemas.mec.RevenueService
import schemas.mec.ServiceOrderService
import schemas.mec.VehicleService
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.getValue
import org.koin.core.qualifier.named // Importante para o named DB

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    configureHTTP()
    configureContentNegotiation()
    configureDependencyInjection()
    configureRouting()
    configureRoutingMec()
    configureRoutingEstrelasLeiria()
    configureRoutingImobiliaria()
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
            mecModule,
            estrelasLeiria,
            imobiliaria
        )
    }
}

private fun Application.configureRouting() {
    val serviceAccess by inject<AccessService>()
    val classesListService by inject<ClassesListService>()
    val uploadListService by inject<UploadService>()
    val clientService: ClientService by inject<ClientService>()

    clientRouting(clientService)
    accessRouting(serviceAccess)
    classesRouting(classesListService)
    uploadRouting(uploadListService)
}

private fun Application.configureRoutingMec() {
    val clientMec by inject<ClientMecService>()
    val expenses by inject<ExpenseService>()
    val revenue by inject<RevenueService>()
    val pricingTable by inject<PriceTableMecService>()
    val serviceOrder: ServiceOrderService by inject<ServiceOrderService>()
    val vehicleService by inject<VehicleService>()


    vehicleRouting(vehicleService)
    clientMecRouting(clientMec)
    expensesRouting(expenses)
    revenueRouting(revenue)
    pricingTableRouting(pricingTable)
    serviceOrderRouting(serviceOrder)
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

    routing {
        staticResources(remotePath = "/painel", basePackage = "static")


        post("/admin/criar-bilhete-manual") {
            try {
                val dados = call.receive<BilheteManualDTO>()
                val codigoBilhete = "MANUAL_" + UUID.randomUUID().toString().substring(0, 8).uppercase()



                if (dados.desejaParticiparVotacao) {


                    val novoIndicado = Indicado(
                        categoriaId = dados.categoriaId,
                        nome = dados.nome,
                        instagram = dados.instagram,
                        imageData = dados.fotoUrl,
                        descricaoDetalhada = dados.descricao,
                        desejaParticiparVotacao = true,
                        stripeId = codigoBilhete,
                        email = dados.email,
                        quantidade = dados.quantidade
                    )

                    indicados.create(novoIndicado, UUID.randomUUID().toString())

                } else {
                    newSuspendedTransaction(Dispatchers.IO, db = databaseEstrelas) {
                        InscritosTable.insert {
                            it[id] = UUID.randomUUID().toString()
                            it[nome] = dados.nome
                            it[instagram] = dados.instagram
                            it[categoriaId] = dados.categoriaId
                            it[descricao] = dados.descricao
                            it[imageData] = dados.fotoUrl
                            it[desejaParticiparVotacao] = false
                            it[stripeId] = codigoBilhete
                            it[email] = dados.email
                            it[quantidade] = dados.quantidade
                            it[checkIn] = false
                        }
                    }
                }

                thread {
                    try {
                        val qrBytes = gerarQrCodeBytes(codigoBilhete)
                        emailService.enviarBilhete(
                            destinatario = dados.email,
                            nomeParticipante = dados.nome,
                            qrCodeBytes = qrBytes,
                            quantidade = dados.quantidade
                        )
                        println(">>> E-mail manual enviado para ${dados.email}")
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                call.response.status(HttpStatusCode.Created)
                call.respond(mapOf(
                    "status" to "SUCESSO",
                    "codigo" to codigoBilhete
                ))

            } catch (e: Exception) {
                e.printStackTrace()
                call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Erro: ${e.message}"))
            }
        }
    }
}

private fun Application.configureRoutingImobiliaria() {
    val propietario by inject<ProprietarioService>()

    proprietarioRouting(propietario)
}







