package services.secretaria

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import schemas.secretaria.AppointmentCreator
import schemas.secretaria.AppointmentDto
import schemas.secretaria.BookResult
import schemas.secretaria.CallIntent
import schemas.secretaria.CallOutcome
import schemas.secretaria.ClinicInfo
import schemas.secretaria.NotificationType
import schemas.secretaria.SecretariaAppointmentService
import schemas.secretaria.SecretariaCallService
import schemas.secretaria.SecretariaClinicService
import schemas.secretaria.SecretariaSlots
import schemas.secretaria.namesMatch
import schemas.secretaria.normalizePhone
import schemas.secretaria.parseLocalIso
import schemas.secretaria.specialtyMatches
import java.time.Instant
import java.time.LocalDate

/** Estado de uma chamada em andamento, compartilhado entre a ponte e as funções da IA. */
class SecretariaCallContext(val clinic: ClinicInfo, val callId: Long, val startedAt: Long, val caller: CallerInfo? = null)

/** Paciente que ligou pelo app já cadastrado (identificado pelo CPF): a IA não pede nome nem telefone. */
data class CallerInfo(val patientId: Long, val name: String, val phone: String, val healthPlan: String)

/**
 * Funções que a IA pode chamar durante a conversa. Toda escrita no banco passa por aqui — a IA nunca
 * recebe ids que não vieram de uma consulta anterior e todo agendamento é revalidado (agenda do médico,
 * horário livre, antecedência mínima) pelo [SecretariaAppointmentService].
 */
