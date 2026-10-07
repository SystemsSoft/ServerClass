package routes.secretaria

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import schemas.secretaria.PhotoDto
import schemas.secretaria.AppointmentCreator
import schemas.secretaria.AppointmentStatus
import schemas.secretaria.BookResult
import schemas.secretaria.CallDetailDto
import schemas.secretaria.CreateAppointmentRequest
import schemas.secretaria.ErrorDto
import schemas.secretaria.RecordingDto
import schemas.secretaria.RescheduleRequest
import schemas.secretaria.ServiceResult
import schemas.secretaria.UpdateAppointmentStatusRequest
import schemas.secretaria.UpsertPatientRequest
import schemas.secretaria.normalizePhone
import schemas.secretaria.parseLocalIso
import schemas.secretaria.toEpochMs
import java.time.Instant
import java.time.LocalDate

private fun today(zone: java.time.ZoneId): LocalDate = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone).toLocalDate()

/** Telas de uso diário: Início, Chamadas, Agendamentos, Pacientes, notificações e plano. */
internal fun Route.operationRoutes(api: SecretariaApi) {

    // ── Início ───────────────────────────────────────────────────────────────
    get("/dashboard") {
        val access = call.requireClinic(api) ?: return@get
        val data = api.dashboard.build(access.summary, access.user.id, access.user.name)
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("Clínica não encontrada"))
        call.respond(data)
    }

    // ── Chamadas ─────────────────────────────────────────────────────────────

    /** Filtros: status (agendada|remarcada|finalizada|em_andamento), q (paciente/telefone/assunto), from/to (AAAA-MM-DD). */
    get("/calls") {
        val access = call.requireClinic(api) ?: return@get
        val q = call.request.queryParameters
        val zone = access.clinic.zone
        val from = q["from"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: return@get call.bad("Data inicial inválida (use AAAA-MM-DD).") }
        val to = q["to"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: return@get call.bad("Data final inválida (use AAAA-MM-DD).") }
        call.respond(
            api.calls.page(
                clinicId = access.clinic.id,
                limit = (q["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100),
                offset = (q["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0),
                status = q["status"],
                query = q["q"],
                fromMs = from?.atStartOfDay()?.toEpochMs(zone),
                toMs = to?.plusDays(1)?.atStartOfDay()?.toEpochMs(zone),
            ),
        )
    }

    get("/calls/{id}") {
        val access = call.requireClinic(api) ?: return@get
        val id = call.idParam() ?: return@get call.bad("id inválido")
        val summary = api.calls.get(access.clinic.id, id) ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("Chamada não encontrada"))
        call.respond(
            CallDetailDto(
                call = summary,
                transcript = api.calls.transcript(access.clinic.id, id).orEmpty(),
                appointments = api.appointments.bySourceCall(access.clinic, id),
                recordingUrl = api.calls.recordingUrl(access.clinic.id, id),
            ),
        )
    }

    get("/calls/{id}/transcript") {
        val access = call.requireClinic(api) ?: return@get
        val id = call.idParam() ?: return@get call.bad("id inválido")
        call.respond(api.calls.transcript(access.clinic.id, id) ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("Chamada não encontrada")))
    }

    /** Botão "Ouvir gravação". O servidor ainda não grava áudio: devolve 404 enquanto `recordingUrl` estiver vazio. */
    get("/calls/{id}/recording") {
        val access = call.requireClinic(api) ?: return@get
        val id = call.idParam() ?: return@get call.bad("id inválido")
        if (api.calls.get(access.clinic.id, id) == null) return@get call.respond(HttpStatusCode.NotFound, ErrorDto("Chamada não encontrada"))
        val url = api.calls.recordingUrl(access.clinic.id, id) ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("Esta chamada não tem gravação"))
        call.respond(RecordingDto(url))
    }

    /** Botão "Encerrar ligação" do dashboard. */
    post("/calls/{id}/end") {
        val access = call.requireClinic(api, Access.WRITE) ?: return@post
        val id = call.idParam() ?: return@post call.bad("id inválido")
        if (api.calls.get(access.clinic.id, id) == null) return@post call.respond(HttpStatusCode.NotFound, ErrorDto("Chamada não encontrada"))
        if (!api.registry.terminate(id)) api.calls.end(id) // não está neste processo (ex.: ficou órfã): só fecha o registro
        call.respond(HttpStatusCode.NoContent)
    }

    // ── Agendamentos ─────────────────────────────────────────────────────────

    /** Horários livres para o formulário "Novo agendamento". */
    get("/slots") {
        val access = call.requireClinic(api) ?: return@get
        val q = call.request.queryParameters
        val from = call.dateParam("date", today(access.clinic.zone)) ?: return@get call.bad("Data inválida (use AAAA-MM-DD).")
        val days = (q["days"]?.toIntOrNull() ?: 7).coerceIn(1, 31)
        call.respond(api.appointments.availableSlots(access.clinic, q["doctorId"]?.toLongOrNull(), q["specialty"], from, days, limit = 60))
    }

    /** Filtros: from/to (AAAA-MM-DD, padrão hoje..+7 dias), doctorId, patientId, status. */
    get("/appointments") {
        val access = call.requireClinic(api) ?: return@get
        val q = call.request.queryParameters
        val zone = access.clinic.zone
        val from = call.dateParam("from", today(zone)) ?: return@get call.bad("Data inicial inválida (use AAAA-MM-DD).")
        val to = (call.dateParam("to", from.plusDays(7)) ?: return@get call.bad("Data final inválida (use AAAA-MM-DD).")).plusDays(1)
        if (to.isAfter(from.plusDays(93))) return@get call.bad("Período máximo de 90 dias")
        val status = q["status"]?.let { s ->
            AppointmentStatus.entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: return@get call.bad("Status inválido")
        }
        call.respond(
            api.appointments.listBetween(
                access.clinic, from.atStartOfDay().toEpochMs(zone), to.atStartOfDay().toEpochMs(zone),
                doctorId = q["doctorId"]?.toLongOrNull(), status = status, patientId = q["patientId"]?.toLongOrNull(),
            ),
        )
    }

    get("/appointments/{id}") {
        val access = call.requireClinic(api) ?: return@get
        val id = call.idParam() ?: return@get call.bad("id inválido")
        call.respond(api.appointments.get(access.clinic, id) ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("Agendamento não encontrado")))
    }

    post("/appointments") {
        call.requireUser(api.auth) ?: return@post
        val body = call.receiveOrNull<CreateAppointmentRequest>() ?: return@post call.badJson()
        val access = call.requireClinic(api, Access.WRITE, clinicId = body.clinicId) ?: return@post
        val phone = normalizePhone(body.patientPhone)
        val start = parseLocalIso(body.startLocal)
        if (body.patientName.isBlank() || phone == null || start == null) {
            return@post call.bad("Informe nome, telefone com DDD e horário (AAAA-MM-DDTHH:MM)")
        }
        val patientId = api.clinics.findOrCreatePatient(access.clinic.id, body.patientName, phone)
        when (val result = api.appointments.book(access.clinic, body.doctorId, patientId, start, AppointmentCreator.USUARIO, notes = body.notes)) {
            is BookResult.Ok -> call.respond(HttpStatusCode.Created, result.appointment)
            is BookResult.Fail -> call.respond(HttpStatusCode.Conflict, ErrorDto(result.reason))
        }
    }

    /** Confirmar, concluir ou marcar falta: {"status": "confirmado" | "concluido" | "faltou"}. */
    post("/appointments/{id}/status") {
        val access = call.requireClinic(api, Access.WRITE) ?: return@post
        val id = call.idParam() ?: return@post call.bad("id inválido")
        val body = call.receiveOrNull<UpdateAppointmentStatusRequest>() ?: return@post call.badJson()
        val status = AppointmentStatus.entries.firstOrNull { it.name.equals(body.status.trim(), ignoreCase = true) }
            ?: return@post call.bad("Status inválido")
        call.respondResult(api.appointments.setStatus(access.clinic, id, status))
    }

    post("/appointments/{id}/reschedule") {
        val access = call.requireClinic(api, Access.WRITE) ?: return@post
        val id = call.idParam() ?: return@post call.bad("id inválido")
        val body = call.receiveOrNull<RescheduleRequest>() ?: return@post call.badJson()
        val start = parseLocalIso(body.startLocal) ?: return@post call.bad("Horário inválido (use AAAA-MM-DDTHH:MM)")
        when (val result = api.appointments.reschedule(access.clinic, id, start, onlyPatientId = null, createdBy = AppointmentCreator.USUARIO)) {
            is BookResult.Ok -> call.respond(result.appointment)
            is BookResult.Fail -> call.respond(HttpStatusCode.Conflict, ErrorDto(result.reason))
        }
    }

    post("/appointments/{id}/cancel") {
        val access = call.requireClinic(api, Access.WRITE) ?: return@post
        val id = call.idParam() ?: return@post call.bad("id inválido")
        if (api.appointments.cancel(access.clinic.id, id, onlyPatientId = null)) call.respond(HttpStatusCode.NoContent)
        else call.respond(HttpStatusCode.NotFound, ErrorDto("Agendamento ativo não encontrado"))
    }

    // ── Pacientes ────────────────────────────────────────────────────────────

    get("/patients") {
        val access = call.requireClinic(api) ?: return@get
        val q = call.request.queryParameters
        call.respond(
            api.patients.page(
                access.clinic.id, q["q"], (q["limit"]?.toIntOrNull() ?: 30).coerceIn(1, 100), (q["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0),
            ),
        )
    }

    get("/patients/{id}") {
        val access = call.requireClinic(api) ?: return@get
        val id = call.idParam() ?: return@get call.bad("id inválido")
        call.respondResult(api.patients.detail(access.clinic, id))
    }

    /** Foto do paciente (cadastro do app) como data URL; 404 se não tiver ou não for desta clínica. */
    get("/patients/{id}/photo") {
        val access = call.requireClinic(api) ?: return@get
        val id = call.idParam() ?: return@get call.bad("id inválido")
        val photo = api.profiles.photoForPatient(access.clinic.id, id)
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("Paciente sem foto"))
        call.respond(PhotoDto(photo))
    }

    post("/patients") {
        val access = call.requireClinic(api, Access.WRITE) ?: return@post
        val body = call.receiveOrNull<UpsertPatientRequest>() ?: return@post call.badJson()
        call.respondResult(api.patients.create(access.clinic.id, body), HttpStatusCode.Created)
    }

    put("/patients/{id}") {
        val access = call.requireClinic(api, Access.WRITE) ?: return@put
        val id = call.idParam() ?: return@put call.bad("id inválido")
        val body = call.receiveOrNull<UpsertPatientRequest>() ?: return@put call.badJson()
        call.respondResult(api.patients.update(access.clinic.id, id, body))
    }

    /** Exclusão LGPD: anonimiza o paciente e cancela consultas futuras (só administrador). */
    delete("/patients/{id}") {
        val access = call.requireClinic(api, Access.ADMIN) ?: return@delete
        val id = call.idParam() ?: return@delete call.bad("id inválido")
        call.respondDone(api.patients.anonymize(access.clinic.id, id))
    }

    // ── Notificações (sino) ──────────────────────────────────────────────────

    get("/notifications") {
        val access = call.requireClinic(api) ?: return@get
        val q = call.request.queryParameters
        call.respond(
            api.clinics.notifications(
                access.clinic.id, access.user.id, unreadOnly = q["unreadOnly"] == "true",
                limit = (q["limit"]?.toIntOrNull() ?: 30).coerceIn(1, 100), offset = (q["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0),
            ),
        )
    }

    post("/notifications/read") {
        val access = call.requireClinic(api) ?: return@post
        api.clinics.markNotificationsRead(access.clinic.id, access.user.id)
        call.respond(HttpStatusCode.NoContent)
    }

    post("/notifications/{id}/read") {
        val access = call.requireClinic(api) ?: return@post
        val id = call.idParam() ?: return@post call.bad("id inválido")
        if (api.clinics.markNotificationRead(access.clinic.id, access.user.id, id)) call.respond(HttpStatusCode.NoContent)
        else call.respond(HttpStatusCode.NotFound, ErrorDto("Notificação não encontrada"))
    }

    // ── Plano ("Ver detalhes") ───────────────────────────────────────────────
    get("/plan") {
        val access = call.requireClinic(api) ?: return@get
        call.respondResult(api.plans.detail(access.clinic))
    }
}
