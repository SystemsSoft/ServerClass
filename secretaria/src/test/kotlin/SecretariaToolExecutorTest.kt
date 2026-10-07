import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import schemas.secretaria.CallChannel
import schemas.secretaria.CallIntent
import schemas.secretaria.CallOutcome
import schemas.secretaria.ScheduleWindowDto
import services.secretaria.SecretariaCallContext
import services.secretaria.SecretariaToolExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretariaToolExecutorTest {

    private class Env {
        val fx = SecretariaFixture() // segunda 09:00 em Brasília
        val boot = fx.bootstrap()
        val clinic = fx.clinic(boot.clinicId)
        val call = runBlocking { fx.calls.start(clinic.id, CallChannel.PWA, null) }
        val ctx = SecretariaCallContext(clinic, call.id, call.startedAt)
        val cardio = runBlocking { fx.clinics.doctors(clinic.id) }.first { it.specialty == "Cardiologia" }

        fun run(name: String, json: String = "{}"): SecretariaToolExecutor.Result =
            runBlocking { fx.tools.execute(ctx, name, Json.parseToJsonElement(json).jsonObject) }

        fun book(name: String = "Ana Souza", phone: String = "(21) 98765-4321", start: String = "2026-10-06T09:00") =
            run("agendar_consulta", """{"medico_id":${cardio.id},"inicio":"$start","nome_paciente":"$name","telefone_paciente":"$phone","motivo":"Check-up"}""")

        fun callRow() = runBlocking { fx.calls.get(clinic.id, call.id)!! }
    }

    private val JsonObject.ok get() = this["ok"]!!.jsonPrimitive.boolean
    private val JsonObject.error get() = this["erro"]?.jsonPrimitive?.content

    @Test
    fun `lista medicos e consulta horarios por especialidade`() {
        val env = Env()
        val list = env.run("listar_medicos_e_especialidades").response
        val doctors = list["medicos"]!!.jsonArray
        assertEquals(2, doctors.size)
        assertEquals(listOf("Cardiologia", "Consulta geral"), list["especialidades"]!!.jsonArray.map { it.jsonPrimitive.content })

        // por médico e por dia, com a duração da consulta e o atendimento (fixture: seg-sex 08-12 e 14-18, 30 min)
        val slots = env.run("consultar_horarios_disponiveis", """{"especialidade":"cardio","dias":1}""").response
        assertTrue(slots.ok)
        val doctor = slots["medicos"]!!.jsonArray.single().jsonObject
        assertEquals(env.cardio.id, doctor["medico_id"]!!.jsonPrimitive.content.toLong())
        assertEquals(30, doctor["duracao_consulta_min"]!!.jsonPrimitive.content.toInt())
        assertEquals("segunda a sexta: 08:00–12:00 e 14:00–18:00, consultas a cada 30 min", doctor["atendimento"]!!.jsonPrimitive.content)
        val today = doctor["dias"]!!.jsonArray.single().jsonObject
        assertEquals("2026-10-05", today["data"]!!.jsonPrimitive.content)
        assertEquals("segunda-feira, 05/10", today["dia"]!!.jsonPrimitive.content)
        // agora é segunda 09:00: com 30 min de antecedência, o primeiro é 09:30; o dia todo, de 30 em 30 (nada de 10:15)
        val times = today["horarios_livres"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("09:30", "10:00", "10:30", "11:00", "11:30", "14:00", "14:30", "15:00", "15:30", "16:00", "16:30", "17:00", "17:30"), times)
        assertTrue(slots["como_usar"]!!.jsonPrimitive.content.contains("data + \"T\" + horário"))
        assertNull(slots["mensagem"])

        // especialidade que a clínica NÃO tem: não é "sem horário", é "não atendemos" + o que ela atende
        val none = env.run("consultar_horarios_disponiveis", """{"especialidade":"dermatologia"}""").response
        assertFalse(none.ok)
        assertTrue(none.error!!.contains("NÃO atende a especialidade \"dermatologia\""), none.error)
        assertNull(none["horarios"])
        assertEquals(listOf("Cardiologia", "Consulta geral"), none["especialidades_da_clinica"]!!.jsonArray.map { it.jsonPrimitive.content })

        // variações do nome acham a especialidade cadastrada; especialidade diferente com começo parecido, não
        val viaVariant = env.run("consultar_horarios_disponiveis", """{"especialidade":"cardiologista","dias":1}""").response
        assertTrue(viaVariant.ok, viaVariant.toString())
        assertEquals(env.cardio.id, viaVariant["medicos"]!!.jsonArray.single().jsonObject["medico_id"]!!.jsonPrimitive.content.toLong())
        assertFalse(env.run("consultar_horarios_disponiveis", """{"especialidade":"neurologista"}""").response.ok)

        // a busca por parte do nome continua valendo (mesma regra de antes) e sem especialidade busca em todos
        assertTrue(env.run("consultar_horarios_disponiveis", """{"especialidade":"Consulta"}""").response.ok)
        assertTrue(env.run("consultar_horarios_disponiveis", """{"dias":1}""").response.ok)
    }

    @Test
    fun `cada medico no seu intervalo, varios dias, e horario fora da grade e recusado com orientacao`() {
        val env = Env()
        // Cardiologia passa a atender de 20 em 20 min de manhã e de 40 em 40 à tarde, só terça e quinta
        runBlocking {
            env.fx.catalog.replaceSchedule(
                env.clinic.id, env.cardio.id,
                listOf(
                    ScheduleWindowDto(2, "08:00", "10:00", 20), ScheduleWindowDto(2, "14:00", "16:00", 40),
                    ScheduleWindowDto(4, "08:00", "10:00", 20), ScheduleWindowDto(4, "14:00", "16:00", 40),
                ),
            )
        }
        val all = env.run("consultar_horarios_disponiveis", """{"dias":14}""").response
        val byName = all["medicos"]!!.jsonArray.associateBy { it.jsonObject["especialidade"]!!.jsonPrimitive.content }.mapValues { it.value.jsonObject }

        val cardio = byName.getValue("Cardiologia")
        assertNull(cardio["duracao_consulta_min"]) // varia por janela: vai no texto do atendimento
        assertEquals(
            "terça: 08:00–10:00 (a cada 20 min) e 14:00–16:00 (a cada 40 min); quinta: 08:00–10:00 (a cada 20 min) e 14:00–16:00 (a cada 40 min)",
            cardio["atendimento"]!!.jsonPrimitive.content,
        )
        val tuesday = cardio["dias"]!!.jsonArray.first().jsonObject
        assertEquals("2026-10-06", tuesday["data"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("08:00", "08:20", "08:40", "09:00", "09:20", "09:40", "14:00", "14:40", "15:20"),
            tuesday["horarios_livres"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(4, cardio["dias"]!!.jsonArray.size) // ter e qui por duas semanas

        // Consulta geral (30 min, seg-sex): 14 dias têm 10 dias úteis; lista 5 e avisa que há mais
        val geral = byName.getValue("Consulta geral")
        assertEquals(30, geral["duracao_consulta_min"]!!.jsonPrimitive.content.toInt())
        assertEquals(5, geral["dias"]!!.jsonArray.size)
        assertEquals(true, geral["ha_mais_dias"]!!.jsonPrimitive.boolean)

        // horário que não existe na grade (08:10 com consultas de 20 em 20) é recusado e a IA é orientada
        val wrong = env.book(start = "2026-10-06T08:10")
        assertFalse(wrong.response.ok)
        assertTrue(wrong.response.error!!.contains("intervalo de consulta do médico"), wrong.response.error)
        assertTrue(env.book(start = "2026-10-06T08:20").response.ok)
    }

    @Test
    fun `clinica sem medicos cadastrados nao oferece consulta nenhuma`() {
        val fx = SecretariaFixture()
        val boot = fx.bootstrap(doctors = emptyList())
        val clinic = fx.clinic(boot.clinicId)
        val call = runBlocking { fx.calls.start(clinic.id, CallChannel.PWA, null) }
        val ctx = SecretariaCallContext(clinic, call.id, call.startedAt)
        fun run(name: String, json: String = "{}") = runBlocking { fx.tools.execute(ctx, name, Json.parseToJsonElement(json).jsonObject) }.response

        val list = run("listar_medicos_e_especialidades")
        assertTrue(list.ok)
        assertTrue(list["medicos"]!!.jsonArray.isEmpty())
        assertTrue(list["mensagem"]!!.jsonPrimitive.content.contains("não tem médicos cadastrados"))

        for (args in listOf("{}", """{"especialidade":"Cardiologia"}""")) {
            val slots = run("consultar_horarios_disponiveis", args)
            assertFalse(slots.ok, args)
            assertTrue(slots.error!!.contains("não tem médicos cadastrados"), args)
        }
    }

    @Test
    fun `agendar grava paciente, agendamento, resultado da chamada e avisa o painel`() {
        val env = Env()
        val result = env.book()
        assertTrue(result.response.ok, result.response.toString())
        assertEquals("2026-10-06T09:00", result.response["inicio"]!!.jsonPrimitive.content)

        val ui = assertNotNull(result.uiEvent)
        assertEquals("appointment", ui["type"]!!.jsonPrimitive.content)
        assertEquals("booked", ui["action"]!!.jsonPrimitive.content)
        assertFalse(ui.toString().contains("5521987654321")) // telefone não volta ao PWA

        val patient = runBlocking { env.fx.clinics.patientsByPhone(env.clinic.id, "5521987654321") }.single()
        assertEquals("Ana Souza", patient.name)
        val appt = runBlocking { env.fx.appointments.upcomingForPatients(env.clinic, listOf(patient.id)) }.single()
        assertEquals("criado por ia", "criado por ${appt.createdBy}")

        val call = env.callRow()
        assertEquals("agendada", call.status)
        assertEquals("Ana Souza", call.patientName)
        assertEquals("5521987654321", call.phone)
        assertEquals(1, runBlocking { env.fx.clinics.unreadNotifications(env.clinic.id, env.boot.userId) })
    }

    @Test
    fun `agendar valida entradas e devolve erro amigavel`() {
        val env = Env()
        assertFalse(env.book(name = "Ana").response.ok) // só primeiro nome
        assertFalse(env.book(phone = "123").response.ok) // telefone inválido
        assertFalse(env.book(start = "amanhã às 9").response.ok) // formato
        assertFalse(env.book(start = "2026-10-06T12:00").response.ok) // fora da agenda
        assertFalse(env.run("agendar_consulta", """{"inicio":"2026-10-06T09:00","nome_paciente":"Ana Souza","telefone_paciente":"21987654321"}""").response.ok) // sem médico
        assertFalse(env.run("agendar_consulta", """{"medico_id":9999,"inicio":"2026-10-06T09:00","nome_paciente":"Ana Souza","telefone_paciente":"21987654321"}""").response.ok)
        assertNull(env.callRow().status) // nada foi marcado: chamada segue sem resultado

        assertTrue(env.book().response.ok)
        val busy = env.book(name = "Beto Lima", phone = "21911112222").response
        assertFalse(busy.ok)
        assertNotNull(busy.error)
    }

    @Test
    fun `aceita numeros como texto, como o Gemini as vezes envia`() {
        val env = Env()
        val r = env.run("agendar_consulta", """{"medico_id":"${env.cardio.id}","inicio":"2026-10-06T10:00","nome_paciente":"Ana Souza","telefone_paciente":"21987654321"}""")
        assertTrue(r.response.ok, r.response.toString())
    }

    @Test
    fun `listar, remarcar e cancelar exigem nome e telefone do dono`() {
        val env = Env()
        val id = env.book().response["agendamento_id"]!!.jsonPrimitive.content.toLong()

        val own = env.run("listar_agendamentos_do_paciente", """{"nome_paciente":"ana souza","telefone_paciente":"21987654321"}""").response
        assertEquals(1, own["agendamentos"]!!.jsonArray.size)
        assertFalse(own.toString().contains("Beto")) // sem dados de terceiros

        // nome errado, telefone errado, só primeiro nome: nada é revelado nem alterado
        for (who in listOf("""{"nome_paciente":"Carla Dias","telefone_paciente":"21987654321"}""", """{"nome_paciente":"Ana Souza","telefone_paciente":"21900000000"}""", """{"nome_paciente":"Ana","telefone_paciente":"21987654321"}""")) {
            assertFalse(env.run("listar_agendamentos_do_paciente", who).response.ok, who)
            val attempt = env.run("cancelar_consulta", who.dropLast(1) + ""","agendamento_id":$id}""")
            assertFalse(attempt.response.ok, who)
        }
        // outro paciente cadastrado não alcança o agendamento da Ana
        runBlocking { env.fx.clinics.findOrCreatePatient(env.clinic.id, "Beto Lima", "5521911112222") }
        assertFalse(env.run("cancelar_consulta", """{"agendamento_id":$id,"nome_paciente":"Beto Lima","telefone_paciente":"21911112222"}""").response.ok)

        // remarcar
        val moved = env.run("remarcar_consulta", """{"agendamento_id":$id,"novo_inicio":"2026-10-07T10:00","nome_paciente":"Ana Souza","telefone_paciente":"21987654321"}""")
        assertTrue(moved.response.ok, moved.response.toString())
        assertEquals("rescheduled", moved.uiEvent!!["action"]!!.jsonPrimitive.content)
        assertEquals("remarcada", env.callRow().status)

        // cancelar o novo
        val newId = moved.response["agendamento_id"]!!.jsonPrimitive.content.toLong()
        val cancelled = env.run("cancelar_consulta", """{"agendamento_id":$newId,"nome_paciente":"Ana Souza","telefone_paciente":"21987654321"}""")
        assertTrue(cancelled.response.ok, cancelled.response.toString())
        assertEquals("finalizada", env.callRow().status)
        val after = env.run("listar_agendamentos_do_paciente", """{"nome_paciente":"Ana Souza","telefone_paciente":"21987654321"}""").response
        assertTrue(after["agendamentos"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `registrar assunto alimenta o card da ligacao em andamento`() {
        val env = Env()
        assertTrue(env.run("registrar_assunto", """{"assunto":"Agendamento de consulta","resumo":"Agendando retorno com o Dr. Henrique","intencao":"agendamento"}""").response.ok)
        assertEquals("Agendando retorno com o Dr. Henrique", runBlocking { env.fx.calls.activeCall(env.clinic.id) }?.description)
        assertEquals("Agendamento de consulta", env.callRow().subject)

        assertTrue(env.run("registrar_assunto", """{"assunto":"x","intencao":"qualquer-coisa"}""").response.ok) // intenção inválida é ignorada
    }

    @Test
    fun `funcao desconhecida e recusada sem derrubar a chamada`() {
        val r = Env().run("apagar_tudo")
        assertFalse(r.response.ok)
    }

    @Test
    fun `declaracoes enviadas ao gemini cobrem todas as funcoes executaveis`() {
        val declared = SecretariaToolExecutor.declarations.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertEquals(
            setOf("listar_medicos_e_especialidades", "consultar_horarios_disponiveis", "agendar_consulta",
                "listar_agendamentos_do_paciente", "remarcar_consulta", "cancelar_consulta", "registrar_assunto"),
            declared,
        )
        // toda função declarada é reconhecida pelo executor (não cai em "Função desconhecida")
        val env = Env()
        declared.forEach { name -> assertNotEquals("Função desconhecida.", env.run(name).response["erro"]?.jsonPrimitive?.content, name) }
    }

    private fun assertNotEquals(unexpected: Any?, actual: Any?, message: String) =
        assertTrue(unexpected != actual, message)
}
