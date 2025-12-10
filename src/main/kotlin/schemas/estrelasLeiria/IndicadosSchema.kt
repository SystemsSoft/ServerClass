package schemas.estrelasLeiria

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

// =================================================================
// 1. MODELOS DE DADOS
// =================================================================

@Serializable
data class Indicado(
    val categoriaId: String, // Agora suporta string longa ("id1,id2,id3")
    val nome: String,
    val instagram: String,
    val imageData: String,
    val descricaoDetalhada: String,
    val stripeId: String? = null,
    val desejaParticiparVotacao: Boolean,
    val email: String? = null
)

@Serializable
data class IndicadoDto(
    val id: String,
    val categoriaId: String,
    val nome: String,
    val instagram: String,
    val imageData: String,
    val descricaoDetalhada: String,
    val stripeId: String?,
    val desejaParticiparVotacao: Boolean,
    val email: String?
)

@Serializable
data class IndicadoUpdate(
    val categoriaId: String,
    val nome: String,
    val instagram: String,
    val descricaoDetalhada: String,
    val desejaParticiparVotacao: Boolean
)

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class IndicadoService(private val database: Database) {

    // TABELA
    object IndicadoTable : Table("indicados") {
        val id = varchar("id", length = 36)

        // --- ALTERAÇÃO AQUI ---
        // Aumentado para 500 para suportar múltiplos IDs concatenados
        val categoriaId = varchar("categoriaId", length = 500)

        val nome = varchar("nome", length = 100)
        val instagram = varchar("instagram", length = 100)

        val email = varchar("email", length = 200).nullable()
        val stripeId = varchar("stripe_id", length = 100).nullable()
        val desejaParticiparVotacao = bool("deseja_participar_votacao").default(false)

        val imageData = largeText("image_data")
        val descricaoDetalhada = varchar("descricaoDetalhada", length = 1000)

        val checkIn = bool("check_in").default(false)
        // Controla se já entrou
        val checkInDate = varchar("check_in_date", 50).nullable()

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(IndicadoTable)
            SchemaUtils.createMissingTablesAndColumns(IndicadoTable)
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }

    // CRIAÇÃO
    suspend fun create(indicado: Indicado, generatedId: String): String {
        return dbQuery {
            IndicadoTable.insert {
                it[id] = generatedId
                it[categoriaId] = indicado.categoriaId
                it[nome] = indicado.nome
                it[instagram] = indicado.instagram
                it[imageData] = indicado.imageData
                it[descricaoDetalhada] = indicado.descricaoDetalhada
                it[stripeId] = indicado.stripeId
                it[desejaParticiparVotacao] = indicado.desejaParticiparVotacao
                it[email] = indicado.email
            }[IndicadoTable.id]
        }
    }

    // LEITURA (TODOS)
    suspend fun readAll(): List<IndicadoDto> {
        return dbQuery {
            IndicadoTable.selectAll().map { toIndicadoDto(it) }
        }
    }

    // ATUALIZAÇÃO
    suspend fun update(id: String, indicado: IndicadoUpdate) {
        dbQuery {
            val currentIndicado = IndicadoTable.selectAll().where { IndicadoTable.id eq id }.singleOrNull()
            val currentImageData = currentIndicado?.get(IndicadoTable.imageData)

            IndicadoTable.update(where = { IndicadoTable.id eq id }) {
                it[categoriaId] = indicado.categoriaId
                it[nome] = indicado.nome
                it[instagram] = indicado.instagram
                it[desejaParticiparVotacao] = indicado.desejaParticiparVotacao
                it[descricaoDetalhada] = indicado.descricaoDetalhada

                if (currentImageData != null) {
                    it[imageData] = currentImageData
                }
            }
        }
    }

    // DELEÇÃO
    suspend fun delete(id: String) {
        dbQuery {
            IndicadoTable.deleteWhere { IndicadoTable.id.eq(id) }
        }
    }

    // --- MAPEAMENTO (Row -> DTO) ---
    private fun toIndicadoDto(row: ResultRow): IndicadoDto {
        return IndicadoDto(
            id = row[IndicadoTable.id],
            categoriaId = row[IndicadoTable.categoriaId],
            nome = row[IndicadoTable.nome],
            instagram = row[IndicadoTable.instagram],
            imageData = row[IndicadoTable.imageData],
            descricaoDetalhada = row[IndicadoTable.descricaoDetalhada],
            stripeId = row[IndicadoTable.stripeId],
            desejaParticiparVotacao = row[IndicadoTable.desejaParticiparVotacao],
            email = row[IndicadoTable.email]
        )
    }
}