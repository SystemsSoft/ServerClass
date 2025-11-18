package schemas.estrelasLeiria

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class Categoria(
    val nome: String,
)


@Serializable
data class CategoriaDto(
    val id: String,
    val nome: String,
)

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class CategoriaService(private val database: Database) {

    object CategoriaTable : Table("categorias") { // Adicionei o nome da tabela por convenção
        val id = varchar("id", length = 36)
        val nome = varchar("nome", length = 100)

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(CategoriaTable)
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }



    suspend fun create(categoria: Categoria, generatedId: String): String {
        return dbQuery {
            CategoriaTable.insert {
                it[id] = generatedId
                it[nome] = categoria.nome
            }[CategoriaTable.id]
        }
    }


    suspend fun readAll(): List<CategoriaDto> {
        return dbQuery {
            CategoriaTable.selectAll().map { toCategoriaDto(it) }
        }
    }

    suspend fun update(id: String, categoria: Categoria) {
        dbQuery {
            CategoriaTable.update({ CategoriaTable.id eq id }) {
                it[nome] = categoria.nome
            }
        }
    }

    suspend fun delete(id: String) {
        dbQuery {
            CategoriaTable.deleteWhere { CategoriaTable.id.eq(id) }
        }
    }

    private fun toCategoriaDto(row: ResultRow) = CategoriaDto(
        id = row[CategoriaTable.id],
        nome = row[CategoriaTable.nome],
    )
}