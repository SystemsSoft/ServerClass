package com.class_erp

import DatabaseConfig.appModule
import com.class_erp.schemas.AcessosService
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import routes.acessosRouting
import routes.classesRouting
import schemas.ClassesListService
import kotlin.getValue

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    configureContentNegotiation()
    configureDependencyInjection()
    configureRouting()
    configureSerialization()
    configureHTTP()

}

private fun Application.configureContentNegotiation() {
    install(ContentNegotiation) {
        json()
    }
}

private fun Application.configureDependencyInjection() {
    install(Koin) {
        slf4jLogger()
        modules(appModule)
    }
}

private fun Application.configureRouting() {
    val serviceAcesso by inject<AcessosService>()
    val classesListService by inject<ClassesListService>()
    acessosRouting(serviceAcesso)
    classesRouting(classesListService)
}


