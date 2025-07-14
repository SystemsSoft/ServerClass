package schemas.mec


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
    val date: String
)

@Serializable
data class ExpenseDto(
    var id: Int,
    val name: String,
    val value: Double,
    val date: String
)


@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class ExpenseService(private val database: Database) {
    object ExpenseTable : Table() {
        val id = integer("id").autoIncrement()
        val name = varchar("name", length = 100)
        val value = double("value")
        val date = varchar("date", length = 50) // Storing date as String, consider using `datetime` for JodaTime

        override val primaryKey = PrimaryKey(id)
    }

    // --- Initialization ---
    init {
        transaction(database) {
            SchemaUtils.create(ExpenseTable)
        }
    }

    // --- CRUD Operations ---

    suspend fun create(expense: Expense): Int {
        return dbQuery {
            ExpenseTable.insert {
                it[name] = expense.name
                it[value] = expense.value
                it[date] = expense.date
            }[ExpenseTable.id]
        }
    }

    suspend fun readAll(): List<ExpenseDto> {
        return dbQuery {
            ExpenseTable.selectAll().map {
                ExpenseDto(
                    it[ExpenseTable.id],
                    it[ExpenseTable.name],
                    it[ExpenseTable.value],
                    it[ExpenseTable.date]
                )
            }
        }
    }

    suspend fun update(id: Int, expense: ExpenseDto) {
        dbQuery {
            ExpenseTable.update({ ExpenseTable.id eq id }) {
                it[name] = expense.name
                it[value] = expense.value
                it[date] = expense.date
            }
        }
    }

    suspend fun delete(id: Int) {
        dbQuery {
            ExpenseTable.deleteWhere { ExpenseTable.id.eq(id) }
        }
    }

    // --- Database Query Helper ---
    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO,database) { block() }
}