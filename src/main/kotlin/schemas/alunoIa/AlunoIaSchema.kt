package schemas.alunoIa

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Valores possíveis de [AlunoIaDto.statusAssinatura], mantidos como String (não enum)
 * para serem persistidos diretamente na coluna do banco sem conversão.
 */
object StatusAssinatura {
    const val INATIVA = "INATIVA"
    const val ATIVA = "ATIVA"
    const val CANCELADA = "CANCELADA"
    const val PAGAMENTO_FALHOU = "PAGAMENTO_FALHOU"
}

@Serializable
data class AlunoIaDto(
    val id: Int = 0,
    val userId: String,
    val nome: String,
    val email: String,
    val stripeCustomerId: String = "",
    val stripeSubscriptionId: String = "",
    val planoAtivo: String = "",
    val statusAssinatura: String = StatusAssinatura.INATIVA,
    val assinaturaAtualizadaEm: String = "",
    val moduloAtual: String = "",
    val missaoAtual: String = "",
    val ultimaSessao: String = "",
)

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class AlunoIaService(private val database: Database) {

    object AlunoIaTable : Table("aluno_ia") {
        val id                     = integer("id").autoIncrement()
        val userId                 = varchar("user_id", 100)
        val nome                   = varchar("nome", 150)
        val email                  = varchar("email", 150)
        val stripeCustomerId       = varchar("stripe_customer_id", 100)
        val stripeSubscriptionId   = varchar("stripe_subscription_id", 100).default("")
        val planoAtivo             = varchar("plano_ativo", 50)
        val statusAssinatura       = varchar("status_assinatura", 30).default(StatusAssinatura.INATIVA)
        val assinaturaAtualizadaEm = varchar("assinatura_atualizada_em", 50).default("")
        val moduloAtual            = varchar("modulo_atual", 50)
        val missaoAtual            = varchar("missao_atual", 50)
        val ultimaSessao           = varchar("ultima_sessao", 50)

        override val primaryKey = PrimaryKey(id)
    }

    /** Registra os IDs de evento do Stripe já processados por este webhook, para idempotência. */
    object ProcessedStripeEventTable : Table("aluno_ia_stripe_events") {
        val eventId = varchar("event_id", 255)
        override val primaryKey = PrimaryKey(eventId)
    }

    init {
        transaction(database) {
            SchemaUtils.create(AlunoIaTable, ProcessedStripeEventTable)
            SchemaUtils.createMissingTablesAndColumns(AlunoIaTable, ProcessedStripeEventTable)
        }
    }

    // ── CREATE ────────────────────────────────────────────────────────────────
    suspend fun create(aluno: AlunoIaDto): AlunoIaDto = dbQuery {
        val newId = AlunoIaTable.insert {
            it[userId]                 = aluno.userId
            it[nome]                   = aluno.nome
            it[email]                  = aluno.email
            it[stripeCustomerId]       = aluno.stripeCustomerId
            it[stripeSubscriptionId]   = aluno.stripeSubscriptionId
            it[planoAtivo]             = aluno.planoAtivo
            it[statusAssinatura]       = aluno.statusAssinatura
            it[assinaturaAtualizadaEm] = aluno.assinaturaAtualizadaEm
            it[moduloAtual]            = aluno.moduloAtual
            it[missaoAtual]            = aluno.missaoAtual
            it[ultimaSessao]           = aluno.ultimaSessao
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

    // ── READ BY EMAIL ─────────────────────────────────────────────────────────
    suspend fun readByEmail(email: String): AlunoIaDto? = dbQuery {
        AlunoIaTable
            .selectAll()
            .where { AlunoIaTable.email eq email }
            .map { it.toDto() }
            .singleOrNull()
    }

    // ── READ BY STRIPE CUSTOMER ID ───────────────────────────────────────────
    suspend fun readByStripeCustomerId(stripeCustomerId: String): AlunoIaDto? = dbQuery {
        AlunoIaTable
            .selectAll()
            .where { AlunoIaTable.stripeCustomerId eq stripeCustomerId }
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
            it[nome]                   = aluno.nome
            it[email]                  = aluno.email
            it[stripeCustomerId]       = aluno.stripeCustomerId
            it[stripeSubscriptionId]   = aluno.stripeSubscriptionId
            it[planoAtivo]             = aluno.planoAtivo
            it[statusAssinatura]       = aluno.statusAssinatura
            it[assinaturaAtualizadaEm] = aluno.assinaturaAtualizadaEm
            it[moduloAtual]            = aluno.moduloAtual
            it[missaoAtual]            = aluno.missaoAtual
            it[ultimaSessao]           = aluno.ultimaSessao
        }
    }

    // ── UPDATE ÚLTIMA SESSÃO ─────────────────────────────────────────────────
    // Atualização parcial (só ultimaSessao), para não sobrescrever moduloAtual/
    // missaoAtual com um snapshot desatualizado do aluno (ver MeganRouting.kt).
    suspend fun updateUltimaSessao(userId: String, ultimaSessao: String): Int = dbQuery {
        AlunoIaTable.update({ AlunoIaTable.userId eq userId }) {
            it[AlunoIaTable.ultimaSessao] = ultimaSessao
        }
    }

    // ── UPDATE ASSINATURA (por userId) ──────────────────────────────────────
    // Atualização parcial (só as colunas de assinatura) para não sobrescrever o
    // restante do registro do aluno ao processar eventos do webhook do Stripe.
    suspend fun updateAssinaturaPorUserId(
        userId: String,
        statusAssinatura: String,
        atualizadoEm: String,
        stripeCustomerId: String? = null,
        stripeSubscriptionId: String? = null,
    ): Int = dbQuery {
        AlunoIaTable.update({ AlunoIaTable.userId eq userId }) {
            it[AlunoIaTable.statusAssinatura] = statusAssinatura
            it[AlunoIaTable.assinaturaAtualizadaEm] = atualizadoEm
            if (stripeCustomerId != null) it[AlunoIaTable.stripeCustomerId] = stripeCustomerId
            if (stripeSubscriptionId != null) it[AlunoIaTable.stripeSubscriptionId] = stripeSubscriptionId
        }
    }

    // ── UPDATE ASSINATURA (por stripeCustomerId) ────────────────────────────
    suspend fun updateAssinaturaPorStripeCustomerId(
        stripeCustomerId: String,
        statusAssinatura: String,
        atualizadoEm: String,
        stripeSubscriptionId: String? = null,
    ): Int = dbQuery {
        AlunoIaTable.update({ AlunoIaTable.stripeCustomerId eq stripeCustomerId }) {
            it[AlunoIaTable.statusAssinatura] = statusAssinatura
            it[AlunoIaTable.assinaturaAtualizadaEm] = atualizadoEm
            if (stripeSubscriptionId != null) it[AlunoIaTable.stripeSubscriptionId] = stripeSubscriptionId
        }
    }

    // ── DELETE ───────────────────────────────────────────────────────────────
    suspend fun delete(userId: String) = dbQuery {
        AlunoIaTable.deleteWhere { AlunoIaTable.userId eq userId }
    }

    // ── IDEMPOTÊNCIA DE EVENTOS DO STRIPE ────────────────────────────────────
    // Retorna true se o evento ainda não tinha sido processado (e já o marca
    // como processado); false se já tinha sido processado antes (deve ser ignorado).
    suspend fun deveProcessarEventoStripe(eventId: String): Boolean = dbQuery {
        val jaProcessado = ProcessedStripeEventTable
            .selectAll()
            .where { ProcessedStripeEventTable.eventId eq eventId }
            .count() > 0

        if (jaProcessado) {
            false
        } else {
            ProcessedStripeEventTable.insertIgnore { it[ProcessedStripeEventTable.eventId] = eventId }
            true
        }
    }

    private fun ResultRow.toDto() = AlunoIaDto(
        id                     = this[AlunoIaTable.id],
        userId                 = this[AlunoIaTable.userId],
        nome                   = this[AlunoIaTable.nome],
        email                  = this[AlunoIaTable.email],
        stripeCustomerId       = this[AlunoIaTable.stripeCustomerId],
        stripeSubscriptionId   = this[AlunoIaTable.stripeSubscriptionId],
        planoAtivo             = this[AlunoIaTable.planoAtivo],
        statusAssinatura       = this[AlunoIaTable.statusAssinatura],
        assinaturaAtualizadaEm = this[AlunoIaTable.assinaturaAtualizadaEm],
        moduloAtual            = this[AlunoIaTable.moduloAtual],
        missaoAtual            = this[AlunoIaTable.missaoAtual],
        ultimaSessao           = this[AlunoIaTable.ultimaSessao],
    )

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }
}
