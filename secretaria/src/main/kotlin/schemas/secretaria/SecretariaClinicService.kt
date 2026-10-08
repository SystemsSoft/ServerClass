package schemas.secretaria

import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.math.BigDecimal
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.util.Base64

/** Dados mínimos de uma clínica usados no atendimento. */
data class ClinicInfo(val id: Long, val name: String, val responsibleName: String?, val zone: ZoneId, val active: Boolean)

class EmailAlreadyUsedException : RuntimeException("E-mail já cadastrado")

/** Clínicas, médicos, pacientes, plano e notificações. */
class SecretariaClinicService(
    private val database: Database,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val random = SecureRandom()

    // ── clínicas ─────────────────────────────────────────────────────────────

    suspend fun clinic(id: Long): ClinicInfo? = database.dbQuery {
        ClinicsTable.selectAll().where { ClinicsTable.id eq id }.singleOrNull()?.toInfo()
    }

    /** Resolve a chave pública do PWA; clínicas inativas não atendem. */
    suspend fun clinicByPublicKey(key: String): ClinicInfo? = database.dbQuery {
        ClinicsTable.selectAll().where { (ClinicsTable.publicKey eq key) and (ClinicsTable.active eq true) }
            .singleOrNull()?.toInfo()
    }

    /**
     * Cria a clínica, o primeiro usuário (ADMIN), médicos com agenda padrão (seg-sex 08-12 e 14-18,
     * consultas de 30 min), plano inicial e assinatura. Lança [EmailAlreadyUsedException] se o e-mail existir.
     */
    suspend fun bootstrap(request: BootstrapRequest): BootstrapResponse {
        val zone = ZoneId.of(request.timezone) // lança se o fuso for inválido
        val email = request.email.trim().lowercase()
        val now = clock()
        val publicKey = ByteArray(24).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

        return database.dbQuery {
            if (UsersTable.selectAll().where { UsersTable.email eq email }.any()) throw EmailAlreadyUsedException()

            val clinicId = ClinicsTable.insert {
                it[name] = request.clinicName.trim()
                it[responsibleName] = request.responsibleName?.trim()
                it[timezone] = zone.id
                it[ClinicsTable.publicKey] = publicKey
                it[createdAt] = now
            }[ClinicsTable.id]

            val userId = try {
                UsersTable.insert {
                    it[name] = request.userName.trim()
                    it[UsersTable.email] = email
                    it[passwordHash] = SecretariaPasswords.hash(request.password)
                    it[createdAt] = now
                }[UsersTable.id]
            } catch (e: ExposedSQLException) {
                throw EmailAlreadyUsedException()
            }
            ClinicUsersTable.insert {
                it[ClinicUsersTable.clinicId] = clinicId
                it[ClinicUsersTable.userId] = userId
                it[role] = ClinicRole.ADMIN
            }

            for (doctor in request.doctors) {
                val specialtyId = ensureSpecialty(doctor.specialty)
                val doctorId = DoctorsTable.insert {
                    it[DoctorsTable.clinicId] = clinicId
                    it[DoctorsTable.specialtyId] = specialtyId
                    it[name] = doctor.name.trim()
                    it[crm] = doctor.crm
                }[DoctorsTable.id]
                for (weekday in 1..5) {
                    for ((start, end) in listOf(8 * 60 to 12 * 60, 14 * 60 to 18 * 60)) {
                        DoctorSchedulesTable.insert {
                            it[DoctorSchedulesTable.doctorId] = doctorId
                            it[DoctorSchedulesTable.weekday] = weekday
                            it[startMinute] = start
                            it[endMinute] = end
                        }
                    }
                }
            }

            val planId = PlansTable.selectAll().where { PlansTable.name eq DEFAULT_PLAN_NAME }.singleOrNull()
                ?.get(PlansTable.id)
                ?: PlansTable.insert {
                    it[name] = DEFAULT_PLAN_NAME
                    it[monthlyPrice] = BigDecimal("199.00")
                    it[includedMinutes] = 300
                    it[costPerMinute] = DEFAULT_COST_PER_MINUTE
                }[PlansTable.id]
            SubscriptionsTable.insert {
                it[SubscriptionsTable.clinicId] = clinicId
                it[SubscriptionsTable.planId] = planId
                it[status] = SubscriptionStatus.ATIVA
                it[periodStart] = now
                it[nextBillingAt] = now + 30L * 24 * 3_600_000
            }

            BootstrapResponse(clinicId, publicKey, userId)
        }
    }

    private fun ensureSpecialty(name: String): Long {
        val trimmed = name.trim()
        return SpecialtiesTable.selectAll().where { SpecialtiesTable.name eq trimmed }.singleOrNull()
            ?.get(SpecialtiesTable.id)
            ?: SpecialtiesTable.insert { it[SpecialtiesTable.name] = trimmed }[SpecialtiesTable.id]
    }

    // ── médicos ──────────────────────────────────────────────────────────────

    suspend fun doctors(clinicId: Long): List<DoctorDto> = database.dbQuery {
        (DoctorsTable innerJoin SpecialtiesTable)
            .selectAll()
            .where { (DoctorsTable.clinicId eq clinicId) and (DoctorsTable.active eq true) }
            .orderBy(DoctorsTable.name)
            .map { DoctorDto(it[DoctorsTable.id], it[DoctorsTable.name], it[SpecialtiesTable.name]) }
    }

    // ── pacientes ────────────────────────────────────────────────────────────

    /** Pacientes da clínica com esse telefone (várias pessoas da família podem compartilhar o número). */
    suspend fun patientsByPhone(clinicId: Long, phone: String): List<PatientDto> = database.dbQuery {
        PatientsTable.selectAll()
            .where { (PatientsTable.clinicId eq clinicId) and (PatientsTable.phone eq phone) }
            .orderBy(PatientsTable.id)
            .map { PatientDto(it[PatientsTable.id], it[PatientsTable.name], it[PatientsTable.phone], it[PatientsTable.email], it[PatientsTable.createdAt]) }
    }

    /**
     * Acha o paciente pelo telefone + nome ([namesMatch]) ou cria um novo cadastro. Nunca sobrescreve um
     * cadastro existente: quem liga não prova identidade só por falar um nome.
     */
    suspend fun findOrCreatePatient(clinicId: Long, name: String, phone: String): Long {
        patientsByPhone(clinicId, phone).firstOrNull { namesMatch(it.name, name) }?.let { return it.id }
        return database.dbQuery {
            PatientsTable.insert {
                it[PatientsTable.clinicId] = clinicId
                it[PatientsTable.name] = name.trim()
                it[PatientsTable.phone] = phone
                it[consentAt] = clock()
                it[createdAt] = clock()
            }[PatientsTable.id]
        }
    }

    suspend fun patients(clinicId: Long, query: String?, limit: Int, offset: Long): List<PatientDto> = database.dbQuery {
        val q = query?.trim().orEmpty()
        PatientsTable.selectAll()
            .where {
                if (q.isEmpty()) PatientsTable.clinicId eq clinicId
                else (PatientsTable.clinicId eq clinicId) and
                    ((PatientsTable.name like "%$q%") or (PatientsTable.phone like "%${q.filter { c -> c.isDigit() }.ifEmpty { q }}%"))
            }
            .orderBy(PatientsTable.name)
            .limit(limit).offset(offset)
            .map { PatientDto(it[PatientsTable.id], it[PatientsTable.name], it[PatientsTable.phone], it[PatientsTable.email], it[PatientsTable.createdAt]) }
    }

    // ── notificações ─────────────────────────────────────────────────────────

    suspend fun notify(clinicId: Long, type: NotificationType, title: String, body: String?) {
        database.dbQuery {
            NotificationsTable.insert {
                it[NotificationsTable.clinicId] = clinicId
                it[NotificationsTable.type] = type
                it[NotificationsTable.title] = title.take(160)
                it[NotificationsTable.body] = body?.take(500)
                it[createdAt] = clock()
            }
        }
    }

    suspend fun unreadNotifications(clinicId: Long, userId: Long): Int = database.dbQuery {
        NotificationsTable.selectAll()
            .where {
                (NotificationsTable.clinicId eq clinicId) and NotificationsTable.readAt.isNull() and
                    (NotificationsTable.userId.isNull() or (NotificationsTable.userId eq userId))
            }
            .count().toInt()
    }

    suspend fun markNotificationsRead(clinicId: Long, userId: Long) {
        database.dbQuery {
            NotificationsTable.update({
                (NotificationsTable.clinicId eq clinicId) and NotificationsTable.readAt.isNull() and
                    (NotificationsTable.userId.isNull() or (NotificationsTable.userId eq userId))
            }) { it[readAt] = clock() }
        }
    }

    suspend fun notifications(clinicId: Long, userId: Long, unreadOnly: Boolean, limit: Int, offset: Long): NotificationPageDto {
        val items = database.dbQuery {
            NotificationsTable.selectAll()
                .where {
                    val visible = (NotificationsTable.clinicId eq clinicId) and (NotificationsTable.userId.isNull() or (NotificationsTable.userId eq userId))
                    if (unreadOnly) visible and NotificationsTable.readAt.isNull() else visible
                }
                .orderBy(NotificationsTable.createdAt, org.jetbrains.exposed.sql.SortOrder.DESC)
                .orderBy(NotificationsTable.id, org.jetbrains.exposed.sql.SortOrder.DESC)
                .limit(limit).offset(offset)
                .map {
                    NotificationDto(
                        it[NotificationsTable.id], it[NotificationsTable.type].name.lowercase(), it[NotificationsTable.title],
                        it[NotificationsTable.body], it[NotificationsTable.readAt] != null, it[NotificationsTable.createdAt],
                    )
                }
        }
        return NotificationPageDto(items, unreadNotifications(clinicId, userId))
    }

    /** Marca uma notificação como lida; false se não existir/não for visível para o usuário. */
    suspend fun markNotificationRead(clinicId: Long, userId: Long, id: Long): Boolean = database.dbQuery {
        NotificationsTable.update({
            (NotificationsTable.id eq id) and (NotificationsTable.clinicId eq clinicId) and
                (NotificationsTable.userId.isNull() or (NotificationsTable.userId eq userId))
        }) { it[readAt] = clock() } > 0
    }

    // ── plano ────────────────────────────────────────────────────────────────

    /** Minutos do ciclo atual (arredondados para cima, como no esquema original) e custo estimado. */
    suspend fun planUsage(clinicId: Long, zone: ZoneId): PlanUsageDto? = database.dbQuery {
        val row = (SubscriptionsTable innerJoin PlansTable).selectAll()
            .where { SubscriptionsTable.clinicId eq clinicId }.singleOrNull() ?: return@dbQuery null

        val seconds = CallsTable.selectAll()
            .where {
                (CallsTable.clinicId eq clinicId) and (CallsTable.state eq CallState.ENCERRADA) and
                    (CallsTable.startedAt greaterEq row[SubscriptionsTable.periodStart]) and
                    (CallsTable.startedAt less row[SubscriptionsTable.nextBillingAt])
            }
            .sumOf { it[CallsTable.durationSeconds] ?: 0 }
        val usedMinutes = Math.ceil(seconds / 60.0).toInt()
        val included = row[PlansTable.includedMinutes]
        val perMinute = row[PlansTable.costPerMinute]

        PlanUsageDto(
            planName = row[PlansTable.name],
            monthlyPrice = row[PlansTable.monthlyPrice].toDouble(),
            includedMinutes = included,
            usedMinutes = usedMinutes,
            usageRatio = if (included == 0) 0.0 else (usedMinutes.toDouble() / included).coerceAtMost(1.0),
            costPerMinute = perMinute.toDouble(),
            estimatedCost = perMinute.multiply(BigDecimal(usedMinutes)).setScale(2, java.math.RoundingMode.HALF_UP).toDouble(),
            nextBillingDate = Instant.ofEpochMilli(row[SubscriptionsTable.nextBillingAt]).atZone(zone).toLocalDate().toString(),
        )
    }

    private fun ResultRow.toInfo() = ClinicInfo(
        id = this[ClinicsTable.id],
        name = this[ClinicsTable.name],
        responsibleName = this[ClinicsTable.responsibleName],
        zone = ZoneId.of(this[ClinicsTable.timezone]),
        active = this[ClinicsTable.active],
    )

    companion object {
        const val DEFAULT_PLAN_NAME = "Plano Clínica"

        /** Custo estimado por minuto de uso (R$), mostrado no painel. Medido na prática: ~R$ 0,09–0,10/min de IA. */
        val DEFAULT_COST_PER_MINUTE: BigDecimal = BigDecimal("0.1200")
    }
}
