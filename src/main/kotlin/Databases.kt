package com.class_erp

import UploadService
import com.class_erp.schemas.AccessService
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.koin.core.qualifier.named
import org.koin.dsl.module
import schemas.classes.ClassesListService
import schemas.estrelasLeiria.CategoriaService
import schemas.estrelasLeiria.EbookPaidSessionService
import schemas.estrelasLeiria.IndicadoService
import schemas.estrelasLeiria.VotoService
import schemas.resolvebr.CadastroService
import schemas.users.ClientService


object DatabaseConfig {

    private fun conectarBanco(dbName: String, maxConexoes: Int): Database {
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:mysql://ls-4c09769be49b9f8b7ca900b4ecadba80d77c8a07.cq7sywsga5zr.us-east-1.rds.amazonaws.com:3306/$dbName"
            username = "dbmasteruser"
            password = "q1w2e3r4"
            driverClassName = "com.mysql.cj.jdbc.Driver"

            // CONFIGURAÇÕES DE LIMITE
            maximumPoolSize = maxConexoes
            minimumIdle = 0
            idleTimeout = 300000
            connectionTimeout = 10000
            maxLifetime = 1800000

            // Permite BLOBs grandes (500 MB) — resolve "Packet too large" do MySQL
            addDataSourceProperty("maxAllowedPacket", "524288000")

            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        }

        val dataSource = HikariDataSource(config)
        return Database.connect(dataSource)
    }


    val classModule = module {
        single(named("MainDB")) {
            // Limite: 2 conexões
            conectarBanco("effective_english_course", maxConexoes = 2)
        }

        single { AccessService(get(named("MainDB"))) }
        single { ClassesListService(get(named("MainDB"))) }
        single { UploadService(get(named("MainDB"))) }
    }

    val clientModule = module {
        single(named("ClientDB")) {
            conectarBanco("Users", maxConexoes = 1)
        }

        single { ClientService(get(named("ClientDB"))) }
    }


    val estrelasLeiria = module {
        single(named("EstrelasLeiriaDB")) {
            conectarBanco("estrelas", maxConexoes = 15)
        }

        single { CategoriaService(get(named("EstrelasLeiriaDB"))) }
        single { IndicadoService(get(named("EstrelasLeiriaDB"))) }
        single { VotoService(get(named("EstrelasLeiriaDB"))) }
        single { EbookPaidSessionService(get(named("EstrelasLeiriaDB"))) }
    }

    val resolvebr = module {
        single(named("ResolveBrDB")) {
            conectarBanco("resolvebr", maxConexoes = 5)
        }

        single { CadastroService(get(named("ResolveBrDB"))) }
    }
}