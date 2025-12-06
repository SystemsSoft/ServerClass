package schemas.estrelasLeiria

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.Base64

// =================================================================
// 1. MODELOS DE DADOS (COM CAMPO INSTAGRAM)
// =================================================================

@Serializable
data class Indicado(
    val categoriaId: String,
    val nome: String,
    val instagram: String, // NOVO CAMPO
    val imageData: String, // Base64
    val descricaoDetalhada: String,
)

@Serializable
data class IndicadoDto(
    val id: String,
    val categoriaId: String,
    val nome: String,
    val instagram: String, // NOVO CAMPO NA SAÍDA
    val imageData: String, // Base64
    val descricaoDetalhada: String,
)

@Serializable
data class IndicadoUpdate(
    val categoriaId: String,
    val nome: String,
    val instagram: String, // PERMITE ATUALIZAR O INSTA
    val descricaoDetalhada: String,
)

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class IndicadoService(private val database: Database) {

    // TABELA
    object IndicadoTable : Table("indicados") {
        val id = varchar("id", length = 36)
        val categoriaId = varchar("categoriaId", length = 36)
        val nome = varchar("nome", length = 100)

        val instagram = varchar("instagram", length = 100)

        // Tipo binário para armazenamento do BLOB (ByteArray)
        val imageData = binary("image_data", 50000000) // ~50MB
        val descricaoDetalhada = varchar("descricaoDetalhada", length = 1000)

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            // Cria a tabela se não existir
            SchemaUtils.create(IndicadoTable)

            // Adiciona a coluna 'instagram' caso a tabela já exista (Migração automática)
            SchemaUtils.createMissingTablesAndColumns(IndicadoTable)
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }

    // CRIAÇÃO
    suspend fun create(indicado: Indicado, generatedId: String): String {
        val imageBytes = Base64.getDecoder().decode(indicado.imageData)

        return dbQuery {
            IndicadoTable.insert {
                it[id] = generatedId
                it[categoriaId] = indicado.categoriaId
                it[nome] = indicado.nome
                it[instagram] = indicado.instagram
                it[imageData] = imageBytes
                it[descricaoDetalhada] = indicado.descricaoDetalhada
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
            val currentImageBytes = currentIndicado?.get(IndicadoTable.imageData)

            IndicadoTable.update(where = { IndicadoTable.id eq id }) {
                it[categoriaId] = indicado.categoriaId
                it[nome] = indicado.nome
                it[instagram] = indicado.instagram // ATUALIZA O INSTAGRAM

                if (currentImageBytes != null) {
                    it[imageData] = currentImageBytes
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

    private fun toIndicadoDto(row: ResultRow): IndicadoDto {
        val imageBytes = row[IndicadoTable.imageData]
        val imageDataBase64 = Base64.getEncoder().encodeToString(imageBytes)

        return IndicadoDto(
            id = row[IndicadoTable.id],
            categoriaId = row[IndicadoTable.categoriaId],
            nome = row[IndicadoTable.nome],
            instagram = row[IndicadoTable.instagram],
            imageData = imageDataBase64,
            descricaoDetalhada = row[IndicadoTable.descricaoDetalhada]
        )
    }
}