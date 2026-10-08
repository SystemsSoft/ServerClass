package schemas.secretaria

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

/** Estado de uma chamada recém-aberta. */
data class StartedCall(val id: Long, val startedAt: Long)

class SecretariaCallService(
    private val database: Database,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pricing: AiPricing = AiPricing.fromConfig(),
) {

    init {
        // Se o servidor caiu no meio de chamadas, elas ficariam "em andamento" para sempre.
        runCatching { kotlinx.coroutines.runBlocking { closeStale(olderThanMs = 3_600_000) } }
    }

    suspend fun start(clinicId: Long, channel: CallChannel, aiModel: String?): StartedCall = database.dbQuery {
        val now = clock()
        val id = CallsTable.insert {
            it[CallsTable.clinicId] = clinicId
            it[CallsTable.channel] = channel
            it[state] = CallState.EM_ANDAMENTO
            it[startedAt] = now
            it[CallsTable.aiModel] = aiModel
        }[CallsTable.id]
        StartedCall(id, now)
    }

    suspend fun activeCount(clinicId: Long): Long = database.dbQuery {
        CallsTable.selectAll().where { (CallsTable.clinicId eq clinicId) and (CallsTable.state eq CallState.EM_ANDAMENTO) }.count()
    }

    /** Liga a chamada ao paciente identificado na conversa. */
    suspend fun attachPatient(callId: Long, patientId: Long, name: String, phone: String) {
        database.dbQuery {
            CallsTable.update({ CallsTable.id eq callId }) {
                it[CallsTable.patientId] = patientId
                it[callerName] = name.take(120)
                it[callerPhone] = phone
            }
        }
    }

    /** Consumo da Gemini na ligação (tokens por tipo) e a chave usada; base do custo real. */
    suspend fun setUsage(callId: Long, usage: TokenUsage, key: String?) {
        database.dbQuery {
            CallsTable.update({ CallsTable.id eq callId }) {
                it[aiInputAudioTokens] = usage.inputAudio
                it[aiInputTextTokens] = usage.inputText
                it[aiOutputAudioTokens] = usage.outputAudio
                it[aiOutputTextTokens] = usage.outputText
                it[aiUsageReports] = usage.reports
                it[aiKey] = key
            }
        }
    }

    fun usdBrl(): Double = pricing.usdBrl

    /** Consumo e custo da IA por chave, de todas as clínicas, nas ligações encerradas entre [fromMs] e [toMs]. */
    suspend fun usageByKey(fromMs: Long, toMs: Long): Pair<List<KeyUsageDto>, Int> = database.dbQuery {
        val rows = CallsTable.selectAll()
            .where { (CallsTable.startedAt greaterEq fromMs) and (CallsTable.startedAt less toMs) and (CallsTable.state eq CallState.ENCERRADA) }
            .toList()
        val measured = rows.filter { it.aiUsage() != null }
        val byKey = measured.groupBy { it[CallsTable.aiKey] ?: "desconhecida" }.map { (key, list) ->
            val free = isFreeKey(key)
            val cost = list.sumOf { pricing.costBrl(it.aiUsage()!!, free) }
            val minutes = list.sumOf { it[CallsTable.durationSeconds] ?: 0 } / 60.0
            KeyUsageDto(
                key = key, free = free, calls = list.size, minutes = Math.round(minutes * 10) / 10.0,
                tokens = list.sumOf { it.aiUsage()!!.total }, costBrl = Math.round(cost * 100) / 100.0,
                costPerMinuteBrl = if (minutes > 0) Math.round(cost / minutes * 1000) / 1000.0 else null,
            )
        }.sortedBy { it.key }
        byKey to (rows.size - measured.size)
    }

    suspend fun setDoctor(callId: Long, doctorId: Long) {
        database.dbQuery { CallsTable.update({ CallsTable.id eq callId }) { it[CallsTable.doctorId] = doctorId } }
    }

    suspend fun setOutcome(callId: Long, outcome: CallOutcome, intent: CallIntent) {
        database.dbQuery {
            CallsTable.update({ CallsTable.id eq callId }) {
                it[CallsTable.outcome] = outcome
                it[CallsTable.intent] = intent
            }
        }
    }

    /** Assunto (coluna "Assunto") e resumo (texto da ligação em andamento), escritos pela IA. */
    suspend fun setSubject(callId: Long, subject: String?, summary: String?, intent: CallIntent?) {
        database.dbQuery {
            CallsTable.update({ CallsTable.id eq callId }) {
                subject?.let { s -> it[CallsTable.subject] = s.take(160) }
                summary?.let { s -> it[CallsTable.summary] = s.take(255) }
                intent?.let { i -> it[CallsTable.intent] = i }
            }
        }
    }

    suspend fun appendMessage(callId: Long, speaker: Speaker, content: String, offsetMs: Long) {
        if (content.isBlank()) return
        database.dbQuery {
            CallMessagesTable.insert {
                it[CallMessagesTable.callId] = callId
                it[CallMessagesTable.speaker] = speaker
                it[CallMessagesTable.content] = content.trim()
                it[CallMessagesTable.offsetMs] = offsetMs
            }
        }
    }

    /**
     * Encerra a chamada (idempotente): grava fim, duração e o resultado — o que a IA definiu, ou
     * FINALIZADA se nada foi marcado/remarcado. Retorna false se já estava encerrada ou não existe.
     */
    suspend fun end(callId: Long): Boolean = database.dbQuery {
        val row = CallsTable.selectAll().where { (CallsTable.id eq callId) and (CallsTable.state eq CallState.EM_ANDAMENTO) }
            .singleOrNull() ?: return@dbQuery false
        val now = clock()
        CallsTable.update({ (CallsTable.id eq callId) and (CallsTable.state eq CallState.EM_ANDAMENTO) }) {
            it[state] = CallState.ENCERRADA
            it[endedAt] = now
            it[durationSeconds] = ((now - row[CallsTable.startedAt]) / 1000).toInt().coerceAtLeast(0)
            it[outcome] = row[CallsTable.outcome] ?: CallOutcome.FINALIZADA
            if (row[CallsTable.intent] == null) it[intent] = CallIntent.OUTRO
        } > 0
    }

    suspend fun closeStale(olderThanMs: Long): Int {
        val ids = database.dbQuery {
            CallsTable.selectAll()
                .where { (CallsTable.state eq CallState.EM_ANDAMENTO) and (CallsTable.startedAt less clock() - olderThanMs) }
                .map { it[CallsTable.id] }
        }
        return ids.count { end(it) }
    }

    // ── leitura (dashboard) ──────────────────────────────────────────────────

    /** Chamada em andamento mais recente da clínica, para o card "Ligação em andamento". */
    suspend fun activeCall(clinicId: Long): ActiveCallDto? = database.dbQuery {
        CallsTable.selectAll()
            .where { (CallsTable.clinicId eq clinicId) and (CallsTable.state eq CallState.EM_ANDAMENTO) }
            .orderBy(CallsTable.startedAt, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.let {
                val patient = it[CallsTable.patientId]?.let { pid -> PatientsTable.selectAll().where { PatientsTable.id eq pid }.singleOrNull() }
                val profileId = patient?.get(PatientsTable.profileId)
                ActiveCallDto(
                    id = it[CallsTable.id],
                    phone = patient?.get(PatientsTable.phone) ?: it[CallsTable.callerPhone],
                    description = it[CallsTable.summary] ?: it[CallsTable.subject] ?: "Atendimento em andamento",
                    startedAt = it[CallsTable.startedAt],
                    elapsedSeconds = (clock() - it[CallsTable.startedAt]) / 1000,
                    patientId = it[CallsTable.patientId],
                    patientName = patient?.get(PatientsTable.name) ?: it[CallsTable.callerName],
                    cpf = patient?.get(PatientsTable.cpf),
                    email = patient?.get(PatientsTable.email),
                    healthPlan = patient?.get(PatientsTable.healthPlan),
                    hasPhoto = profileId != null && profileId in profilesWithPhoto(listOf(profileId)),
                )
            }
    }

    /**
     * Página de chamadas com filtros. [status]: agendada | remarcada | finalizada (resultado) ou em_andamento (estado).
     * [query] procura no nome/telefone do paciente e no assunto. [fromMs]/[toMs]: intervalo de início (UTC).
     */
    suspend fun page(
        clinicId: Long,
        limit: Int,
        offset: Long,
        status: String? = null,
        query: String? = null,
        fromMs: Long? = null,
        toMs: Long? = null,
        onlyEnded: Boolean = false,
    ): CallPageDto = database.dbQuery {
        var cond: Op<Boolean> = CallsTable.clinicId eq clinicId
        if (onlyEnded) cond = cond and (CallsTable.state eq CallState.ENCERRADA)
        when (status?.lowercase()) {
            null, "" -> {}
            "em_andamento" -> cond = cond and (CallsTable.state eq CallState.EM_ANDAMENTO)
            else -> {
                val outcome = CallOutcome.entries.firstOrNull { it.name.equals(status, ignoreCase = true) }
                cond = if (outcome == null) cond and Op.FALSE else cond and (CallsTable.outcome eq outcome)
            }
        }
        fromMs?.let { cond = cond and (CallsTable.startedAt greaterEq it) }
        toMs?.let { cond = cond and (CallsTable.startedAt less it) }
        val q = query?.replace("%", "")?.replace("_", "")?.trim().orEmpty()
        if (q.isNotEmpty()) {
            val digits = q.filter { it.isDigit() }
            cond = cond and (
                (PatientsTable.name.lowerCase() like "%${q.lowercase()}%") or (CallsTable.callerName.lowerCase() like "%${q.lowercase()}%") or
                    (CallsTable.subject.lowerCase() like "%${q.lowercase()}%") or
                    (if (digits.length >= 3) (PatientsTable.phone like "%$digits%") or (CallsTable.callerPhone like "%$digits%") else Op.FALSE)
                )
        }

        val base = CallsTable.join(PatientsTable, JoinType.LEFT, CallsTable.patientId, PatientsTable.id)
        val total = base.selectAll().where(cond).count()
        val rows = base.selectAll().where(cond).orderBy(CallsTable.startedAt, SortOrder.DESC).limit(limit).offset(offset).toList()
        CallPageDto(toDtos(rows), total)
    }

    suspend fun recent(clinicId: Long, limit: Int, offset: Long, onlyEnded: Boolean = false): List<CallDto> =
        page(clinicId, limit, offset, onlyEnded = onlyEnded).items

    suspend fun get(clinicId: Long, callId: Long): CallDto? = database.dbQuery {
        val rows = CallsTable.join(PatientsTable, JoinType.LEFT, CallsTable.patientId, PatientsTable.id)
            .selectAll().where { (CallsTable.id eq callId) and (CallsTable.clinicId eq clinicId) }.toList()
        toDtos(rows).singleOrNull()
    }

    suspend fun recordingUrl(clinicId: Long, callId: Long): String? = database.dbQuery {
        CallsTable.selectAll().where { (CallsTable.id eq callId) and (CallsTable.clinicId eq clinicId) }.singleOrNull()?.get(CallsTable.recordingUrl)
    }

    suspend fun transcript(clinicId: Long, callId: Long): List<TranscriptMessageDto>? = database.dbQuery {
        if (CallsTable.selectAll().where { (CallsTable.id eq callId) and (CallsTable.clinicId eq clinicId) }.empty()) return@dbQuery null
        CallMessagesTable.selectAll().where { CallMessagesTable.callId eq callId }
            .orderBy(CallMessagesTable.offsetMs).orderBy(CallMessagesTable.id)
            .map { TranscriptMessageDto(it[CallMessagesTable.speaker].name.lowercase(), it[CallMessagesTable.content], it[CallMessagesTable.offsetMs]) }
    }

    /** Quantidade e tempo médio (s) das chamadas iniciadas no intervalo. */
    suspend fun statsBetween(clinicId: Long, fromMs: Long, toMs: Long): Pair<Long, Long> = database.dbQuery {
        val rows = CallsTable.selectAll()
            .where { (CallsTable.clinicId eq clinicId) and (CallsTable.startedAt greaterEq fromMs) and (CallsTable.startedAt less toMs) }
            .toList()
        val durations = rows.filter { it[CallsTable.state] == CallState.ENCERRADA }.mapNotNull { it[CallsTable.durationSeconds] }
        rows.size.toLong() to (if (durations.isEmpty()) 0L else Math.round(durations.average()))
    }

    /** Monta os DTOs de uma só vez (uma consulta para saber quais chamadas têm transcrição, em vez de uma por linha). */
    private fun Transaction.toDtos(rows: List<ResultRow>): List<CallDto> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it[CallsTable.id] }
        val withTranscript = CallMessagesTable.select(CallMessagesTable.callId).where { CallMessagesTable.callId inList ids }
            .withDistinct().map { it[CallMessagesTable.callId] }.toSet()
        val withPhoto = profilesWithPhoto(rows.mapNotNull { it.getOrNull(PatientsTable.profileId) })
        return rows.map { row ->
            CallDto(
                id = row[CallsTable.id],
                startedAt = row[CallsTable.startedAt],
                durationSeconds = row[CallsTable.durationSeconds],
                patientName = row.getOrNull(PatientsTable.name) ?: row[CallsTable.callerName],
                phone = row.getOrNull(PatientsTable.phone) ?: row[CallsTable.callerPhone],
                subject = row[CallsTable.subject],
                status = row[CallsTable.outcome]?.name?.lowercase(),
                state = row[CallsTable.state].name.lowercase(),
                hasRecording = row[CallsTable.recordingUrl] != null,
                hasTranscript = row[CallsTable.id] in withTranscript,
                patientId = row[CallsTable.patientId],
                cpf = row.getOrNull(PatientsTable.cpf),
                email = row.getOrNull(PatientsTable.email),
                healthPlan = row.getOrNull(PatientsTable.healthPlan),
                hasPhoto = row.getOrNull(PatientsTable.profileId)?.let { it in withPhoto } ?: false,
                aiTokens = row.aiUsage()?.total,
                aiCostBrl = row.aiUsage()?.let { pricing.costBrl(it, isFreeKey(row[CallsTable.aiKey])) },
                aiFreeKey = isFreeKey(row[CallsTable.aiKey]),
            )
        }
    }
}
