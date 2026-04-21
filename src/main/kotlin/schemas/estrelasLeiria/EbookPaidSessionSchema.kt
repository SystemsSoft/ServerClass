package schemas.estrelasLeiria

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class EbookPaidSessionService(private val database: Database) {

    object EbookPaidSessionTable : Table("ebook_paid_sessions") {
        val sessionId = varchar("session_id", length = 255)
        override val primaryKey = PrimaryKey(sessionId)
    }

    init {
        transaction(database) {
            SchemaUtils.create(EbookPaidSessionTable)
            SchemaUtils.createMissingTablesAndColumns(EbookPaidSessionTable)
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }

    suspend fun register(sessionId: String) = dbQuery {
        EbookPaidSessionTable.insertIgnore {
            it[EbookPaidSessionTable.sessionId] = sessionId
        }
    }

    suspend fun isPaid(sessionId: String): Boolean = dbQuery {
        EbookPaidSessionTable
            .selectAll().where { EbookPaidSessionTable.sessionId eq sessionId }
            .count() > 0
    }
}

