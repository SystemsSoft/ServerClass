package schemas.secretaria

import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Janela semanal de um médico, em minutos locais desde 00:00. */
data class ScheduleWindow(val weekday: Int, val startMinute: Int, val endMinute: Int, val slotMinutes: Int)

/** Regras puras de agenda (sem banco) — testáveis isoladamente. */
object SecretariaSlots {
    private val LABEL = DateTimeFormatter.ofPattern("EEEE, dd/MM 'às' HH:mm", Locale.forLanguageTag("pt-BR"))

    /** 0 = domingo ... 6 = sábado (DayOfWeek do Java: segunda = 1 ... domingo = 7). */
    fun weekdayOf(date: LocalDate): Int = date.dayOfWeek.value % 7

    fun generate(date: LocalDate, windows: List<ScheduleWindow>): List<LocalDateTime> =
        windows.filter { it.weekday == weekdayOf(date) }.flatMap { w ->
            generateSequence(w.startMinute) { it + w.slotMinutes }
                .takeWhile { it + w.slotMinutes <= w.endMinute }
                .map { date.atStartOfDay().plusMinutes(it.toLong()) }
                .toList()
        }.sorted()

    /** A janela que contém exatamente esse horário de início (alinhado ao tamanho do slot), ou null. */
    fun windowFor(start: LocalDateTime, windows: List<ScheduleWindow>): ScheduleWindow? {
        if (start.second != 0 || start.nano != 0) return null
        val minute = start.hour * 60 + start.minute
        return windows.firstOrNull {
            it.weekday == weekdayOf(start.toLocalDate()) &&
                minute >= it.startMinute && minute + it.slotMinutes <= it.endMinute &&
                (minute - it.startMinute) % it.slotMinutes == 0
        }
    }

    fun label(start: LocalDateTime): String = start.format(LABEL)
}

sealed class BookResult {
    data class Ok(val appointment: AppointmentDto) : BookResult()

    /** [reason] é uma frase em português que a IA pode repetir ao paciente. */
    data class Fail(val reason: String) : BookResult()
}

