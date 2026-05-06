package schemas.inovacloudSchema

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

// =================================================================
// 1. MODELOS DE DADOS
// =================================================================

/** Representa os metadados de um vídeo salvo. */
@Serializable
data class VideoDto(
    val id: String,
    val email: String,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val s3Key: String,
    val url: String,
    val uploadedAt: String,
)

/** Corpo de atualização (PUT /videos/{id}). */
@Serializable
data class VideoUpdateRequest(
    val email: String,
    val name: String? = null,
)

// =================================================================
// 2. SERVICE
// =================================================================

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class VideoService(private val database: Database) {

    // ── TABELA ────────────────────────────────────────────────────
    object VideosTable : Table("videos") {
        val id         = varchar("id", 36)
        val email      = varchar("email", 200)
        val name       = varchar("name", 500)
        val sizeBytes  = long("size_bytes")
        val mimeType   = varchar("mime_type", 100)
        val s3Key      = varchar("s3_key", 1000)
        val url        = varchar("url", 2000)
        val uploadedAt = varchar("uploaded_at", 50)

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(VideosTable)
            SchemaUtils.createMissingTablesAndColumns(VideosTable)
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }

    private fun now(): String = java.time.Instant.now().toString()

    // ── MAPEAMENTO ────────────────────────────────────────────────
    private fun toDto(row: ResultRow) = VideoDto(
        id         = row[VideosTable.id],
        email      = row[VideosTable.email],
        name       = row[VideosTable.name],
        sizeBytes  = row[VideosTable.sizeBytes],
        mimeType   = row[VideosTable.mimeType],
        s3Key      = row[VideosTable.s3Key],
        url        = row[VideosTable.url],
        uploadedAt = row[VideosTable.uploadedAt],
    )

    // ── CREATE ────────────────────────────────────────────────────
    suspend fun create(
        id: String,
        email: String,
        name: String,
        sizeBytes: Long,
        mimeType: String,
        s3Key: String,
        url: String,
    ): VideoDto {
        val ts = now()
        return dbQuery {
            VideosTable.insert {
                it[VideosTable.id]        = id
                it[VideosTable.email]     = email
                it[VideosTable.name]      = name
                it[VideosTable.sizeBytes] = sizeBytes
                it[VideosTable.mimeType]  = mimeType
                it[VideosTable.s3Key]     = s3Key
                it[VideosTable.url]       = url
                it[VideosTable.uploadedAt] = ts
            }
            VideosTable.selectAll()
                .where { VideosTable.id eq id }
                .single()
                .let { toDto(it) }
        }
    }

    // ── READ ALL (by email) ───────────────────────────────────────
    suspend fun readByEmail(email: String): List<VideoDto> = dbQuery {
        VideosTable.selectAll()
            .where { VideosTable.email eq email }
            .orderBy(VideosTable.uploadedAt, SortOrder.DESC)
            .map { toDto(it) }
    }

    // ── READ BY ID ────────────────────────────────────────────────
    suspend fun readById(id: String): VideoDto? = dbQuery {
        VideosTable.selectAll()
            .where { VideosTable.id eq id }
            .singleOrNull()
            ?.let { toDto(it) }
    }

    // ── UPDATE ────────────────────────────────────────────────────
    suspend fun update(id: String, name: String): VideoDto? = dbQuery {
        val updated = VideosTable.update({ VideosTable.id eq id }) {
            it[VideosTable.name] = name
        }
        if (updated == 0) return@dbQuery null
        VideosTable.selectAll()
            .where { VideosTable.id eq id }
            .single()
            .let { toDto(it) }
    }

    // ── DELETE ────────────────────────────────────────────────────
    /** Deletes the record and returns the s3Key so the caller can remove it from S3. */
    suspend fun delete(id: String, email: String): String? = dbQuery {
        val row = VideosTable.selectAll()
            .where { (VideosTable.id eq id) and (VideosTable.email eq email) }
            .singleOrNull() ?: return@dbQuery null

        val key = row[VideosTable.s3Key]
        VideosTable.deleteWhere { VideosTable.id eq id }
        key
    }
}

