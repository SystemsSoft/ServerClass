package schemas.sentinela

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Duration
import java.time.Instant

/** Tempo de gravação grátis, uma única vez por conta. */
const val SENTINELA_TRIAL_SECONDS = 30L * 60

/**
 * Teto do uso grátis: uma gravação que passa dos 30 min grátis não é cortada na hora (o usuário pode
 * estar numa emergência), mas o total grátis nunca passa de 1 hora.
 */
const val SENTINELA_TRIAL_HARD_CAP_SECONDS = 60L * 60

/**
 * Planos à venda. Os valores em reais ficam só na Stripe (o servidor guarda apenas os IDs dos preços);
 * aqui ficam as regras de uso: horas incluídas e por quanto tempo o plano vale.
 */
enum class SentinelaPlan(
    val title: String,
    val hours: Int,
    val retentionDays: Int,
    /** true = assinatura que renova sozinha; false = compra única. */
    val recurring: Boolean,
    /** Duração do acesso de uma compra única (assinaturas usam o período informado pela Stripe). */
    val validityDays: Int = 0,
) {
    AVULSO("Avulso", hours = 1, retentionDays = 7, recurring = false, validityDays = 7),
    MENSAL("Mensal", hours = 3, retentionDays = 30, recurring = true),
    TRIMESTRAL("Trimestral", hours = 10, retentionDays = 90, recurring = true),
    SEMESTRAL("Semestral", hours = 20, retentionDays = 180, recurring = true),
    ANUAL("Anual", hours = 40, retentionDays = 365, recurring = true);

    val seconds: Long get() = hours * 3600L
}

/** Situação do plano pago (coluna `paid_status`). */
enum class SentinelaPaidStatus { ACTIVE, PAST_DUE, CANCELED }

/** De onde sai o tempo da gravação: do teste grátis, do plano pago, ou de nenhum (precisa comprar). */
enum class SentinelaBillingMode { TRIAL, PLAN, NONE }

/**
 * [remainingSeconds] é o saldo que a gravação tem direito de usar; [hardRemainingSeconds] é até onde ela pode
 * ir antes de ser encerrada (no teste grátis é maior: vai até o teto de 1 hora). No plano pago os dois são iguais.
 */
class SentinelaAvailability(
    val mode: SentinelaBillingMode,
    val remainingSeconds: Long,
    val hardRemainingSeconds: Long = remainingSeconds,
) {
    val canRecord: Boolean get() = mode != SentinelaBillingMode.NONE && remainingSeconds > 0
}

