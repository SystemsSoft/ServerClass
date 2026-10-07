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

        val slots = env.run("consultar_horarios_disponiveis", """{"especialidade":"cardio","dias":1}""").response
        assertTrue(slots.ok)
        val first = slots["horarios"]!!.jsonArray.first().jsonObject
        assertEquals(env.cardio.id, first["medico_id"]!!.jsonPrimitive.content.toLong())
        assertEquals("2026-10-05T09:30", first["inicio"]!!.jsonPrimitive.content)
        assertTrue(slots["horarios"]!!.jsonArray.size <= 8)

        // especialidade que a clínica NÃO tem: não é "sem horário", é "não atendemos" + o que ela atende
        val none = env.run("consultar_horarios_disponiveis", """{"especialidade":"dermatologia"}""").response
        assertFalse(none.ok)
        assertTrue(none.error!!.contains("NÃO atende a especialidade \"dermatologia\""), none.error)
        assertNull(none["horarios"])
        assertEquals(listOf("Cardiologia", "Consulta geral"), none["especialidades_da_clinica"]!!.jsonArray.map { it.jsonPrimitive.content })

        // a busca por parte do nome continua valendo (mesma regra de antes) e sem especialidade busca em todos
        assertTrue(env.run("consultar_horarios_disponiveis", """{"especialidade":"Consulta"}""").response.ok)
        assertTrue(env.run("consultar_horarios_disponiveis", """{"dias":1}""").response.ok)
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
