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
    val categoriaId: String,
    val nome: String,
    val instagram: String,
    val imageData: String, // Base64
    val descricaoDetalhada: String,
    // NOVOS CAMPOS
    val stripeId: String? = null, // ID do pagamento (QR Code)
    val desejaParticiparVotacao: Boolean // Checkbox do formulário
)

@Serializable
data class IndicadoDto(
    val id: String,
    val categoriaId: String,
    val nome: String,
    val instagram: String,
    val imageData: String,
    val descricaoDetalhada: String,
    // NOVOS CAMPOS NA SAÍDA
    val stripeId: String?,
    val desejaParticiparVotacao: Boolean
)

@Serializable
data class IndicadoUpdate(
    val categoriaId: String,
    val nome: String,
    val instagram: String,
    val descricaoDetalhada: String,
    val desejaParticiparVotacao: Boolean // Permite admin alterar se a pessoa participa ou não
)

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class IndicadoService(private val database: Database) {

    // TABELA
    object IndicadoTable : Table("indicados") {
        val id = varchar("id", length = 36)
        val categoriaId = varchar("categoriaId", length = 36)
        val nome = varchar("nome", length = 100)
        val instagram = varchar("instagram", length = 100)

        // NOVOS CAMPOS NO BANCO DE DADOS
        val stripeId = varchar("stripe_id", length = 100).nullable()
        val desejaParticiparVotacao = bool("deseja_participar_votacao").default(false)

        // Mantendo largeText para imagens grandes
        val imageData = largeText("image_data")
        val descricaoDetalhada = varchar("descricaoDetalhada", length = 1000)

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(IndicadoTable)
            // Cria as colunas stripeId e desejaParticiparVotacao automaticamente se não existirem
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

                // SALVA OS NOVOS CAMPOS
                it[stripeId] = indicado.stripeId
                it[desejaParticiparVotacao] = indicado.desejaParticiparVotacao
            }[IndicadoTable.id]
        }
    }

    // LEITURA
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
                it[desejaParticiparVotacao] = indicado.desejaParticiparVotacao // Atualiza participação

                if (currentImageData != null) {
                    it[imageData] = currentImageData
                }
                it[descricaoDetalhada] = indicado.descricaoDetalhada
            }
        }
    }

    // DELEÇÃO
    suspend fun delete(id: String) {
        dbQuery {
            IndicadoTable.deleteWhere { IndicadoTable.id.eq(id) }
        }
    }

    // --- Mapeamento ---
    private fun toIndicadoDto(row: ResultRow): IndicadoDto {
        return IndicadoDto(
            id = row[IndicadoTable.id],
            categoriaId = row[IndicadoTable.categoriaId],
            nome = row[IndicadoTable.nome],
            instagram = row[IndicadoTable.instagram],
            imageData = row[IndicadoTable.imageData],
            descricaoDetalhada = row[IndicadoTable.descricaoDetalhada],
            // MAPEIA OS NOVOS CAMPOS
            stripeId = row[IndicadoTable.stripeId],
            desejaParticiparVotacao = row[IndicadoTable.desejaParticiparVotacao]
        )
    }
}