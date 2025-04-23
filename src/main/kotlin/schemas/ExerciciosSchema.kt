package com.class_erp.schemas

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class Exercicios(
    val nomeExercicio: String,
    val className: String,
    val codClass: String,
    val isDoc: String,
)

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class ExerciciosService(database: Database) {

    object Exercises : Table() {
        val id = integer("id").autoIncrement()
        val nomeExercicio = varchar("nomeExercicio", length = 50)
        val className = varchar("className",length = 50)
        val codClass = varchar("codClass",length = 50)
        val isDoc = varchar("isDoc",length = 10)

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(Exercises)
        }
    }

    suspend fun create(exe: Exercicios): Int = dbQuery {
        Exercises.insert {
            it[nomeExercicio] = exe.nomeExercicio
            it[className] = exe.className
            it[codClass] = exe.codClass
            it[isDoc] = exe.isDoc
        }[Exercises.id]
    }

    suspend fun read(id: Int): Exercicios? {
        return dbQuery {
            Exercises.selectAll()
                .where { Exercises.id eq id }
                .map { Exercicios(
                    it[Exercises.nomeExercicio],
                    it[Exercises.className],
                    it[Exercises.codClass],
                    it[Exercises.isDoc]
                ) }.singleOrNull()
        }
    }

    suspend fun update(id: Int, exe: Exercicios) {
        dbQuery {
            Exercises.update({ Exercises.id eq id }) {
                it[nomeExercicio] = exe.nomeExercicio
                it[className] = exe.className
                it[codClass] = exe.codClass
                it[isDoc] = exe.isDoc
            }
        }
    }

    suspend fun delete(id: Int) {
        dbQuery {
            Exercises.deleteWhere { Exercises.id.eq(id) }
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}

