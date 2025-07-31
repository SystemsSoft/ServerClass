package schemas.users

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi

@Serializable
data class Client(
    val license: Boolean,
    val idLicense: String? = null,
    val name: String,
    val password: String,
)

@Serializable
data class User(
    var name: String,
    var password: String
)

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class ClientService(private val db: Database) {
    object ClientTable : Table() {
        val id = integer("id").autoIncrement()
        val license = bool("license").default(false)
        val idLicense = varchar("idLicense", length = 50)
        val name = varchar("name", length = 50)
        val password = varchar("password", length = 50)
        override val primaryKey = PrimaryKey(id)

    }

    init {
        transaction(db) {
            SchemaUtils.create(ClientTable)
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun create(client: Client): Int {
        return dbQuery {
            ClientTable.insert {
                it[license] = client.license
                it[idLicense] = UUID.randomUUID().toString()
                it[name] = client.name
                it[password] = client.password
            }[ClientTable.id]
        }
    }

    suspend fun readAll(): List<Client> {
        return dbQuery {
            ClientTable.selectAll().map {
                Client(
                    it[ClientTable.license],
                    it[ClientTable.idLicense],
                    it[ClientTable.name],
                    it[ClientTable.password]
                )
            }
        }
    }
    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, db = db) { block() }
}