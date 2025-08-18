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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.and

@Serializable
data class ClientMec(
    val name: String,
    val phone: String,
    val userId: String,
    val veiculo: String,
    val ano: String,
    val marca: String,
    val placa: String
)

@Serializable
data class ClientMecDto(
    var id: Int,
    var name: String,
    var phone: String,
    val userId: String,
    var veiculo: String,
    var ano: String,
    var marca: String,
    var placa: String
)

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class ClientMecService(private val db: Database) {
    object ClientMecTable : Table() {
        val id = integer("id").autoIncrement()
        val name = varchar("name", length = 50)
        val phone = varchar("phone", length = 50)
        val userId = varchar("userId",length = 50)
        val veiculo = varchar("veiculo", length = 50).nullable()
        val ano = varchar("ano", length = 10).nullable()
        val marca = varchar("marca", length = 50).nullable()
        val placa = varchar("placa", length = 10).nullable()

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
                it[userId] = client.userId
                it[veiculo] = client.veiculo
                it[ano] = client.ano
                it[marca] = client.marca
                it[placa] = client.placa
            }[ClientMecTable.id]
        }
    }

    suspend fun readAll(userId: String): List<ClientMecDto> {
        return dbQuery {
            ClientMecTable.selectAll().where { ClientMecTable.userId eq  userId }.map {
                ClientMecDto(
                    it[ClientMecTable.id],
                    it[ClientMecTable.name],
                    it[ClientMecTable.phone],
                    it[ClientMecTable.userId],
                    it[ClientMecTable.veiculo] ?: "",
                    it[ClientMecTable.ano] ?: "",
                    it[ClientMecTable.marca] ?: "",
                    it[ClientMecTable.placa] ?: ""
                )
            }
        }
    }

    suspend fun update(id: Int, client: ClientMecDto) {
        dbQuery {
            ClientMecTable.update({ (ClientMecTable.id eq id) and (ClientMecTable.userId eq client.userId) }) {
                it[name] = client.name
                it[phone] = client.phone
                it[veiculo] = client.veiculo
                it[ano] = client.ano
                it[marca] = client.marca
                it[placa] = client.placa
            }
        }
    }

    suspend fun delete(id: Int, userId: String) {
        dbQuery {
            ClientMecTable.deleteWhere { (ClientMecTable.id eq id) and (ClientMecTable.userId eq userId) }
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, db = db) { block() }
}