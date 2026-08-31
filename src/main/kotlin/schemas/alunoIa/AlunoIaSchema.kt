package schemas.alunoIa

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class AlunoIaDto(
    val id: Int = 0,
    val userId: String,
    val nome: String,
    val email: String,
    val stripeCustomerId: String = "",
    val planoAtivo: String = "",
    val moduloAtual: String = "",
    val missaoAtual: String = "",
    val ultimaSessao: String = "",
)

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class AlunoIaService(private val database: Database) {

    object AlunoIaTable : Table("aluno_ia") {
        val id               = integer("id").autoIncrement()
        val userId           = varchar("user_id", 100)
        val nome             = varchar("nome", 150)
        val email            = varchar("email", 150)
        val stripeCustomerId = varchar("stripe_customer_id", 100)
        val planoAtivo       = varchar("plano_ativo", 50)
        val moduloAtual      = varchar("modulo_atual", 50)
        val missaoAtual      = varchar("missao_atual", 50)
        val ultimaSessao     = varchar("ultima_sessao", 50)

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(AlunoIaTable)
            SchemaUtils.createMissingTablesAndColumns(AlunoIaTable)
        }
    }

    // ── CREATE ────────────────────────────────────────────────────────────────
    suspend fun create(aluno: AlunoIaDto): AlunoIaDto = dbQuery {
        val newId = AlunoIaTable.insert {
            it[userId]           = aluno.userId
            it[nome]             = aluno.nome
            it[email]            = aluno.email
            it[stripeCustomerId] = aluno.stripeCustomerId
            it[planoAtivo]       = aluno.planoAtivo
            it[moduloAtual]      = aluno.moduloAtual
            it[missaoAtual]      = aluno.missaoAtual
            it[ultimaSessao]     = aluno.ultimaSessao
        }[AlunoIaTable.id]

        aluno.copy(id = newId)
    }

    // ── READ BY USER ID ──────────────────────────────────────────────────────
    suspend fun readByUserId(userId: String): AlunoIaDto? = dbQuery {
        AlunoIaTable
            .selectAll()
            .where { AlunoIaTable.userId eq userId }
            .map { it.toDto() }
            .singleOrNull()
    }

    // ── READ ALL ─────────────────────────────────────────────────────────────
    suspend fun readAll(): List<AlunoIaDto> = dbQuery {
        AlunoIaTable.selectAll().map { it.toDto() }
    }

    // ── UPDATE ───────────────────────────────────────────────────────────────
    suspend fun update(userId: String, aluno: AlunoIaDto): Int = dbQuery {
        AlunoIaTable.update({ AlunoIaTable.userId eq userId }) {
            it[nome]             = aluno.nome
            it[email]            = aluno.email
            it[stripeCustomerId] = aluno.stripeCustomerId
            it[planoAtivo]       = aluno.planoAtivo
            it[moduloAtual]      = aluno.moduloAtual
            it[missaoAtual]      = aluno.missaoAtual
            it[ultimaSessao]     = aluno.ultimaSessao
        }
    }

    // ── DELETE ───────────────────────────────────────────────────────────────
    suspend fun delete(userId: String) = dbQuery {
        AlunoIaTable.deleteWhere { AlunoIaTable.userId eq userId }
    }

    private fun ResultRow.toDto() = AlunoIaDto(
        id               = this[AlunoIaTable.id],
        userId           = this[AlunoIaTable.userId],
        nome             = this[AlunoIaTable.nome],
        email            = this[AlunoIaTable.email],
        stripeCustomerId = this[AlunoIaTable.stripeCustomerId],
        planoAtivo       = this[AlunoIaTable.planoAtivo],
        moduloAtual      = this[AlunoIaTable.moduloAtual],
        missaoAtual      = this[AlunoIaTable.missaoAtual],
        ultimaSessao     = this[AlunoIaTable.ultimaSessao],
    )

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }
}
