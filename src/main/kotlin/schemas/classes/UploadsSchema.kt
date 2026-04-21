package schemas.classes

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

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
        val id         = integer("id").autoIncrement()
        val title      = varchar("title", length = 255)
        val description = text("description")
        val classNames = text("classNames")
        val classCodes = text("classCodes")
        // videoName armazena a S3 key (ex: "lessons/3.mp4")
        val videoName  = varchar("videoName", length = 512).nullable()
        val active     = bool("active").default(true)
        val createdAt  = varchar("createdAt", length = 50)
        val updatedAt  = varchar("updatedAt", length = 50)

        // Colunas legadas para compatibilidade
        val fileName  = varchar("fileName", length = 50).nullable()
        val fileCode  = varchar("fileCode", length = 50).nullable()
        val classCode = varchar("classCode", length = 50).nullable()
        val fileType  = varchar("fileType", length = 50).nullable()

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(FilesTable)
            SchemaUtils.createMissingTablesAndColumns(FilesTable)
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private fun now(): String = Instant.now().toString()
    private fun encodeList(values: List<String>): String = json.encodeToString(values)
    private fun decodeList(value: String): List<String> =
        runCatching { json.decodeFromString<List<String>>(value) }.getOrDefault(emptyList())

    private fun toDto(row: ResultRow) = UploadListDto(
        id          = row[FilesTable.id],
        title       = row[FilesTable.title],
        description = row[FilesTable.description],
        classNames  = decodeList(row[FilesTable.classNames]),
        classCodes  = decodeList(row[FilesTable.classCodes]),
        videoName   = row[FilesTable.videoName],
        active      = row[FilesTable.active],
        createdAt   = row[FilesTable.createdAt],
    )

    suspend fun create(upload: UploadList): Int = dbQuery {
        println("[DB] create: title='${upload.title}'")
        val timestamp = now()
        FilesTable.insert {
            it[title]       = upload.title
            it[description] = upload.description
            it[classNames]  = encodeList(upload.classNames)
            it[classCodes]  = encodeList(upload.classCodes)
            it[videoName]   = upload.videoName
            it[active]      = upload.active
            it[createdAt]   = timestamp
            it[updatedAt]   = timestamp
            it[fileName]    = upload.videoName
            it[fileCode]    = upload.classCodes.firstOrNull()
            it[classCode]   = upload.classCodes.firstOrNull()
            it[fileType]    = "lesson"
        }[FilesTable.id].also { println("[DB] create: id=$it") }
    }

    suspend fun readAll(): List<UploadListDto> = dbQuery {
        FilesTable.selectAll().orderBy(FilesTable.createdAt, SortOrder.DESC).map(::toDto)
    }

    suspend fun readFiltered(classCode: String, active: Boolean?): List<UploadListDto> = dbQuery {
        var query = FilesTable.selectAll().where {
            FilesTable.classCodes like "%\"$classCode\"%"
        }
        if (active != null) query = query.andWhere { FilesTable.active eq active }
        query.orderBy(FilesTable.createdAt, SortOrder.DESC).map(::toDto)
    }

    suspend fun update(id: Int, upload: UploadList) = dbQuery {
        FilesTable.update({ FilesTable.id eq id }) {
            it[title]       = upload.title
            it[description] = upload.description
            it[classNames]  = encodeList(upload.classNames)
            it[classCodes]  = encodeList(upload.classCodes)
            it[videoName]   = upload.videoName
            it[active]      = upload.active
            it[updatedAt]   = now()
            it[fileName]    = upload.videoName
            it[fileCode]    = upload.classCodes.firstOrNull()
            it[classCode]   = upload.classCodes.firstOrNull()
            it[fileType]    = "lesson"
        }
    }

    suspend fun delete(id: Int) = dbQuery {
        FilesTable.deleteWhere { FilesTable.id.eq(id) }
    }

    /**
     * Salva a S3 key do vídeo no registro (coluna videoName).
     */
    suspend fun saveVideoKey(id: Int, s3Key: String): Boolean = dbQuery {
        println("[DB] saveVideoKey: id=$id, key=$s3Key")
        val updated = FilesTable.update({ FilesTable.id eq id }) {
            it[videoName] = s3Key
            it[updatedAt] = now()
        }
        println("[DB] saveVideoKey: rows afetadas=$updated")
        updated > 0
    }

    /**
     * Retorna o registro completo por id, ou null se não encontrado.
     */
    suspend fun getById(id: Int): UploadListDto? = dbQuery {
        FilesTable.selectAll()
            .where { FilesTable.id eq id }
            .singleOrNull()
            ?.let(::toDto)
    }

    /**
     * Retorna a S3 key do vídeo para o registro, ou null se não houver.
     */
    suspend fun getVideoKey(id: Int): String? = dbQuery {
        FilesTable
            .select(FilesTable.videoName)
            .where { FilesTable.id eq id }
            .singleOrNull()
            ?.get(FilesTable.videoName)
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }
}
