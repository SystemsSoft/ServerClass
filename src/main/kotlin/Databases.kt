package com.class_erp

import com.class_erp.schemas.Acessos
import com.class_erp.schemas.AcessosService
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*


fun Application.configureDatabases() {
    install(ContentNegotiation) {
        json()  
    }

    val database = Database.connect(
        url = "jdbc:mysql://ls-4c09769be49b9f8b7ca900b4ecadba80d77c8a07.cq7sywsga5zr.us-east-1.rds.amazonaws.com:3306/effective_english_course",
        user = "dbmasteruser",
        driver = "com.mysql.cj.jdbc.Driver",
        password = "q1w2e3r4",
    )

    environment.log.info("✅ Conexão com o banco de dados 'effective_english_course' estabelecida com sucesso.")

    val userService = AcessosService(database)

    routing {
        // Create user
        post("/acessos/add") {
            val user = call.receive<Acessos>()
            val id = userService.create(user)
            call.respond(HttpStatusCode.Created, id)
        }
        
        // Read user
        get("/users/{id}") {

        }
        
        // Update user
        put("/users/{id}") {

        }
        
        // Delete user
        delete("/users/{id}") {

        }
    }
}
