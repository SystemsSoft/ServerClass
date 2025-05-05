package com.class_erp.schemas

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class Acessos(
    val className: String,
    val codClass: String,
    val nome: String,
    val senha: String,
    val email: String,
)

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class AcessosService(database: Database) {
    object Accesses : Table() {
        val id = integer("id").autoIncrement()
        val className = varchar("className", length = 50)
        val codClass = varchar("codClass",length = 50)
        val nome = varchar("nome",length = 50)
        val senha = varchar("senha",length = 50)
        val email = varchar("email",length = 50)

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(Accesses)
        }
    }

    suspend fun create(user: Acessos): Int {

        return dbQuery {
            Accesses.insert {
                it[className] = user.className
                it[codClass] = user.codClass
                it[nome] = user.nome
                it[senha] = user.senha
                it[email] = user.email
            }[Accesses.id]
        }
    }

    suspend fun read(id: Int): Acessos? {
        return dbQuery {
            Accesses.selectAll()
                .where { Accesses.id eq id }
                .map { Acessos(
                    it[Accesses.className],
                    it[Accesses.codClass],
                    it[Accesses.nome],
                    it[Accesses.senha],
                    it[Accesses.email]
                ) }.singleOrNull()
        }
    }

    suspend fun readAll(): List<Acessos> {
        return dbQuery {
            Accesses.selectAll().map {
                Acessos(
                    it[Accesses.className],
                    it[Accesses.codClass],
                    it[Accesses.nome],
                    it[Accesses.senha],
                    it[Accesses.email]
                )
            }
        }
    }

    suspend fun update(id: Int, user: Acessos) {
        dbQuery {
            Accesses.update({ Accesses.id eq id }) {
                it[className] = user.className
                it[codClass] = user.codClass
                it[nome] = user.nome
                it[senha] = user.senha
                it[email] = user.senha
            }
        }
    }

    suspend fun delete(id: Int) {
        dbQuery {
            Accesses.deleteWhere { Accesses.id.eq(id) }
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}

