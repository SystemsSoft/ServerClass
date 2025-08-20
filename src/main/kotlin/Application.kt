package com.class_erp


import ClientMecService
import DatabaseConfig.clientModule
import DatabaseConfig.classModule
import DatabaseConfig.mecModule
import ExpenseService
import UploadService
import clientMecRouting
import com.class_erp.schemas.AccessService
import configureSockets
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
import routes.mec.serviceOrderRouting
import schemas.classes.ClassesListService
import schemas.users.ClientService

import schemas.mec.PriceTableMecService
import schemas.mec.RevenueService
import schemas.mec.ServiceOrderService
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
    configureSockets()
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
        modules(classModule, clientModule, mecModule)
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


    clientMecRouting(clientMec)
    expensesRouting(expenses)
    revenueRouting(revenue)
    pricingTableRouting(pricingTable)
    serviceOrderRouting(serviceOrder)
}







