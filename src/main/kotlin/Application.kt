package com.class_erp


import DatabaseConfig.appClient
import DatabaseConfig.appMain
import com.class_erp.schemas.AccessService
import com.class_erp.service.JanusService
import configureSockets
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation // ESTE É O ContentNegotiation DO SERVIDOR
import org.koin.ktor.ext.inject
import io.ktor.client.*
import io.ktor.client.engine.cio.* // ou outro engine que você esteja usando

// --- ADICIONE ESTE IMPORT PARA O PLUGIN DE CLIENTE COM ALIAS ---
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as KtorClientContentNegotiation
// --- FIM DO NOVO IMPORT ---

import kotlinx.serialization.json.Json
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import routes.accessRouting
import routes.classesRouting
import routes.clientRouting
import routes.uploadRouting
import schemas.ClassesListService
import schemas.ClientService
import schemas.UploadService
import kotlin.getValue

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    configureHTTP()
    configureContentNegotiation()
    configureDependencyInjection()
    configureRouting()
    configureSockets()
}

private fun Application.configureContentNegotiation() {
    install(ContentNegotiation) {
        json()
    }
}

private fun Application.configureDependencyInjection() {
    install(Koin) {
        slf4jLogger()
        modules(appMain, appClient)
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


