package schemas.sentinela

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

/**
 * Payload enviado pelo app (uid vem do Firebase Authentication; o backend nunca
 * recebe nem armazena senha — a credencial fica inteiramente a cargo do Firebase).
 */
@Serializable
data class SentinelaUser(
    val uid: String,
    val name: String,
    val email: String,
    val photoUrl: String? = null
)

@Serializable
data class SentinelaUserDto(
    val uid: String,
    val name: String,
    val email: String,
    val photoUrl: String?,
    val createdAt: String,
    val updatedAt: String
)

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class SentinelaUserService(private val database: Database) {

    object SentinelaUserTable : Table("sentinela_users") {
        val uid = varchar("uid", length = 128)
        val name = varchar("name", length = 150)
        val email = varchar("email", length = 200)
        val photoUrl = varchar("photo_url", length = 500).nullable()
        val createdAt = varchar("created_at", length = 30)
        val updatedAt = varchar("updated_at", length = 30)

        override val primaryKey = PrimaryKey(uid)
    }

    init {
        transaction(database) {
            SchemaUtils.create(SentinelaUserTable)
            SchemaUtils.createMissingTablesAndColumns(SentinelaUserTable)
        }
    }

    /** Cria o usuário se o uid ainda não existir, ou atualiza nome/e-mail/foto se já existir. */
    suspend fun upsert(user: SentinelaUser): SentinelaUserDto {
        return dbQuery {
            val now = Instant.now().toString()
            val existing = SentinelaUserTable
                .selectAll()
                .where { SentinelaUserTable.uid eq user.uid }
                .singleOrNull()

            if (existing == null) {
                SentinelaUserTable.insert {
                    it[uid] = user.uid
                    it[name] = user.name
                    it[email] = user.email
                    it[photoUrl] = user.photoUrl
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            } else {
                SentinelaUserTable.update(where = { SentinelaUserTable.uid eq user.uid }) {
                    it[name] = user.name
                    it[email] = user.email
                    it[photoUrl] = user.photoUrl
                    it[updatedAt] = now
                }
            }

            toDto(
                SentinelaUserTable
                    .selectAll()
                    .where { SentinelaUserTable.uid eq user.uid }
                    .single()
            )
        }
    }

    suspend fun findByUid(uid: String): SentinelaUserDto? {
        return dbQuery {
            SentinelaUserTable
                .selectAll()
                .where { SentinelaUserTable.uid eq uid }
                .singleOrNull()
                ?.let { toDto(it) }
        }
    }

    suspend fun findByEmail(email: String): SentinelaUserDto? {
        val normalized = email.trim().lowercase()
        return dbQuery {
            SentinelaUserTable
                .selectAll()
                .where { SentinelaUserTable.email.lowerCase() eq normalized }
                .limit(1)
                .singleOrNull()
                ?.let { toDto(it) }
        }
    }

    suspend fun readAll(): List<SentinelaUserDto> {
        return dbQuery {
            SentinelaUserTable.selectAll().map { toDto(it) }
        }
    }

    suspend fun delete(uid: String) {
        dbQuery {
            SentinelaUserTable.deleteWhere { SentinelaUserTable.uid eq uid }
        }
    }

    private fun toDto(row: ResultRow) = SentinelaUserDto(
        uid = row[SentinelaUserTable.uid],
        name = row[SentinelaUserTable.name],
        email = row[SentinelaUserTable.email],
        photoUrl = row[SentinelaUserTable.photoUrl],
        createdAt = row[SentinelaUserTable.createdAt],
        updatedAt = row[SentinelaUserTable.updatedAt]
    )

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }
}
