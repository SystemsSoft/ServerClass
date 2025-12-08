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
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.json.Json
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
import routes.estrelasLeiria.categoriaRouting
import routes.estrelasLeiria.indicadoRouting
import routes.estrelasLeiria.stripeRouting
import routes.estrelasLeiria.votoRouting
import routes.imobiliaria.proprietarioRouting
import routes.mec.serviceOrderRouting
import routes.mec.vehicleRouting
import schemas.classes.ClassesListService
import schemas.estrelasLeiria.CategoriaService
import schemas.estrelasLeiria.IndicadoService
import schemas.estrelasLeiria.VotoService
import schemas.imobiliaria.ProprietarioService
import schemas.users.ClientService
import schemas.mec.PriceTableMecService
import schemas.mec.RevenueService
import schemas.mec.ServiceOrderService
import schemas.mec.VehicleService
import kotlin.getValue

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

    categoriaRouting(categorias)
    indicadoRouting(indicados)
    votoRouting(votos)
    stripeRouting(indicados)
}

private fun Application.configureRoutingImobiliaria() {
    val propietario by inject<ProprietarioService>()

    proprietarioRouting(propietario)
}







