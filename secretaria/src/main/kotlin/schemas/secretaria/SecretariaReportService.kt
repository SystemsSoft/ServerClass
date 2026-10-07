package schemas.secretaria

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Relatórios por período (menu Relatórios) e exportação CSV. */
class SecretariaReportService(private val database: Database) {

    suspend fun summary(clinic: ClinicInfo, from: LocalDate, to: LocalDate): ServiceResult<ReportDto> {
        validate(from, to)?.let { return it }
        val zone = clinic.zone
        val fromMs = from.atStartOfDay().toEpochMs(zone)
        val toMs = to.plusDays(1).atStartOfDay().toEpochMs(zone)

        return database.dbQuery {
            val calls = CallsTable.selectAll()
                .where { (CallsTable.clinicId eq clinic.id) and (CallsTable.startedAt greaterEq fromMs) and (CallsTable.startedAt less toMs) }.toList()
            val ended = calls.filter { it[CallsTable.state] == CallState.ENCERRADA }
            val durations = ended.mapNotNull { it[CallsTable.durationSeconds] }

            val appts = (AppointmentsTable
                .join(DoctorsTable, JoinType.INNER, AppointmentsTable.doctorId, DoctorsTable.id)
                .join(SpecialtiesTable, JoinType.INNER, AppointmentsTable.specialtyId, SpecialtiesTable.id))
                .selectAll()
                .where { (AppointmentsTable.clinicId eq clinic.id) and (AppointmentsTable.createdAt greaterEq fromMs) and (AppointmentsTable.createdAt less toMs) }
                .toList()
            val workload = appts.filter { it[AppointmentsTable.status].occupiesSlot } // exclui cancelados/remarcados

            val callsByHour = IntArray(24).also { hours -> calls.forEach { hours[it[CallsTable.startedAt].toLocalDateTime(zone).hour]++ } }
            val callsPerDay = calls.groupBy { it[CallsTable.startedAt].toLocalDateTime(zone).toLocalDate() }
            val apptsPerDay = appts.groupBy { it[AppointmentsTable.createdAt].toLocalDateTime(zone).toLocalDate() }
            val perDay = (0..ChronoUnit.DAYS.between(from, to)).map { from.plusDays(it) }.map { day ->
                val dayCalls = callsPerDay[day].orEmpty()
                val seconds = dayCalls.filter { it[CallsTable.state] == CallState.ENCERRADA }.sumOf { it[CallsTable.durationSeconds] ?: 0 }
                DayCountDto(day.toString(), dayCalls.size, Math.round(seconds / 6.0) / 10.0, apptsPerDay[day].orEmpty().size)
            }

            ServiceResult.Ok(
                ReportDto(
                    from = from.toString(),
                    to = to.toString(),
                    totalCalls = calls.size,
                    totalMinutes = Math.round(durations.sum() / 6.0) / 10.0,
                    avgSeconds = if (durations.isEmpty()) 0 else Math.round(durations.average()).toInt(),
                    appointmentsCreated = appts.size,
                    appointmentsByAi = appts.count { it[AppointmentsTable.createdBy] == AppointmentCreator.IA },
                    conversionRate = if (ended.isEmpty()) 0.0 else ended.count { it[CallsTable.outcome] == CallOutcome.AGENDADA }.toDouble() / ended.size,
                    byOutcome = counts(ended.mapNotNull { it[CallsTable.outcome]?.name?.lowercase() }),
                    byIntent = counts(calls.mapNotNull { it[CallsTable.intent]?.name?.lowercase() }),
                    bySpecialty = counts(workload.map { it[SpecialtiesTable.name] }),
                    byDoctor = counts(workload.map { it[DoctorsTable.name] }),
                    byStatus = counts(appts.map { it[AppointmentsTable.status].name.lowercase() }),
                    callsByHour = callsByHour.toList(),
                    perDay = perDay,
                ),
            )
        }
    }