@Serializable
data class SentinelaBillingStatusDto(
    val mode: String,
    val canRecord: Boolean,
    /** Segundos que ainda podem ser gravados agora (do plano, se ativo; senão do teste grátis). */
    val remainingSeconds: Long,
    val trialTotalSeconds: Long,
    val trialUsedSeconds: Long,
    val plan: String?,
    val planStatus: String?,
    val planIncludedSeconds: Long,
    val planUsedSeconds: Long,
    val planActiveUntil: String?,
    val cancelAtPeriodEnd: Boolean,
    /** true se a conta já tem cadastro na Stripe (então o portal de gerenciamento está disponível). */
    val hasBillingAccount: Boolean,
)

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class SentinelaBillingService(private val database: Database) {

    object SentinelaBillingTable : Table("sentinela_billing") {
        val uid = varchar("uid", length = 128)
        val trialUsedSeconds = long("trial_used_seconds").default(0)
        val plan = varchar("plan", length = 20).nullable()
        val paidStatus = varchar("paid_status", length = 20).nullable()
        val paidIncludedSeconds = long("paid_included_seconds").default(0)
        val paidUsedSeconds = long("paid_used_seconds").default(0)
        val paidUntil = varchar("paid_until", length = 30).nullable()
        val cancelAtPeriodEnd = bool("cancel_at_period_end").default(false)
        val stripeCustomerId = varchar("stripe_customer_id", length = 64).nullable().index()
        val stripeSubscriptionId = varchar("stripe_subscription_id", length = 64).nullable()

        override val primaryKey = PrimaryKey(uid)
    }

    /** Eventos da Stripe já tratados (a Stripe reenvia eventos; cada um só pode valer uma vez). */
    object SentinelaStripeEventTable : Table("sentinela_stripe_events") {
        val id = varchar("id", length = 120)
        val processedAt = varchar("processed_at", length = 30)

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(SentinelaBillingTable, SentinelaStripeEventTable)
            SchemaUtils.createMissingTablesAndColumns(SentinelaBillingTable, SentinelaStripeEventTable)
        }
    }

    private class Row(
        val trialUsed: Long,
        val plan: SentinelaPlan?,
        val paidStatus: SentinelaPaidStatus?,
        val paidIncluded: Long,
        val paidUsed: Long,
        val paidUntil: Instant?,
        val cancelAtPeriodEnd: Boolean,
        val customerId: String?,
    ) {
        fun planActive(now: Instant): Boolean =
            plan != null && paidStatus == SentinelaPaidStatus.ACTIVE && paidUntil != null && paidUntil.isAfter(now)

        fun planRemaining(now: Instant): Long = if (planActive(now)) (paidIncluded - paidUsed).coerceAtLeast(0) else 0

        fun trialRemaining(): Long = (SENTINELA_TRIAL_SECONDS - trialUsed).coerceAtLeast(0)
    }

    suspend fun status(uid: String, now: Instant = Instant.now()): SentinelaBillingStatusDto {
        val row = load(uid)
        val availability = availabilityOf(row, now)
        return SentinelaBillingStatusDto(
            mode = availability.mode.name,
            canRecord = availability.canRecord,
            remainingSeconds = availability.remainingSeconds,
            trialTotalSeconds = SENTINELA_TRIAL_SECONDS,
            trialUsedSeconds = row.trialUsed.coerceAtMost(SENTINELA_TRIAL_SECONDS),
            plan = row.plan?.name,
            planStatus = row.paidStatus?.name,
            planIncludedSeconds = row.paidIncluded,
            planUsedSeconds = row.paidUsed,
            planActiveUntil = row.paidUntil?.toString(),
            cancelAtPeriodEnd = row.cancelAtPeriodEnd,
            hasBillingAccount = row.customerId != null,
        )
    }

    suspend fun availability(uid: String, now: Instant = Instant.now()): SentinelaAvailability =
        availabilityOf(load(uid), now)

    /** O plano pago tem prioridade; o teste grátis só é usado enquanto não há plano com saldo. */
    private fun availabilityOf(row: Row, now: Instant): SentinelaAvailability {
        val plan = row.planRemaining(now)
        if (plan > 0) return SentinelaAvailability(SentinelaBillingMode.PLAN, plan)
        val trial = row.trialRemaining()
        if (trial > 0) {
            val hard = (SENTINELA_TRIAL_HARD_CAP_SECONDS - row.trialUsed).coerceAtLeast(trial)
            return SentinelaAvailability(SentinelaBillingMode.TRIAL, trial, hard)
        }
        return SentinelaAvailability(SentinelaBillingMode.NONE, 0)
    }

    /** Desconta [seconds] gravados do saldo de onde a gravação começou. */
    suspend fun consume(uid: String, mode: SentinelaBillingMode, seconds: Long) {
        if (seconds <= 0 || mode == SentinelaBillingMode.NONE) return
        ensureRow(uid)
        dbQuery {
            SentinelaBillingTable.update(where = { SentinelaBillingTable.uid eq uid }) {
                if (mode == SentinelaBillingMode.TRIAL) {
                    it[trialUsedSeconds] = trialUsedSeconds + seconds
                } else {
                    it[paidUsedSeconds] = paidUsedSeconds + seconds
                }
            }
        }
    }

    suspend fun customerIdOf(uid: String): String? = load(uid).customerId

    suspend fun linkCustomer(uid: String, customerId: String) {
        ensureRow(uid)
        dbQuery {
            SentinelaBillingTable.update(where = { SentinelaBillingTable.uid eq uid }) {
                it[stripeCustomerId] = customerId
            }
        }
    }

    suspend fun uidOfCustomer(customerId: String): String? = dbQuery {
        SentinelaBillingTable
            .selectAll()
            .where { SentinelaBillingTable.stripeCustomerId eq customerId }
            .singleOrNull()
            ?.get(SentinelaBillingTable.uid)
    }

    /**
     * Ativa (ou renova) uma assinatura: define as horas do período e zera o consumo dele.
     * Se a conta tinha saldo de compra avulsa, esse saldo é somado ao novo período.
     */
    suspend fun activateSubscription(
        uid: String,
        plan: SentinelaPlan,
        periodEnd: Instant,
        subscriptionId: String?,
        now: Instant = Instant.now(),
    ) {
        val before = load(uid)
        val carried = if (before.plan == SentinelaPlan.AVULSO) before.planRemaining(now) else 0
        dbQuery {
            SentinelaBillingTable.update(where = { SentinelaBillingTable.uid eq uid }) {
                it[this.plan] = plan.name
                it[paidStatus] = SentinelaPaidStatus.ACTIVE.name
                it[paidIncludedSeconds] = plan.seconds + carried
                it[paidUsedSeconds] = 0
                it[paidUntil] = periodEnd.toString()
                it[cancelAtPeriodEnd] = false
                if (subscriptionId != null) it[stripeSubscriptionId] = subscriptionId
            }
        }
    }

    /**
     * Compra avulsa: soma 1 hora. Com assinatura ativa, a hora vale até o fim do período dela;
     * sem assinatura, vale por [SentinelaPlan.validityDays] dias a partir de agora.
     */
    suspend fun grantAvulso(uid: String, now: Instant = Instant.now()) {
        val before = load(uid)
        val hasSubscription = before.planActive(now) && before.plan?.recurring == true
        dbQuery {
            SentinelaBillingTable.update(where = { SentinelaBillingTable.uid eq uid }) {
                if (hasSubscription) {
                    it[paidIncludedSeconds] = paidIncludedSeconds + SentinelaPlan.AVULSO.seconds
                } else {
                    val remaining = before.planRemaining(now)
                    it[this.plan] = SentinelaPlan.AVULSO.name
                    it[paidStatus] = SentinelaPaidStatus.ACTIVE.name
                    it[paidIncludedSeconds] = remaining + SentinelaPlan.AVULSO.seconds
                    it[paidUsedSeconds] = 0
                    it[paidUntil] = now.plus(Duration.ofDays(SentinelaPlan.AVULSO.validityDays.toLong())).toString()
                    it[cancelAtPeriodEnd] = false
                }
            }
        }
    }

    suspend fun setStatusByCustomer(customerId: String, status: SentinelaPaidStatus) {
        dbQuery {
            SentinelaBillingTable.update(where = { SentinelaBillingTable.stripeCustomerId eq customerId }) {
                it[paidStatus] = status.name
            }
        }
    }

    suspend fun setCancelAtPeriodEnd(customerId: String, value: Boolean) {
        dbQuery {
            SentinelaBillingTable.update(where = { SentinelaBillingTable.stripeCustomerId eq customerId }) {
                it[cancelAtPeriodEnd] = value
            }
        }
    }

    /** Assinatura encerrada: o plano deixa de valer agora; o que sobrou do teste grátis continua valendo. */
    suspend fun endSubscription(customerId: String, now: Instant = Instant.now()) {
        dbQuery {
            SentinelaBillingTable.update(where = { SentinelaBillingTable.stripeCustomerId eq customerId }) {
                it[paidStatus] = SentinelaPaidStatus.CANCELED.name
                it[paidUntil] = now.toString()
                it[cancelAtPeriodEnd] = false
            }
        }
    }

    /** true se este [key] ainda não tinha sido tratado (e o marca como tratado); false se já foi. */
    suspend fun markProcessed(key: String, now: Instant = Instant.now()): Boolean = dbQuery {
        SentinelaStripeEventTable.insertIgnore {
            it[id] = key
            it[processedAt] = now.toString()
        }.insertedCount > 0
    }

    /** Desfaz [markProcessed] quando o tratamento falhou, para a Stripe poder reenviar o evento. */
    suspend fun forget(key: String) {
        dbQuery { SentinelaStripeEventTable.deleteWhere { id eq key } }
    }

    private suspend fun ensureRow(uid: String) {
        dbQuery { SentinelaBillingTable.insertIgnore { it[this.uid] = uid } }
    }

    private suspend fun load(uid: String): Row {
        ensureRow(uid)
        return dbQuery {
            val row = SentinelaBillingTable.selectAll().where { SentinelaBillingTable.uid eq uid }.single()
            Row(
                trialUsed = row[SentinelaBillingTable.trialUsedSeconds],
                plan = row[SentinelaBillingTable.plan]?.let { name -> SentinelaPlan.entries.firstOrNull { it.name == name } },
                paidStatus = row[SentinelaBillingTable.paidStatus]?.let { name -> SentinelaPaidStatus.entries.firstOrNull { it.name == name } },
                paidIncluded = row[SentinelaBillingTable.paidIncludedSeconds],
                paidUsed = row[SentinelaBillingTable.paidUsedSeconds],
                paidUntil = row[SentinelaBillingTable.paidUntil]?.let(Instant::parse),
                cancelAtPeriodEnd = row[SentinelaBillingTable.cancelAtPeriodEnd],
                customerId = row[SentinelaBillingTable.stripeCustomerId],
            )
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }
}
