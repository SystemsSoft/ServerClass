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

/** Representa os metadados de uma foto salva. */
@Serializable
data class PhotoDto(
    val id: String,
    val email: String,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val s3Key: String,
    val url: String,
    val uploadedAt: String,
)

/** Corpo de atualização (PUT /photos/{id}). */
@Serializable
data class PhotoUpdateRequest(
    val email: String,
    val name: String? = null,
)

// =================================================================
// 2. SERVICE
// =================================================================

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class PhotoService(private val database: Database) {

    // ── TABELA ────────────────────────────────────────────────────
    object PhotosTable : Table("photos") {
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
            SchemaUtils.create(PhotosTable)
            SchemaUtils.createMissingTablesAndColumns(PhotosTable)
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }

    private fun now(): String = java.time.Instant.now().toString()

    // ── MAPEAMENTO ────────────────────────────────────────────────
    private fun toDto(row: ResultRow) = PhotoDto(
        id        = row[PhotosTable.id],
        email     = row[PhotosTable.email],
        name      = row[PhotosTable.name],
        sizeBytes = row[PhotosTable.sizeBytes],
        mimeType  = row[PhotosTable.mimeType],
        s3Key     = row[PhotosTable.s3Key],
        url       = row[PhotosTable.url],
        uploadedAt = row[PhotosTable.uploadedAt],
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
    ): PhotoDto {
        val ts = now()
        return dbQuery {
            PhotosTable.insert {
                it[PhotosTable.id]        = id
                it[PhotosTable.email]     = email
                it[PhotosTable.name]      = name
                it[PhotosTable.sizeBytes] = sizeBytes
                it[PhotosTable.mimeType]  = mimeType
                it[PhotosTable.s3Key]     = s3Key
                it[PhotosTable.url]       = url
                it[PhotosTable.uploadedAt] = ts
            }
            PhotosTable.selectAll()
                .where { PhotosTable.id eq id }
                .single()
                .let { toDto(it) }
        }
    }

    // ── READ ALL (by email) ───────────────────────────────────────
    suspend fun readByEmail(email: String): List<PhotoDto> = dbQuery {
        PhotosTable.selectAll()
            .where { PhotosTable.email eq email }
            .orderBy(PhotosTable.uploadedAt, SortOrder.DESC)
            .map { toDto(it) }
    }

    // ── READ BY ID ────────────────────────────────────────────────
    suspend fun readById(id: String): PhotoDto? = dbQuery {
        PhotosTable.selectAll()
            .where { PhotosTable.id eq id }
            .singleOrNull()
            ?.let { toDto(it) }
    }

    // ── UPDATE ────────────────────────────────────────────────────
    suspend fun update(id: String, name: String): PhotoDto? = dbQuery {
        val updated = PhotosTable.update({ PhotosTable.id eq id }) {
            it[PhotosTable.name] = name
        }
        if (updated == 0) return@dbQuery null
        PhotosTable.selectAll()
            .where { PhotosTable.id eq id }
            .single()
            .let { toDto(it) }
    }

    // ── DELETE ────────────────────────────────────────────────────
    /** Deletes the record and returns the s3Key so the caller can remove it from S3. */
    suspend fun delete(id: String, email: String): String? = dbQuery {
        val row = PhotosTable.selectAll()
            .where { (PhotosTable.id eq id) and (PhotosTable.email eq email) }
            .singleOrNull() ?: return@dbQuery null

        val key = row[PhotosTable.s3Key]
        PhotosTable.deleteWhere { PhotosTable.id eq id }
        key
    }
}

