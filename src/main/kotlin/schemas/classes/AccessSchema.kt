package com.class_erp.schemas

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.SecureRandom

object UlidGenerator {
    private const val CROCKFORD_BASE32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()

    fun nextUlid(timestamp: Long = System.currentTimeMillis()): String {
        val chars = CharArray(26)
        var time = timestamp
        for (i in 9 downTo 0) {
            chars[i] = CROCKFORD_BASE32[(time and 0x1F).toInt()]
            time = time ushr 5
        }
        val randBytes = ByteArray(10)
        random.nextBytes(randBytes)

        var buffer = 0L
        var bits = 0
        var charIdx = 10
        for (b in randBytes) {
            buffer = (buffer shl 8) or (b.toLong() and 0xFFL)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                val idx = ((buffer shr bits) and 0x1FL).toInt()
                chars[charIdx++] = CROCKFORD_BASE32[idx]
            }
        }
        if (bits > 0 && charIdx < 26) {
            val idx = ((buffer shl (5 - bits)) and 0x1FL).toInt()
            chars[charIdx] = CROCKFORD_BASE32[idx]
        }
        return String(chars)
    }
}

@Serializable
data class Access(
    val className: String,
    val classCode: String,
    val name: String,
    val password: String,
    val email: String,
    val ulid: String? = null,
)

@Serializable
data class AccessDto(
    var id: Int,
    val className: String,
    val classCode: String,
    val name: String,
    val password: String,
    val email: String,
    val ulid: String? = null,
)

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class AccessService(private val database: Database) {
    object AccessTable : Table() {
        val id = integer("id").autoIncrement()
        val className = varchar("className", length = 50)
        val classCode = varchar("classCode", length = 50)
        val name = varchar("name", length = 50)
        val password = varchar("password", length = 50)
        val email = varchar("email", length = 50)
        val ulid = varchar("ulid", length = 36).default("")

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(AccessTable)
            SchemaUtils.createMissingTablesAndColumns(AccessTable)

            // Preenche o ULID para todos os itens já existentes na tabela que ainda não possuem valor
            val existingIdsWithoutUlid = AccessTable.selectAll().where {
                (AccessTable.ulid eq "") or (AccessTable.ulid.isNull())
            }.map { it[AccessTable.id] }

            for (recordId in existingIdsWithoutUlid) {
                val newUlid = UlidGenerator.nextUlid()
                AccessTable.update({ AccessTable.id eq recordId }) {
                    it[ulid] = newUlid
                }
            }
        }
    }

    suspend fun create(access: Access): Int {
        val assignedUlid = if (!access.ulid.isNullOrBlank()) access.ulid else UlidGenerator.nextUlid()
        return dbQuery {
            AccessTable.insert {
                it[className] = access.className
                it[classCode] = access.classCode
                it[name] = access.name
                it[password] = access.password
                it[email] = access.email
                it[ulid] = assignedUlid
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
                    it[AccessTable.email],
                    it[AccessTable.ulid]
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
                if (!access.ulid.isNullOrBlank()) {
                    it[ulid] = access.ulid
                }
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
