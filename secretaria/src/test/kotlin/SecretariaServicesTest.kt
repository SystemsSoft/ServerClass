import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import schemas.secretaria.AppointmentCreator
import schemas.secretaria.BookResult
import schemas.secretaria.CallChannel
import schemas.secretaria.CallIntent
import schemas.secretaria.CallOutcome
import schemas.secretaria.EmailAlreadyUsedException
import schemas.secretaria.Speaker
import schemas.secretaria.parseLocalIso
import schemas.secretaria.PlansTable
import schemas.secretaria.SecretariaSchema
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretariaServicesTest {

    private val monday = LocalDate.of(2026, 10, 5)
    private fun local(text: String) = parseLocalIso(text)!!

    // ── clínica e login ──────────────────────────────────────────────────────

    @Test
    fun `bootstrap cria clinica, admin, medicos, agenda e plano`() = runBlocking<Unit> {
        val fx = SecretariaFixture()
        val boot = fx.bootstrap()

        val clinic = fx.clinic(boot.clinicId)
        assertEquals("Clínica Teste", clinic.name)
        assertEquals(2, fx.clinics.doctors(boot.clinicId).size)
        assertTrue(boot.publicKey.length >= 30)
        assertEquals(boot.clinicId, fx.clinics.clinicByPublicKey(boot.publicKey)?.id)
        assertNull(fx.clinics.clinicByPublicKey("chave-errada"))

        val plan = fx.clinics.planUsage(boot.clinicId, clinic.zone)
        assertNotNull(plan)
        assertEquals(10, plan.includedMinutes) // clínica nova: teste grátis
        assertEquals("Teste grátis", plan.planName); assertTrue(plan.trial); assertEquals(0.0, plan.monthlyPrice)
        assertEquals(0, plan.usedMinutes)
    }

    @Test
    fun `e-mail duplicado e recusado`() = runBlocking<Unit> {
        val fx = SecretariaFixture()
        fx.bootstrap()
        assertFailsWith<EmailAlreadyUsedException> { fx.bootstrap(clinicName = "Outra") }
        Unit
    }

    @Test
    fun `login valida senha e devolve token e clinicas`() = runBlocking<Unit> {
        val fx = SecretariaFixture()
        val boot = fx.bootstrap()

        assertNull(fx.auth.login("maria@clinica.test", "senha-errada"))
        assertNull(fx.auth.login("ninguem@clinica.test", "senha-segura-123"))
        val ok = assertNotNull(fx.auth.login("  Maria@Clinica.test ", "senha-segura-123"))
        assertEquals(boot.userId, fx.auth.userIdFromToken(ok.token))
        assertEquals(listOf("admin"), ok.clinics.map { it.role })
        assertEquals(schemas.secretaria.ClinicRole.ADMIN, fx.auth.roleIn(boot.userId, boot.clinicId))
        assertNull(fx.auth.roleIn(boot.userId, 9999))
    }

    // ── horários e agendamentos ──────────────────────────────────────────────

    @Test
    fun `horarios livres respeitam a antecedencia minima e a agenda`() = runBlocking<Unit> {
        val fx = SecretariaFixture() // segunda 09:00 -> primeiro livre é 09:30
        val clinic = fx.clinic(fx.bootstrap().clinicId)
        val doctorId = fx.clinics.doctors(clinic.id).first { it.specialty == "Cardiologia" }.id

        val slots = fx.appointments.availableSlots(clinic, doctorId, null, monday, days = 1, limit = 50)
        assertEquals("2026-10-05T09:30", slots.first().startLocal)
        assertEquals(13, slots.size) // 5 de manhã (09:30-11:30) + 8 à tarde
        assertTrue(slots.all { it.doctorId == doctorId && it.specialty == "Cardiologia" })

        assertTrue(fx.appointments.availableSlots(clinic, doctorId, null, LocalDate.of(2026, 10, 10), days = 2, limit = 50).isEmpty())
        assertEquals(0, fx.appointments.availableSlots(clinic, null, "dermato", monday, days = 7, limit = 50).size)
        assertTrue(fx.appointments.availableSlots(clinic, null, "cardio", monday, days = 7, limit = 3).size == 3) // filtro parcial + limite
    }

    @Test
    fun `agendar ocupa o horario, duplicar falha e cancelar libera`() = runBlocking<Unit> {
        val fx = SecretariaFixture()
        val clinic = fx.clinic(fx.bootstrap().clinicId)
        val doctorId = fx.clinics.doctors(clinic.id).first().id
        val ana = fx.clinics.findOrCreatePatient(clinic.id, "Ana Souza", "5521987654321")
        val beto = fx.clinics.findOrCreatePatient(clinic.id, "Beto Lima", "5521911112222")
        val start = local("2026-10-06T09:00")

        val first = assertIs<BookResult.Ok>(fx.appointments.book(clinic, doctorId, ana, start, AppointmentCreator.IA))
        assertEquals("2026-10-06T09:00", first.appointment.startLocal)
        assertEquals("2026-10-06T09:30", first.appointment.endLocal)
        assertEquals("agendado", first.appointment.status)

        assertIs<BookResult.Fail>(fx.appointments.book(clinic, doctorId, beto, start, AppointmentCreator.IA)) // mesmo horário
        val taken = fx.appointments.availableSlots(clinic, doctorId, null, LocalDate.of(2026, 10, 6), 1, 50)
        assertFalse(taken.any { it.startLocal == "2026-10-06T09:00" })

        assertTrue(fx.appointments.cancel(clinic.id, first.appointment.id, onlyPatientId = null))
        assertFalse(fx.appointments.cancel(clinic.id, first.appointment.id, onlyPatientId = null)) // já cancelado
        assertIs<BookResult.Ok>(fx.appointments.book(clinic, doctorId, beto, start, AppointmentCreator.IA)) // horário liberado
    }

    @Test
    fun `agendamento recusa horario fora da agenda, desalinhado, passado e de outra clinica`() = runBlocking<Unit> {
        val fx = SecretariaFixture()
        val clinic = fx.clinic(fx.bootstrap().clinicId)
        val other = fx.clinic(fx.bootstrap(email = "b@x.test", clinicName = "Outra").clinicId)
        val doctorId = fx.clinics.doctors(clinic.id).first().id
        val patient = fx.clinics.findOrCreatePatient(clinic.id, "Ana Souza", "5521987654321")

        fun book(text: String, c: schemas.secretaria.ClinicInfo = clinic) =
            runBlocking { fx.appointments.book(c, doctorId, patient, local(text), AppointmentCreator.IA) }

        assertIs<BookResult.Fail>(book("2026-10-06T12:00")) // almoço
        assertIs<BookResult.Fail>(book("2026-10-06T07:30")) // antes do expediente
        assertIs<BookResult.Fail>(book("2026-10-06T09:15")) // desalinhado
        assertIs<BookResult.Fail>(book("2026-10-11T09:00")) // domingo
        assertIs<BookResult.Fail>(book("2026-10-05T09:00")) // agora (dentro da antecedência)
        assertIs<BookResult.Fail>(book("2026-10-04T09:00")) // passado
        assertIs<BookResult.Fail>(book("2026-10-06T09:00", other)) // médico de outra clínica
        Unit
    }

    @Test
    fun `remarcar move o agendamento e libera o horario antigo`() = runBlocking<Unit> {
        val fx = SecretariaFixture()
        val clinic = fx.clinic(fx.bootstrap().clinicId)
        val doctorId = fx.clinics.doctors(clinic.id).first().id
        val ana = fx.clinics.findOrCreatePatient(clinic.id, "Ana Souza", "5521987654321")
        val outro = fx.clinics.findOrCreatePatient(clinic.id, "Beto Lima", "5521911112222")
        val old = (fx.appointments.book(clinic, doctorId, ana, local("2026-10-06T09:00"), AppointmentCreator.IA) as BookResult.Ok).appointment

        // outro paciente não pode mexer no agendamento da Ana
        assertIs<BookResult.Fail>(fx.appointments.reschedule(clinic, old.id, local("2026-10-07T10:00"), outro, AppointmentCreator.IA))
        // horário de destino inválido: nada muda
        assertIs<BookResult.Fail>(fx.appointments.reschedule(clinic, old.id, local("2026-10-07T12:00"), ana, AppointmentCreator.IA))
        assertEquals("agendado", fx.appointments.get(clinic, old.id)?.status)

        val moved = assertIs<BookResult.Ok>(fx.appointments.reschedule(clinic, old.id, local("2026-10-07T10:00"), ana, AppointmentCreator.IA)).appointment
        assertEquals("2026-10-07T10:00", moved.startLocal)
        assertEquals("remarcado", fx.appointments.get(clinic, old.id)?.status)
        assertIs<BookResult.Ok>(fx.appointments.book(clinic, doctorId, outro, local("2026-10-06T09:00"), AppointmentCreator.IA)) // antigo livre

        val upcoming = fx.appointments.upcomingForPatients(clinic, listOf(ana)).map { it.id }
        assertEquals(listOf(moved.id), upcoming) // só o ativo
    }

    @Test
    fun `corrida - duas reservas simultaneas do mesmo horario, so uma vence`() = runBlocking<Unit> {
        val fx = SecretariaFixture()
        val clinic = fx.clinic(fx.bootstrap().clinicId)
        val doctorId = fx.clinics.doctors(clinic.id).first().id
        val patients = (1..8).map { fx.clinics.findOrCreatePatient(clinic.id, "Paciente Numero$it", "55219000000$it") }

        val results = coroutineScope {
            patients.map { p ->
                async(Dispatchers.Default) {
                    fx.appointments.book(clinic, doctorId, p, local("2026-10-06T10:00"), AppointmentCreator.IA)
                }
            }.map { it.await() }
        }
        assertEquals(1, results.count { it is BookResult.Ok })
        assertEquals(7, results.count { it is BookResult.Fail })
    }

    @Test
    fun `mesmo telefone com nomes diferentes gera pacientes diferentes sem sobrescrever`() = runBlocking<Unit> {
        val fx = SecretariaFixture()
        val clinic = fx.clinic(fx.bootstrap().clinicId)
        val a = fx.clinics.findOrCreatePatient(clinic.id, "Ana Souza", "5521987654321")
        assertEquals(a, fx.clinics.findOrCreatePatient(clinic.id, "ana souza", "5521987654321"))
        val filha = fx.clinics.findOrCreatePatient(clinic.id, "Clara Souza", "5521987654321")
        assertTrue(a != filha)
        assertEquals(setOf("Ana Souza", "Clara Souza"), fx.clinics.patientsByPhone(clinic.id, "5521987654321").map { it.name }.toSet())
    }

    // ── chamadas, dashboard e plano ──────────────────────────────────────────

    @Test
    fun `ciclo de vida da chamada - duracao, resultado padrao e idempotencia`() = runBlocking<Unit> {
        val fx = SecretariaFixture()
        val clinic = fx.clinic(fx.bootstrap().clinicId)

        val call = fx.calls.start(clinic.id, CallChannel.PWA, "modelo-x")
        assertEquals(1, fx.calls.activeCount(clinic.id))
        assertNotNull(fx.calls.activeCall(clinic.id))
        fx.calls.setSubject(call.id, "Dúvida", "Respondendo dúvida sobre exames", CallIntent.INFORMACAO)
        assertEquals("Respondendo dúvida sobre exames", fx.calls.activeCall(clinic.id)?.description)

        fx.advanceSeconds(154)
        assertTrue(fx.calls.end(call.id))
        assertFalse(fx.calls.end(call.id)) // idempotente

        val done = assertNotNull(fx.calls.get(clinic.id, call.id))
        assertEquals(154, done.durationSeconds)
        assertEquals("finalizada", done.status) // nada marcado -> finalizada
        assertEquals("encerrada", done.state)
        assertEquals(0, fx.calls.activeCount(clinic.id))
        assertNull(fx.calls.activeCall(clinic.id))
    }

    @Test
    fun `transcricao fica ordenada e isolada por clinica`() = runBlocking<Unit> {
        val fx = SecretariaFixture()
        val clinic = fx.clinic(fx.bootstrap().clinicId)
        val other = fx.clinic(fx.bootstrap(email = "b@x.test", clinicName = "Outra").clinicId)
        val call = fx.calls.start(clinic.id, CallChannel.PWA, null)
        fx.calls.appendMessage(call.id, Speaker.IA, "Olá!", 100)
        fx.calls.appendMessage(call.id, Speaker.PACIENTE, "Quero marcar", 900)
        fx.calls.appendMessage(call.id, Speaker.PACIENTE, "   ", 950) // vazio é ignorado

        val transcript = assertNotNull(fx.calls.transcript(clinic.id, call.id))
        assertEquals(listOf("ia", "paciente"), transcript.map { it.speaker })
        assertNull(fx.calls.transcript(other.id, call.id)) // outra clínica não enxerga
        assertTrue(fx.calls.get(clinic.id, call.id)!!.hasTranscript)
    }

    @Test
    fun `chamadas orfas sao fechadas`() = runBlocking<Unit> {
        val fx = SecretariaFixture()
        val clinic = fx.clinic(fx.bootstrap().clinicId)
        fx.calls.start(clinic.id, CallChannel.PWA, null)
        fx.advanceSeconds(2 * 3600)
        assertEquals(1, fx.calls.closeStale(olderThanMs = 3_600_000))
        assertEquals(0, fx.calls.activeCount(clinic.id))
    }

    @Test
    fun `dashboard consolida metricas, chamadas, agenda e plano`() = runBlocking<Unit> {
        val fx = SecretariaFixture()
        val boot = fx.bootstrap()
        val clinic = fx.clinic(boot.clinicId)
        val summary = fx.auth.clinicsOf(boot.userId).single()
        val doctorId = fx.clinics.doctors(clinic.id).first().id

        // ontem: 1 chamada de 60 s  |  hoje: 2 chamadas (154 s e 100 s) + 1 em andamento
        fx.advanceSeconds(-24 * 3600)
        fx.calls.start(clinic.id, CallChannel.PWA, null).also { fx.advanceSeconds(60); fx.calls.end(it.id) }
        fx.advanceSeconds(24 * 3600 - 60)
        fx.calls.start(clinic.id, CallChannel.PWA, null).also { fx.advanceSeconds(154); fx.calls.end(it.id) }
        val withBooking = fx.calls.start(clinic.id, CallChannel.PWA, null)
        val pid = fx.clinics.findOrCreatePatient(clinic.id, "Ana Souza", "5521987654321")
        fx.appointments.book(clinic, doctorId, pid, local("2026-10-06T09:00"), AppointmentCreator.IA, withBooking.id)
        fx.calls.attachPatient(withBooking.id, pid, "Ana Souza", "5521987654321")
        fx.calls.setOutcome(withBooking.id, CallOutcome.AGENDADA, CallIntent.AGENDAMENTO)
        fx.advanceSeconds(100); fx.calls.end(withBooking.id)
        fx.calls.start(clinic.id, CallChannel.PWA, null) // em andamento
        fx.clinics.notify(clinic.id, schemas.secretaria.NotificationType.SISTEMA, "Olá", null)

        val d = assertNotNull(fx.dashboard.build(summary, boot.userId, "Maria Silva"))
        assertEquals(3, d.stats.callsToday.today)
        assertEquals(1, d.stats.callsToday.yesterday)
        assertEquals(200, d.stats.callsToday.changePercent)
        assertTrue(d.stats.callsToday.trendUp)
        assertEquals(1, d.stats.appointmentsToday.today)
        assertEquals(127, d.stats.avgSeconds.today) // média de 154 e 100
        assertEquals(60, d.stats.avgSeconds.yesterday)
        assertNotNull(d.activeCall)
        assertEquals(3, d.recentCalls.size) // só encerradas (inclui a de ontem)
        assertTrue(d.recentCalls.all { it.state == "encerrada" })
        assertEquals("Ana Souza", d.recentCalls.first().patientName)
        assertEquals("agendada", d.recentCalls.first().status)
        assertEquals(1, d.upcomingAppointments.size)
        assertEquals(1, d.unreadNotifications)

        // plano: só conta o ciclo atual (a chamada de ontem é anterior ao início): 154 + 100 = 254 s -> 5 min; R$ 0,12/min
        val plan = assertNotNull(d.plan)
        assertEquals(5, plan.usedMinutes)
        assertEquals(0.12, plan.costPerMinute)
        assertEquals(0.6, plan.estimatedCost)
        assertEquals(5.0 / 10, plan.usageRatio, 1e-9)

        fx.clinics.markNotificationsRead(clinic.id, boot.userId)
        assertEquals(0, fx.clinics.unreadNotifications(clinic.id, boot.userId))
    }

    @Test
    fun `estimativa antiga de R$ 0,50 por minuto do plano padrao vira R$ 0,12 ao subir o servidor`() = runBlocking<Unit> {
        val fx = SecretariaFixture()
        val clinic = fx.clinic(fx.bootstrap().clinicId)
        // simula uma clínica antiga de produção: no "Plano Clínica" de 300 minutos, com o valor antigo
        val old = (fx.plans.createPlan(schemas.secretaria.CreatePlanRequest("Plano Clínica", 199.0, 300, 0.5)) as schemas.secretaria.ServiceResult.Ok).value
        fx.plans.assign(clinic.id, old.id)
        assertEquals(0.5, fx.clinics.planUsage(clinic.id, clinic.zone)!!.costPerMinute)
        SecretariaSchema.create(fx.db)
        assertEquals(0.12, fx.clinics.planUsage(clinic.id, clinic.zone)!!.costPerMinute)

        // valor ajustado à mão (diferente do antigo padrão) é mantido
        transaction(fx.db) { PlansTable.update { it[costPerMinute] = java.math.BigDecimal("0.3000") } }
        SecretariaSchema.create(fx.db)
        assertEquals(0.3, fx.clinics.planUsage(clinic.id, clinic.zone)!!.costPerMinute)
    }
}
