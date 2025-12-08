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
)

@Serializable
data class IndicadoDto(
    val id: String,
    val categoriaId: String,
    val nome: String,
    val instagram: String,
    val imageData: String, // Base64
    val descricaoDetalhada: String,
)

@Serializable
data class IndicadoUpdate(
    val categoriaId: String,
    val nome: String,
    val instagram: String,
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

        // CORREÇÃO CRÍTICA:
        // 'binary' tem limite de 64KB no MySQL.
        // Usamos 'largeText' (LONGTEXT) para suportar Strings Base64 enormes (até 4GB).
        // Isso também elimina a necessidade de converter para ByteArray manualmente.
        val imageData = largeText("image_data")

        val descricaoDetalhada = varchar("descricaoDetalhada", length = 1000)

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
        // Não precisamos mais do Base64.decode aqui, salvamos a String direto
        return dbQuery {
            IndicadoTable.insert {
                it[id] = generatedId
                it[categoriaId] = indicado.categoriaId
                it[nome] = indicado.nome
                it[instagram] = indicado.instagram
                it[imageData] = indicado.imageData // Salva a String Base64 direto
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
            // Buscamos a imagem atual para preservá-la (já que o DTO de update não tem imagem)
            val currentIndicado = IndicadoTable.selectAll().where { IndicadoTable.id eq id }.singleOrNull()
            val currentImageData = currentIndicado?.get(IndicadoTable.imageData)

            IndicadoTable.update(where = { IndicadoTable.id eq id }) {
                it[categoriaId] = indicado.categoriaId
                it[nome] = indicado.nome
                it[instagram] = indicado.instagram

                // Mantém a String Base64 atual
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
        // Não precisamos mais do Base64.encode, já vem como String do banco
        return IndicadoDto(
            id = row[IndicadoTable.id],
            categoriaId = row[IndicadoTable.categoriaId],
            nome = row[IndicadoTable.nome],
            instagram = row[IndicadoTable.instagram],
            imageData = row[IndicadoTable.imageData], // Lê direto
            descricaoDetalhada = row[IndicadoTable.descricaoDetalhada]
        )
    }
}