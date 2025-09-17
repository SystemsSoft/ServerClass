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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.and

@Serializable
data class Vehicle(
    val userId: String,
    val idCliente: Int,
    val veiculo: String,
    val ano: String,
    val marca: String,
    val placa: String
)

@Serializable
data class VehicleDto(
    var id: Int,
    val userId: String,
    val idCliente: Int,
    var veiculo: String,
    var ano: String,
    var marca: String,
    var placa: String
)

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class VehicleService(private val db: Database) {

    object VehicleTable : Table() {
        val id = integer("id").autoIncrement()
        val userId = varchar("userId", length = 50)
        val idCliente = integer("idCliente")
        val veiculo = varchar("veiculo", length = 50)
        val ano = varchar("ano", length = 10)
        val marca = varchar("marca", length = 50)
        val placa = varchar("placa", length = 10)

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(db) {
            SchemaUtils.create(VehicleTable)
        }
    }


    suspend fun create(vehicle: Vehicle): Int {
        return dbQuery {
            VehicleTable.insert {
                it[userId] = vehicle.userId
                it[idCliente] = vehicle.idCliente
                it[veiculo] = vehicle.veiculo
                it[ano] = vehicle.ano
                it[marca] = vehicle.marca
                it[placa] = vehicle.placa
            }[VehicleTable.id]
        }
    }

    suspend fun readAll(userId: String): List<VehicleDto> {
        return dbQuery {
            VehicleTable.selectAll().where { VehicleTable.userId eq userId }.map {
                VehicleDto(
                    it[VehicleTable.id],
                    it[VehicleTable.userId],
                    it[VehicleTable.idCliente],
                    it[VehicleTable.veiculo],
                    it[VehicleTable.ano],
                    it[VehicleTable.marca],
                    it[VehicleTable.placa]
                )
            }
        }
    }

    suspend fun readByClientId(idCliente: Int, userId: String): List<VehicleDto> {
        return dbQuery {
            VehicleTable.selectAll()
                .where { (VehicleTable.idCliente eq idCliente) and (VehicleTable.userId eq userId) }
                .map {
                    VehicleDto(
                        it[VehicleTable.id],
                        it[VehicleTable.userId],
                        it[VehicleTable.idCliente],
                        it[VehicleTable.veiculo],
                        it[VehicleTable.ano],
                        it[VehicleTable.marca],
                        it[VehicleTable.placa]
                    )
                }
        }
    }

    suspend fun update(id: Int, vehicle: VehicleDto) {
        dbQuery {
            VehicleTable.update({ (VehicleTable.id eq id) and (VehicleTable.userId eq vehicle.userId) }) {
                it[veiculo] = vehicle.veiculo
                it[ano] = vehicle.ano
                it[marca] = vehicle.marca
                it[placa] = vehicle.placa
            }
        }
    }

    suspend fun delete(id: Int, userId: String) {
        dbQuery {
            VehicleTable.deleteWhere { (VehicleTable.id eq id) and (VehicleTable.userId eq userId) }
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, db = db) { block() }
}