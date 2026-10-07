package schemas.secretaria

import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.lowerCase

/** Cadastro de pacientes (menu Pacientes), incluindo a exclusão para atender a LGPD. */
class SecretariaPatientService(
    private val database: Database,
    private val appointments: SecretariaAppointmentService,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun page(clinicId: Long, query: String?, limit: Int, offset: Long): PatientPageDto = database.dbQuery {
        val q = query?.replace("%", "")?.replace("_", "")?.trim().orEmpty()
        val digits = q.filter { it.isDigit() }
        val where: Op<Boolean> = if (q.isEmpty()) PatientsTable.clinicId eq clinicId
        else (PatientsTable.clinicId eq clinicId) and (
            (PatientsTable.name.lowerCase() like "%${q.lowercase()}%") or (if (digits.length >= 3) PatientsTable.phone like "%$digits%" else Op.FALSE)
            )
        val total = PatientsTable.selectAll().where(where).count()
        val items = PatientsTable.selectAll().where(where).orderBy(PatientsTable.name).limit(limit).offset(offset).map { it.toDto() }
        PatientPageDto(items, total)
    }

    suspend fun detail(clinic: ClinicInfo, patientId: Long): ServiceResult<PatientDetailDto> {
        val patient = database.dbQuery { find(clinic.id, patientId) } ?: return notFound("Paciente não encontrado.")
        val all = appointments.forPatient(clinic, patientId)
        val now = clock()
        val upcoming = all.filter { it.startsAt >= now && it.status in setOf("agendado", "confirmado") }.sortedBy { it.startsAt }
        val history = all.filter { it !in upcoming }.sortedByDescending { it.startsAt }
        val calls = database.dbQuery {
            CallsTable.selectAll().where { (CallsTable.clinicId eq clinic.id) and (CallsTable.patientId eq patientId) }.count()
        }
        return ServiceResult.Ok(PatientDetailDto(patient, upcoming, history, calls))
    }

    suspend fun create(clinicId: Long, request: UpsertPatientRequest): ServiceResult<PatientDto> {
        val v = validate(request)
        if (v is ServiceResult.Err) return v
        val (name, phone, email) = (v as ServiceResult.Ok).value
        return database.dbQuery {
            if (phone != null) {
                val duplicate = PatientsTable.selectAll().where { (PatientsTable.clinicId eq clinicId) and (PatientsTable.phone eq phone) }
                    .any { namesMatch(it[PatientsTable.name], name) }
                if (duplicate) return@dbQuery conflict("Já existe um paciente com esse nome e telefone.")
            }
            val id = PatientsTable.insert {
                it[PatientsTable.clinicId] = clinicId
                it[PatientsTable.name] = name
                it[PatientsTable.phone] = phone
                it[PatientsTable.email] = email
                it[consentAt] = clock()
                it[createdAt] = clock()
            }[PatientsTable.id]
            ServiceResult.Ok(find(clinicId, id)!!)
        }
    }

    suspend fun update(clinicId: Long, patientId: Long, request: UpsertPatientRequest): ServiceResult<PatientDto> {
        val v = validate(request)
        if (v is ServiceResult.Err) return v
        val (name, phone, email) = (v as ServiceResult.Ok).value
        return database.dbQuery {
            find(clinicId, patientId) ?: return@dbQuery notFound("Paciente não encontrado.")
            PatientsTable.update({ (PatientsTable.id eq patientId) and (PatientsTable.clinicId eq clinicId) }) {
                it[PatientsTable.name] = name
                it[PatientsTable.phone] = phone
                it[PatientsTable.email] = email
            }
            ServiceResult.Ok(find(clinicId, patientId)!!)
        }
    }

    /**
     * Direito de eliminação (LGPD): remove nome, telefone, e-mail e as transcrições/resumos das chamadas do
     * paciente, e cancela as consultas futuras. Os registros de agendamento e as métricas permanecem, já
     * anonimizados ("Paciente removido"). Arquivos de gravação, se houver, precisam ser apagados do armazenamento.
     */
    suspend fun anonymize(clinicId: Long, patientId: Long): ServiceResult<Unit> = database.dbQuery {
        find(clinicId, patientId) ?: return@dbQuery notFound("Paciente não encontrado.")
        AppointmentsTable.update({
            (AppointmentsTable.patientId eq patientId) and AppointmentsTable.activeSlot.isNotNull() and (AppointmentsTable.startsAt greaterEq clock())
        }) {
            it[status] = AppointmentStatus.CANCELADO
            it[activeSlot] = null
        }
        val callIds = CallsTable.selectAll().where { (CallsTable.clinicId eq clinicId) and (CallsTable.patientId eq patientId) }.map { it[CallsTable.id] }
        if (callIds.isNotEmpty()) {
            CallMessagesTable.deleteWhere { CallMessagesTable.callId inList callIds }
            CallsTable.update({ CallsTable.id inList callIds }) {
                it[callerName] = null
                it[callerPhone] = null
                it[subject] = null
                it[summary] = null
                it[recordingUrl] = null
            }
        }
        PatientsTable.update({ (PatientsTable.id eq patientId) and (PatientsTable.clinicId eq clinicId) }) {
            it[name] = "Paciente removido"
            it[phone] = null
            it[email] = null
            it[consentAt] = null
        }
        ServiceResult.Ok(Unit)
    }

    // ── internos ─────────────────────────────────────────────────────────────

    private fun validate(request: UpsertPatientRequest): ServiceResult<Triple<String, String?, String?>> {
        val name = request.name.trim()
        if (name.length !in 3..120) return invalid("Nome inválido (3 a 120 caracteres).")
        val phone = request.phone?.takeIf { it.isNotBlank() }?.let { normalizePhone(it) ?: return invalid("Telefone inválido. Use DDD + número.") }
        val email = request.email?.trim()?.takeIf { it.isNotEmpty() }
        if (email != null && (!email.contains('@') || email.length > 160)) return invalid("E-mail inválido.")
        return ServiceResult.Ok(Triple(name, phone, email))
    }

    private fun Transaction.find(clinicId: Long, id: Long): PatientDto? =
        PatientsTable.selectAll().where { (PatientsTable.id eq id) and (PatientsTable.clinicId eq clinicId) }.singleOrNull()?.toDto()

    private fun ResultRow.toDto() = PatientDto(this[PatientsTable.id], this[PatientsTable.name], this[PatientsTable.phone], this[PatientsTable.email], this[PatientsTable.createdAt])
}
