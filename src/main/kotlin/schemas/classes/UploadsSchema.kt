import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class UploadList(
    val id: Int? = null,
    val title: String,
    val description: String,
    val classNames: List<String> = emptyList(),
    val classCodes: List<String> = emptyList(),
    val videoName: String? = null,
    val active: Boolean = true,
)

@Serializable
data class UploadListDto(
    val id: Int,
    val title: String,
    val description: String,
    val classNames: List<String> = emptyList(),
    val classCodes: List<String> = emptyList(),
    val videoName: String? = null,
    val active: Boolean = true,
    val createdAt: String,
)

@Serializable
data class UploadDeleteRequest(
    val id: Int,
)

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class UploadService(private val database: Database) {
    object FilesTable : Table() {
        val id = integer("id").autoIncrement()

        val title = varchar("title", length = 255)
        val description = text("description")
        val classNames = text("classNames")
        val classCodes = text("classCodes")
        val videoName = varchar("videoName", length = 255).nullable()
        val videoData = blob("videoData").nullable()
        val active = bool("active").default(true)
        val createdAt = varchar("createdAt", length = 50)
        val updatedAt = varchar("updatedAt", length = 50)

        // Colunas legadas para compatibilidade com bancos já existentes.
        val fileName = varchar("fileName", length = 50).nullable()
        val fileCode = varchar("fileCode", length = 50).nullable()
        val classCode = varchar("classCode", length = 50).nullable()
        val fileType = varchar("fileType", length = 50).nullable()

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(FilesTable)
            SchemaUtils.createMissingTablesAndColumns(FilesTable)
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun now(): String = java.time.Instant.now().toString()

    private fun encodeList(values: List<String>): String = json.encodeToString(values)

    private fun decodeList(value: String): List<String> =
        runCatching { json.decodeFromString<List<String>>(value) }.getOrDefault(emptyList())

    private fun toDto(row: ResultRow) = UploadListDto(
        id = row[FilesTable.id],
        title = row[FilesTable.title],
        description = row[FilesTable.description],
        classNames = decodeList(row[FilesTable.classNames]),
        classCodes = decodeList(row[FilesTable.classCodes]),
        videoName = row[FilesTable.videoName],
        active = row[FilesTable.active],
        createdAt = row[FilesTable.createdAt],
    )

    suspend fun create(upload: UploadList): Int = dbQuery {
        val timestamp = now()
        FilesTable.insert {
            it[title] = upload.title
            it[description] = upload.description
            it[classNames] = encodeList(upload.classNames)
            it[classCodes] = encodeList(upload.classCodes)
            it[videoName] = upload.videoName
            it[active] = upload.active
            it[createdAt] = timestamp
            it[updatedAt] = timestamp

            // Espelha parte dos dados no formato antigo para transição suave.
            it[fileName] = upload.videoName
            it[fileCode] = upload.classCodes.firstOrNull()
            it[classCode] = upload.classCodes.firstOrNull()
            it[fileType] = "lesson"
        }[FilesTable.id]
    }

    suspend fun readAll(): List<UploadListDto> {
        return dbQuery {
            FilesTable.selectAll().orderBy(FilesTable.createdAt, SortOrder.DESC).map(::toDto)
        }
    }

    suspend fun readFiltered(classCode: String, active: Boolean?): List<UploadListDto> {
        return dbQuery {
            var query = FilesTable.selectAll().where {
                FilesTable.classCodes like "%\"$classCode\"%"
            }

            if (active != null) {
                query = query.andWhere { FilesTable.active eq active }
            }

            query.orderBy(FilesTable.createdAt, SortOrder.DESC).map(::toDto)
        }
    }

    suspend fun update(id: Int, upload: UploadList) {
        dbQuery {
            val timestamp = now()
            FilesTable.update({ FilesTable.id eq id }) {
                it[title] = upload.title
                it[description] = upload.description
                it[classNames] = encodeList(upload.classNames)
                it[classCodes] = encodeList(upload.classCodes)
                it[videoName] = upload.videoName
                it[active] = upload.active
                it[updatedAt] = timestamp

                it[fileName] = upload.videoName
                it[fileCode] = upload.classCodes.firstOrNull()
                it[classCode] = upload.classCodes.firstOrNull()
                it[fileType] = "lesson"
            }
        }
    }

    suspend fun delete(id: Int) {
        dbQuery {
            FilesTable.deleteWhere { FilesTable.id.eq(id) }
        }
    }

    /**
     * Salva (ou substitui) os bytes do vídeo MP4 para um registro já existente.
     * Retorna true se o registro foi encontrado e atualizado.
     */
    suspend fun saveVideo(id: Int, videoBytes: ByteArray): Boolean = dbQuery {
        val updated = FilesTable.update({ FilesTable.id eq id }) {
            it[videoData] = ExposedBlob(videoBytes)
            it[updatedAt] = now()
        }
        updated > 0
    }

    /**
     * Retorna os bytes do vídeo armazenado para o registro, ou null se não houver.
     */
    suspend fun getVideo(id: Int): ByteArray? = dbQuery {
        FilesTable
            .select(FilesTable.videoData)
            .where { FilesTable.id eq id }
            .singleOrNull()
            ?.get(FilesTable.videoData)
            ?.bytes
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO,database) { block() }
}

