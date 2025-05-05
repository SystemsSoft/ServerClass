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
    val codClass: String,
)

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class ClassesListService(database: Database) {
    object Class : Table() {
        val id = integer("id").autoIncrement()
        val className = varchar("className", length = 50)
        val codClasse = varchar("codClass",length = 50)

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(Class)
        }
    }

    suspend fun create(classe: ClassesList): Int  {
        return dbQuery {
            Class.insert {
                it[className] = classe.className
                it[codClasse] = classe.codClass
            }[Class.id]
        }
    }

    suspend fun readAll(): List<ClassesList> {
        return dbQuery {
            Class.selectAll().map {
                ClassesList(
                    it[Class.className],
                    it[Class.codClasse]
                )
            }
        }
    }

    suspend fun update(id: Int, classe: ClassesList) {
        dbQuery {
            Class.update({ Class.id eq id }) {
                it[className] = classe.className
                it[codClasse] = classe.codClass
            }
        }
    }

    suspend fun delete(id: Int) {
        dbQuery {
            Class.deleteWhere { Class.id.eq(id) }
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}