class SecretariaToolExecutor(
    private val clinics: SecretariaClinicService,
    private val appointments: SecretariaAppointmentService,
    private val calls: SecretariaCallService,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** [uiEvent]: aviso opcional para o PWA do paciente mostrar um cartão de confirmação. */
    data class Result(val response: JsonObject, val uiEvent: JsonObject? = null)

    private val log = LoggerFactory.getLogger("Secretaria.Tools")

    suspend fun execute(ctx: SecretariaCallContext, name: String, args: JsonObject): Result = try {
        when (name) {
            "listar_medicos_e_especialidades" -> listDoctors(ctx)
            "consultar_horarios_disponiveis" -> availableSlots(ctx, args)
            "agendar_consulta" -> book(ctx, args)
            "listar_agendamentos_do_paciente" -> listPatientAppointments(ctx, args)
            "remarcar_consulta" -> reschedule(ctx, args)
            "cancelar_consulta" -> cancel(ctx, args)
            "registrar_assunto" -> registerSubject(ctx, args)
            else -> Result(fail("Função desconhecida."))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error("Falha na função {} (chamada {}): {}", name, ctx.callId, e.message, e)
        Result(fail("Não consegui concluir isso agora. Peça desculpas e diga que a equipe da clínica retornará."))
    }

    // ── consultas ────────────────────────────────────────────────────────────

    private suspend fun listDoctors(ctx: SecretariaCallContext): Result {
        val doctors = clinics.doctors(ctx.clinic.id)
        return Result(buildJsonObject {
            put("ok", true)
            putJsonArray("medicos") {
                doctors.forEach { d ->
                    add(buildJsonObject { put("medico_id", d.id); put("nome", d.name); put("especialidade", d.specialty) })
                }
            }
            putJsonArray("especialidades") { specialtiesOf(doctors).forEach { add(JsonPrimitive(it)) } }
            if (doctors.isEmpty()) put("mensagem", NO_DOCTORS)
            else put("mensagem", "Estas são TODAS as especialidades e médicos da clínica. Não cite nenhum outro.")
        })
    }

    private suspend fun availableSlots(ctx: SecretariaCallContext, args: JsonObject): Result {
        val zone = ctx.clinic.zone
        val today = Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()
        val from = args.str("a_partir_de")?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }
            ?.takeIf { !it.isBefore(today) } ?: today
        val days = (args.long("dias") ?: 7L).toInt().coerceIn(1, 14)

        // Especialidade que a clínica não tem não é "sem horário": é "não atendemos". Sem isso a IA dizia
        // "não há horário de X esta semana", dando a entender que a clínica atende X.
        val doctors = clinics.doctors(ctx.clinic.id)
        if (doctors.isEmpty()) return Result(fail(NO_DOCTORS))
        val specialty = args.str("especialidade")?.trim()
        if (specialty != null && doctors.none { specialtyMatches(it.specialty, specialty) }) { // mesma regra de availableSlots
            return Result(buildJsonObject {
                put("ok", false)
                put(
                    "erro",
                    "A clínica NÃO atende a especialidade \"$specialty\". Diga isso ao paciente e informe as especialidades que a clínica atende " +
                        "(lista abaixo); não ofereça horários de \"$specialty\". Se o paciente quis dizer uma delas, chame de novo com o nome exato.",
                )
                putJsonArray("especialidades_da_clinica") { specialtiesOf(doctors).forEach { add(JsonPrimitive(it)) } }
            })
        }

        // Por médico e por dia, com a duração da consulta e o atendimento de cada um: a IA só fala horários que
        // existem na grade do médico (ex.: de 30 em 30 min) e sabe que há outros dias além dos listados.
        val availability = appointments.availability(ctx.clinic, args.long("medico_id"), specialty, from, days)
        val anyFree = availability.any { it.freeSlots.isNotEmpty() }
        return Result(buildJsonObject {
            put("ok", true)
            putJsonArray("medicos") {
                availability.forEach { a ->
                    val byDay = a.freeSlots.groupBy { it.toLocalDate() }.toSortedMap()
                    add(buildJsonObject {
                        put("medico_id", a.doctorId)
                        put("medico", a.doctorName)
                        put("especialidade", a.specialty)
                        SecretariaSlots.slotMinutesOf(a.windows)?.let { put("duracao_consulta_min", it) }
                        put("atendimento", SecretariaSlots.describe(a.windows))
                        putJsonArray("dias") {
                            byDay.entries.take(MAX_DAYS).forEach { (date, times) ->
                                add(buildJsonObject {
                                    put("data", date.toString())
                                    put("dia", SecretariaSlots.dayLabel(date))
                                    putJsonArray("horarios_livres") { times.take(MAX_TIMES_PER_DAY).forEach { add(JsonPrimitive(it.toLocalTime().toString())) } }
                                })
                            }
                        }
                        if (byDay.size > MAX_DAYS) put("ha_mais_dias", true)
                    })
                }
            }
            put(
                "como_usar",
                "Só existem os horários de horarios_livres: eles já seguem a duração da consulta e o atendimento de cada médico. " +
                    "Para agendar, use o medico_id e inicio = data + \"T\" + horário (ex.: ${from}T09:30). " +
                    "Para outros dias, chame de novo com a_partir_de.",
            )
            if (!anyFree) put("mensagem", "Não há horários livres nesse período para o que foi pedido. Ofereça buscar outra data (a_partir_de) ou outro médico da mesma especialidade.")
        })
    }

    // ── escrita ──────────────────────────────────────────────────────────────

    private suspend fun book(ctx: SecretariaCallContext, args: JsonObject): Result {
        val doctorId = args.long("medico_id") ?: return Result(fail("Falta o medico_id (use consultar_horarios_disponiveis)."))
        val start = parseLocalIso(args.str("inicio")) ?: return Result(fail("O 'inicio' deve ser AAAA-MM-DDTHH:MM: a data + \"T\" + um horário livre devolvido por consultar_horarios_disponiveis."))
        val caller = ctx.caller
        val name = caller?.name ?: args.str("nome_paciente")?.trim()?.takeIf { it.split(' ').size >= 2 && it.length >= 5 }
            ?: return Result(fail("Preciso do nome completo do paciente (nome e sobrenome)."))
        val phone = caller?.phone ?: normalizePhone(args.str("telefone_paciente"))
            ?: return Result(fail("Telefone inválido. Peça o número com DDD."))

        // quem ligou pelo app já está identificado pelo cadastro (CPF): a consulta é sempre dele
        val patientId = caller?.patientId ?: clinics.findOrCreatePatient(ctx.clinic.id, name, phone)
        return when (val result = appointments.book(ctx.clinic, doctorId, patientId, start, AppointmentCreator.IA, ctx.callId, args.str("motivo"))) {
            is BookResult.Fail -> Result(fail(withAgendaHint(result.reason)))
            is BookResult.Ok -> {
                val a = result.appointment
                calls.attachPatient(ctx.callId, patientId, name, phone)
                calls.setDoctor(ctx.callId, a.doctorId)
                calls.setOutcome(ctx.callId, CallOutcome.AGENDADA, CallIntent.AGENDAMENTO)
                clinics.notify(ctx.clinic.id, NotificationType.AGENDAMENTO, "Novo agendamento", "$name — ${a.specialty}, ${a.startLocal.replace('T', ' ')} (via IA)")
                Result(
                    response = buildJsonObject {
                        put("ok", true)
                        put("agendamento_id", a.id)
                        put("paciente", a.patientName)
                        put("medico", a.doctorName)
                        put("especialidade", a.specialty)
                        put("inicio", a.startLocal)
                        put("instrucao", "Consulta marcada. Confirme ao paciente o médico, o dia e o horário e diga que a equipe poderá entrar em contato.")
                    },
                    uiEvent = uiEvent("booked", a),
                )
            }
        }
    }

    private suspend fun listPatientAppointments(ctx: SecretariaCallContext, args: JsonObject): Result {
        val (ids, error) = identifyPatients(ctx, args)
        if (error != null) return Result(fail(error))
        val list = appointments.upcomingForPatients(ctx.clinic, ids)
        return Result(buildJsonObject {
            put("ok", true)
            putJsonArray("agendamentos") {
                list.forEach { a ->
                    add(buildJsonObject {
                        put("agendamento_id", a.id)
                        put("medico", a.doctorName)
                        put("especialidade", a.specialty)
                        put("inicio", a.startLocal)
                    })
                }
            }
            if (list.isEmpty()) put("mensagem", "Nenhum agendamento futuro encontrado para esse paciente.")
        })
    }

    private suspend fun reschedule(ctx: SecretariaCallContext, args: JsonObject): Result {
        val (ids, error) = identifyPatients(ctx, args)
        if (error != null) return Result(fail(error))
        val id = args.long("agendamento_id") ?: return Result(fail("Falta o agendamento_id."))
        val start = parseLocalIso(args.str("novo_inicio")) ?: return Result(fail("O 'novo_inicio' deve estar no formato AAAA-MM-DDTHH:MM."))

        // O agendamento precisa ser de um dos pacientes identificados nesta chamada.
        val owner = appointments.upcomingForPatients(ctx.clinic, ids).firstOrNull { it.id == id }
            ?: return Result(fail("Não encontrei esse agendamento para o paciente informado."))
        return when (val result = appointments.reschedule(ctx.clinic, id, start, owner.patientId, AppointmentCreator.IA, ctx.callId)) {
            is BookResult.Fail -> Result(fail(withAgendaHint(result.reason)))
            is BookResult.Ok -> {
                val a = result.appointment
                calls.attachPatient(ctx.callId, a.patientId, a.patientName, a.patientPhone.orEmpty())
                calls.setDoctor(ctx.callId, a.doctorId)
                calls.setOutcome(ctx.callId, CallOutcome.REMARCADA, CallIntent.REMARCACAO)
                clinics.notify(ctx.clinic.id, NotificationType.AGENDAMENTO, "Consulta remarcada", "${a.patientName} — ${a.specialty}, ${a.startLocal.replace('T', ' ')} (via IA)")
                Result(
                    response = buildJsonObject {
                        put("ok", true)
                        put("agendamento_id", a.id)
                        put("medico", a.doctorName)
                        put("inicio", a.startLocal)
                        put("instrucao", "Consulta remarcada. Confirme o novo dia e horário ao paciente.")
                    },
                    uiEvent = uiEvent("rescheduled", a),
                )
            }
        }
    }

    private suspend fun cancel(ctx: SecretariaCallContext, args: JsonObject): Result {
        val (ids, error) = identifyPatients(ctx, args)
        if (error != null) return Result(fail(error))
        val id = args.long("agendamento_id") ?: return Result(fail("Falta o agendamento_id."))
        val owner = appointments.upcomingForPatients(ctx.clinic, ids).firstOrNull { it.id == id }
            ?: return Result(fail("Não encontrei esse agendamento para o paciente informado."))
        if (!appointments.cancel(ctx.clinic.id, id, owner.patientId)) return Result(fail("Não consegui cancelar esse agendamento."))

        calls.attachPatient(ctx.callId, owner.patientId, owner.patientName, owner.patientPhone.orEmpty())
        calls.setOutcome(ctx.callId, CallOutcome.FINALIZADA, CallIntent.CANCELAMENTO)
        clinics.notify(ctx.clinic.id, NotificationType.AGENDAMENTO, "Consulta cancelada", "${owner.patientName} — ${owner.specialty}, ${owner.startLocal.replace('T', ' ')} (via IA)")
        return Result(
            response = buildJsonObject { put("ok", true); put("instrucao", "Consulta cancelada. Confirme ao paciente e pergunte se deseja remarcar.") },
            uiEvent = uiEvent("cancelled", owner),
        )
    }

    private suspend fun registerSubject(ctx: SecretariaCallContext, args: JsonObject): Result {
        val intent = args.str("intencao")?.uppercase()?.let { v -> CallIntent.entries.firstOrNull { it.name == v } }
        calls.setSubject(ctx.callId, args.str("assunto"), args.str("resumo"), intent)
        return Result(buildJsonObject { put("ok", true) })
    }

    // ── identificação ────────────────────────────────────────────────────────

    /**
     * Pacientes cadastrados com esse telefone E nome compatível. Telefone + nome é uma verificação fraca
     * (não impede que alguém que conheça os dois mexa na consulta); para mais segurança, exija um código
     * enviado por SMS/WhatsApp antes de remarcar ou cancelar.
     */
    private suspend fun identifyPatients(ctx: SecretariaCallContext, args: JsonObject): Pair<List<Long>, String?> {
        ctx.caller?.let { return listOf(it.patientId) to null } // pelo app: só as consultas de quem ligou
        val phone = normalizePhone(args.str("telefone_paciente")) ?: return emptyList<Long>() to "Telefone inválido. Peça o número com DDD."
        val name = args.str("nome_paciente")?.takeIf { it.isNotBlank() } ?: return emptyList<Long>() to "Preciso do nome completo do paciente."
        val ids = clinics.patientsByPhone(ctx.clinic.id, phone).filter { namesMatch(it.name, name) }.map { it.id }
        return if (ids.isEmpty()) emptyList<Long>() to "Não encontrei cadastro com esse nome e telefone." else ids to null
    }

    private fun uiEvent(action: String, a: AppointmentDto): JsonObject = buildJsonObject {
        put("type", "appointment")
        put("action", action)
        put("appointment", Json.encodeToJsonElement(AppointmentDto.serializer(), a.copy(patientPhone = null)))
    }

    private fun fail(message: String): JsonObject = buildJsonObject { put("ok", false); put("erro", message) }

    /** Horário fora da grade do médico: lembra a IA de usar só os horários livres (que seguem o intervalo de consulta). */
    private fun withAgendaHint(reason: String): String =
        if (reason.contains("agenda do médico")) "$reason Consulte de novo consultar_horarios_disponiveis e ofereça só os horários livres de lá (eles seguem o intervalo de consulta do médico)." else reason

    private fun specialtiesOf(doctors: List<schemas.secretaria.DoctorDto>): List<String> =
        doctors.map { it.specialty }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    /** O Gemini às vezes manda números como string. */
    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.trim()?.toLongOrNull() }

    // ── declarações enviadas ao Gemini ───────────────────────────────────────

    companion object {
        /** Dias com horário livre listados por médico, e horários por dia (15 em 15 min por 8 h = 32). */
        private const val MAX_DAYS = 5
        private const val MAX_TIMES_PER_DAY = 32

        private const val NO_DOCTORS =
            "A clínica ainda não tem médicos cadastrados. Não ofereça nem agende consultas; registre o assunto e diga que a equipe da clínica retornará o contato."

        private fun prop(type: String, description: String, enum: List<String>? = null) = buildJsonObject {
            put("type", type)
            put("description", description)
            enum?.let { values -> putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } } }
        }

        private fun function(name: String, description: String, props: Map<String, JsonObject> = emptyMap(), required: List<String> = emptyList()) =
            buildJsonObject {
                put("name", name)
                put("description", description)
                if (props.isNotEmpty()) putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
                    if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
                }
            }

        private val identity = mapOf(
            "nome_paciente" to prop("STRING", "Nome completo do paciente"),
            "telefone_paciente" to prop("STRING", "Telefone do paciente com DDD"),
        )

        /** Funções para quem liga sem cadastro (a IA pede nome e telefone). */
        val declarations: JsonArray get() = declarationsFor(identified = false)

        /** [identified]: paciente do app (CPF) — as funções usam o cadastro dele e não têm nome/telefone. */
        fun declarationsFor(identified: Boolean): JsonArray = buildJsonArray {
            val who = if (identified) emptyMap() else identity
            val identityRequired = if (identified) emptyList() else listOf("nome_paciente", "telefone_paciente")
            add(function("listar_medicos_e_especialidades", "Lista os médicos da clínica e suas especialidades."))
            add(function(
                "consultar_horarios_disponiveis",
                "Consulta os horários livres, por médico e por dia, já no intervalo de consulta de cada médico (duracao_consulta_min) e com o atendimento dele. " +
                    "Sempre use antes de oferecer horários ao paciente: só existem os horários devolvidos aqui.",
                mapOf(
                    "especialidade" to prop("STRING", "Especialidade desejada, ex.: Cardiologia (opcional)"),
                    "medico_id" to prop("INTEGER", "Id do médico, vindo de listar_medicos_e_especialidades (opcional)"),
                    "a_partir_de" to prop("STRING", "Data inicial AAAA-MM-DD (opcional; padrão: hoje)"),
                    "dias" to prop("INTEGER", "Quantos dias à frente buscar, de 1 a 14 (padrão 7)"),
                ),
            ))
            add(function(
                "agendar_consulta",
                if (identified) "Marca a consulta para o paciente que está ligando (já identificado pelo cadastro). Só chame depois de ele escolher e confirmar o horário."
                else "Marca a consulta. Só chame depois de o paciente escolher um horário e confirmar nome completo e telefone.",
                mapOf(
                    "medico_id" to prop("INTEGER", "Id do médico (campo medico_id do horário escolhido)"),
                    "inicio" to prop("STRING", "data + \"T\" + horário livre escolhido, de consultar_horarios_disponiveis (AAAA-MM-DDTHH:MM, ex.: 2026-10-06T09:30)"),
                    "motivo" to prop("STRING", "Motivo da consulta, em poucas palavras (opcional)"),
                ) + who,
                required = listOf("medico_id", "inicio") + identityRequired,
            ))
            add(function(
                "listar_agendamentos_do_paciente",
                if (identified) "Lista as consultas futuras do paciente que está ligando. Use antes de remarcar ou cancelar."
                else "Lista as consultas futuras de um paciente. Use antes de remarcar ou cancelar.",
                who,
                required = identityRequired,
            ))
            add(function(
                "remarcar_consulta",
                "Move uma consulta existente para outro horário livre.",
                mapOf(
                    "agendamento_id" to prop("INTEGER", "Id vindo de listar_agendamentos_do_paciente"),
                    "novo_inicio" to prop("STRING", "data + \"T\" + horário livre de consultar_horarios_disponiveis (AAAA-MM-DDTHH:MM)"),
                ) + who,
                required = listOf("agendamento_id", "novo_inicio") + identityRequired,
            ))
            add(function(
                "cancelar_consulta",
                "Cancela uma consulta existente, depois de o paciente confirmar qual.",
                mapOf("agendamento_id" to prop("INTEGER", "Id vindo de listar_agendamentos_do_paciente")) + who,
                required = listOf("agendamento_id") + identityRequired,
            ))
            add(function(
                "registrar_assunto",
                "Registra no painel da clínica o assunto da chamada. Chame uma vez, assim que entender o motivo.",
                mapOf(
                    "assunto" to prop("STRING", "Assunto em poucas palavras, ex.: Agendamento de consulta"),
                    "resumo" to prop("STRING", "O que está sendo feito agora, ex.: Agendando retorno com o Dr. Henrique"),
                    "intencao" to prop("STRING", "Intenção do paciente", listOf("agendamento", "remarcacao", "cancelamento", "informacao", "outro")),
                ),
                required = listOf("assunto"),
            ))
        }
    }
}
