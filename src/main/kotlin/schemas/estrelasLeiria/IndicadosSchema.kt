package schemas.estrelasLeiria

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.Base64

// =================================================================
// 1. MODELOS DE DADOS (NOMENCLATURA AJUSTADA)
// =================================================================

@Serializable
data class Indicado(
    val categoriaId: String,
    val nome: String,
    val imageData: String, // Nome alterado para refletir o conteúdo (Contém a String Base64)
    val descricaoDetalhada: String,
)

@Serializable
data class IndicadoDto(
    val id: String,
    val categoriaId: String,
    val nome: String,
    val imageData: String, // String Base64 de saída
    val descricaoDetalhada: String,
)

@Serializable
data class IndicadoUpdate(
    val categoriaId: String,
    val nome: String,
    val descricaoDetalhada: String,
)



@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class IndicadoService(private val database: Database) {

    // TABELA: O tipo binary() já garante o armazenamento em bytes
    object IndicadoTable : Table("indicados") {
        val id = varchar("id", length = 36)
        val categoriaId = varchar("categoriaId", length = 36)
        val nome = varchar("nome", length = 100)

        // Tipo binário para armazenamento do BLOB (ByteArray)
        val imageData = binary("image_data", 50000000) // ~50MB
        val descricaoDetalhada = varchar("descricaoDetalhada", length = 1000)

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(IndicadoTable)
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }


    suspend fun create(indicado: Indicado, generatedId: String): String {
        val imageBytes = Base64.getDecoder().decode(indicado.imageData)

        return dbQuery {
            IndicadoTable.insert {
                it[id] = generatedId
                it[categoriaId] = indicado.categoriaId
                it[nome] = indicado.nome
                it[imageData] = imageBytes // Salva o ByteArray
                it[descricaoDetalhada] = indicado.descricaoDetalhada
            }[IndicadoTable.id]
        }
    }

    suspend fun readAll(): List<IndicadoDto> {
        return dbQuery {
            IndicadoTable.selectAll().map { toIndicadoDto(it) }
        }
    }


    suspend fun update(id: String, indicado: IndicadoUpdate) {
        dbQuery {
            // Buscamos os dados atuais para preservar o BLOB (imageData)
            val currentIndicado = IndicadoTable.selectAll().where { IndicadoTable.id eq id }.singleOrNull()
            val currentImageBytes = currentIndicado?.get(IndicadoTable.imageData)

            // CORREÇÃO: Usamos 'where =' para resolver o erro de type mismatch
            IndicadoTable.update(where = { IndicadoTable.id eq id }) {
                it[categoriaId] = indicado.categoriaId
                it[nome] = indicado.nome

                // Mantemos o BLOB atual (sem alteração)
                if (currentImageBytes != null) {
                    it[imageData] = currentImageBytes
                }
                it[descricaoDetalhada] = indicado.descricaoDetalhada
            }
        }
    }

    suspend fun delete(id: String) {
        dbQuery {
            IndicadoTable.deleteWhere { IndicadoTable.id.eq(id) }
        }
    }

    // --- Mapeamento ---

    /**
     * Função auxiliar para mapear um ResultRow para um IndicadoDto.
     * Converte o ByteArray do banco de volta para String Base64.
     */
    private fun toIndicadoDto(row: ResultRow): IndicadoDto {
        // Converte o ByteArray do banco de volta para Base64 para ser enviado via JSON
        val imageBytes = row[IndicadoTable.imageData]
        val imageDataBase64 = Base64.getEncoder().encodeToString(imageBytes)

        return IndicadoDto(
            id = row[IndicadoTable.id],
            categoriaId = row[IndicadoTable.categoriaId],
            nome = row[IndicadoTable.nome],
            imageData = imageDataBase64, // Usa a String Base64
            descricaoDetalhada = row[IndicadoTable.descricaoDetalhada]
        )
    }
}