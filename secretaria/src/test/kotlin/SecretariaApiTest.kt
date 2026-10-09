import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import schemas.secretaria.AddTeamMemberRequest
import schemas.secretaria.AppointmentCreator
import schemas.secretaria.BookResult
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import schemas.secretaria.CallChannel
import schemas.secretaria.ClinicsTable
import schemas.secretaria.parseLocalIso
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/** Rotas HTTP do dashboard: autorização por papel, isolamento entre clínicas e contratos de resposta. */
class SecretariaApiTest {

    @AfterTest
    fun cleanup() {
        System.clearProperty("secretaria.adminKey")
    }

    private class Env {
        val fx = SecretariaFixture()
        val a = fx.bootstrap()
        val b = fx.bootstrap(email = "dona@outra.test", clinicName = "Outra Clínica")

        init {
            runBlocking {
                fx.team.add(a.clinicId, AddTeamMemberRequest("secretaria@clinica.test", "secretaria", "Sara Secretária", "senha-bem-longa-1"))
                fx.team.add(a.clinicId, AddTeamMemberRequest("medico@clinica.test", "profissional", "Dr. Leitor", "senha-bem-longa-1"))
            }
        }

        val doctorA = runBlocking { fx.catalog.doctors(a.clinicId, false) }.first()
        val doctorB = runBlocking { fx.catalog.doctors(b.clinicId, false) }.first()
        val patientA = runBlocking { fx.clinics.findOrCreatePatient(a.clinicId, "Ana Souza", "5521987654321") }
        val patientB = runBlocking { fx.clinics.findOrCreatePatient(b.clinicId, "Bia Outra", "5521911112222") }
        val clinicA = fx.clinic(a.clinicId)
        val clinicB = fx.clinic(b.clinicId)
        val callB = runBlocking { fx.calls.start(b.clinicId, CallChannel.PWA, null) }
        val apptB = runBlocking {
            (fx.appointments.book(clinicB, doctorB.id, patientB, parseLocalIso("2026-10-06T09:00")!!, AppointmentCreator.IA) as BookResult.Ok).appointment
        }
    }

    private suspend fun ApplicationTestBuilder.req(method: HttpMethod, path: String, token: String? = null, body: String? = null): HttpResponse =
        client.request(path) {
            this.method = method
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            body?.let { contentType(ContentType.Application.Json); setBody(it) }
        }

