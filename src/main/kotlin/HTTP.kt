package com.class_erp

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.hsts.*
import io.ktor.server.plugins.httpsredirect.*


fun Application.configureHTTP() {
   /* install(HttpsRedirect) {
       /*     sslPort = 443
            permanentRedirect = true*/
        }*/

    install(CORS) {
        // Métodos permitidos
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Get)

        // Headers permitidos
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader("MyCustomHeader")

        anyHost()

        allowCredentials = true
    }
}
