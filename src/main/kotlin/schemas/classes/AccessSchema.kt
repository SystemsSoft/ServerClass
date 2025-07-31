package com.class_erp.schemas

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class Access(
    val className: String,
    val classCode: String,
    val name: String,
    val password: String,
    val email: String,
)

@Serializable
data class AccessDto(
    var id: Int,
    val className: String,
    val classCode: String,
    val name: String,
    val password: String,
    val email: String,
)

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class AccessService(private val database: Database) {
    object AccessTable : Table() {
        val id = integer("id").autoIncrement()
        val className = varchar("className", length = 50)
        val classCode = varchar("classCode",length = 50)
        val name = varchar("name",length = 50)
        val password = varchar("password",length = 50)
        val email = varchar("email",length = 50)

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(AccessTable)
        }
    }

    suspend fun create(access: Access): Int {
        return dbQuery {
            AccessTable.insert {
                it[className] = access.className
                it[classCode] = access.classCode
                it[name] = access.name
                it[password] = access.password
                it[email] = access.email
            }[AccessTable.id]
        }
    }

    suspend fun readAll(): List<AccessDto> {
        return dbQuery {
            AccessTable.selectAll().map {
                AccessDto(
                    it[AccessTable.id],
                    it[AccessTable.className],
                    it[AccessTable.classCode],
                    it[AccessTable.name],
                    it[AccessTable.password],
                    it[AccessTable.email]
                )
            }
        }
    }

    suspend fun update(id: Int, access: AccessDto) {
        dbQuery {
            AccessTable.update({ AccessTable.id eq id }) {
                it[className] = access.className
                it[classCode] = access.classCode
                it[name] = access.name
                it[password] = access.password
                it[email] = access.email
            }
        }
    }

    suspend fun delete(id: Int) {
        dbQuery {
            AccessTable.deleteWhere { AccessTable.id.eq(id) }
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }
}