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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq // Important import for 'eq'
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere

@Serializable
data class PriceTableMec(
    val name: String,
    val price: String,
    val userId: String
)

@Serializable
data class PriceTableMecDto(
    var id: Int,
    var name: String,
    var price: String,
    var userId: String
)

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class PriceTableMecService(private val db: Database) {
    object PriceTableMec : Table() {
        val id = integer("id").autoIncrement()
        val name = varchar("name", length = 50)
        val price = varchar("price", length = 50)
        val userId = varchar("userId",length = 50)
        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(db) {
            SchemaUtils.create(PriceTableMec)
        }
    }

    suspend fun create(pricingEntry: schemas.mec.PriceTableMec): Int {
        return dbQuery {
            PriceTableMec.insert {
                it[name] = pricingEntry.name
                it[price] = pricingEntry.price
                it[userId] = pricingEntry.userId
            }[PriceTableMec.id]
        }
    }

    suspend fun readAll(userId: String): List<PriceTableMecDto> {
        return dbQuery {
            PriceTableMec.selectAll().where{ PriceTableMec.userId eq userId }.map {
                PriceTableMecDto(
                    it[PriceTableMec.id],
                    it[PriceTableMec.name],
                    it[PriceTableMec.price],
                    it[PriceTableMec.userId]
                )
            }
        }
    }

    suspend fun update(id: Int, priceTableMec: PriceTableMecDto) {
        dbQuery {
            PriceTableMec.update({ (PriceTableMec.id eq id) and (PriceTableMec.userId eq priceTableMec.userId) }) {
                it[name] = priceTableMec.name
                it[price] = priceTableMec.price
                it[userId] = priceTableMec.userId
            }
        }
    }

    suspend fun delete(id: Int, userId: String) {
        dbQuery {
            PriceTableMec.deleteWhere { (PriceTableMec.id eq id) and (PriceTableMec.userId eq userId) }
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, db = db) { block() }
}