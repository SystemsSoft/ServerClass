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
    val codFile: String,
    val codClass: String,
    val tipoFile: String,
)

@Serializable
data class UploadListDto(
    val id: Int,
    val fileName: String,
    val codFile: String,
    val codClass: String,
    val tipoFile: String,
)

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class UploadService(database: Database) {
    object Files : Table() {
        val id = integer("id").autoIncrement()

        val fileName = varchar("fileName", length = 50)
        val codFile = varchar("codFile",length = 50)
        val codClass = varchar("codClass",length = 50)
        val tipoFile = varchar("tipoFile",length = 50)

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(Files)
        }
    }

    suspend fun create(uplods: UploadList): Int = dbQuery {
        Files.insert {
            it[fileName] = uplods.fileName
            it[codFile] = uplods.codFile
            it[codClass] = uplods.codClass
            it[tipoFile] = uplods.tipoFile
        }[Files.id]
    }

    suspend fun readAll(): List<UploadListDto> {
        return dbQuery {
            Files.selectAll().map {
                UploadListDto(
                    it[Files.id],
                    it[Files.fileName],
                    it[Files.codFile],
                    it[Files.codClass],
                    it[Files.tipoFile],
                )
            }
        }
    }

    suspend fun update(id: Int, uploads: UploadListDto) {
        dbQuery {
            Files.update({ Files.id eq id }) {
                it[fileName] = uploads.fileName
                it[codFile] = uploads.codFile
                it[codClass] = uploads.codClass
                it[tipoFile] = uploads.tipoFile
            }
        }
    }

    suspend fun delete(id: Int) {
        dbQuery {
            Files.deleteWhere { Files.id.eq(id) }
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}