class SecretariaAppointmentService(
    private val database: Database,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Antecedência mínima para marcar (evita horários "daqui a 2 minutos"). */
    private val minLeadMinutes: Long = 30,
) {

    // ── horários livres ──────────────────────────────────────────────────────

    /**
     * Próximos horários livres. Filtra por médico e/ou especialidade (texto parcial, sem diferenciar
     * maiúsculas). Devolve os mais próximos primeiro, até [limit].
     */
    suspend fun availableSlots(
        clinic: ClinicInfo,
        doctorId: Long?,
        specialty: String?,
        fromDate: LocalDate,
        days: Int,
        limit: Int,
    ): List<SlotDto> = database.dbQuery {
        val doctors = (DoctorsTable innerJoin SpecialtiesTable).selectAll()
            .where { (DoctorsTable.clinicId eq clinic.id) and (DoctorsTable.active eq true) }
            .filter { doctorId == null || it[DoctorsTable.id] == doctorId }
            .filter { specialty.isNullOrBlank() || specialtyMatches(it[SpecialtiesTable.name], specialty) }
        if (doctors.isEmpty()) return@dbQuery emptyList()

        val ids = doctors.map { it[DoctorsTable.id] }
        val windows = DoctorSchedulesTable.selectAll().where { DoctorSchedulesTable.doctorId inList ids }
            .groupBy({ it[DoctorSchedulesTable.doctorId] }, {
                ScheduleWindow(it[DoctorSchedulesTable.weekday], it[DoctorSchedulesTable.startMinute], it[DoctorSchedulesTable.endMinute], it[DoctorSchedulesTable.slotMinutes])
            })

        val earliest = clock() + minLeadMinutes * 60_000
        val rangeStart = fromDate.atStartOfDay().toEpochMs(clinic.zone)
        val rangeEnd = fromDate.plusDays(days.toLong()).atStartOfDay().toEpochMs(clinic.zone)
        val taken = AppointmentsTable.selectAll()
            .where {
                (AppointmentsTable.doctorId inList ids) and AppointmentsTable.activeSlot.isNotNull() and
                    (AppointmentsTable.startsAt greaterEq rangeStart) and (AppointmentsTable.startsAt less rangeEnd)
            }
            .map { it[AppointmentsTable.doctorId] to it[AppointmentsTable.startsAt] }
            .toSet()

        doctors.flatMap { d ->
            val id = d[DoctorsTable.id]
            (0 until days).flatMap { offset ->
                SecretariaSlots.generate(fromDate.plusDays(offset.toLong()), windows[id].orEmpty())
            }.mapNotNull { local ->
                val ms = local.toEpochMs(clinic.zone)
                if (ms < earliest || (id to ms) in taken) null
                else ms to SlotDto(id, d[DoctorsTable.name], d[SpecialtiesTable.name], local.format(LOCAL_ISO), SecretariaSlots.label(local))
            }
        }.sortedBy { it.first }.take(limit).map { it.second }
    }

    // ── marcar / remarcar / cancelar ─────────────────────────────────────────

    suspend fun book(
        clinic: ClinicInfo,
        doctorId: Long,
        patientId: Long,
        startLocal: LocalDateTime,
        createdBy: AppointmentCreator,
        sourceCallId: Long? = null,
        notes: String? = null,
    ): BookResult = database.dbQuery {
        bookInTx(clinic, doctorId, patientId, startLocal, createdBy, sourceCallId, notes, rescheduledFromId = null)
    }

    /**
     * Move um agendamento ativo para [newStartLocal] (cria o novo e marca o antigo como REMARCADO, na mesma
     * transação). Se [onlyPatientId] vier preenchido, só remarca se o agendamento for desse paciente.
     */
    suspend fun reschedule(
        clinic: ClinicInfo,
        appointmentId: Long,
        newStartLocal: LocalDateTime,
        onlyPatientId: Long?,
        createdBy: AppointmentCreator,
        sourceCallId: Long? = null,
    ): BookResult = database.dbQuery {
        val old = activeAppointment(clinic.id, appointmentId, onlyPatientId)
            ?: return@dbQuery BookResult.Fail("Não encontrei esse agendamento ativo.")
        val result = bookInTx(
            clinic, old[AppointmentsTable.doctorId], old[AppointmentsTable.patientId], newStartLocal,
            createdBy, sourceCallId, old[AppointmentsTable.notes], rescheduledFromId = appointmentId,
        )
        if (result is BookResult.Ok) {
            AppointmentsTable.update({ AppointmentsTable.id eq appointmentId }) {
                it[status] = AppointmentStatus.REMARCADO
                it[activeSlot] = null
            }
        }
        result
    }

    /** Cancela e libera o horário. Retorna false se não houver agendamento ativo (ou não for do paciente). */
    suspend fun cancel(clinicId: Long, appointmentId: Long, onlyPatientId: Long?): Boolean = database.dbQuery {
        if (activeAppointment(clinicId, appointmentId, onlyPatientId) == null) return@dbQuery false
        AppointmentsTable.update({ AppointmentsTable.id eq appointmentId }) {
            it[status] = AppointmentStatus.CANCELADO
            it[activeSlot] = null
        } > 0
    }

    private fun Transaction.activeAppointment(clinicId: Long, id: Long, onlyPatientId: Long?): ResultRow? =
        AppointmentsTable.selectAll()
            .where { (AppointmentsTable.id eq id) and (AppointmentsTable.clinicId eq clinicId) and AppointmentsTable.activeSlot.isNotNull() }
            .singleOrNull()
            ?.takeIf { onlyPatientId == null || it[AppointmentsTable.patientId] == onlyPatientId }

    private fun Transaction.bookInTx(
        clinic: ClinicInfo,
        doctorId: Long,
        patientId: Long,
        startLocal: LocalDateTime,
        createdBy: AppointmentCreator,
        sourceCallId: Long?,
        notes: String?,
        rescheduledFromId: Long?,
    ): BookResult {
        val doctor = (DoctorsTable innerJoin SpecialtiesTable).selectAll()
            .where { (DoctorsTable.id eq doctorId) and (DoctorsTable.clinicId eq clinic.id) and (DoctorsTable.active eq true) }
            .singleOrNull() ?: return BookResult.Fail("Médico não encontrado nesta clínica.")

        val windows = DoctorSchedulesTable.selectAll().where { DoctorSchedulesTable.doctorId eq doctorId }.map {
            ScheduleWindow(it[DoctorSchedulesTable.weekday], it[DoctorSchedulesTable.startMinute], it[DoctorSchedulesTable.endMinute], it[DoctorSchedulesTable.slotMinutes])
        }
        val window = SecretariaSlots.windowFor(startLocal, windows)
            ?: return BookResult.Fail("Esse horário não faz parte da agenda do médico.")

        val startsAt = startLocal.toEpochMs(clinic.zone)
        if (startsAt < clock() + minLeadMinutes * 60_000) return BookResult.Fail("Esse horário já passou ou está muito próximo.")

        val busy = AppointmentsTable.selectAll()
            .where { (AppointmentsTable.doctorId eq doctorId) and (AppointmentsTable.startsAt eq startsAt) and AppointmentsTable.activeSlot.isNotNull() }
            .any()
        if (busy) return BookResult.Fail("Esse horário acabou de ser ocupado.")

        val id = try {
            AppointmentsTable.insert {
                it[AppointmentsTable.clinicId] = clinic.id
                it[AppointmentsTable.doctorId] = doctorId
                it[AppointmentsTable.patientId] = patientId
                it[specialtyId] = doctor[DoctorsTable.specialtyId]
                it[AppointmentsTable.sourceCallId] = sourceCallId
                it[AppointmentsTable.rescheduledFromId] = rescheduledFromId
                it[AppointmentsTable.startsAt] = startsAt
                it[endsAt] = startsAt + window.slotMinutes * 60_000L
                it[status] = AppointmentStatus.AGENDADO
                it[AppointmentsTable.createdBy] = createdBy
                it[AppointmentsTable.notes] = notes
                it[createdAt] = clock()
                it[activeSlot] = 1
            }[AppointmentsTable.id]
        } catch (e: ExposedSQLException) {
            // Corrida: outro agendamento ocupou o horário entre a checagem e o insert (índice único).
            return BookResult.Fail("Esse horário acabou de ser ocupado.")
        }
        return BookResult.Ok(loadDto(id, clinic.zone))
    }

    // ── consultas ────────────────────────────────────────────────────────────

    suspend fun get(clinic: ClinicInfo, id: Long): AppointmentDto? = database.dbQuery {
        if (AppointmentsTable.selectAll().where { (AppointmentsTable.id eq id) and (AppointmentsTable.clinicId eq clinic.id) }.empty()) null
        else loadDto(id, clinic.zone)
    }

    /** Próximos agendamentos ativos da clínica (do mais próximo para o mais distante). */
    suspend fun upcoming(clinic: ClinicInfo, limit: Int): List<AppointmentDto> = database.dbQuery {
        baseQuery(clinic.zone)
            .where {
                (AppointmentsTable.clinicId eq clinic.id) and AppointmentsTable.activeSlot.isNotNull() and
                    (AppointmentsTable.startsAt greaterEq clock())
            }
            .orderBy(AppointmentsTable.startsAt)
            .limit(limit)
            .map { it.toDto(clinic.zone) }
    }

    /** Agenda da clínica entre dois instantes (inclui cancelados/remarcados para a tela de agenda), com filtros opcionais. */
    suspend fun listBetween(
        clinic: ClinicInfo,
        fromMs: Long,
        toMs: Long,
        doctorId: Long? = null,
        status: AppointmentStatus? = null,
        patientId: Long? = null,
    ): List<AppointmentDto> = database.dbQuery {
        baseQuery(clinic.zone)
            .where {
                var cond: Op<Boolean> = (AppointmentsTable.clinicId eq clinic.id) and
                    (AppointmentsTable.startsAt greaterEq fromMs) and (AppointmentsTable.startsAt less toMs)
                doctorId?.let { cond = cond and (AppointmentsTable.doctorId eq it) }
                status?.let { cond = cond and (AppointmentsTable.status eq it) }
                patientId?.let { cond = cond and (AppointmentsTable.patientId eq it) }
                cond
            }
            .orderBy(AppointmentsTable.startsAt)
            .map { it.toDto(clinic.zone) }
    }

    /** Todos os agendamentos do paciente (qualquer status), do mais recente para o mais antigo. */
    suspend fun forPatient(clinic: ClinicInfo, patientId: Long): List<AppointmentDto> = database.dbQuery {
        baseQuery(clinic.zone)
            .where { (AppointmentsTable.clinicId eq clinic.id) and (AppointmentsTable.patientId eq patientId) }
            .orderBy(AppointmentsTable.startsAt, SortOrder.DESC)
            .map { it.toDto(clinic.zone) }
    }

    /** Agendamentos feitos durante uma chamada (para a tela de detalhe da chamada). */
    suspend fun bySourceCall(clinic: ClinicInfo, callId: Long): List<AppointmentDto> = database.dbQuery {
        baseQuery(clinic.zone)
            .where { (AppointmentsTable.clinicId eq clinic.id) and (AppointmentsTable.sourceCallId eq callId) }
            .orderBy(AppointmentsTable.startsAt)
            .map { it.toDto(clinic.zone) }
    }

    /**
     * Confirma, conclui ou marca falta. Só vale para agendamentos ativos (agendado/confirmado); para cancelar ou
     * remarcar use [cancel] / [reschedule], que também liberam o horário.
     */
    suspend fun setStatus(clinic: ClinicInfo, id: Long, newStatus: AppointmentStatus): ServiceResult<AppointmentDto> {
        if (newStatus !in setOf(AppointmentStatus.CONFIRMADO, AppointmentStatus.CONCLUIDO, AppointmentStatus.FALTOU)) {
            return invalid("Status inválido. Use confirmado, concluido ou faltou (para cancelar/remarcar use as rotas próprias).")
        }
        return database.dbQuery {
            val row = AppointmentsTable.selectAll().where { (AppointmentsTable.id eq id) and (AppointmentsTable.clinicId eq clinic.id) }.singleOrNull()
                ?: return@dbQuery notFound("Agendamento não encontrado.")
            if (row[AppointmentsTable.status] !in setOf(AppointmentStatus.AGENDADO, AppointmentStatus.CONFIRMADO)) {
                return@dbQuery conflict("Esse agendamento já foi ${row[AppointmentsTable.status].name.lowercase()} e não pode mudar de status.")
            }
            AppointmentsTable.update({ AppointmentsTable.id eq id }) { it[status] = newStatus }
            ServiceResult.Ok(loadDto(id, clinic.zone))
        }
    }

    /** Agendamentos futuros e ativos dos pacientes informados (usado pela IA para remarcar/cancelar). */
    suspend fun upcomingForPatients(clinic: ClinicInfo, patientIds: List<Long>): List<AppointmentDto> {
        if (patientIds.isEmpty()) return emptyList()
        return database.dbQuery {
            baseQuery(clinic.zone)
                .where {
                    (AppointmentsTable.clinicId eq clinic.id) and (AppointmentsTable.patientId inList patientIds) and
                        AppointmentsTable.activeSlot.isNotNull() and (AppointmentsTable.startsAt greaterEq clock())
                }
                .orderBy(AppointmentsTable.startsAt)
                .map { it.toDto(clinic.zone) }
        }
    }

    suspend fun countCreatedBetween(clinicId: Long, fromMs: Long, toMs: Long): Long = database.dbQuery {
        AppointmentsTable.selectAll()
            .where {
                (AppointmentsTable.clinicId eq clinicId) and (AppointmentsTable.createdAt greaterEq fromMs) and
                    (AppointmentsTable.createdAt less toMs) and AppointmentsTable.activeSlot.isNotNull()
            }
            .count()
    }

    /** Joins explícitos: `specialty_id` existe em appointments e em doctors, então o join automático seria ambíguo. */
    private fun baseQuery(@Suppress("UNUSED_PARAMETER") zone: ZoneId) =
        AppointmentsTable
            .join(DoctorsTable, JoinType.INNER, AppointmentsTable.doctorId, DoctorsTable.id)
            .join(SpecialtiesTable, JoinType.INNER, AppointmentsTable.specialtyId, SpecialtiesTable.id)
            .join(PatientsTable, JoinType.INNER, AppointmentsTable.patientId, PatientsTable.id)
            .selectAll()

    private fun Transaction.loadDto(id: Long, zone: ZoneId): AppointmentDto =
        baseQuery(zone).where { AppointmentsTable.id eq id }.single().toDto(zone)

    private fun ResultRow.toDto(zone: ZoneId) = AppointmentDto(
        id = this[AppointmentsTable.id],
        startsAt = this[AppointmentsTable.startsAt],
        startLocal = this[AppointmentsTable.startsAt].toLocalIso(zone),
        endLocal = this[AppointmentsTable.endsAt].toLocalIso(zone),
        doctorId = this[DoctorsTable.id],
        doctorName = this[DoctorsTable.name],
        specialty = this[SpecialtiesTable.name],
        patientId = this[PatientsTable.id],
        patientName = this[PatientsTable.name],
        patientPhone = this[PatientsTable.phone],
        status = this[AppointmentsTable.status].name.lowercase(),
        createdBy = this[AppointmentsTable.createdBy].name.lowercase(),
    )
}
