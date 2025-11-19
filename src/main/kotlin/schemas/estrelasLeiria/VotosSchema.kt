package schemas.estrelasLeiria

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction


@Serializable
data class Votos(
    val categoriaId: String,
    val indicadoId: String,
    val nome: String,
    val email: String,
    val telefone: String,
)

@Serializable
data class VotosDto(
    val id: String,
    val categoriaId: String,
    val indicadoId: String,
    val nome: String,
    val email: String,
    val telefone: String,
)

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class VotoService(private val database: Database) {

    object VotoTable : Table("votos") {
        val id = varchar("id", length = 36)
        val categoriaId = varchar("categoriaId", length = 36)
        val indicadoId = varchar("indicadoId", length = 36)
        val nome = varchar("nome_eleitor", length = 100)
        val email = varchar("email", length = 100)
        val telefone = varchar("telefone", length = 20)

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(VotoTable)
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }

    suspend fun create(voto: Votos, generatedId: String): String {
        return dbQuery {
            VotoTable.insert {
                it[id] = generatedId
                it[categoriaId] = voto.categoriaId
                it[indicadoId] = voto.indicadoId
                it[nome] = voto.nome
                it[email] = voto.email
                it[telefone] = voto.telefone
            }[VotoTable.id]
        }
    }

    suspend fun readAll(): List<VotosDto> {
        return dbQuery {
            VotoTable.selectAll().map { toVotosDto(it) }
        }
    }

    suspend fun update(id: String, voto: Votos) {
        dbQuery {
            VotoTable.update({ VotoTable.id eq id }) {
                it[categoriaId] = voto.categoriaId
                it[indicadoId] = voto.indicadoId
                it[nome] = voto.nome
                it[email] = voto.email
                it[telefone] = voto.telefone
            }
        }
    }

    suspend fun delete(id: String) {
        dbQuery {
            VotoTable.deleteWhere { VotoTable.id eq id }
        }
    }


    private fun toVotosDto(row: ResultRow): VotosDto {
        return VotosDto(
            id = row[VotoTable.id],
            categoriaId = row[VotoTable.categoriaId],
            indicadoId = row[VotoTable.indicadoId],
            nome = row[VotoTable.nome],
            email = row[VotoTable.email],
            telefone = row[VotoTable.telefone]
        )
    }
}