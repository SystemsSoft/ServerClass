package schemas

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class ClassesList(
    val className: String,
    val classCode: String,
)
@Serializable
data class ClassDto(
    var id: Int,
    val className: String,
    val classCode: String,
)

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class ClassesListService(private val database: Database) {
    object ClassTable : Table() {
        val id = integer("id").autoIncrement()
        val className = varchar("className", length = 50)
        val classCode = varchar("codClass",length = 50)

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(ClassTable)
        }
    }

    suspend fun create(classe: ClassesList): Int  {
        return dbQuery {
            ClassTable.insert {
                it[className] = classe.className
                it[classCode] = classe.classCode
            }[ClassTable.id]
        }
    }

    suspend fun readAll(): List<ClassDto> {
        return dbQuery {
            ClassTable.selectAll().map {
                ClassDto(
                    it[ClassTable.id],
                    it[ClassTable.className],
                    it[ClassTable.classCode]
                )
            }
        }
    }

    suspend fun update(id: Int, classe: ClassDto) {
        dbQuery {
            ClassTable.update({ ClassTable.id eq id }) {
                it[className] = classe.className
                it[classCode] = classe.classCode
            }
        }
    }

    suspend fun delete(id: Int) {
        dbQuery {
            ClassTable.deleteWhere { ClassTable.id.eq(id) }
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }
}

