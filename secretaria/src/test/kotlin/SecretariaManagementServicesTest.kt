import kotlinx.coroutines.runBlocking
import schemas.secretaria.AddTeamMemberRequest
import schemas.secretaria.AppointmentCreator
import schemas.secretaria.AppointmentStatus
import schemas.secretaria.BookResult
import schemas.secretaria.CallChannel
import schemas.secretaria.CallIntent
import schemas.secretaria.CallOutcome
import schemas.secretaria.CreatePlanRequest
import schemas.secretaria.DoctorUpsertRequest
import schemas.secretaria.ErrorKind
import schemas.secretaria.ScheduleWindowDto
import schemas.secretaria.ServiceResult
import schemas.secretaria.Speaker
import schemas.secretaria.UpdateClinicRequest
import schemas.secretaria.UpdateSettingsRequest
import schemas.secretaria.UpsertPatientRequest
import schemas.secretaria.parseLocalIso
import schemas.secretaria.toEpochMs
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretariaManagementServicesTest {

    private fun local(text: String) = parseLocalIso(text)!!
    private val monday = LocalDate.of(2026, 10, 5)

    private fun <T> ServiceResult<T>.ok(): T = (this as? ServiceResult.Ok)?.value ?: error("esperava Ok, veio $this")
    private fun ServiceResult<*>.err(kind: ErrorKind): String {
        val e = this as? ServiceResult.Err ?: error("esperava erro $kind, veio $this")
        assertEquals(kind, e.kind, e.message)
        return e.message
    }

    // ── ajustes da IA e dados da clínica ─────────────────────────────────────

    @Test
    fun `ajustes da IA - padrao, desligar, voz e orientacoes`() = runBlocking<Unit> {
        val fx = SecretariaFixture()
        val id = fx.bootstrap().clinicId

        val default = fx.settings.settings(id)
        assertTrue(default.aiEnabled)
        assertNull(default.voice)
        assertTrue("Aoede" in default.availableVoices)
        assertTrue(fx.settings.assistantStatus(id).online)

        fx.settings.updateSettings(id, UpdateSettingsRequest(aiEnabled = false, voice = "Puck", extraInstructions = "  Aceitamos Unimed.  ")).ok()
        val s = fx.settings.settings(id)
        assertEquals(false, s.aiEnabled); assertEquals("Puck", s.voice); assertEquals("Aceitamos Unimed.", s.extraInstructions)
        assertFalse(fx.settings.assistantStatus(id).online)
        assertTrue(fx.settings.assistantStatus(id).message.contains("desativado"))

        // campo ausente não muda; "" limpa
        fx.settings.updateSettings(id, UpdateSettingsRequest(aiEnabled = true)).ok()
        assertEquals("Puck", fx.settings.settings(id).voice)
        fx.settings.updateSettings(id, UpdateSettingsRequest(voice = "", extraInstructions = "")).ok()
        assertNull(fx.settings.settings(id).voice); assertNull(fx.settings.settings(id).extraInstructions)

        fx.settings.updateSettings(id, UpdateSettingsRequest(voice = "Inexistente")).err(ErrorKind.INVALID)
        fx.settings.updateSettings(id, UpdateSettingsRequest(extraInstructions = "x".repeat(1001))).err(ErrorKind.INVALID)

        fx.geminiConfigured = false // servidor sem chave do Gemini
        assertFalse(fx.settings.assistantStatus(id).online)
        assertTrue(fx.settings.assistantStatus(id).message.contains("indisponível"))
    }

    @Test
    fun `dados da clinica - atualizacao parcial, validacao e CNPJ unico`() = runBlocking<Unit> {
        val fx = SecretariaFixture()
        val a = fx.bootstrap().clinicId
        val b = fx.bootstrap(email = "b@x.test", clinicName = "Outra").clinicId

        val updated = fx.settings.updateClinic(a, UpdateClinicRequest(name = "  Vida Mais ", phone = "(21) 3333-4444", cnpj = "12.345.678/0001-95", address = "Rua A, 10")).ok()
        assertEquals("Vida Mais", updated.name); assertEquals("12345678000195", updated.cnpj); assertEquals("Rua A, 10", updated.address)
        assertEquals("Dr. Henrique Martins", updated.responsibleName) // não enviado -> intacto
        assertNotNull(updated.publicKey)
        assertNull(fx.settings.clinicDetail(a, includeKey = false)!!.publicKey)

        assertNull(fx.settings.updateClinic(a, UpdateClinicRequest(address = "")).ok().address) // "" limpa
        fx.settings.updateClinic(a, UpdateClinicRequest(timezone = "Marte/Olimpo")).err(ErrorKind.INVALID)
        fx.settings.updateClinic(a, UpdateClinicRequest(cnpj = "123")).err(ErrorKind.INVALID)
        fx.settings.updateClinic(a, UpdateClinicRequest(email = "sem-arroba")).err(ErrorKind.INVALID)
        fx.settings.updateClinic(a, UpdateClinicRequest(name = "   ")).err(ErrorKind.INVALID)
        assertEquals("America/Recife", fx.settings.updateClinic(a, UpdateClinicRequest(timezone = "America/Recife")).ok().timezone)
        fx.settings.updateClinic(b, UpdateClinicRequest(cnpj = "12345678000195")).err(ErrorKind.CONFLICT) // CNPJ de outra clínica
    }

    @Test
    fun `girar a chave publica invalida a antiga`() = runBlocking<Unit> {
        val fx = SecretariaFixture()
        val boot = fx.bootstrap()
        val novo = fx.settings.rotatePublicKey(boot.clinicId)
        assertTrue(novo != boot.publicKey && novo.length >= 30)
        assertNull(fx.clinics.clinicByPublicKey(boot.publicKey))
        assertEquals(boot.clinicId, fx.clinics.clinicByPublicKey(novo)?.id)
    }

    // ── equipe, perfil e senha ───────────────────────────────────────────────

    @Test
    fun `equipe - adicionar, vincular usuario existente, trocar papel e remover`() = runBlocking<Unit> {
        val fx = SecretariaFixture()
        val a = fx.bootstrap()
        val b = fx.bootstrap(email = "dona@outra.test", clinicName = "Outra")

        val novo = fx.team.add(a.clinicId, AddTeamMemberRequest("Nova@Clinica.test", "secretaria", "Nova Pessoa", "senha-bem-longa-1")).ok()
        assertEquals("secretaria", novo.role)
        assertNotNull(fx.auth.login("nova@clinica.test", "senha-bem-longa-1")) // já consegue entrar

        // e-mail que já existe em outra clínica: só vincula (sem mexer na senha)
        val vinculada = fx.team.add(a.clinicId, AddTeamMemberRequest("dona@outra.test", "profissional")).ok()
        assertEquals(b.userId, vinculada.userId)
        assertEquals(2, fx.auth.clinicsOf(b.userId).size) // "Trocar clínica" agora tem duas
        assertNotNull(fx.auth.login("dona@outra.test", "senha-segura-123")) // senha original intacta

        fx.team.add(a.clinicId, AddTeamMemberRequest("dona@outra.test", "admin")).err(ErrorKind.CONFLICT) // já é membro
        fx.team.add(a.clinicId, AddTeamMemberRequest("x@x.test", "chefe", "X Y", "senha-bem-longa-1")).err(ErrorKind.INVALID) // papel
        fx.team.add(a.clinicId, AddTeamMemberRequest("sem-arroba", "admin", "X Y", "senha-bem-longa-1")).err(ErrorKind.INVALID)
        fx.team.add(a.clinicId, AddTeamMemberRequest("curta@x.test", "admin", "X Y", "curta")).err(ErrorKind.INVALID) // 5 caracteres: curta
        fx.team.add(a.clinicId, AddTeamMemberRequest("seis@x.test", "secretaria", "Seis Letras", "abc123")).ok() // 6 caracteres: vale
        fx.team.add(a.clinicId, AddTeamMemberRequest("semnome@x.test", "admin", null, "senha-bem-longa-1")).err(ErrorKind.INVALID)

        assertEquals(4, fx.team.list(a.clinicId).size) // maria, nova, a vinculada e a de senha de 6 caracteres
        assertEquals("admin", fx.team.changeRole(a.clinicId, novo.userId, "ADMIN").ok().role)
        fx.team.changeRole(a.clinicId, 9999, "admin").err(ErrorKind.NOT_FOUND)
        fx.team.remove(a.clinicId, novo.userId).ok()
        assertEquals(3, fx.team.list(a.clinicId).size)
        assertEquals(0, fx.auth.clinicsOf(novo.userId).size) // saiu da clínica, mas a conta continua existindo
        assertNotNull(fx.auth.login("nova@clinica.test", "senha-bem-longa-1"))
    }

    @Test
    fun `a clinica nunca fica sem administrador`() = runBlocking<Unit> {
        val fx = SecretariaFixture()
        val a = fx.bootstrap()
        fx.team.changeRole(a.clinicId, a.userId, "secretaria").err(ErrorKind.CONFLICT)
        fx.team.remove(a.clinicId, a.userId).err(ErrorKind.CONFLICT)

        fx.team.add(a.clinicId, AddTeamMemberRequest("outro@x.test", "admin", "Outro Admin", "senha-bem-longa-1")).ok()
        fx.team.changeRole(a.clinicId, a.userId, "secretaria").ok() // agora há outro administrador
        fx.team.remove(a.clinicId, a.userId).ok()
        assertEquals(1, fx.team.list(a.clinicId).size)
    }

    @Test
    fun `perfil e senha`() = runBlocking<Unit> {
        val fx = SecretariaFixture()
        val a = fx.bootstrap()

        assertEquals("Maria da Silva", fx.team.updateProfile(a.userId, " Maria da Silva ").ok().name)
        fx.team.updateProfile(a.userId, "M").err(ErrorKind.INVALID)

        fx.team.changePassword(a.userId, "errada-errada", "outra-senha-longa").err(ErrorKind.FORBIDDEN)
        fx.team.changePassword(a.userId, "senha-segura-123", "curta").err(ErrorKind.INVALID)
        fx.team.changePassword(a.userId, "senha-segura-123", "senha-segura-123").err(ErrorKind.INVALID)
        fx.team.changePassword(a.userId, "senha-segura-123", "nova-senha-muito-boa").ok()
        assertNull(fx.auth.login("maria@clinica.test", "senha-segura-123"))
        assertNotNull(fx.auth.login("maria@clinica.test", "nova-senha-muito-boa"))
    }

    // ── médicos, especialidades e agenda ─────────────────────────────────────

    @Test
    fun `especialidades sugeridas nao vazam dados de outras clinicas`() = runBlocking<Unit> {
        val fx = SecretariaFixture()
        val a = fx.bootstrap().clinicId
        fx.bootstrap(email = "b@x.test", clinicName = "Outra", doctors = listOf(schemas.secretaria.BootstrapDoctor("Dr. Secreto", "Especialidade Muito Secreta")))
        val names = fx.catalog.specialties(a).map { it.name }
        assertTrue("Cardiologia" in names && "Consulta geral" in names && "Pediatria" in names)
        assertFalse("Especialidade Muito Secreta" in names)
        assertEquals(names.size, names.toSet().size) // sem duplicadas
    }

    @Test
    fun `medico - criar com agenda padrao, editar e desativar com protecao`() = runBlocking<Unit> {
        val fx = SecretariaFixture()
        val clinic = fx.clinic(fx.bootstrap().clinicId)

        val d = fx.catalog.createDoctor(clinic.id, DoctorUpsertRequest("Dra. Paula Reis", "Pediatria", "CRM 123")).ok()
        assertEquals("Pediatria", d.specialty)
        assertEquals(10, fx.catalog.schedule(clinic.id, d.id).ok().size) // seg-sex, manhã e tarde
        assertTrue(fx.appointments.availableSlots(clinic, d.id, null, monday, 7, 5).isNotEmpty()) // a IA já tem o que oferecer

        fx.catalog.createDoctor(clinic.id, DoctorUpsertRequest("X", "Pediatria")).err(ErrorKind.INVALID)
        fx.catalog.createDoctor(clinic.id, DoctorUpsertRequest("Dr. Fulano", "")).err(ErrorKind.INVALID)

        val edited = fx.catalog.updateDoctor(clinic.id, d.id, DoctorUpsertRequest("Dra. Paula Reis Lima", "Neurologia", null)).ok()
        assertEquals("Neurologia", edited.specialty); assertNull(edited.crm)
        fx.catalog.updateDoctor(clinic.id, 9999, DoctorUpsertRequest("Dr. Fulano", "Pediatria")).err(ErrorKind.NOT_FOUND)

        // com consulta futura, não desativa
        val pid = fx.clinics.findOrCreatePatient(clinic.id, "Ana Souza", "5521987654321")
        val booked = fx.appointments.book(clinic, d.id, pid, local("2026-10-06T09:00"), AppointmentCreator.IA) as BookResult.Ok
        assertTrue(fx.catalog.deactivateDoctor(clinic.id, d.id).err(ErrorKind.CONFLICT).contains("1 consulta"))
        assertTrue(fx.catalog.doctors(clinic.id, false).any { it.id == d.id })

        fx.appointments.cancel(clinic.id, booked.appointment.id, null)
        assertFalse(fx.catalog.deactivateDoctor(clinic.id, d.id).ok().active)
        assertFalse(fx.catalog.doctors(clinic.id, false).any { it.id == d.id })
        assertTrue(fx.catalog.doctors(clinic.id, true).any { it.id == d.id })
        assertTrue(fx.appointments.availableSlots(clinic, d.id, null, monday, 7, 5).isEmpty()) // inativo não recebe consultas
    }

    @Test
    fun `limite de consultas por dia - dia lotado some dos horarios e recusa marcar alem dele`() = runBlocking<Unit> {
        val fx = SecretariaFixture()
        val clinic = fx.clinic(fx.bootstrap().clinicId)
        val d = fx.catalog.createDoctor(clinic.id, DoctorUpsertRequest("Dra. Paula Reis", "Pediatria", maxPerDay = 2)).ok()
        assertEquals(2, d.maxPerDay)
        fun tuesday() = runBlocking { fx.appointments.availableSlots(clinic, d.id, null, monday, 3, 100).map { it.startLocal }.filter { it.startsWith("2026-10-06") } }
        assertTrue(tuesday().isNotEmpty())

        val ana = fx.clinics.findOrCreatePatient(clinic.id, "Ana Souza", "5521987654321")
        val bia = fx.clinics.findOrCreatePatient(clinic.id, "Bia Lima", "5521987654322")
        val caio = fx.clinics.findOrCreatePatient(clinic.id, "Caio Reis", "5521987654323")
        val first = fx.appointments.book(clinic, d.id, ana, local("2026-10-06T09:00"), AppointmentCreator.IA) as BookResult.Ok
        assertTrue(tuesday().isNotEmpty(), "1 de 2: ainda há vaga")
        fx.appointments.book(clinic, d.id, bia, local("2026-10-06T14:00"), AppointmentCreator.IA) as BookResult.Ok

        // dia lotado: terça some dos horários livres (a IA não oferece), quarta continua
        assertTrue(tuesday().isEmpty())
        assertTrue(fx.appointments.availableSlots(clinic, d.id, null, monday, 3, 100).any { it.startLocal.startsWith("2026-10-07") })
        val refused = fx.appointments.book(clinic, d.id, caio, local("2026-10-06T10:00"), AppointmentCreator.USUARIO) as BookResult.Fail
        assertTrue(refused.reason.contains("completa nesse dia"))
        // remarcar dentro do mesmo dia lotado é permitido (a consulta que sai não conta)
        assertTrue(fx.appointments.reschedule(clinic, first.appointment.id, local("2026-10-06T10:00"), null, AppointmentCreator.IA) is BookResult.Ok)

        // cancelar libera o dia
        fx.appointments.cancel(clinic.id, fx.appointments.forPatient(clinic, bia).first().id, null)
        assertTrue(tuesday().isNotEmpty())

        // editar sem mandar o limite mantém; 0 tira; fora da faixa é recusado
        assertEquals(2, fx.catalog.updateDoctor(clinic.id, d.id, DoctorUpsertRequest("Dra. Paula Reis", "Pediatria")).ok().maxPerDay)
        fx.catalog.updateDoctor(clinic.id, d.id, DoctorUpsertRequest("Dra. Paula Reis", "Pediatria", maxPerDay = 201)).err(ErrorKind.INVALID)
        fx.catalog.updateDoctor(clinic.id, d.id, DoctorUpsertRequest("Dra. Paula Reis", "Pediatria", maxPerDay = -1)).err(ErrorKind.INVALID)
        assertEquals(1, fx.catalog.updateDoctor(clinic.id, d.id, DoctorUpsertRequest("Dra. Paula Reis", "Pediatria", maxPerDay = 1)).ok().maxPerDay)
        assertTrue(tuesday().isEmpty(), "limite baixou para 1 e a terça já tem 1")
        assertNull(fx.catalog.updateDoctor(clinic.id, d.id, DoctorUpsertRequest("Dra. Paula Reis", "Pediatria", maxPerDay = 0)).ok().maxPerDay)
        assertTrue(tuesday().isNotEmpty(), "sem limite, só a agenda manda")
        assertNull(fx.catalog.createDoctor(clinic.id, DoctorUpsertRequest("Dr. Sem Limite", "Pediatria")).ok().maxPerDay)
    }

    @Test
    fun `agenda semanal - validacao e efeito nos horarios livres`() = runBlocking<Unit> {
        val fx = SecretariaFixture()
        val clinic = fx.clinic(fx.bootstrap().clinicId)
        val doctor = fx.catalog.doctors(clinic.id, false).first()
        fun put(vararg w: ScheduleWindowDto) = runBlocking { fx.catalog.replaceSchedule(clinic.id, doctor.id, w.toList()) }

        put(ScheduleWindowDto(1, "10:00", "09:00")).err(ErrorKind.INVALID) // fim antes do início
        put(ScheduleWindowDto(1, "9h", "12:00")).err(ErrorKind.INVALID) // formato
        put(ScheduleWindowDto(7, "09:00", "12:00")).err(ErrorKind.INVALID) // dia
        put(ScheduleWindowDto(1, "09:00", "09:20", 30)).err(ErrorKind.INVALID) // janela menor que a consulta
        put(ScheduleWindowDto(1, "09:00", "12:00", 3)).err(ErrorKind.INVALID) // duração
        put(ScheduleWindowDto(1, "09:00", "12:00"), ScheduleWindowDto(1, "11:00", "13:00")).err(ErrorKind.INVALID) // sobreposição
        assertEquals(10, fx.catalog.schedule(clinic.id, doctor.id).ok().size) // nada foi alterado pelos pedidos inválidos

        // terça 14:00–15:30 (consultas de 45 min) e quarta o dia todo até meia-noite
        val saved = put(ScheduleWindowDto(2, "14:00", "15:30", 45), ScheduleWindowDto(3, "08:00", "24:00", 60), ScheduleWindowDto(3, "07:00", "08:00", 60)).ok()
        assertEquals(listOf("07:00", "08:00"), saved.filter { it.weekday == 3 }.map { it.start }) // devolvido ordenado
        val slots = fx.appointments.availableSlots(clinic, doctor.id, null, monday, 3, 50).map { it.startLocal }
        assertEquals(listOf("2026-10-06T14:00", "2026-10-06T14:45"), slots.filter { it.startsWith("2026-10-06") }) // terça: 2 consultas de 45 min
        assertTrue("2026-10-07T23:00" in slots) // quarta, "24:00" aceito
        assertTrue(slots.none { it.startsWith("2026-10-05") }) // segunda não atende mais

        put().ok() // pode zerar a agenda
        assertTrue(fx.appointments.availableSlots(clinic, doctor.id, null, monday, 7, 5).isEmpty())
        fx.catalog.schedule(clinic.id, 9999).err(ErrorKind.NOT_FOUND)
    }

    // ── pacientes ────────────────────────────────────────────────────────────

    @Test
    fun `pacientes - cadastro, busca, duplicidade e isolamento`() = runBlocking<Unit> {
        val fx = SecretariaFixture()
        val a = fx.clinic(fx.bootstrap().clinicId)
        val b = fx.clinic(fx.bootstrap(email = "b@x.test", clinicName = "Outra").clinicId)

        val ana = fx.patients.create(a.id, UpsertPatientRequest("Ana Souza", "(21) 98765-4321", "ana@x.test")).ok()
        assertEquals("5521987654321", ana.phone)
        fx.patients.create(a.id, UpsertPatientRequest("ana souza", "21987654321")).err(ErrorKind.CONFLICT)
        fx.patients.create(a.id, UpsertPatientRequest("Clara Souza", "21987654321")).ok() // mesma família, outro nome
        fx.patients.create(a.id, UpsertPatientRequest("Sem Telefone")).ok()
        fx.patients.create(a.id, UpsertPatientRequest("Xx", "21987654321")).err(ErrorKind.INVALID)
        fx.patients.create(a.id, UpsertPatientRequest("Fulano Silva", "123")).err(ErrorKind.INVALID)
        fx.patients.create(a.id, UpsertPatientRequest("Fulano Silva", null, "sem-arroba")).err(ErrorKind.INVALID)

        assertEquals(3, fx.patients.page(a.id, null, 10, 0).total)
        assertEquals(listOf("Ana Souza"), fx.patients.page(a.id, "ana", 10, 0).items.map { it.name })
        assertEquals(2, fx.patients.page(a.id, "98765", 10, 0).total) // por telefone
        assertEquals(1, fx.patients.page(a.id, null, 1, 0).items.size) // paginação
        assertEquals(0, fx.patients.page(a.id, "S%a", 10, 0).total) // "%" é removido: "S%a" busca "Sa", não "S...a"
        assertEquals(0, fx.patients.page(b.id, null, 10, 0).total) // outra clínica não vê

        assertEquals("Ana Maria Souza", fx.patients.update(a.id, ana.id, UpsertPatientRequest("Ana Maria Souza", "21987654321")).ok().name)
        fx.patients.update(b.id, ana.id, UpsertPatientRequest("Invasor Teste", "21999990000")).err(ErrorKind.NOT_FOUND)
        fx.patients.detail(b, ana.id).err(ErrorKind.NOT_FOUND)
    }

    @Test
    fun `detalhe do paciente separa proximas consultas e historico`() = runBlocking<Unit> {
        val fx = SecretariaFixture()
        val clinic = fx.clinic(fx.bootstrap().clinicId)
        val doctorId = fx.catalog.doctors(clinic.id, false).first().id
        val pid = fx.clinics.findOrCreatePatient(clinic.id, "Ana Souza", "5521987654321")
        val c1 = (fx.appointments.book(clinic, doctorId, pid, local("2026-10-06T09:00"), AppointmentCreator.IA) as BookResult.Ok).appointment
        val c2 = (fx.appointments.book(clinic, doctorId, pid, local("2026-10-07T09:00"), AppointmentCreator.IA) as BookResult.Ok).appointment
        fx.appointments.cancel(clinic.id, c2.id, null)
        fx.calls.start(clinic.id, CallChannel.PWA, null).also { fx.calls.attachPatient(it.id, pid, "Ana Souza", "5521987654321") }

        val d = fx.patients.detail(clinic, pid).ok()
        assertEquals(listOf(c1.id), d.upcoming.map { it.id })
        assertEquals(listOf(c2.id), d.history.map { it.id }) // cancelada vai para o histórico
        assertEquals(1, d.callsCount)
    }

    @Test
    fun `exclusao LGPD anonimiza, apaga transcricoes e cancela consultas futuras`() = runBlocking<Unit> {
        val fx = SecretariaFixture()
        val clinic = fx.clinic(fx.bootstrap().clinicId)
        val doctorId = fx.catalog.doctors(clinic.id, false).first().id
        val pid = fx.clinics.findOrCreatePatient(clinic.id, "Ana Souza", "5521987654321")
        val outro = fx.clinics.findOrCreatePatient(clinic.id, "Beto Lima", "5521911112222")
        val appt = (fx.appointments.book(clinic, doctorId, pid, local("2026-10-06T09:00"), AppointmentCreator.IA) as BookResult.Ok).appointment
        val call = fx.calls.start(clinic.id, CallChannel.PWA, null)
        fx.calls.attachPatient(call.id, pid, "Ana Souza", "5521987654321")
        fx.calls.setSubject(call.id, "Dor no peito", "Paciente relata dor", CallIntent.INFORMACAO)
        fx.calls.appendMessage(call.id, Speaker.PACIENTE, "Tenho dor no peito", 100)
        fx.calls.end(call.id)
        val callOutro = fx.calls.start(clinic.id, CallChannel.PWA, null).also { fx.calls.attachPatient(it.id, outro, "Beto Lima", "5521911112222"); fx.calls.appendMessage(it.id, Speaker.PACIENTE, "oi", 1) }

        fx.patients.anonymize(clinic.id, pid).ok()

        val p = fx.patients.detail(clinic, pid).ok().patient
        assertEquals("Paciente removido", p.name); assertNull(p.phone); assertNull(p.email)
        assertEquals("cancelado", fx.appointments.get(clinic, appt.id)?.status)
        val c = fx.calls.get(clinic.id, call.id)!!
        assertNull(c.subject); assertNull(c.phone); assertFalse(c.hasTranscript)
        assertEquals(0, fx.calls.transcript(clinic.id, call.id)!!.size)
        assertTrue(fx.calls.get(clinic.id, callOutro.id)!!.hasTranscript) // dados de outras pessoas intactos
        assertEquals("Beto Lima", fx.patients.detail(clinic, outro).ok().patient.name)
        assertTrue(fx.appointments.availableSlots(clinic, doctorId, null, LocalDate.of(2026, 10, 6), 1, 50).any { it.startLocal == "2026-10-06T09:00" }) // horário liberado
        fx.patients.anonymize(clinic.id + 99, pid).err(ErrorKind.NOT_FOUND)
    }

    // ── agendamentos: status e filtros ───────────────────────────────────────

    @Test
    fun `status do agendamento - transicoes permitidas`() = runBlocking<Unit> {
        val fx = SecretariaFixture()
        val clinic = fx.clinic(fx.bootstrap().clinicId)
        val doctorId = fx.catalog.doctors(clinic.id, false).first().id
        val pid = fx.clinics.findOrCreatePatient(clinic.id, "Ana Souza", "5521987654321")
        val a = (fx.appointments.book(clinic, doctorId, pid, local("2026-10-06T09:00"), AppointmentCreator.IA) as BookResult.Ok).appointment

        assertEquals("confirmado", fx.appointments.setStatus(clinic, a.id, AppointmentStatus.CONFIRMADO).ok().status)
        assertEquals("concluido", fx.appointments.setStatus(clinic, a.id, AppointmentStatus.CONCLUIDO).ok().status)
        fx.appointments.setStatus(clinic, a.id, AppointmentStatus.FALTOU).err(ErrorKind.CONFLICT) // já concluído
        fx.appointments.setStatus(clinic, a.id, AppointmentStatus.CANCELADO).err(ErrorKind.INVALID) // cancelar tem rota própria
        fx.appointments.setStatus(clinic, 9999, AppointmentStatus.CONFIRMADO).err(ErrorKind.NOT_FOUND)
        // concluído continua ocupando o horário
        assertTrue(fx.appointments.availableSlots(clinic, doctorId, null, LocalDate.of(2026, 10, 6), 1, 50).none { it.startLocal == "2026-10-06T09:00" })

        val outra = fx.clinic(fx.bootstrap(email = "b@x.test", clinicName = "Outra").clinicId)
        fx.appointments.setStatus(outra, a.id, AppointmentStatus.CONFIRMADO).err(ErrorKind.NOT_FOUND) // isolamento
    }

    @Test
    fun `listagem de agenda com filtros e vinculo com a chamada`() = runBlocking<Unit> {
        val fx = SecretariaFixture()
        val clinic = fx.clinic(fx.bootstrap().clinicId)
        val (d1, d2) = fx.catalog.doctors(clinic.id, false).let { it[0].id to it[1].id }
        val ana = fx.clinics.findOrCreatePatient(clinic.id, "Ana Souza", "5521987654321")
        val beto = fx.clinics.findOrCreatePatient(clinic.id, "Beto Lima", "5521911112222")
        val call = fx.calls.start(clinic.id, CallChannel.PWA, null)
        val a1 = (fx.appointments.book(clinic, d1, ana, local("2026-10-06T09:00"), AppointmentCreator.IA, call.id) as BookResult.Ok).appointment
        val a2 = (fx.appointments.book(clinic, d2, beto, local("2026-10-06T10:00"), AppointmentCreator.USUARIO) as BookResult.Ok).appointment
        fx.appointments.cancel(clinic.id, a2.id, null)
        val from = local("2026-10-06T00:00").toEpochMs(clinic.zone)
        val to = from + 24 * 3_600_000

        assertEquals(2, fx.appointments.listBetween(clinic, from, to).size)
        assertEquals(listOf(a1.id), fx.appointments.listBetween(clinic, from, to, doctorId = d1).map { it.id })
        assertEquals(listOf(a2.id), fx.appointments.listBetween(clinic, from, to, status = AppointmentStatus.CANCELADO).map { it.id })
        assertEquals(listOf(a1.id), fx.appointments.listBetween(clinic, from, to, patientId = ana).map { it.id })
        assertEquals(listOf(a1.id), fx.appointments.bySourceCall(clinic, call.id).map { it.id })
        assertEquals(2, fx.appointments.forPatient(clinic, ana).size + fx.appointments.forPatient(clinic, beto).size)
    }

    // ── chamadas: filtros, detalhe e notificações ────────────────────────────

    private class CallsEnv {
        val fx = SecretariaFixture()
        val clinic = fx.clinic(fx.bootstrap().clinicId)

        /** Abre uma chamada agora; com [outcome] ela dura 60 s e é encerrada, sem ele fica em andamento. */
        fun call(name: String?, phone: String?, subject: String, outcome: CallOutcome?): Long = runBlocking {
            val c = fx.calls.start(clinic.id, CallChannel.PWA, null)
            if (name != null) fx.calls.attachPatient(c.id, fx.clinics.findOrCreatePatient(clinic.id, name, phone!!), name, phone)
            fx.calls.setSubject(c.id, subject, null, null)
            if (outcome != null) {
                fx.calls.setOutcome(c.id, outcome, CallIntent.AGENDAMENTO)
                fx.advanceSeconds(60)
                fx.calls.end(c.id)
            }
            c.id
        }
    }

    @Test
    fun `chamadas - filtros, busca, paginacao e total`() = runBlocking<Unit> {
        val e = CallsEnv()
        val fx = e.fx
        e.call("Ana Souza", "5521987654321", "Agendamento de consulta", CallOutcome.AGENDADA); fx.advanceSeconds(3600)
        e.call("Carlos Mendes", "5521976543210", "Remarcação", CallOutcome.REMARCADA); fx.advanceSeconds(3600)
        val t3 = fx.clock() // início da 3ª chamada
        e.call("Juliana Lima", "5521965432109", "Informações sobre exames", CallOutcome.FINALIZADA); fx.advanceSeconds(3600)
        e.call(null, null, "Dúvida", CallOutcome.FINALIZADA); fx.advanceSeconds(3600)
        e.call("Ana Souza", "5521987654321", "Retorno", null) // em andamento

        val all = fx.calls.page(e.clinic.id, 10, 0)
        assertEquals(5, all.total)
        assertEquals("Retorno", all.items.first().subject) // mais recente primeiro
        assertEquals(1, fx.calls.page(e.clinic.id, 10, 0, status = "agendada").total)
        assertEquals(2, fx.calls.page(e.clinic.id, 10, 0, status = "finalizada").total)
        assertEquals(1, fx.calls.page(e.clinic.id, 10, 0, status = "em_andamento").total)
        assertEquals(0, fx.calls.page(e.clinic.id, 10, 0, status = "inexistente").total)
        assertEquals(3, fx.calls.page(e.clinic.id, 10, 0, query = "ana").total) // por paciente: Ana (2) e Juliana (contém "ana")
        assertEquals(1, fx.calls.page(e.clinic.id, 10, 0, query = "exames").total) // por assunto
        assertEquals(3, fx.calls.page(e.clinic.id, 10, 0, query = "7654321").total) // por telefone: 2 de Ana e 1 de Carlos
        assertEquals(0, fx.calls.page(e.clinic.id, 10, 0, query = "A%a").total) // "%" não funciona como curinga
        val page2 = fx.calls.page(e.clinic.id, 2, 2)
        assertEquals(2, page2.items.size); assertEquals(5, page2.total) // total independe da página
        assertEquals(4, fx.calls.page(e.clinic.id, 10, 0, onlyEnded = true).total)

        assertEquals(3, fx.calls.page(e.clinic.id, 10, 0, fromMs = t3).total) // 3ª, 4ª e 5ª
        assertEquals(1, fx.calls.page(e.clinic.id, 10, 0, fromMs = t3, toMs = t3 + 1).total) // só a 3ª
        assertEquals("Informações sobre exames", fx.calls.page(e.clinic.id, 10, 0, fromMs = t3, toMs = t3 + 1).items.single().subject)
    }

    @Test
    fun `notificacoes - listar, nao lidas, marcar uma e visibilidade`() = runBlocking<Unit> {
        val fx = SecretariaFixture()
        val a = fx.bootstrap()
        val clinic = fx.clinic(a.clinicId)
        fx.clinics.notify(clinic.id, schemas.secretaria.NotificationType.AGENDAMENTO, "Novo agendamento", "Ana — Cardiologia")
        fx.advanceSeconds(10)
        fx.clinics.notify(clinic.id, schemas.secretaria.NotificationType.PLANO, "80% do plano", null)

        val page = fx.clinics.notifications(clinic.id, a.userId, unreadOnly = false, limit = 10, offset = 0)
        assertEquals(listOf("80% do plano", "Novo agendamento"), page.items.map { it.title }) // mais recente primeiro
        assertEquals(2, page.unread)
        assertTrue(fx.clinics.markNotificationRead(clinic.id, a.userId, page.items.first().id))
        assertEquals(1, fx.clinics.notifications(clinic.id, a.userId, true, 10, 0).items.size)
        assertEquals(1, fx.clinics.unreadNotifications(clinic.id, a.userId))
        assertFalse(fx.clinics.markNotificationRead(clinic.id, a.userId, 9999))

        val outra = fx.clinic(fx.bootstrap(email = "b@x.test", clinicName = "Outra").clinicId)
        assertFalse(fx.clinics.markNotificationRead(outra.id, a.userId, page.items.last().id)) // de outra clínica
    }

    // ── plano ────────────────────────────────────────────────────────────────

    @Test
    fun `plano - detalhe com uso por dia, catalogo e troca`() = runBlocking<Unit> {
        val fx = SecretariaFixture()
        val clinic = fx.clinic(fx.bootstrap().clinicId)
        fx.calls.start(clinic.id, CallChannel.PWA, null).also { fx.advanceSeconds(150); fx.calls.end(it.id) }
        fx.advanceSeconds(24 * 3600)
        fx.calls.start(clinic.id, CallChannel.PWA, null).also { fx.advanceSeconds(90); fx.calls.end(it.id) }
        fx.calls.start(clinic.id, CallChannel.PWA, null).also { fx.advanceSeconds(30); fx.calls.end(it.id) }

        val d = fx.plans.detail(clinic).ok()
        assertEquals(10, d.usage.includedMinutes); assertEquals("ativa", d.status)
        assertEquals(listOf("2026-10-05", "2026-10-06"), d.usageByDay.map { it.date })
        assertEquals(listOf(1, 2), d.usageByDay.map { it.calls })
        assertEquals(listOf(2.5, 2.0), d.usageByDay.map { it.minutes })
        assertEquals(1, d.availablePlans.size)

        fx.plans.createPlan(CreatePlanRequest("Plano Pro", 499.0, 1000, 0.4)).ok()
        fx.plans.createPlan(CreatePlanRequest("Plano Pro", 1.0, 1, 0.1)).err(ErrorKind.CONFLICT)
        fx.plans.createPlan(CreatePlanRequest("X", 1.0, 1, 0.1)).err(ErrorKind.INVALID)
        fx.plans.createPlan(CreatePlanRequest("Plano Ruim", -1.0, 1, 0.1)).err(ErrorKind.INVALID)
        fx.plans.createPlan(CreatePlanRequest("Plano Zero", 10.0, 0, 0.1)).err(ErrorKind.INVALID)
        val pro = fx.plans.plans().first { it.name == "Plano Pro" }

        fx.plans.assign(clinic.id, pro.id).ok()
        assertEquals(1000, fx.plans.detail(clinic).ok().usage.includedMinutes)
        fx.plans.assign(clinic.id, 9999).err(ErrorKind.NOT_FOUND)
        fx.plans.assign(9999, pro.id).err(ErrorKind.NOT_FOUND)
    }

    // ── relatórios ───────────────────────────────────────────────────────────

    @Test
    fun `relatorio resume chamadas, agendamentos e conversao`() = runBlocking<Unit> {
        val fx = SecretariaFixture() // segunda 09:00 BRT
        val clinic = fx.clinic(fx.bootstrap().clinicId)
        val (d1, d2) = fx.catalog.doctors(clinic.id, false).let { it[0].id to it[1].id }
        val ana = fx.clinics.findOrCreatePatient(clinic.id, "Ana Souza", "5521987654321")

        // 3 chamadas hoje: agendada (120 s), finalizada (60 s), em andamento
        val c1 = fx.calls.start(clinic.id, CallChannel.PWA, null)
        fx.appointments.book(clinic, d1, ana, local("2026-10-06T09:00"), AppointmentCreator.IA, c1.id)
        fx.calls.setOutcome(c1.id, CallOutcome.AGENDADA, CallIntent.AGENDAMENTO); fx.advanceSeconds(120); fx.calls.end(c1.id)
        val c2 = fx.calls.start(clinic.id, CallChannel.PWA, null); fx.advanceSeconds(60); fx.calls.end(c2.id)
        fx.calls.start(clinic.id, CallChannel.PWA, null)
        val a2 = (fx.appointments.book(clinic, d2, ana, local("2026-10-06T10:00"), AppointmentCreator.USUARIO) as BookResult.Ok).appointment
        fx.appointments.cancel(clinic.id, a2.id, null)

        val r = fx.reports.summary(clinic, monday, monday).ok()
        assertEquals(3, r.totalCalls)
        assertEquals(3.0, r.totalMinutes)
        assertEquals(90, r.avgSeconds) // média de 120 e 60
        assertEquals(2, r.appointmentsCreated); assertEquals(1, r.appointmentsByAi)
        assertEquals(0.5, r.conversionRate, 1e-9) // 1 agendada de 2 encerradas
        assertEquals(mapOf("agendada" to 1, "finalizada" to 1), r.byOutcome.associate { it.label to it.count })
        assertEquals(mapOf("Consulta geral" to 1), r.bySpecialty.associate { it.label to it.count }) // cancelada não conta
        assertEquals(mapOf("agendado" to 1, "cancelado" to 1), r.byStatus.associate { it.label to it.count })
        assertEquals(24, r.callsByHour.size); assertEquals(3, r.callsByHour[9]) // hora local (09h BRT)
        assertEquals(1, r.perDay.size); assertEquals(3, r.perDay.single().calls); assertEquals(2, r.perDay.single().appointments)

        val week = fx.reports.summary(clinic, monday.minusDays(6), monday).ok()
        assertEquals(7, week.perDay.size) // dias sem movimento aparecem zerados
        assertEquals(6, week.perDay.count { it.calls == 0 })
        fx.reports.summary(clinic, monday, monday.minusDays(1)).err(ErrorKind.INVALID)
        fx.reports.summary(clinic, monday.minusDays(400), monday).err(ErrorKind.INVALID)
        assertEquals(0, fx.reports.summary(clinic, monday.plusDays(10), monday.plusDays(11)).ok().totalCalls) // período vazio
    }

    @Test
    fun `CSV - cabecalho, aspas e protecao contra formulas`() = runBlocking<Unit> {
        val fx = SecretariaFixture()
        val clinic = fx.clinic(fx.bootstrap().clinicId)
        val doctorId = fx.catalog.doctors(clinic.id, false).first().id
        val perigoso = fx.clinics.findOrCreatePatient(clinic.id, "=HYPERLINK(\"http://evil\")", "5521987654321")
        val virgula = fx.clinics.findOrCreatePatient(clinic.id, "Silva; Ana \"A\"", "5521911112222")
        fx.appointments.book(clinic, doctorId, perigoso, local("2026-10-06T09:00"), AppointmentCreator.IA)
        fx.appointments.book(clinic, doctorId, virgula, local("2026-10-06T09:30"), AppointmentCreator.IA)

        val csv = fx.reports.appointmentsCsv(clinic, LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 6)).ok()
        val lines = csv.trimEnd().split("\r\n")
        assertEquals("id;inicio;fim;medico;especialidade;paciente;telefone;status;origem;criado_em", lines.first())
        assertEquals(3, lines.size)
        assertTrue(lines[1].contains("'=HYPERLINK"), lines[1]) // fórmula neutralizada com apóstrofo
        assertTrue(lines.none { it.split(";").any { c -> c.startsWith("=") } })
        assertTrue(lines[2].contains("\"Silva; Ana \"\"A\"\"\""), lines[2]) // ; e aspas escapados
        assertTrue(lines[1].contains("2026-10-06T09:00"))

        val calls = fx.reports.callsCsv(clinic, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)).ok()
        assertEquals("id;inicio;duracao_segundos;paciente;telefone;assunto;resultado;intencao;canal", calls.trimEnd().split("\r\n").single())
    }
}
