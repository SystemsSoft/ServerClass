package schemas.classes

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class FlashcardDto(
    val id: Int = 0,
    val studentName: String,
    val className: String,
    val word: String,
    val type: String = "word",
    val definition: String,
    val example: String = "",
)

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class FlashcardService(private val database: Database) {

    object FlashcardTable : Table("flashcards") {
        val id          = integer("id").autoIncrement()
        val studentName = varchar("studentName", 100)
        val className   = varchar("className",   100)
        val word        = varchar("word",        200)
        val type        = varchar("type",         50)
        val definition  = text("definition")
        val example     = text("example")

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            // TODO: remover o DROP após o primeiro deploy bem-sucedido
            exec("DROP TABLE IF EXISTS flashcards")
            SchemaUtils.create(FlashcardTable)
        }
    }

    // ── CREATE ────────────────────────────────────────────────────────────────
    suspend fun create(card: FlashcardDto): FlashcardDto = dbQuery {
        val newId = FlashcardTable.insert {
            it[studentName] = card.studentName
            it[className]   = card.className
            it[word]        = card.word
            it[type]        = card.type
            it[definition]  = card.definition
            it[example]     = card.example
        }[FlashcardTable.id]

        card.copy(id = newId)
    }

    // ── READ ALL (by student + class) ─────────────────────────────────────────
    suspend fun readAll(studentName: String, className: String): List<FlashcardDto> = dbQuery {
        FlashcardTable
            .selectAll()
            .where {
                (FlashcardTable.studentName eq studentName) and
                (FlashcardTable.className   eq className)
            }
            .map { row ->
                FlashcardDto(
                    id          = row[FlashcardTable.id],
                    studentName = row[FlashcardTable.studentName],
                    className   = row[FlashcardTable.className],
                    word        = row[FlashcardTable.word],
                    type        = row[FlashcardTable.type],
                    definition  = row[FlashcardTable.definition],
                    example     = row[FlashcardTable.example],
                )
            }
    }

    // ── UPDATE ────────────────────────────────────────────────────────────────
    suspend fun update(id: Int, card: FlashcardDto): FlashcardDto = dbQuery {
        FlashcardTable.update({ FlashcardTable.id eq id }) {
            it[word]       = card.word
            it[type]       = card.type
            it[definition] = card.definition
            it[example]    = card.example
        }
        card.copy(id = id)
    }

    // ── DELETE ONE ────────────────────────────────────────────────────────────
    suspend fun delete(id: Int) = dbQuery {
        FlashcardTable.deleteWhere { FlashcardTable.id eq id }
    }

    // ── DELETE ALL (by student + class) ───────────────────────────────────────
    suspend fun deleteAll(studentName: String, className: String) = dbQuery {
        FlashcardTable.deleteWhere {
            (FlashcardTable.studentName eq studentName) and
            (FlashcardTable.className   eq className)
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }
}

