package schemas.mec

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction


@Serializable
data class Revenue(
    val name: String,
    val value: Double,
    val date: String,
    val userId: String
)

@Serializable
data class RevenueDto(
    var id: Int,
    val name: String,
    val value: Double,
    val date: String,
    val userId: String
)

@Serializable
data class DeleteDto(
    val id: Int,
    val userId: String
)

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class RevenueService(private val database: Database) {
    object RevenueTable : Table() {
        val id = integer("id").autoIncrement()
        val name = varchar("name", length = 100)

        val value = double("value")
        val date = varchar("date", length = 50)
        val userId = varchar("userId",length = 50)

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(RevenueTable)
        }
    }


    suspend fun create(revenue: Revenue): Int {
        return dbQuery {
            RevenueTable.insert {
                it[name] = revenue.name
                it[value] = revenue.value
                it[date] = revenue.date
                it[userId] = revenue.userId
            }[RevenueTable.id]
        }
    }

    suspend fun readAll(userId: String): List<RevenueDto> {
        return dbQuery {
            RevenueTable.selectAll().where { RevenueTable.userId eq  userId }.map {
                RevenueDto(
                    it[RevenueTable.id],
                    it[RevenueTable.name],
                    it[RevenueTable.value],
                    it[RevenueTable.date],
                    it[RevenueTable.userId]
                )
            }
        }
    }

    suspend fun update(id: Int, revenue: RevenueDto) {
        dbQuery {
            RevenueTable.update({ RevenueTable.id eq id }) {
                it[name] = revenue.name
                it[value] = revenue.value
                it[date] = revenue.date
                it[userId] = revenue.userId
            }
        }
    }

    suspend fun delete(id: Int, userId: String) {
        dbQuery {
            RevenueTable.deleteWhere { (RevenueTable.id eq id) and (RevenueTable.userId eq userId) }
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO,database) { block() }
}