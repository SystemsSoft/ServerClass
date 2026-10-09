package schemas.secretaria

import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/** Plano e consumo (botão "Ver detalhes" do card Uso do plano). Troca de plano é feita só por administração do sistema. */
class SecretariaPlanService(
    private val database: Database,
    private val clinics: SecretariaClinicService,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Contratação por pacotes (Stripe) que o painel mostra junto do plano. */
    private val billing: () -> BillingInfoDto = { BillingInfoDto() },
) {

    suspend fun detail(clinic: ClinicInfo): ServiceResult<PlanDetailDto> {
        val usage = clinics.planUsage(clinic.id, clinic.zone) ?: return notFound("Esta clínica ainda não tem assinatura.")
        val (status, periodStart, byDay) = database.dbQuery {
            val sub = SubscriptionsTable.selectAll().where { SubscriptionsTable.clinicId eq clinic.id }.single()
            val days = CallsTable.selectAll()
                .where {
                    (CallsTable.clinicId eq clinic.id) and (CallsTable.state eq CallState.ENCERRADA) and
                        (CallsTable.startedAt greaterEq sub[SubscriptionsTable.periodStart])
                }
                .groupBy { it[CallsTable.startedAt].toLocalDateTime(clinic.zone).toLocalDate() }
                .toSortedMap()
                .map { (date, rows) ->
                    val seconds = rows.sumOf { it[CallsTable.durationSeconds] ?: 0 }
                    DayUsageDto(date.toString(), rows.size, Math.round(seconds / 6.0) / 10.0)
                }
            Triple(sub[SubscriptionsTable.status].name.lowercase(), Instant.ofEpochMilli(sub[SubscriptionsTable.periodStart]).atZone(clinic.zone).toLocalDate().toString(), days)
        }
        val billing = billing()
        return ServiceResult.Ok(PlanDetailDto(usage.copy(billingEnabled = billing.enabled), status, periodStart, byDay, plans(), billing))
    }

    suspend fun plans(): List<PlanDto> = database.dbQuery {
        PlansTable.selectAll().orderBy(PlansTable.monthlyPrice).map { it.toDto() }
    }

    suspend fun createPlan(request: CreatePlanRequest): ServiceResult<PlanDto> {
        val name = request.name.trim()
        if (name.length !in 2..80) return invalid("Nome do plano inválido (2 a 80 caracteres).")
        if (request.monthlyPrice < 0 || request.costPerMinute < 0) return invalid("Valores não podem ser negativos.")
        if (request.includedMinutes <= 0) return invalid("O plano precisa incluir ao menos 1 minuto.")
        return try {
            database.dbQuery {
                val id = PlansTable.insert {
                    it[PlansTable.name] = name
                    it[monthlyPrice] = BigDecimal.valueOf(request.monthlyPrice).setScale(2, RoundingMode.HALF_UP)
                    it[includedMinutes] = request.includedMinutes
                    it[costPerMinute] = BigDecimal.valueOf(request.costPerMinute).setScale(4, RoundingMode.HALF_UP)
                }[PlansTable.id]
                ServiceResult.Ok(PlansTable.selectAll().where { PlansTable.id eq id }.single().toDto())
            }
        } catch (e: ExposedSQLException) {
            conflict("Já existe um plano com esse nome.")
        }
    }

    /** Associa a clínica a um plano (mantém o ciclo de cobrança atual; cria a assinatura se ainda não existir). */
    suspend fun assign(clinicId: Long, planId: Long): ServiceResult<Unit> = database.dbQuery {
        if (ClinicsTable.selectAll().where { ClinicsTable.id eq clinicId }.empty()) return@dbQuery notFound("Clínica não encontrada.")
        if (PlansTable.selectAll().where { PlansTable.id eq planId }.empty()) return@dbQuery notFound("Plano não encontrado.")
        val exists = !SubscriptionsTable.selectAll().where { SubscriptionsTable.clinicId eq clinicId }.empty()
        if (exists) {
            SubscriptionsTable.update({ SubscriptionsTable.clinicId eq clinicId }) {
                it[SubscriptionsTable.planId] = planId
                it[status] = SubscriptionStatus.ATIVA
            }
        } else {
            val now = clock()
            SubscriptionsTable.insert {
                it[SubscriptionsTable.clinicId] = clinicId
                it[SubscriptionsTable.planId] = planId
                it[status] = SubscriptionStatus.ATIVA
                it[periodStart] = now
                it[nextBillingAt] = now + 30L * 24 * 3_600_000
            }
        }
        ServiceResult.Ok(Unit)
    }

    private fun ResultRow.toDto() = PlanDto(
        this[PlansTable.id], this[PlansTable.name], this[PlansTable.monthlyPrice].toDouble(),
        this[PlansTable.includedMinutes], this[PlansTable.costPerMinute].toDouble(),
    )
}
