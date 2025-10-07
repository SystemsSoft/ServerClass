package schemas.mec

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
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

import org.jetbrains.exposed.sql.ResultRow


@Serializable
data class ServiceOrder(
    val clientId: Int,
    val userId: String,
    val clientName: String,
    val idVeiculo: Int, // <-- Adicionado
    val veiculo: String,
    val description: String,
    val partsUsed: String,
    val partsValue: Double,
    val laborValue: Double,
    val totalValue: Double,
    val deliveryDate: String,
    val km: String,
    val status: String
)

@Serializable
data class ServiceOrderDto(
    var id: Int,
    val clientId: Int,
    val userId: String,
    val clientName: String,
    val idVeiculo: Int,
    val veiculo: String,
    val description: String,
    val partsUsed: String,
    val partsValue: Double,
    val laborValue: Double,
    val totalValue: Double,
    val deliveryDate: String,
    val km: String,
    val status: String
)

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class ServiceOrderService(private val db: Database) {
    object ServiceOrderTable : Table("ServiceOrder") {
        val id = integer("id").autoIncrement()
        val clientId = integer("clientId")

        val userId = varchar("userId", length = 50)

        val clientName = varchar("clientName", length = 150)
        val idVeiculo = integer("idVeiculo")
        val veiculo = varchar("veiculo", length = 150)
        val description = text("description")
        val partsUsed = text("partsUsed")
        val partsValue = double("partsValue")
        val laborValue = double("laborValue")
        val totalValue = double("totalValue")
        val deliveryDate = varchar("deliveryDate", length = 50)
        val km = varchar("km", length = 50)
        val status = varchar("status", length = 50)

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(db) {
            SchemaUtils.create(ServiceOrderTable)
        }
    }

    suspend fun create(serviceOrder: ServiceOrder): Int {
        return dbQuery {
            ServiceOrderTable.insert {
                it[clientId] = serviceOrder.clientId
                it[clientName] = serviceOrder.clientName
                it[userId] = serviceOrder.userId
                it[idVeiculo] = serviceOrder.idVeiculo
                it[veiculo] = serviceOrder.veiculo
                it[description] = serviceOrder.description
                it[partsUsed] = serviceOrder.partsUsed
                it[partsValue] = serviceOrder.partsValue
                it[laborValue] = serviceOrder.laborValue
                it[totalValue] = serviceOrder.totalValue
                it[deliveryDate] = serviceOrder.deliveryDate
                it[km] = serviceOrder.km
                it[status] = serviceOrder.status
            }[ServiceOrderTable.id]
        }
    }

    suspend fun readAll(userId: String): List<ServiceOrderDto> {
        return dbQuery {
            ServiceOrderTable.selectAll().where { ServiceOrderTable.userId eq userId }.map {
                toServiceOrderDto(it)
            }
        }
    }

    suspend fun update(id: Int, serviceOrder: ServiceOrderDto) {
        dbQuery {
            ServiceOrderTable.update({ (ServiceOrderTable.id eq id) and (ServiceOrderTable.userId eq serviceOrder.userId) }) {
                it[description] = serviceOrder.description
                it[clientName] = serviceOrder.clientName
                it[idVeiculo] = serviceOrder.idVeiculo
                it[veiculo] = serviceOrder.veiculo
                it[partsUsed] = serviceOrder.partsUsed
                it[partsValue] = serviceOrder.partsValue
                it[laborValue] = serviceOrder.laborValue
                it[totalValue] = serviceOrder.totalValue
                it[deliveryDate] = serviceOrder.deliveryDate
                it[km] = serviceOrder.km
                it[status] = serviceOrder.status
            }
        }
    }

    suspend fun delete(id: Int, userId: String) {
        dbQuery {
            ServiceOrderTable.deleteWhere { (ServiceOrderTable.id eq id) and (ServiceOrderTable.userId eq userId) }
        }
    }

    private fun toServiceOrderDto(row: ResultRow) = ServiceOrderDto(
        id = row[ServiceOrderTable.id],
        clientId = row[ServiceOrderTable.clientId],
        clientName = row[ServiceOrderTable.clientName],
        idVeiculo = row[ServiceOrderTable.idVeiculo],
        veiculo = row[ServiceOrderTable.veiculo],
        userId = row[ServiceOrderTable.userId],
        description = row[ServiceOrderTable.description],
        partsUsed = row[ServiceOrderTable.partsUsed],
        partsValue = row[ServiceOrderTable.partsValue],
        laborValue = row[ServiceOrderTable.laborValue],
        totalValue = row[ServiceOrderTable.totalValue],
        deliveryDate = row[ServiceOrderTable.deliveryDate],
        km = row[ServiceOrderTable.km],
        status = row[ServiceOrderTable.status]
    )

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, db = db) { block() }
}