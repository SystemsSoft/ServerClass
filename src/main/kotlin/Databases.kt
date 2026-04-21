package com.class_erp

import schemas.classes.UploadService
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

    private val host = "ls-83cb12ed62490b7f04b5b693968286e4df55d2a4.codoai20o7g2.us-east-1.rds.amazonaws.com"
    private val dbUser = "dbmasteruser"
    private val dbPassword = "dbmasteruser"

    private fun criarBancoSeNaoExistir(dbName: String) {
        val url = "jdbc:mysql://$host:3306/?maxAllowedPacket=629145600"
        val conn = java.sql.DriverManager.getConnection(url, dbUser, dbPassword)
        conn.use { c ->
            c.createStatement().use { stmt ->
                stmt.executeUpdate("CREATE DATABASE IF NOT EXISTS `$dbName` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
                println("[DB] Banco '$dbName' verificado/criado com sucesso.")
            }
        }
    }

    private fun conectarBanco(dbName: String, maxConexoes: Int): Database {
        criarBancoSeNaoExistir(dbName)

        val config = HikariConfig().apply {
            // maxAllowedPacket=629145600 (~600 MB) na URL para garantir que o driver negocie corretamente
            jdbcUrl = "jdbc:mysql://$host:3306/$dbName?maxAllowedPacket=629145600"
            username = dbUser
            password = dbPassword
            driverClassName = "com.mysql.cj.jdbc.Driver"

            // CONFIGURAÇÕES DE LIMITE
            maximumPoolSize = maxConexoes
            minimumIdle = 0
            idleTimeout = 300000
            connectionTimeout = 10000
            maxLifetime = 1800000

            // Permite BLOBs grandes (600 MB) — resolve "Packet too large" do MySQL
            addDataSourceProperty("maxAllowedPacket", "629145600")

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
        single { ClientService(get(named("MainDB"))) }
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