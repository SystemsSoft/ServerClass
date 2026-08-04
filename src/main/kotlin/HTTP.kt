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
        allowHeader("Stripe-Signature")

        // Origens permitidas
        allowHost("athennaclass.netlify.app")
        allowHost("athennaclass.netlify.app", schemes = listOf("https", "http"))
        allowHost("localhost:3000")
        allowHost("localhost:8080")
        allowHost("127.0.0.1:3000")
        allowHost("127.0.0.1:8080")

        // Para desenvolvimento, descomente a linha abaixo (não use em produção)
         anyHost()

        allowCredentials = true
    }
}