    /** CSV de chamadas (separador `;` e texto à prova de fórmulas, para abrir direto no Excel em português). */
    suspend fun callsCsv(clinic: ClinicInfo, from: LocalDate, to: LocalDate): ServiceResult<String> {
        validate(from, to)?.let { return it }
        val fromMs = from.atStartOfDay().toEpochMs(clinic.zone)
        val toMs = to.plusDays(1).atStartOfDay().toEpochMs(clinic.zone)
        return database.dbQuery {
            val rows = CallsTable.join(PatientsTable, JoinType.LEFT, CallsTable.patientId, PatientsTable.id).selectAll()
                .where { (CallsTable.clinicId eq clinic.id) and (CallsTable.startedAt greaterEq fromMs) and (CallsTable.startedAt less toMs) }
                .orderBy(CallsTable.startedAt).limit(MAX_CSV_ROWS)
            ServiceResult.Ok(
                csv(
                    listOf("id", "inicio", "duracao_segundos", "paciente", "telefone", "assunto", "resultado", "intencao", "canal"),
                    rows.toList().map {
                        listOf(
                            it[CallsTable.id].toString(), it[CallsTable.startedAt].toLocalIso(clinic.zone), it[CallsTable.durationSeconds]?.toString().orEmpty(),
                            it.getOrNull(PatientsTable.name) ?: it[CallsTable.callerName].orEmpty(), it.getOrNull(PatientsTable.phone) ?: it[CallsTable.callerPhone].orEmpty(),
                            it[CallsTable.subject].orEmpty(), it[CallsTable.outcome]?.name?.lowercase() ?: it[CallsTable.state].name.lowercase(),
                            it[CallsTable.intent]?.name?.lowercase().orEmpty(), it[CallsTable.channel].name.lowercase(),
                        )
                    },
                ),
            )
        }
    }

    suspend fun appointmentsCsv(clinic: ClinicInfo, from: LocalDate, to: LocalDate): ServiceResult<String> {
        validate(from, to)?.let { return it }
        val fromMs = from.atStartOfDay().toEpochMs(clinic.zone)
        val toMs = to.plusDays(1).atStartOfDay().toEpochMs(clinic.zone)
        return database.dbQuery {
            val rows = (AppointmentsTable
                .join(DoctorsTable, JoinType.INNER, AppointmentsTable.doctorId, DoctorsTable.id)
                .join(SpecialtiesTable, JoinType.INNER, AppointmentsTable.specialtyId, SpecialtiesTable.id)
                .join(PatientsTable, JoinType.INNER, AppointmentsTable.patientId, PatientsTable.id))
                .selectAll()
                .where { (AppointmentsTable.clinicId eq clinic.id) and (AppointmentsTable.startsAt greaterEq fromMs) and (AppointmentsTable.startsAt less toMs) }
                .orderBy(AppointmentsTable.startsAt).limit(MAX_CSV_ROWS)
            ServiceResult.Ok(
                csv(
                    listOf("id", "inicio", "fim", "medico", "especialidade", "paciente", "telefone", "status", "origem", "criado_em"),
                    rows.toList().map {
                        listOf(
                            it[AppointmentsTable.id].toString(), it[AppointmentsTable.startsAt].toLocalIso(clinic.zone), it[AppointmentsTable.endsAt].toLocalIso(clinic.zone),
                            it[DoctorsTable.name], it[SpecialtiesTable.name], it[PatientsTable.name], it[PatientsTable.phone].orEmpty(),
                            it[AppointmentsTable.status].name.lowercase(), it[AppointmentsTable.createdBy].name.lowercase(), it[AppointmentsTable.createdAt].toLocalIso(clinic.zone),
                        )
                    },
                ),
            )
        }
    }

    // ── internos ─────────────────────────────────────────────────────────────

    private fun validate(from: LocalDate, to: LocalDate): ServiceResult.Err? = when {
        to.isBefore(from) -> invalid("A data final deve ser igual ou posterior à inicial.")
        ChronoUnit.DAYS.between(from, to) > 366 -> invalid("Período máximo de 1 ano.")
        else -> null
    }

    private fun counts(values: List<String>) =
        values.groupingBy { it }.eachCount().map { LabelCountDto(it.key, it.value) }.sortedWith(compareByDescending<LabelCountDto> { it.count }.thenBy { it.label })

    companion object {
        private const val MAX_CSV_ROWS = 50_000

        /** Aspas duplicadas e prefixo `'` em células que começariam uma fórmula (=, +, -, @) — evita injeção de planilha. */
        internal fun cell(raw: String): String {
            val safe = if (raw.isNotEmpty() && raw[0] in "=+-@\t\r") "'$raw" else raw
            return if (safe.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
        }

        internal fun csv(header: List<String>, rows: Iterable<List<String>>): String =
            buildString {
                append(header.joinToString(";")).append("\r\n")
                rows.forEach { append(it.joinToString(";") { c -> cell(c) }).append("\r\n") }
            }
    }
}
