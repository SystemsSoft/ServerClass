package com.class_erp.schemas

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class AulasSchema(
    val videoAulaName: String,
    val className: String,
    val codClass: String,
)

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class AulasService(database: Database) {
    object Classes : Table() {
        val id = integer("id").autoIncrement()

        val videoAulaName = varchar("videoAulaName", length = 50)
        val className = varchar("className",length = 50)
        val codClass = varchar("codClass",length = 50)

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(Classes)
        }
    }

    suspend fun create(aulas: AulasSchema): Int = dbQuery {
        Classes.insert {
            it[videoAulaName] = aulas.videoAulaName
            it[className] = aulas.className
            it[codClass] = aulas.codClass

        }[Classes.id]
    }

    suspend fun read(id: Int): AulasSchema? {
        return dbQuery {
            Classes.selectAll()
                .where { Classes.id eq id }
                .map { AulasSchema(
                    it[Classes.videoAulaName],
                    it[Classes.className],
                    it[Classes.codClass],
                ) }.singleOrNull()
        }
    }

    suspend fun update(id: Int, aulas: AulasSchema) {
        dbQuery {
            Classes.update({ Classes.id eq id }) {
                it[videoAulaName] = aulas.videoAulaName
                it[className] = aulas.className
                it[codClass] = aulas.codClass
            }
        }
    }

    suspend fun delete(id: Int) {
        dbQuery {
            Classes.deleteWhere { Classes.id.eq(id) }
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}

