import ClientMecService.ClientMecTable
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class Expense(
    val name: String,
    val value: Double,
    val date: String,
    val userId: String
)

@Serializable
data class ExpenseDto(
    var id: Int,
    val name: String,
    val value: Double,
    val date: String,
    val userId: String
)


@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class ExpenseService(private val database: Database) {
    object ExpenseTable : Table() {
        val id = integer("id").autoIncrement()
        val name = varchar("name", length = 100)
        val value = double("value")
        val date = varchar("date", length = 50)
        val userId = varchar("userId",length = 50)

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(ExpenseTable)
        }
    }


    suspend fun create(expense: Expense): Int {
        return dbQuery {
            ExpenseTable.insert {
                it[name] = expense.name
                it[value] = expense.value
                it[date] = expense.date
                it[userId] = expense.userId
            }[ExpenseTable.id]
        }
    }

    suspend fun readAll(userId: String): List<ExpenseDto> {
        return dbQuery {
            ExpenseTable.selectAll().where { ClientMecTable.userId eq  userId }.map {
                ExpenseDto(
                    it[ExpenseTable.id],
                    it[ExpenseTable.name],
                    it[ExpenseTable.value],
                    it[ExpenseTable.date],
                    it[ExpenseTable.userId]
                )
            }
        }
    }

    suspend fun update(id: Int, expense: ExpenseDto) {
        dbQuery {
            ExpenseTable.update({ (ExpenseTable.id eq id) and (ExpenseTable.userId eq expense.userId) }) {
                it[name] = expense.name
                it[value] = expense.value
                it[date] = expense.date
                it[userId] = expense.userId
            }
        }
    }

    suspend fun delete(id: Int,userId: String) {
        dbQuery {
            ExpenseTable.deleteWhere { (ClientMecTable.id eq id) and (ClientMecTable.userId eq userId) }
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO,database) { block() }
}