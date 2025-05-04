package com.class_erp

import DatabaseConfig.appModule
import com.class_erp.schemas.AcessosService
import io.ktor.server.application.*
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
    install(Koin) {
        slf4jLogger()
        modules(appModule)
    }
    val serviceAcesso by inject<AcessosService>()
    val classesListService by inject<ClassesListService>()

    configureSerialization()
    configureHTTP()
    acessosRouting(serviceAcesso)
    classesRouting(classesListService)
}
