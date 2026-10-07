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
import schemas.secretaria.namesMatch
import schemas.secretaria.normalizePhone
import schemas.secretaria.parseLocalIso
import schemas.secretaria.specialtyMatches
import java.time.Instant
import java.time.LocalDate

/** Estado de uma chamada em andamento, compartilhado entre a ponte e as funções da IA. */
class SecretariaCallContext(val clinic: ClinicInfo, val callId: Long, val startedAt: Long)

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

        val slots = appointments.availableSlots(ctx.clinic, args.long("medico_id"), specialty, from, days, limit = 8)
        return Result(buildJsonObject {
            put("ok", true)
            putJsonArray("horarios") {
                slots.forEach { s ->
                    add(buildJsonObject {
                        put("medico_id", s.doctorId)
                        put("medico", s.doctorName)
                        put("especialidade", s.specialty)
                        put("inicio", s.startLocal)
                        put("descricao", s.label)
                    })
                }
            }
            if (slots.isEmpty()) put("mensagem", "Não há horários livres nesse período para o que foi pedido. Ofereça outra data ou especialidade.")
        })
    }

    // ── escrita ──────────────────────────────────────────────────────────────

    private suspend fun book(ctx: SecretariaCallContext, args: JsonObject): Result {
        val doctorId = args.long("medico_id") ?: return Result(fail("Falta o medico_id (use consultar_horarios_disponiveis)."))
        val start = parseLocalIso(args.str("inicio")) ?: return Result(fail("O 'inicio' deve estar no formato AAAA-MM-DDTHH:MM, igual ao devolvido por consultar_horarios_disponiveis."))
        val name = args.str("nome_paciente")?.trim()?.takeIf { it.split(' ').size >= 2 && it.length >= 5 }
            ?: return Result(fail("Preciso do nome completo do paciente (nome e sobrenome)."))
        val phone = normalizePhone(args.str("telefone_paciente"))
            ?: return Result(fail("Telefone inválido. Peça o número com DDD."))

        val patientId = clinics.findOrCreatePatient(ctx.clinic.id, name, phone)
        return when (val result = appointments.book(ctx.clinic, doctorId, patientId, start, AppointmentCreator.IA, ctx.callId, args.str("motivo"))) {
            is BookResult.Fail -> Result(fail(result.reason))
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
            is BookResult.Fail -> Result(fail(result.reason))
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

    private fun specialtiesOf(doctors: List<schemas.secretaria.DoctorDto>): List<String> =
        doctors.map { it.specialty }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    /** O Gemini às vezes manda números como string. */
    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.trim()?.toLongOrNull() }

    // ── declarações enviadas ao Gemini ───────────────────────────────────────

    companion object {
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

        val declarations: JsonArray = buildJsonArray {
            add(function("listar_medicos_e_especialidades", "Lista os médicos da clínica e suas especialidades."))
            add(function(
                "consultar_horarios_disponiveis",
                "Consulta os próximos horários livres. Sempre use antes de oferecer horários ao paciente.",
                mapOf(
                    "especialidade" to prop("STRING", "Especialidade desejada, ex.: Cardiologia (opcional)"),
                    "medico_id" to prop("INTEGER", "Id do médico, vindo de listar_medicos_e_especialidades (opcional)"),
                    "a_partir_de" to prop("STRING", "Data inicial AAAA-MM-DD (opcional; padrão: hoje)"),
                    "dias" to prop("INTEGER", "Quantos dias à frente buscar, de 1 a 14 (padrão 7)"),
                ),
            ))
            add(function(
                "agendar_consulta",
                "Marca a consulta. Só chame depois de o paciente escolher um horário e confirmar nome completo e telefone.",
                mapOf(
                    "medico_id" to prop("INTEGER", "Id do médico (campo medico_id do horário escolhido)"),
                    "inicio" to prop("STRING", "Início exato do horário escolhido, igual ao campo 'inicio' devolvido (AAAA-MM-DDTHH:MM)"),
                    "motivo" to prop("STRING", "Motivo da consulta, em poucas palavras (opcional)"),
                ) + identity,
                required = listOf("medico_id", "inicio", "nome_paciente", "telefone_paciente"),
            ))
            add(function(
                "listar_agendamentos_do_paciente",
                "Lista as consultas futuras de um paciente. Use antes de remarcar ou cancelar.",
                identity,
                required = listOf("nome_paciente", "telefone_paciente"),
            ))
            add(function(
                "remarcar_consulta",
                "Move uma consulta existente para outro horário livre.",
                mapOf(
                    "agendamento_id" to prop("INTEGER", "Id vindo de listar_agendamentos_do_paciente"),
                    "novo_inicio" to prop("STRING", "Novo horário, igual ao campo 'inicio' de um horário livre (AAAA-MM-DDTHH:MM)"),
                ) + identity,
                required = listOf("agendamento_id", "novo_inicio", "nome_paciente", "telefone_paciente"),
            ))
            add(function(
                "cancelar_consulta",
                "Cancela uma consulta existente, depois de o paciente confirmar qual.",
                mapOf("agendamento_id" to prop("INTEGER", "Id vindo de listar_agendamentos_do_paciente")) + identity,
                required = listOf("agendamento_id", "nome_paciente", "telefone_paciente"),
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
