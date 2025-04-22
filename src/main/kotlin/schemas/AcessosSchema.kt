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
)

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class AcessosService(database: Database) {
    object AccessesEntity : Table() {
        val id = integer("id").autoIncrement()
        val className = varchar("className", length = 50)
        val codClass = varchar("codClass",length = 50)
        val nome = varchar("nome",length = 50)
        val senha = varchar("senha",length = 50)

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(AccessesEntity)
        }
    }

    suspend fun create(user: Acessos): Int = dbQuery {
        AccessesEntity.insert {
            it[className] = user.className
            it[codClass] = user.codClass
            it[nome] = user.nome
            it[senha] = user.senha
        }[AccessesEntity.id]
    }

    suspend fun read(id: Int): Acessos? {
        return dbQuery {
            AccessesEntity.selectAll()
                .where { AccessesEntity.id eq id }
                .map { Acessos(
                    it[AccessesEntity.className],
                    it[AccessesEntity.codClass],
                    it[AccessesEntity.nome],
                    it[AccessesEntity.senha]
                ) }.singleOrNull()
        }
    }

    suspend fun update(id: Int, user: Acessos) {
        dbQuery {
            AccessesEntity.update({ AccessesEntity.id eq id }) {
                it[className] = user.className
                it[codClass] = user.codClass
                it[nome] = user.nome
                it[senha] = user.senha
            }
        }
    }

    suspend fun delete(id: Int) {
        dbQuery {
            AccessesEntity.deleteWhere { AccessesEntity.id.eq(id) }
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}

