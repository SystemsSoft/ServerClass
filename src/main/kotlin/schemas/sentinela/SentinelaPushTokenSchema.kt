package schemas.sentinela

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

/**
 * Tokens do Firebase Cloud Messaging dos dispositivos de cada usuário. Um usuário pode ter
 * vários (celular, tablet); um token pertence a um único usuário por vez, então ao logar
 * outra conta no mesmo aparelho o token muda de dono.
 */
@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class SentinelaPushTokenService(private val database: Database) {

    object SentinelaPushTokenTable : Table("sentinela_push_tokens") {
        val token = varchar("token", length = 400)
        val uid = varchar("uid", length = 128).index()
        val updatedAt = varchar("updated_at", length = 30)

        override val primaryKey = PrimaryKey(token)
    }

    init {
        transaction(database) {
            SchemaUtils.create(SentinelaPushTokenTable)
            SchemaUtils.createMissingTablesAndColumns(SentinelaPushTokenTable)
        }
    }

    suspend fun register(uid: String, token: String) {
        dbQuery {
            val now = Instant.now().toString()
            val exists = SentinelaPushTokenTable
                .selectAll()
                .where { SentinelaPushTokenTable.token eq token }
                .any()

            if (exists) {
                SentinelaPushTokenTable.update(where = { SentinelaPushTokenTable.token eq token }) {
                    it[this.uid] = uid
                    it[updatedAt] = now
                }
            } else {
                SentinelaPushTokenTable.insert {
                    it[this.token] = token
                    it[this.uid] = uid
                    it[updatedAt] = now
                }
            }
        }
    }

    /** Remove o token só se ele ainda pertencer a [uid] (evita apagar o de outra conta no aparelho). */
    suspend fun unregister(uid: String, token: String) {
        dbQuery {
            SentinelaPushTokenTable.deleteWhere {
                (SentinelaPushTokenTable.token eq token) and (SentinelaPushTokenTable.uid eq uid)
            }
        }
    }

    suspend fun tokensOf(uid: String): List<String> = dbQuery {
        SentinelaPushTokenTable
            .selectAll()
            .where { SentinelaPushTokenTable.uid eq uid }
            .map { it[SentinelaPushTokenTable.token] }
    }

    suspend fun remove(token: String) {
        dbQuery {
            SentinelaPushTokenTable.deleteWhere { SentinelaPushTokenTable.token eq token }
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }
}
