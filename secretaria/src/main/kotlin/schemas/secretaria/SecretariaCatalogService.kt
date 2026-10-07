package schemas.secretaria

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import java.util.Locale

/** Especialidades, médicos e agenda semanal (Configurações > Médicos e horários). */
class SecretariaCatalogService(
    private val database: Database,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Sugestões de especialidades: as da própria clínica + um catálogo comum (não vaza dados de outras clínicas). */
    suspend fun specialties(clinicId: Long): List<SpecialtyDto> = database.dbQuery {
        val own = (DoctorsTable innerJoin SpecialtiesTable).selectAll().where { DoctorsTable.clinicId eq clinicId }
            .map { SpecialtyDto(it[SpecialtiesTable.id], it[SpecialtiesTable.name]) }.distinctBy { it.id }
        val ownNames = own.map { it.name.lowercase(Locale.ROOT) }.toSet()
        val common = COMMON_SPECIALTIES.filter { it.lowercase(Locale.ROOT) !in ownNames }.map { SpecialtyDto(0, it) }
        (own + common).sortedBy { it.name }
    }

    suspend fun doctors(clinicId: Long, includeInactive: Boolean): List<DoctorDetailDto> = database.dbQuery {
        (DoctorsTable innerJoin SpecialtiesTable).selectAll()
            .where { if (includeInactive) DoctorsTable.clinicId eq clinicId else (DoctorsTable.clinicId eq clinicId) and (DoctorsTable.active eq true) }
            .orderBy(DoctorsTable.name)
            .map { it.toDetail() }
    }

    suspend fun createDoctor(clinicId: Long, request: DoctorUpsertRequest): ServiceResult<DoctorDetailDto> {
        val error = validate(request)
        if (error != null) return error
        return database.dbQuery {
            val specialtyId = ensureSpecialty(request.specialty)
            val id = DoctorsTable.insert {
                it[DoctorsTable.clinicId] = clinicId
                it[DoctorsTable.specialtyId] = specialtyId
                it[name] = request.name.trim()
                it[crm] = request.crm?.trim()?.ifEmpty { null }
            }[DoctorsTable.id]
            writeSchedule(id, DEFAULT_WINDOWS) // sem agenda a IA não teria horários para oferecer
            ServiceResult.Ok(loadDoctor(clinicId, id)!!)
        }
    }

    suspend fun updateDoctor(clinicId: Long, doctorId: Long, request: DoctorUpsertRequest): ServiceResult<DoctorDetailDto> {
        val error = validate(request)
        if (error != null) return error
        return database.dbQuery {
            loadDoctor(clinicId, doctorId) ?: return@dbQuery notFound("Médico não encontrado.")
            if (request.active == false) {
                val pending = futureAppointments(doctorId)
                if (pending > 0) return@dbQuery conflict("O médico tem $pending consulta(s) futura(s). Cancele ou remarque antes de desativar.")
            }
            val specialtyId = ensureSpecialty(request.specialty)
            DoctorsTable.update({ (DoctorsTable.id eq doctorId) and (DoctorsTable.clinicId eq clinicId) }) {
                it[name] = request.name.trim()
                it[DoctorsTable.specialtyId] = specialtyId
                it[crm] = request.crm?.trim()?.ifEmpty { null }
                request.active?.let { v -> it[active] = v }
            }
            ServiceResult.Ok(loadDoctor(clinicId, doctorId)!!)
        }
    }

    /** Desativa o médico (ele deixa de receber consultas; o histórico permanece). */
    suspend fun deactivateDoctor(clinicId: Long, doctorId: Long): ServiceResult<DoctorDetailDto> {
        val current = doctors(clinicId, includeInactive = true).firstOrNull { it.id == doctorId } ?: return notFound("Médico não encontrado.")
        return updateDoctor(clinicId, doctorId, DoctorUpsertRequest(current.name, current.specialty, current.crm, active = false))
    }

    suspend fun schedule(clinicId: Long, doctorId: Long): ServiceResult<List<ScheduleWindowDto>> = database.dbQuery {
        loadDoctor(clinicId, doctorId) ?: return@dbQuery notFound("Médico não encontrado.")
        ServiceResult.Ok(readSchedule(doctorId))
    }

    /**
     * Substitui toda a agenda semanal do médico. Consultas já marcadas fora das novas janelas NÃO são
     * alteradas — apenas deixam de ser oferecidas novos horários ali.
     */
    suspend fun replaceSchedule(clinicId: Long, doctorId: Long, windows: List<ScheduleWindowDto>): ServiceResult<List<ScheduleWindowDto>> {
        val parsed = parseWindows(windows).let { if (it is ServiceResult.Err) return it else (it as ServiceResult.Ok).value }
        return database.dbQuery {
            loadDoctor(clinicId, doctorId) ?: return@dbQuery notFound("Médico não encontrado.")
            writeSchedule(doctorId, parsed)
            ServiceResult.Ok(readSchedule(doctorId))
        }
    }

    // ── validação ────────────────────────────────────────────────────────────

    private fun validate(request: DoctorUpsertRequest): ServiceResult.Err? = when {
        request.name.trim().length !in 3..120 -> invalid("Nome do médico inválido (3 a 120 caracteres).")
        request.specialty.trim().length !in 2..80 -> invalid("Especialidade inválida (2 a 80 caracteres).")
        (request.crm?.trim()?.length ?: 0) > 20 -> invalid("CRM deve ter no máximo 20 caracteres.")
        else -> null
    }

    private fun parseWindows(windows: List<ScheduleWindowDto>): ServiceResult<List<ScheduleWindow>> {
        if (windows.size > 70) return invalid("Janelas demais (máximo 70).")
        val parsed = windows.map { w ->
            val start = parseHm(w.start) ?: return invalid("Horário inválido em '${w.start}' (use HH:mm).")
            val end = parseHm(w.end) ?: return invalid("Horário inválido em '${w.end}' (use HH:mm).")
            if (w.weekday !in 0..6) return invalid("Dia da semana inválido (0 = domingo ... 6 = sábado).")
            if (end <= start) return invalid("O fim (${w.end}) deve ser depois do início (${w.start}).")
            if (w.slotMinutes !in 5..240) return invalid("A duração da consulta deve ficar entre 5 e 240 minutos.")
            if (end - start < w.slotMinutes) return invalid("A janela ${w.start}–${w.end} é menor que a duração da consulta.")
            ScheduleWindow(w.weekday, start, end, w.slotMinutes)
        }
        for ((_, day) in parsed.groupBy { it.weekday }) {
            val sorted = day.sortedBy { it.startMinute }
            if (sorted.zipWithNext().any { (a, b) -> b.startMinute < a.endMinute }) return invalid("Há janelas sobrepostas no mesmo dia.")
        }
        return ServiceResult.Ok(parsed)
    }

    private fun parseHm(text: String): Int? {
        val m = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$").matchEntire(text.trim())
        if (m != null) return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
        return if (text.trim() == "24:00") 24 * 60 else null
    }

    // ── internos ─────────────────────────────────────────────────────────────

    private fun Transaction.ensureSpecialty(name: String): Long {
        val trimmed = name.trim()
        return SpecialtiesTable.selectAll().where { SpecialtiesTable.name eq trimmed }.singleOrNull()?.get(SpecialtiesTable.id)
            ?: SpecialtiesTable.insert { it[SpecialtiesTable.name] = trimmed }[SpecialtiesTable.id]
    }

    private fun Transaction.loadDoctor(clinicId: Long, doctorId: Long): DoctorDetailDto? =
        (DoctorsTable innerJoin SpecialtiesTable).selectAll()
            .where { (DoctorsTable.id eq doctorId) and (DoctorsTable.clinicId eq clinicId) }.singleOrNull()?.toDetail()

    private fun Transaction.futureAppointments(doctorId: Long): Long =
        AppointmentsTable.selectAll()
            .where { (AppointmentsTable.doctorId eq doctorId) and AppointmentsTable.activeSlot.isNotNull() and (AppointmentsTable.startsAt greaterEq clock()) }
            .count()

    private fun Transaction.readSchedule(doctorId: Long): List<ScheduleWindowDto> =
        DoctorSchedulesTable.selectAll().where { DoctorSchedulesTable.doctorId eq doctorId }
            .orderBy(DoctorSchedulesTable.weekday).orderBy(DoctorSchedulesTable.startMinute)
            .map {
                ScheduleWindowDto(it[DoctorSchedulesTable.weekday], hm(it[DoctorSchedulesTable.startMinute]), hm(it[DoctorSchedulesTable.endMinute]), it[DoctorSchedulesTable.slotMinutes])
            }

    private fun Transaction.writeSchedule(doctorId: Long, windows: List<ScheduleWindow>) {
        DoctorSchedulesTable.deleteWhere { DoctorSchedulesTable.doctorId eq doctorId }
        for (w in windows) {
            DoctorSchedulesTable.insert {
                it[DoctorSchedulesTable.doctorId] = doctorId
                it[weekday] = w.weekday
                it[startMinute] = w.startMinute
                it[endMinute] = w.endMinute
                it[slotMinutes] = w.slotMinutes
            }
        }
    }

    private fun hm(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

    private fun ResultRow.toDetail() = DoctorDetailDto(
        this[DoctorsTable.id], this[DoctorsTable.name], this[SpecialtiesTable.name], this[DoctorsTable.crm], this[DoctorsTable.active],
    )

    private companion object {
        val COMMON_SPECIALTIES = listOf(
            "Clínica geral", "Cardiologia", "Dermatologia", "Endocrinologia", "Fisioterapia", "Ginecologia", "Neurologia",
            "Nutrição", "Odontologia", "Oftalmologia", "Ortopedia", "Otorrinolaringologia", "Pediatria", "Psicologia",
            "Psiquiatria", "Urologia",
        )
    }
}
