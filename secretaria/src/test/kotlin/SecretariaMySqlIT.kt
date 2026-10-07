import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.junit.Assume.assumeTrue
import schemas.secretaria.AppointmentCreator
import schemas.secretaria.AddTeamMemberRequest
import schemas.secretaria.BookResult
import schemas.secretaria.CallChannel
import schemas.secretaria.DoctorUpsertRequest
import schemas.secretaria.ScheduleWindowDto
import schemas.secretaria.ServiceResult
import schemas.secretaria.Speaker
import schemas.secretaria.UpdateClinicRequest
import schemas.secretaria.UpdateSettingsRequest
import schemas.secretaria.SecretariaSchema
import schemas.secretaria.parseLocalIso
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Teste de integração OPCIONAL contra um MySQL real (o H2 em "modo MySQL" não garante tudo).
 * Só roda se SECRETARIA_TEST_MYSQL_URL estiver definida, p.ex.:
 *   SECRETARIA_TEST_MYSQL_URL=jdbc:mysql://127.0.0.1:3306/secretaria_it SECRETARIA_TEST_MYSQL_USER=... SECRETARIA_TEST_MYSQL_PASSWORD=...
 * Use um banco DESCARTÁVEL: o teste cria tabelas e grava dados.
 */
class SecretariaMySqlIT {

    @Test
    fun `esquema, agendamento e disputa por horario num MySQL real`() = runBlocking<Unit> {
        val url = System.getenv("SECRETARIA_TEST_MYSQL_URL")
        assumeTrue("SECRETARIA_TEST_MYSQL_URL não definida — teste de integração ignorado", url != null)

        // Mesma configuração do conectarBanco() de produção.
        val source = HikariDataSource(HikariConfig().apply {
            jdbcUrl = url
            username = System.getenv("SECRETARIA_TEST_MYSQL_USER")
            password = System.getenv("SECRETARIA_TEST_MYSQL_PASSWORD")
            driverClassName = "com.mysql.cj.jdbc.Driver"
            maximumPoolSize = 10
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        })
        val fx = SecretariaFixture(database = Database.connect(source))
        SecretariaSchema.create(fx.db) // segunda execução: precisa ser idempotente (partida do servidor)

        val boot = fx.bootstrap(email = "it-${System.nanoTime()}@clinica.test")
        val clinic = fx.clinic(boot.clinicId)
        val doctorId = fx.clinics.doctors(clinic.id).first().id
        val start = parseLocalIso("2026-10-06T10:00")!!

        val patients = (1..12).map { fx.clinics.findOrCreatePatient(clinic.id, "Paciente Numero$it", "552190000%04d".format(it)) }
        val results = coroutineScope {
            patients.map { p -> async(Dispatchers.Default) { fx.appointments.book(clinic, doctorId, p, start, AppointmentCreator.IA) } }.map { it.await() }
        }
        assertEquals(1, results.count { it is BookResult.Ok }, "só uma reserva do mesmo horário pode vencer")
        assertEquals(11, results.count { it is BookResult.Fail })

        val winner = (results.first { it is BookResult.Ok } as BookResult.Ok).appointment
        assertEquals(true, fx.appointments.cancel(clinic.id, winner.id, null))
        assertEquals(true, fx.appointments.book(clinic, doctorId, patients[0], start, AppointmentCreator.IA) is BookResult.Ok) // liberou

        val summary = fx.auth.clinicsOf(boot.userId).single()
        val dash = assertNotNull(fx.dashboard.build(summary, boot.userId, "Maria"))
        assertEquals(1, dash.upcomingAppointments.size)
        assertNotNull(dash.plan)
        assertEquals(LocalDate.of(2026, 10, 6), LocalDate.parse(dash.upcomingAppointments.single().startLocal.substringBefore('T')))

        // ── consultas novas, no MySQL de verdade (LIKE, joins, paginação, agregações, LGPD) ──
        val ok = { r: ServiceResult<*> -> assertEquals(true, r is ServiceResult.Ok, r.toString()) }
        ok(fx.settings.updateSettings(clinic.id, UpdateSettingsRequest(aiEnabled = true, voice = "Puck", extraInstructions = "Aceitamos Unimed")))
        assertEquals("Puck", fx.settings.settings(clinic.id).voice)
        ok(fx.settings.updateClinic(clinic.id, UpdateClinicRequest(address = "Rua A, 10", cnpj = "12345678000195")))

        ok(fx.team.add(clinic.id, AddTeamMemberRequest("sec@clinica.test", "secretaria", "Sara Secretária", "senha-bem-longa-1")))
        assertEquals(2, fx.team.list(clinic.id).size)

        assertEquals(true, fx.patients.page(clinic.id, "PACIENTE numero1", 5, 0).total >= 1) // maiúsculas/minúsculas
        assertEquals(12, fx.patients.page(clinic.id, null, 50, 0).total)
        assertEquals(5, fx.patients.page(clinic.id, null, 5, 5).items.size)

        val call = fx.calls.start(clinic.id, CallChannel.PWA, null)
        fx.calls.attachPatient(call.id, patients[0], "Paciente Numero1", "552190000001")
        fx.calls.setSubject(call.id, "Agendamento de Consulta", null, null)
        fx.calls.appendMessage(call.id, Speaker.PACIENTE, "olá", 10)
        fx.advanceSeconds(120); fx.calls.end(call.id)
        val calls = fx.calls.page(clinic.id, 10, 0, query = "agendamento", status = "finalizada")
        assertEquals(1, calls.total)
        assertEquals(true, calls.items.single().hasTranscript)

        val d = (fx.catalog.createDoctor(clinic.id, DoctorUpsertRequest("Dra. Teste Reis", "Pediatria")) as ServiceResult.Ok).value
        ok(fx.catalog.replaceSchedule(clinic.id, d.id, listOf(ScheduleWindowDto(2, "08:00", "12:00", 30))))
        assertEquals(1, (fx.catalog.schedule(clinic.id, d.id) as ServiceResult.Ok).value.size)

        val report = (fx.reports.summary(clinic, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 7)) as ServiceResult.Ok).value
        assertEquals(1, report.totalCalls)
        assertEquals(1, (fx.plans.detail(clinic) as ServiceResult.Ok).value.usageByDay.size)
        assertEquals(true, (fx.reports.appointmentsCsv(clinic, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 7)) as ServiceResult.Ok).value.startsWith("id;inicio"))

        ok(fx.patients.anonymize(clinic.id, patients[0]))
        assertEquals("Paciente removido", (fx.patients.detail(clinic, patients[0]) as ServiceResult.Ok).value.patient.name)
        assertEquals(false, fx.calls.get(clinic.id, call.id)!!.hasTranscript)
        source.close()
    }
}
