package schemas

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
@Serializable
data class UploadList(
    val fileName: String,
    val fileCode: String,
    val classCode: String,
    val fileType: String,
)

@Serializable
data class UploadListDto(
    val id: Int,
    val fileName: String,
    val fileCode: String,
    val classCode: String,
    val fileType: String,
)

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class UploadService(database: Database) {
    object FilesTable : Table() {
        val id = integer("id").autoIncrement()

        val fileName = varchar("fileName", length = 50)
        val fileCode = varchar("fileCode",length = 50)
        val classCode = varchar("classCode",length = 50)
        val fileType = varchar("fileType",length = 50)

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(FilesTable)
        }
    }

    suspend fun create(uplods: UploadList): Int = dbQuery {
        FilesTable.insert {
            it[fileName] = uplods.fileName
            it[fileCode] = uplods.fileCode
            it[classCode] = uplods.classCode
            it[fileType] = uplods.fileType
        }[FilesTable.id]
    }

    suspend fun readAll(): List<UploadListDto> {
        return dbQuery {
            FilesTable.selectAll().map {
                UploadListDto(
                    it[FilesTable.id],
                    it[FilesTable.fileName],
                    it[FilesTable.fileCode],
                    it[FilesTable.classCode],
                    it[FilesTable.fileType],
                )
            }
        }
    }

    suspend fun update(id: Int, uploads: UploadListDto) {
        dbQuery {
            FilesTable.update({ FilesTable.id eq id }) {
                it[fileName] = uploads.fileName
                it[fileCode] = uploads.fileCode
                it[classCode] = uploads.classCode
                it[fileType] = uploads.fileType
            }
        }
    }

    suspend fun delete(id: Int) {
        dbQuery {
            FilesTable.deleteWhere { FilesTable.id.eq(id) }
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}

