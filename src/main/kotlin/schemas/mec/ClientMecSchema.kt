package schemas.mec

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq // Essential import for 'eq'
import org.jetbrains.exposed.sql.deleteWhere // For the delete function


@Serializable
data class ClientMec(
    val name: String,
    val phone: String,
)

@Serializable
data class ClientMecDto(
    var id: Int,
    var name: String,
    var phone: String
)

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class ClientMecService(private val db: Database) {
    object ClientMecTable : Table() {
        val id = integer("id").autoIncrement()
        val name = varchar("name", length = 50)
        val phone = varchar("phone", length = 50)
        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(db) {
            SchemaUtils.create(ClientMecTable)
        }
    }

    suspend fun create(client: ClientMec): Int {
        return dbQuery {
            ClientMecTable.insert {
                it[name] = client.name
                it[phone] = client.phone
            }[ClientMecTable.id]
        }
    }

    suspend fun readAll(): List<ClientMecDto> {
        return dbQuery {
            ClientMecTable.selectAll().map {
                ClientMecDto(
                    it[ClientMecTable.id],
                    it[ClientMecTable.name],
                    it[ClientMecTable.phone]
                )
            }
        }
    }

    suspend fun update(id: Int, client: ClientMecDto) {
        dbQuery {
            ClientMecTable.update({ ClientMecTable.id eq id }) {
                it[name] = client.name
                it[phone] = client.phone
            }
        }
    }

    suspend fun delete(id: Int) { // Added delete function
        dbQuery {
            ClientMecTable.deleteWhere { ClientMecTable.id.eq(id) }
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, db = db) { block() }
}