    private suspend fun ApplicationTestBuilder.token(email: String, password: String = "senha-segura-123"): String {
        val r = req(HttpMethod.Post, "/secretaria/auth/login", body = """{"email":"$email","password":"$password"}""")
        assertEquals(HttpStatusCode.OK, r.status, "login de $email")
        return Json.parseToJsonElement(r.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    // ── permissões por papel ─────────────────────────────────────────────────

    private data class Endpoint(val method: HttpMethod, val path: String, val body: String? = null)

    @Test
    fun `papeis - profissional so le, secretaria escreve, so admin administra`() = runBlocking<Unit> {
        val env = Env()
        val c = env.a.clinicId
        val q = "clinicId=$c"
        val write = listOf(
            Endpoint(HttpMethod.Post, "/secretaria/calls/1/end?$q"),
            Endpoint(HttpMethod.Post, "/secretaria/appointments", """{"clinicId":$c,"doctorId":${env.doctorA.id},"patientName":"Ana Souza","patientPhone":"21987654321","startLocal":"2026-10-06T09:00"}"""),
            Endpoint(HttpMethod.Post, "/secretaria/appointments/1/status?$q", """{"status":"confirmado"}"""),
            Endpoint(HttpMethod.Post, "/secretaria/appointments/1/reschedule?$q", """{"startLocal":"2026-10-07T09:00"}"""),
            Endpoint(HttpMethod.Post, "/secretaria/appointments/1/cancel?$q"),
            Endpoint(HttpMethod.Post, "/secretaria/patients?$q", """{"name":"Novo Paciente","phone":"21999990000"}"""),
            Endpoint(HttpMethod.Put, "/secretaria/patients/${env.patientA}?$q", """{"name":"Ana Souza","phone":"21987654321"}"""),
            Endpoint(HttpMethod.Post, "/secretaria/doctors?$q", """{"name":"Dr. Novo","specialty":"Pediatria"}"""),
            Endpoint(HttpMethod.Put, "/secretaria/doctors/${env.doctorA.id}?$q", """{"name":"Dr. Novo","specialty":"Pediatria"}"""),
            Endpoint(HttpMethod.Delete, "/secretaria/doctors/${env.doctorA.id}?$q"),
            Endpoint(HttpMethod.Put, "/secretaria/doctors/${env.doctorA.id}/schedule?$q", """{"windows":[]}"""),
            Endpoint(HttpMethod.Get, "/secretaria/team?$q"),
            Endpoint(HttpMethod.Get, "/secretaria/reports/calls.csv?$q"),
            Endpoint(HttpMethod.Get, "/secretaria/reports/appointments.csv?$q"),
        )
        val admin = listOf(
            Endpoint(HttpMethod.Delete, "/secretaria/patients/${env.patientA}?$q"),
            Endpoint(HttpMethod.Put, "/secretaria/clinic?$q", """{"name":"Novo Nome"}"""),
            Endpoint(HttpMethod.Post, "/secretaria/clinic/rotate-key?$q"),
            Endpoint(HttpMethod.Put, "/secretaria/settings?$q", """{"aiEnabled":false}"""),
            Endpoint(HttpMethod.Post, "/secretaria/team?$q", """{"email":"x@x.test","role":"admin","name":"X Y","password":"senha-bem-longa-1"}"""),
            Endpoint(HttpMethod.Put, "/secretaria/team/1?$q", """{"role":"admin"}"""),
            Endpoint(HttpMethod.Delete, "/secretaria/team/1?$q"),
        )

        testApplication {
            startSecretaria(env.fx)
            val pro = token("medico@clinica.test", "senha-bem-longa-1")
            val sec = token("secretaria@clinica.test", "senha-bem-longa-1")

            for (e in write + admin) {
                assertEquals(HttpStatusCode.Forbidden, req(e.method, e.path, pro, e.body).status, "profissional não pode ${e.method.value} ${e.path}")
                assertEquals(HttpStatusCode.Unauthorized, req(e.method, e.path, null, e.body).status, "sem token: ${e.method.value} ${e.path}")
            }
            for (e in admin) {
                assertEquals(HttpStatusCode.Forbidden, req(e.method, e.path, sec, e.body).status, "secretária não administra: ${e.method.value} ${e.path}")
            }
            for (e in write) {
                val s = req(e.method, e.path, sec, e.body).status
                assertTrue(s != HttpStatusCode.Forbidden && s != HttpStatusCode.Unauthorized, "secretária deve poder: ${e.method.value} ${e.path} (veio $s)")
            }
        }
    }

    @Test
    fun `leitura - todos os papeis leem, sem token e 401, e a chave publica so aparece para admin`() = runBlocking<Unit> {
        val env = Env()
        val q = "clinicId=${env.a.clinicId}"
        val reads = listOf(
            "/secretaria/dashboard?$q", "/secretaria/calls?$q", "/secretaria/appointments?$q&from=2026-10-01&to=2026-10-31",
            "/secretaria/patients?$q", "/secretaria/patients/${env.patientA}?$q", "/secretaria/doctors?$q", "/secretaria/specialties?$q",
            "/secretaria/doctors/${env.doctorA.id}/schedule?$q", "/secretaria/settings?$q", "/secretaria/assistant/status?$q",
            "/secretaria/notifications?$q", "/secretaria/plan?$q", "/secretaria/clinic?$q",
            "/secretaria/reports/summary?$q&from=2026-10-01&to=2026-10-31", "/secretaria/slots?$q&date=2026-10-06&days=1", "/secretaria/me",
        )
        testApplication {
            startSecretaria(env.fx)
            val pro = token("medico@clinica.test", "senha-bem-longa-1")
            val admin = token("maria@clinica.test")
            for (path in reads) {
                assertEquals(HttpStatusCode.OK, req(HttpMethod.Get, path, pro).status, "profissional lê $path")
                assertEquals(HttpStatusCode.Unauthorized, req(HttpMethod.Get, path).status, "sem token $path")
            }
            val asPro = json(req(HttpMethod.Get, "/secretaria/clinic?$q", pro).bodyAsText()).jsonObject
            val asAdmin = json(req(HttpMethod.Get, "/secretaria/clinic?$q", admin).bodyAsText()).jsonObject
            assertTrue(asPro["publicKey"] is kotlinx.serialization.json.JsonNull)
            assertEquals(env.a.publicKey, asAdmin["publicKey"]!!.jsonPrimitive.content)
        }
    }

    // ── isolamento entre clínicas ────────────────────────────────────────────

    @Test
    fun `uma clinica nunca enxerga nem altera dados da outra`() = runBlocking<Unit> {
        val env = Env()
        val qa = "clinicId=${env.a.clinicId}"
        val qb = "clinicId=${env.b.clinicId}"
        testApplication {
            startSecretaria(env.fx)
            val admin = token("maria@clinica.test")

            // pedir diretamente a clínica B: sem acesso
            for (path in listOf("/secretaria/dashboard?$qb", "/secretaria/calls?$qb", "/secretaria/patients?$qb", "/secretaria/settings?$qb", "/secretaria/reports/summary?$qb", "/secretaria/plan?$qb", "/secretaria/team?$qb")) {
                assertEquals(HttpStatusCode.Forbidden, req(HttpMethod.Get, path, admin).status, path)
            }
            assertEquals(HttpStatusCode.Forbidden, req(HttpMethod.Put, "/secretaria/settings?$qb", admin, """{"aiEnabled":false}""").status)
            assertTrue(env.fx.settings.settings(env.b.clinicId).aiEnabled)

            // usar a clínica A (permitida) com ids da B: não existe para quem pergunta
            val cases = listOf(
                Endpoint(HttpMethod.Get, "/secretaria/patients/${env.patientB}?$qa"),
                Endpoint(HttpMethod.Put, "/secretaria/patients/${env.patientB}?$qa", """{"name":"Invasor Teste","phone":"21900000000"}"""),
                Endpoint(HttpMethod.Delete, "/secretaria/patients/${env.patientB}?$qa"),
                Endpoint(HttpMethod.Get, "/secretaria/calls/${env.callB.id}?$qa"),
                Endpoint(HttpMethod.Get, "/secretaria/calls/${env.callB.id}/transcript?$qa"),
                Endpoint(HttpMethod.Get, "/secretaria/calls/${env.callB.id}/recording?$qa"),
                Endpoint(HttpMethod.Post, "/secretaria/calls/${env.callB.id}/end?$qa"),
                Endpoint(HttpMethod.Get, "/secretaria/appointments/${env.apptB.id}?$qa"),
                Endpoint(HttpMethod.Post, "/secretaria/appointments/${env.apptB.id}/status?$qa", """{"status":"confirmado"}"""),
                Endpoint(HttpMethod.Post, "/secretaria/appointments/${env.apptB.id}/cancel?$qa"),
                Endpoint(HttpMethod.Put, "/secretaria/doctors/${env.doctorB.id}?$qa", """{"name":"Dr. Invasor","specialty":"Pediatria"}"""),
                Endpoint(HttpMethod.Delete, "/secretaria/doctors/${env.doctorB.id}?$qa"),
                Endpoint(HttpMethod.Get, "/secretaria/doctors/${env.doctorB.id}/schedule?$qa"),
                Endpoint(HttpMethod.Put, "/secretaria/doctors/${env.doctorB.id}/schedule?$qa", """{"windows":[]}"""),
                Endpoint(HttpMethod.Delete, "/secretaria/team/${env.b.userId}?$qa"),
                Endpoint(HttpMethod.Put, "/secretaria/team/${env.b.userId}?$qa", """{"role":"profissional"}"""),
            )
            for (c in cases) assertEquals(HttpStatusCode.NotFound, req(c.method, c.path, admin, c.body).status, "${c.method.value} ${c.path}")

            // e nada mudou do outro lado
            assertEquals("Bia Outra", env.fx.patients.detail(env.clinicB, env.patientB).let { (it as schemas.secretaria.ServiceResult.Ok).value.patient.name })
            assertEquals("agendado", env.fx.appointments.get(env.clinicB, env.apptB.id)?.status)
            assertEquals(1, env.fx.team.list(env.b.clinicId).size)
            assertEquals(10, (env.fx.catalog.schedule(env.b.clinicId, env.doctorB.id) as schemas.secretaria.ServiceResult.Ok).value.size)
        }
    }

    // ── configurações da IA: efeito real na chamada ──────────────────────────

    @Test
    fun `desligar a IA nas configuracoes recusa a chamada do PWA e some o botao`() = runBlocking<Unit> {
        val env = Env()
        val q = "clinicId=${env.a.clinicId}"
        testApplication {
            startSecretaria(env.fx)
            val admin = token("maria@clinica.test")
            val wsClient = createClient { install(ClientWebSockets) }

            assertTrue(json(req(HttpMethod.Get, "/secretaria/assistant/status?$q", admin).bodyAsText()).jsonObject["online"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(HttpStatusCode.OK, req(HttpMethod.Put, "/secretaria/settings?$q", admin, """{"aiEnabled":false,"voice":"Puck","extraInstructions":"Aceitamos Unimed"}""").status)

            val status = json(req(HttpMethod.Get, "/secretaria/assistant/status?$q", admin).bodyAsText()).jsonObject
            assertFalse(status["online"]!!.jsonPrimitive.content.toBoolean())
            val dash = json(req(HttpMethod.Get, "/secretaria/dashboard?$q", admin).bodyAsText()).jsonObject
            assertFalse(dash["assistant"]!!.jsonObject["online"]!!.jsonPrimitive.content.toBoolean())
            val public = json(req(HttpMethod.Get, "/secretaria/public/${env.a.publicKey}").bodyAsText()).jsonObject
            assertFalse(public["online"]!!.jsonPrimitive.content.toBoolean()) // o PWA esconde o botão "Chamar"

            wsClient.webSocket("/ws/secretaria/${env.a.publicKey}?consent=true") {
                val msg = json(withTimeout(5_000) { (incoming.receive() as Frame.Text).readText() }).jsonObject
                assertEquals("unavailable", msg["code"]!!.jsonPrimitive.content)
            }
            assertEquals(0, env.fx.calls.recent(env.a.clinicId, 5, 0).size) // nenhuma chamada foi aberta nem cobrada
            assertEquals(HttpStatusCode.BadRequest, req(HttpMethod.Put, "/secretaria/settings?$q", admin, """{"voice":"Inexistente"}""").status)
        }
    }

    @Test
    fun `girar a chave publica corta o acesso antigo`() = runBlocking<Unit> {
        val env = Env()
        testApplication {
            startSecretaria(env.fx)
            val admin = token("maria@clinica.test")
            val novo = json(req(HttpMethod.Post, "/secretaria/clinic/rotate-key?clinicId=${env.a.clinicId}", admin).bodyAsText()).jsonObject["publicKey"]!!.jsonPrimitive.content
            assertEquals(HttpStatusCode.NotFound, req(HttpMethod.Get, "/secretaria/public/${env.a.publicKey}").status)
            assertEquals(HttpStatusCode.OK, req(HttpMethod.Get, "/secretaria/public/$novo").status)
            assertEquals(novo, json(req(HttpMethod.Get, "/secretaria/clinic?clinicId=${env.a.clinicId}", admin).bodyAsText()).jsonObject["publicKey"]!!.jsonPrimitive.content)
        }
    }

    // ── equipe, perfil e senha ───────────────────────────────────────────────

    @Test
    fun `equipe e perfil pela API`() = runBlocking<Unit> {
        val env = Env()
        val q = "clinicId=${env.a.clinicId}"
        testApplication {
            startSecretaria(env.fx)
            val admin = token("maria@clinica.test")

            val created = req(HttpMethod.Post, "/secretaria/team?$q", admin, """{"email":"novo@clinica.test","role":"secretaria","name":"Novo Membro","password":"senha-bem-longa-1"}""")
            assertEquals(HttpStatusCode.Created, created.status)
            val newId = json(created.bodyAsText()).jsonObject["userId"]!!.jsonPrimitive.content
            assertEquals(HttpStatusCode.Conflict, req(HttpMethod.Post, "/secretaria/team?$q", admin, """{"email":"novo@clinica.test","role":"secretaria"}""").status)
            assertEquals(HttpStatusCode.BadRequest, req(HttpMethod.Post, "/secretaria/team?$q", admin, """{"email":"x@x.test","role":"chefe","name":"X Y","password":"senha-bem-longa-1"}""").status)
            assertEquals(HttpStatusCode.BadRequest, req(HttpMethod.Post, "/secretaria/team?$q", admin, "isto não é json").status)
            assertEquals(HttpStatusCode.OK, req(HttpMethod.Put, "/secretaria/team/$newId?$q", admin, """{"role":"profissional"}""").status)
            assertEquals(4, json(req(HttpMethod.Get, "/secretaria/team?$q", admin).bodyAsText()).jsonArray.size)
            assertEquals(HttpStatusCode.NoContent, req(HttpMethod.Delete, "/secretaria/team/$newId?$q", admin).status)

            // a clínica não pode ficar sem administrador
            assertEquals(HttpStatusCode.Conflict, req(HttpMethod.Delete, "/secretaria/team/${env.a.userId}?$q", admin).status)
            assertEquals(HttpStatusCode.Conflict, req(HttpMethod.Put, "/secretaria/team/${env.a.userId}?$q", admin, """{"role":"secretaria"}""").status)

            // perfil
            val me = req(HttpMethod.Put, "/secretaria/me", admin, """{"name":"Maria Souza Silva"}""")
            assertEquals("Maria Souza Silva", json(me.bodyAsText()).jsonObject["name"]!!.jsonPrimitive.content)
            assertEquals(HttpStatusCode.BadRequest, req(HttpMethod.Put, "/secretaria/me", admin, """{"name":"M"}""").status)
        }
    }

    @Test
    fun `trocar senha - valida a atual, aplica e bloqueia tentativas repetidas`() = runBlocking<Unit> {
        val env = Env()
        testApplication {
            startSecretaria(env.fx)
            val sec = token("secretaria@clinica.test", "senha-bem-longa-1")
            fun body(cur: String, new: String) = """{"currentPassword":"$cur","newPassword":"$new"}"""

            assertEquals(HttpStatusCode.Unauthorized, req(HttpMethod.Post, "/secretaria/auth/change-password", null, body("a", "bbbbbbbbbbbb")).status)
            assertEquals(HttpStatusCode.BadRequest, req(HttpMethod.Post, "/secretaria/auth/change-password", sec, body("senha-bem-longa-1", "curta")).status)
            assertEquals(HttpStatusCode.NoContent, req(HttpMethod.Post, "/secretaria/auth/change-password", sec, body("senha-bem-longa-1", "outra-senha-boa-22")).status)
            assertEquals(HttpStatusCode.OK, req(HttpMethod.Post, "/secretaria/auth/login", body = """{"email":"secretaria@clinica.test","password":"outra-senha-boa-22"}""").status)
            assertEquals(HttpStatusCode.Unauthorized, req(HttpMethod.Post, "/secretaria/auth/login", body = """{"email":"secretaria@clinica.test","password":"senha-bem-longa-1"}""").status)

            // quem roubou um token não pode adivinhar a senha atual à vontade
            repeat(5) { assertEquals(HttpStatusCode.Forbidden, req(HttpMethod.Post, "/secretaria/auth/change-password", sec, body("palpite-errado-$it", "nova-senha-qualquer-1")).status) }
            assertEquals(HttpStatusCode.TooManyRequests, req(HttpMethod.Post, "/secretaria/auth/change-password", sec, body("outra-senha-boa-22", "nova-senha-qualquer-1")).status)
        }
    }

    // ── médicos, pacientes, agenda e chamadas pela API ───────────────────────

    @Test
    fun `medicos e agenda semanal pela API`() = runBlocking<Unit> {
        val env = Env()
        val q = "clinicId=${env.a.clinicId}"
        testApplication {
            startSecretaria(env.fx)
            val admin = token("maria@clinica.test")

            val created = req(HttpMethod.Post, "/secretaria/doctors?$q", admin, """{"name":"Dra. Paula Reis","specialty":"Pediatria","crm":"123"}""")
            assertEquals(HttpStatusCode.Created, created.status)
            val id = json(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
            assertEquals(10, json(req(HttpMethod.Get, "/secretaria/doctors/$id/schedule?$q", admin).bodyAsText()).jsonArray.size)

            assertEquals(HttpStatusCode.BadRequest, req(HttpMethod.Put, "/secretaria/doctors/$id/schedule?$q", admin, """{"windows":[{"weekday":1,"start":"10:00","end":"09:00"}]}""").status)
            val ok = req(HttpMethod.Put, "/secretaria/doctors/$id/schedule?$q", admin, """{"windows":[{"weekday":2,"start":"14:00","end":"16:00","slotMinutes":60}]}""")
            assertEquals(HttpStatusCode.OK, ok.status)
            val slots = json(req(HttpMethod.Get, "/secretaria/slots?$q&doctorId=$id&date=2026-10-06&days=1", admin).bodyAsText()).jsonArray
            assertEquals(listOf("2026-10-06T14:00", "2026-10-06T15:00"), slots.map { it.jsonObject["startLocal"]!!.jsonPrimitive.content })

            // com consulta futura marcada, não desativa
            val appt = req(HttpMethod.Post, "/secretaria/appointments", admin, """{"clinicId":${env.a.clinicId},"doctorId":$id,"patientName":"Ana Souza","patientPhone":"21987654321","startLocal":"2026-10-06T14:00"}""")
            assertEquals(HttpStatusCode.Created, appt.status)
            assertEquals(HttpStatusCode.Conflict, req(HttpMethod.Delete, "/secretaria/doctors/$id?$q", admin).status)
            assertEquals(HttpStatusCode.NoContent, req(HttpMethod.Post, "/secretaria/appointments/${json(appt.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content}/cancel?$q", admin).status)
            assertFalse(json(req(HttpMethod.Delete, "/secretaria/doctors/$id?$q", admin).bodyAsText()).jsonObject["active"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(0, json(req(HttpMethod.Get, "/secretaria/doctors?$q", admin).bodyAsText()).jsonArray.count { it.jsonObject["id"]!!.jsonPrimitive.content == id })
            assertEquals(1, json(req(HttpMethod.Get, "/secretaria/doctors?$q&includeInactive=true", admin).bodyAsText()).jsonArray.count { it.jsonObject["id"]!!.jsonPrimitive.content == id })
        }
    }

    @Test
    fun `pacientes e exclusao LGPD pela API`() = runBlocking<Unit> {
        val env = Env()
        val q = "clinicId=${env.a.clinicId}"
        testApplication {
            startSecretaria(env.fx)
            val admin = token("maria@clinica.test")

            val r = req(HttpMethod.Post, "/secretaria/patients?$q", admin, """{"name":"Carla Dias","phone":"(21) 97777-6666","email":"carla@x.test"}""")
            assertEquals(HttpStatusCode.Created, r.status)
            val id = json(r.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
            assertEquals(HttpStatusCode.Conflict, req(HttpMethod.Post, "/secretaria/patients?$q", admin, """{"name":"carla dias","phone":"21977776666"}""").status)
            assertEquals(HttpStatusCode.BadRequest, req(HttpMethod.Post, "/secretaria/patients?$q", admin, """{"name":"X","phone":"21977776666"}""").status)

            val page = json(req(HttpMethod.Get, "/secretaria/patients?$q&q=carla", admin).bodyAsText()).jsonObject
            assertEquals(1, page["total"]!!.jsonPrimitive.content.toInt())
            assertEquals(HttpStatusCode.OK, req(HttpMethod.Get, "/secretaria/patients/$id?$q", admin).status)
            assertEquals(HttpStatusCode.NotFound, req(HttpMethod.Get, "/secretaria/patients/99999?$q", admin).status)
            assertEquals(HttpStatusCode.BadRequest, req(HttpMethod.Get, "/secretaria/patients/abc?$q", admin).status)

            assertEquals(HttpStatusCode.NoContent, req(HttpMethod.Delete, "/secretaria/patients/$id?$q", admin).status)
            val after = json(req(HttpMethod.Get, "/secretaria/patients/$id?$q", admin).bodyAsText()).jsonObject["patient"]!!.jsonObject
            assertEquals("Paciente removido", after["name"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `agendamentos - status, remarcar, filtros e detalhe da chamada`() = runBlocking<Unit> {
        val env = Env()
        val c = env.a.clinicId
        val q = "clinicId=$c"
        val call = runBlocking { env.fx.calls.start(c, CallChannel.PWA, null) }
        val appt = runBlocking {
            (env.fx.appointments.book(env.clinicA, env.doctorA.id, env.patientA, parseLocalIso("2026-10-06T09:00")!!, AppointmentCreator.IA, call.id) as BookResult.Ok).appointment
        }
        testApplication {
            startSecretaria(env.fx)
            val admin = token("maria@clinica.test")
            val base = "/secretaria/appointments"

            assertEquals(HttpStatusCode.OK, req(HttpMethod.Get, "$base/${appt.id}?$q", admin).status)
            assertEquals("confirmado", json(req(HttpMethod.Post, "$base/${appt.id}/status?$q", admin, """{"status":"confirmado"}""").bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)
            assertEquals(HttpStatusCode.BadRequest, req(HttpMethod.Post, "$base/${appt.id}/status?$q", admin, """{"status":"voando"}""").status)
            assertEquals(HttpStatusCode.BadRequest, req(HttpMethod.Post, "$base/${appt.id}/status?$q", admin, """{"status":"cancelado"}""").status)

            assertEquals(HttpStatusCode.BadRequest, req(HttpMethod.Post, "$base/${appt.id}/reschedule?$q", admin, """{"startLocal":"amanhã"}""").status)
            assertEquals(HttpStatusCode.Conflict, req(HttpMethod.Post, "$base/${appt.id}/reschedule?$q", admin, """{"startLocal":"2026-10-06T12:00"}""").status) // almoço
            val moved = json(req(HttpMethod.Post, "$base/${appt.id}/reschedule?$q", admin, """{"startLocal":"2026-10-07T10:00"}""").bodyAsText()).jsonObject
            assertEquals("2026-10-07T10:00", moved["startLocal"]!!.jsonPrimitive.content)
            assertEquals("remarcado", json(req(HttpMethod.Get, "$base/${appt.id}?$q", admin).bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)

            val window = "from=2026-10-01&to=2026-10-31"
            assertEquals(2, json(req(HttpMethod.Get, "$base?$q&$window", admin).bodyAsText()).jsonArray.size)
            assertEquals(1, json(req(HttpMethod.Get, "$base?$q&$window&status=remarcado", admin).bodyAsText()).jsonArray.size)
            assertEquals(2, json(req(HttpMethod.Get, "$base?$q&$window&patientId=${env.patientA}", admin).bodyAsText()).jsonArray.size)
            assertEquals(0, json(req(HttpMethod.Get, "$base?$q&$window&doctorId=${env.doctorA.id + 1}", admin).bodyAsText()).jsonArray.size)
            assertEquals(HttpStatusCode.BadRequest, req(HttpMethod.Get, "$base?$q&status=xyz", admin).status)
            assertEquals(HttpStatusCode.BadRequest, req(HttpMethod.Get, "$base?$q&from=ontem", admin).status)
            assertEquals(HttpStatusCode.BadRequest, req(HttpMethod.Get, "$base?$q&from=2026-01-01&to=2026-12-31", admin).status) // > 90 dias

            // detalhe da chamada traz o agendamento feito nela
            val detail = json(req(HttpMethod.Get, "/secretaria/calls/${call.id}?$q", admin).bodyAsText()).jsonObject
            assertEquals(1, detail["appointments"]!!.jsonArray.size)
            assertEquals("em_andamento", detail["call"]!!.jsonObject["state"]!!.jsonPrimitive.content)
            assertEquals(HttpStatusCode.NotFound, req(HttpMethod.Get, "/secretaria/calls/${call.id}/recording?$q", admin).status) // sem gravação ainda
        }
    }

    @Test
    fun `chamadas com filtros e notificacoes pela API`() = runBlocking<Unit> {
        val env = Env()
        val c = env.a.clinicId
        val q = "clinicId=$c"
        runBlocking {
            val one = env.fx.calls.start(c, CallChannel.PWA, null)
            env.fx.calls.setSubject(one.id, "Agendamento de consulta", null, null)
            env.fx.calls.setOutcome(one.id, schemas.secretaria.CallOutcome.AGENDADA, schemas.secretaria.CallIntent.AGENDAMENTO)
            env.fx.advanceSeconds(60); env.fx.calls.end(one.id)
            env.fx.calls.start(c, CallChannel.PWA, null)
            env.fx.clinics.notify(c, schemas.secretaria.NotificationType.SISTEMA, "Bem-vinda", null)
        }
        testApplication {
            startSecretaria(env.fx)
            val admin = token("maria@clinica.test")
            val page = json(req(HttpMethod.Get, "/secretaria/calls?$q&limit=1", admin).bodyAsText()).jsonObject
            assertEquals(2, page["total"]!!.jsonPrimitive.content.toInt())
            assertEquals(1, page["items"]!!.jsonArray.size)
            assertEquals(1, json(req(HttpMethod.Get, "/secretaria/calls?$q&status=agendada", admin).bodyAsText()).jsonObject["total"]!!.jsonPrimitive.content.toInt())
            assertEquals(1, json(req(HttpMethod.Get, "/secretaria/calls?$q&q=agendamento", admin).bodyAsText()).jsonObject["total"]!!.jsonPrimitive.content.toInt())
            assertEquals(HttpStatusCode.BadRequest, req(HttpMethod.Get, "/secretaria/calls?$q&from=ontem", admin).status)

            val n = json(req(HttpMethod.Get, "/secretaria/notifications?$q", admin).bodyAsText()).jsonObject
            assertEquals(1, n["unread"]!!.jsonPrimitive.content.toInt())
            val nid = n["items"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content
            assertEquals(HttpStatusCode.NoContent, req(HttpMethod.Post, "/secretaria/notifications/$nid/read?$q", admin).status)
            assertEquals(0, json(req(HttpMethod.Get, "/secretaria/notifications?$q&unreadOnly=true", admin).bodyAsText()).jsonObject["items"]!!.jsonArray.size)
            assertEquals(HttpStatusCode.NotFound, req(HttpMethod.Post, "/secretaria/notifications/99999/read?$q", admin).status)
        }
    }

    // ── relatórios e planos ──────────────────────────────────────────────────

    @Test
    fun `relatorios - resumo e CSV com cabecalhos corretos`() = runBlocking<Unit> {
        val env = Env()
        val q = "clinicId=${env.a.clinicId}"
        runBlocking {
            env.fx.appointments.book(env.clinicA, env.doctorA.id, env.patientA, parseLocalIso("2026-10-06T09:00")!!, AppointmentCreator.IA)
        }
        testApplication {
            startSecretaria(env.fx)
            val admin = token("maria@clinica.test")

            val sum = json(req(HttpMethod.Get, "/secretaria/reports/summary?$q&from=2026-10-05&to=2026-10-06", admin).bodyAsText()).jsonObject
            assertEquals(1, sum["appointmentsCreated"]!!.jsonPrimitive.content.toInt())
            assertEquals(2, sum["perDay"]!!.jsonArray.size)
            assertEquals(24, sum["callsByHour"]!!.jsonArray.size)
            assertEquals(HttpStatusCode.BadRequest, req(HttpMethod.Get, "/secretaria/reports/summary?$q&from=2026-10-06&to=2026-10-05", admin).status)
            assertEquals(HttpStatusCode.BadRequest, req(HttpMethod.Get, "/secretaria/reports/summary?$q&from=xx", admin).status)
            assertEquals(HttpStatusCode.OK, req(HttpMethod.Get, "/secretaria/reports/summary?$q", admin).status) // período padrão

            val csv = req(HttpMethod.Get, "/secretaria/reports/appointments.csv?$q&from=2026-10-06&to=2026-10-06", admin)
            assertEquals(HttpStatusCode.OK, csv.status)
            assertTrue(csv.headers[HttpHeaders.ContentType]!!.startsWith("text/csv"), csv.headers[HttpHeaders.ContentType])
            assertTrue(csv.headers[HttpHeaders.ContentDisposition]!!.contains("agendamentos_2026-10-06_2026-10-06.csv"))
            val text = csv.bodyAsText()
            assertTrue(text.startsWith("﻿id;inicio;fim;medico;especialidade;paciente"), text.take(40)) // BOM + separador ";"
            assertTrue(text.contains("Ana Souza"))
            assertEquals(HttpStatusCode.OK, req(HttpMethod.Get, "/secretaria/reports/calls.csv?$q&from=2026-10-01&to=2026-10-31", admin).status)
        }
    }

    @Test
    fun `plano - detalhe e administracao protegida por chave`() = runBlocking<Unit> {
        val env = Env()
        val q = "clinicId=${env.a.clinicId}"
        testApplication {
            startSecretaria(env.fx)
            val admin = token("maria@clinica.test")

            val detail = json(req(HttpMethod.Get, "/secretaria/plan?$q", admin).bodyAsText()).jsonObject
            assertEquals(10, detail["usage"]!!.jsonObject["includedMinutes"]!!.jsonPrimitive.content.toInt())
            assertEquals(1, detail["availablePlans"]!!.jsonArray.size)

            val plan = """{"name":"Plano Pro","monthlyPrice":499.0,"includedMinutes":1000,"costPerMinute":0.4}"""
            assertEquals(HttpStatusCode.NotFound, req(HttpMethod.Post, "/secretaria/admin/plans", null, plan).status) // sem chave configurada: desligado
        }
        System.setProperty("secretaria.adminKey", "chave-admin-de-teste")
        val env2 = Env()
        testApplication {
            startSecretaria(env2.fx)
            val admin = token("maria@clinica.test")
            suspend fun adm(path: String, body: String, key: String? = "chave-admin-de-teste") = client.request(path) {
                method = HttpMethod.Post
                key?.let { header("X-Admin-Key", it) }
                contentType(ContentType.Application.Json); setBody(body)
            }
            val plan = """{"name":"Plano Pro","monthlyPrice":499.0,"includedMinutes":1000,"costPerMinute":0.4}"""
            assertEquals(HttpStatusCode.Forbidden, adm("/secretaria/admin/plans", plan, null).status)
            assertEquals(HttpStatusCode.Forbidden, adm("/secretaria/admin/plans", plan, "errada").status)
            val created = adm("/secretaria/admin/plans", plan)
            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals(HttpStatusCode.Conflict, adm("/secretaria/admin/plans", plan).status)
            val planId = json(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
            assertEquals(HttpStatusCode.NoContent, adm("/secretaria/admin/subscription", """{"clinicId":${env2.a.clinicId},"planId":$planId}""").status)
            assertEquals(HttpStatusCode.NotFound, adm("/secretaria/admin/subscription", """{"clinicId":999,"planId":$planId}""").status)
            assertEquals(1000, json(req(HttpMethod.Get, "/secretaria/plan?clinicId=${env2.a.clinicId}", admin).bodyAsText()).jsonObject["usage"]!!.jsonObject["includedMinutes"]!!.jsonPrimitive.content.toInt())
            assertNotNull(JsonArray(emptyList()))
        }
    }

    @Test
    fun `cadastro publico de clinica - cria clinica e admin, ja devolve a sessao e valida os dados`() = runBlocking<Unit> {
        val env = Env()
        testApplication {
            startSecretaria(env.fx)
            suspend fun register(email: String = "nova@clinica.test", password: String = "senha-bem-longa-1", clinic: String = "Clínica Nova", extra: String = "") =
                req(
                    HttpMethod.Post, "/secretaria/auth/register-clinic",
                    body = """{"clinicName":"$clinic","responsibleName":"Dra. Nova","userName":"Nina Nova","email":"$email","password":"$password"$extra}""",
                )

            // sem chave de administração nem token: é público e já devolve a sessão (mesmo formato do login)
            val created = register(email = "  Nova@Clinica.test ")
            assertEquals(HttpStatusCode.Created, created.status)
            val session = json(created.bodyAsText()).jsonObject
            val token = session["token"]!!.jsonPrimitive.content
            assertEquals("nova@clinica.test", session["user"]!!.jsonObject["email"]!!.jsonPrimitive.content)
            val clinic = session["clinics"]!!.jsonArray.single().jsonObject
            assertEquals("Clínica Nova", clinic["name"]!!.jsonPrimitive.content)
            assertEquals("admin", clinic["role"]!!.jsonPrimitive.content)
            val clinicId = clinic["id"]!!.jsonPrimitive.content

            // o token funciona e quem se cadastrou é administrador: vê a chave pública, que abre o PWA do paciente
            assertEquals(HttpStatusCode.OK, req(HttpMethod.Get, "/secretaria/me", token).status)
            val detail = json(req(HttpMethod.Get, "/secretaria/clinic?clinicId=$clinicId", token).bodyAsText()).jsonObject
            val publicKey = detail["publicKey"]!!.jsonPrimitive.content
            assertEquals(HttpStatusCode.OK, req(HttpMethod.Get, "/secretaria/public/$publicKey").status)
            assertEquals(1, json(req(HttpMethod.Get, "/secretaria/plan?clinicId=$clinicId", token).bodyAsText()).jsonObject["availablePlans"]!!.jsonArray.size)

            // o login normal também funciona com a senha escolhida
            assertEquals(HttpStatusCode.OK, req(HttpMethod.Post, "/secretaria/auth/login", body = """{"email":"nova@clinica.test","password":"senha-bem-longa-1"}""").status)

            // e-mail já usado (mesmo com outra caixa), senha curta, e-mail inválido, nome vazio, fuso inválido, JSON ruim
            assertEquals(HttpStatusCode.Conflict, register(email = "NOVA@clinica.test").status)
            assertEquals(HttpStatusCode.Conflict, register(email = "maria@clinica.test").status) // já existe (clínica A)
            assertEquals(HttpStatusCode.BadRequest, register(email = "curta@clinica.test", password = "12345").status) // mínimo: 6
            assertEquals(HttpStatusCode.Created, register(email = "seis@clinica.test", password = "abc123").status)
            assertEquals(HttpStatusCode.BadRequest, register(email = "sem-arroba").status)
            assertEquals(HttpStatusCode.BadRequest, register(email = "vazio@clinica.test", clinic = "   ").status)
            assertEquals(HttpStatusCode.BadRequest, register(email = "fuso@clinica.test", extra = ""","timezone":"Marte/Olimpo" """).status)
            assertEquals(HttpStatusCode.BadRequest, req(HttpMethod.Post, "/secretaria/auth/register-clinic", body = "isso não é json").status)

            // nada foi criado pelas tentativas recusadas: o admin da nova clínica não enxerga a clínica de outro
            assertEquals(HttpStatusCode.Forbidden, req(HttpMethod.Get, "/secretaria/clinic?clinicId=${env.a.clinicId}", token).status)
        }

        // o afrouxamento da senha vale só para o cadastro público: o /admin/bootstrap continua pedindo 10+
        System.setProperty("secretaria.adminKey", "chave-admin-de-teste")
        testApplication {
            startSecretaria(env.fx)
            suspend fun bootstrap(password: String) = client.request("/secretaria/admin/bootstrap") {
                method = HttpMethod.Post
                header("X-Admin-Key", "chave-admin-de-teste")
                contentType(ContentType.Application.Json)
                setBody("""{"clinicName":"Outra","userName":"Fulano","email":"fulano@outra.test","password":"$password"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, bootstrap("abc123").status)
            assertEquals(HttpStatusCode.Created, bootstrap("abc123def4").status)
        }
    }

    @Test
    fun `lista publica de clinicas - so as ativas, com a chave que abre o PWA e o status da IA`() = runBlocking<Unit> {
        val env = Env()
        testApplication {
            startSecretaria(env.fx)
            val admin = token("maria@clinica.test")
            suspend fun directory() = json(req(HttpMethod.Get, "/secretaria/public/clinics").bodyAsText()).jsonArray.map { it.jsonObject }
            fun JsonObject.text(name: String) = this[name]!!.jsonPrimitive.content
            fun JsonObject.online() = this["online"]!!.jsonPrimitive.boolean

            // sem login: as duas clínicas, em ordem alfabética, todas atendendo
            assertEquals(HttpStatusCode.OK, req(HttpMethod.Get, "/secretaria/public/clinics").status)
            val list = directory()
            assertEquals(listOf("Clínica Teste", "Outra Clínica"), list.map { it.text("name") })
            assertTrue(list.all { it.online() })
            assertEquals("Dr. Henrique Martins", list[0].text("responsibleName"))

            // a chave da lista é a mesma que o administrador vê e abre a página pública da clínica
            val keyA = list[0].text("publicKey")
            val ownKey = json(req(HttpMethod.Get, "/secretaria/clinic?clinicId=${env.a.clinicId}", admin).bodyAsText()).jsonObject.text("publicKey")
            assertEquals(ownKey, keyA)
            assertEquals(HttpStatusCode.OK, req(HttpMethod.Get, "/secretaria/public/$keyA").status)

            // a clínica desliga a IA: só ela aparece indisponível
            assertEquals(HttpStatusCode.OK, req(HttpMethod.Put, "/secretaria/settings?clinicId=${env.a.clinicId}", admin, """{"aiEnabled":false}""").status)
            assertEquals(listOf(false, true), directory().map { it.online() })

            // servidor sem chave do Gemini: ninguém atende
            env.fx.geminiConfigured = false
            assertEquals(listOf(false, false), directory().map { it.online() })
            env.fx.geminiConfigured = true

            // girar a chave: a lista passa a mostrar a nova e a antiga deixa de abrir
            assertEquals(HttpStatusCode.OK, req(HttpMethod.Post, "/secretaria/clinic/rotate-key?clinicId=${env.a.clinicId}", admin).status)
            val rotated = directory()[0].text("publicKey")
            assertTrue(rotated != keyA)
            assertEquals(HttpStatusCode.NotFound, req(HttpMethod.Get, "/secretaria/public/$keyA").status)

            // clínica desativada some da lista (e a chave dela deixa de abrir)
            val keyB = directory()[1].text("publicKey")
            transaction(env.fx.db) { ClinicsTable.update({ ClinicsTable.id eq env.b.clinicId }) { it[active] = false } }
            assertEquals(listOf("Clínica Teste"), directory().map { it.text("name") })
            assertEquals(HttpStatusCode.NotFound, req(HttpMethod.Get, "/secretaria/public/$keyB").status)
        }
    }

    @Test
    fun `app do paciente - cadastro e consultas pelo CPF, foto no painel so da propria clinica e LGPD`() = runBlocking<Unit> {
        val env = Env()
        testApplication {
            startSecretaria(env.fx)
            val adminA = token("maria@clinica.test")
            val adminB = token("dona@outra.test")
            suspend fun save(body: String) = req(HttpMethod.Post, "/secretaria/public/patients", body = body)
            val ana = """{"cpf":"529.982.247-25","name":"Ana Souza","phone":"(21) 98765-4321","email":"ana@exemplo.test","healthPlan":"Unimed","photo":"${SecretariaProfileTest.JPEG_DATA_URL}"}"""

            // cadastro sem login, validado; o CPF vai no corpo
            assertEquals(HttpStatusCode.BadRequest, save(ana.replace("529.982.247-25", "529.982.247-24")).status)
            val saved = save(ana)
            assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
            val profile = json(saved.bodyAsText()).jsonObject
            assertEquals("52998224725", profile["cpf"]!!.jsonPrimitive.content)
            assertTrue(profile["hasPhoto"]!!.jsonPrimitive.boolean)

            // entrar pelo CPF em outro aparelho: o servidor devolve o cadastro salvo
            suspend fun lookup(cpf: String) = req(HttpMethod.Post, "/secretaria/public/patients/lookup", body = """{"cpf":"$cpf"}""")
            assertEquals(HttpStatusCode.NotFound, lookup("390.533.447-05").status)
            assertEquals(HttpStatusCode.BadRequest, lookup("123").status)
            val back = json(lookup("52998224725").bodyAsText()).jsonObject
            assertEquals("Ana Souza", back["name"]!!.jsonPrimitive.content)
            assertEquals("Unimed", back["healthPlan"]!!.jsonPrimitive.content)
            assertEquals(SecretariaProfileTest.JPEG_DATA_URL, back["photo"]!!.jsonPrimitive.content)

            // consultas do app
            suspend fun mine(cpf: String) = req(HttpMethod.Post, "/secretaria/public/patients/appointments", body = """{"cpf":"$cpf"}""")
            assertEquals(HttpStatusCode.NotFound, mine("390.533.447-05").status)
            assertEquals(HttpStatusCode.BadRequest, mine("123").status)
            assertEquals(0, json(mine("52998224725").bodyAsText()).jsonObject["upcoming"]!!.jsonArray.size)

            // ela liga para a clínica A: a ficha nasce com CPF, convênio e foto
            val patientId = env.fx.profiles.linkToClinic(env.a.clinicId, env.fx.profiles.find("52998224725")!!)
            val list = json(req(HttpMethod.Get, "/secretaria/patients?clinicId=${env.a.clinicId}&q=98224", adminA).bodyAsText()).jsonObject
            val item = list["items"]!!.jsonArray.single().jsonObject // busca também pelo CPF
            assertEquals("52998224725", item["cpf"]!!.jsonPrimitive.content)
            assertEquals("Unimed", item["healthPlan"]!!.jsonPrimitive.content)
            assertTrue(item["hasPhoto"]!!.jsonPrimitive.boolean)

            val photo = req(HttpMethod.Get, "/secretaria/patients/$patientId/photo?clinicId=${env.a.clinicId}", adminA)
            assertEquals(HttpStatusCode.OK, photo.status)
            assertEquals(SecretariaProfileTest.JPEG_DATA_URL, json(photo.bodyAsText()).jsonObject["photo"]!!.jsonPrimitive.content)
            // a clínica B não vê a foto de paciente da A (nem pedindo pela A, nem pela própria)
            assertEquals(HttpStatusCode.Forbidden, req(HttpMethod.Get, "/secretaria/patients/$patientId/photo?clinicId=${env.a.clinicId}", adminB).status)
            assertEquals(HttpStatusCode.NotFound, req(HttpMethod.Get, "/secretaria/patients/$patientId/photo?clinicId=${env.b.clinicId}", adminB).status)
            assertEquals(HttpStatusCode.Unauthorized, req(HttpMethod.Get, "/secretaria/patients/$patientId/photo?clinicId=${env.a.clinicId}").status)

            // exclusão LGPD na clínica: some CPF, convênio e a ligação com o app (e a foto deixa de aparecer)
            assertEquals(HttpStatusCode.NoContent, req(HttpMethod.Delete, "/secretaria/patients/$patientId?clinicId=${env.a.clinicId}", adminA).status)
            val after = json(req(HttpMethod.Get, "/secretaria/patients/$patientId?clinicId=${env.a.clinicId}", adminA).bodyAsText()).jsonObject["patient"]!!.jsonObject
            assertEquals("Paciente removido", after["name"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, after["cpf"])
            assertFalse(after["hasPhoto"]!!.jsonPrimitive.boolean)
            assertEquals(HttpStatusCode.NotFound, req(HttpMethod.Get, "/secretaria/patients/$patientId/photo?clinicId=${env.a.clinicId}", adminA).status)
        }
    }
}
