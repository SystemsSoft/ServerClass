package com.class_erp.schemas

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class Imagens(
    val idImagem: String,
)

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class ImagensService(database: Database) {

    object Images : Table() {
        val id = integer("id").autoIncrement()
        val idImagem = varchar("idImagem", length = 50)

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(Images)
        }
    }

    suspend fun create(img: Imagens): Int = dbQuery {
        Images.insert {
            it[idImagem] = img.idImagem
        }[Images.id]
    }

    suspend fun read(id: Int): Imagens? {
        return dbQuery {
            Images.selectAll()
                .where { Images.id eq id }
                .map { Imagens(
                    it[Images.idImagem],
                ) }.singleOrNull()
        }
    }

    suspend fun update(id: Int, exe: Imagens) {
        dbQuery {
            Images.update({ Images.id eq id }) {
                it[idImagem] = exe.idImagem
            }
        }
    }

    suspend fun delete(id: Int) {
        dbQuery {
            Images.deleteWhere { Images.id.eq(id) }
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}

