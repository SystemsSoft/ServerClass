package routes.secretaria

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import schemas.secretaria.AddTeamMemberRequest
import schemas.secretaria.AssignPlanRequest
import schemas.secretaria.BootstrapRequest
import schemas.secretaria.ChangePasswordRequest
import schemas.secretaria.CreatePlanRequest
import schemas.secretaria.DoctorUpsertRequest
import schemas.secretaria.EmailAlreadyUsedException
import schemas.secretaria.ErrorDto
import schemas.secretaria.ErrorKind
import schemas.secretaria.LoginRequest
import schemas.secretaria.MeResponse
import schemas.secretaria.RegisterClinicRequest
import schemas.secretaria.RotateKeyResponse
import schemas.secretaria.ScheduleRequest
import schemas.secretaria.ServiceResult
import schemas.secretaria.UpdateClinicRequest
import schemas.secretaria.UpdateProfileRequest
import schemas.secretaria.UpdateSettingsRequest
import schemas.secretaria.UpdateTeamMemberRequest
import schemas.secretaria.UsageSummaryDto
import java.security.MessageDigest

private fun config(property: String, env: String): String? = System.getProperty(property) ?: System.getenv(env)

/** Autenticação, perfil, Configurações (clínica, IA, equipe, médicos e agenda) e administração do sistema. */
internal fun Route.managementRoutes(api: SecretariaApi) {

    // ── administração do sistema (chave X-Admin-Key; desligada se não configurada) ───
    post("/admin/bootstrap") {
        if (!call.requireAdminKey()) return@post
        val body = call.receiveOrNull<BootstrapRequest>() ?: return@post call.badJson()
        if (isInvalidNewClinic(body.clinicName, body.userName, body.email, body.password, BOOTSTRAP_MIN_PASSWORD)) {
            return@post call.bad(newClinicError(BOOTSTRAP_MIN_PASSWORD))
        }
        try {
            call.respond(HttpStatusCode.Created, api.clinics.bootstrap(body))
        } catch (e: EmailAlreadyUsedException) {
            call.respond(HttpStatusCode.Conflict, ErrorDto("E-mail já cadastrado"))
        } catch (e: java.time.DateTimeException) {
            call.bad("Fuso horário inválido")
        }
    }

    /** Consumo e custo da IA por chave do Gemini (todas as clínicas). ?from=AAAA-MM-DD&to=AAAA-MM-DD; padrão: últimos 30 dias. */
    get("/admin/usage") {
        if (!call.requireAdminKey()) return@get
        val zone = java.time.ZoneId.of("America/Sao_Paulo")
        val today = java.time.LocalDate.now(zone)
        val to = call.dateParam("to", today) ?: return@get call.bad("to inválido (AAAA-MM-DD)")
        val from = call.dateParam("from", to.minusDays(29)) ?: return@get call.bad("from inválido (AAAA-MM-DD)")
        if (from.isAfter(to)) return@get call.bad("from deve ser antes de to")
        val (byKey, unmeasured) = api.calls.usageByKey(
            from.atStartOfDay(zone).toInstant().toEpochMilli(),
            to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )
        call.respond(
            UsageSummaryDto(
                from.toString(), to.toString(), api.calls.usdBrl(), byKey,
                Math.round(byKey.sumOf { it.costBrl } * 100) / 100.0, unmeasured,
            ),
        )
    }

    post("/admin/plans") {
        if (!call.requireAdminKey()) return@post
        val body = call.receiveOrNull<CreatePlanRequest>() ?: return@post call.badJson()
        call.respondResult(api.plans.createPlan(body), HttpStatusCode.Created)
    }

    post("/admin/subscription") {
        if (!call.requireAdminKey()) return@post
        val body = call.receiveOrNull<AssignPlanRequest>() ?: return@post call.badJson()
        call.respondDone(api.plans.assign(body.clinicId, body.planId))
    }

    // ── cadastro de clínica, login, perfil e senha ───────────────────────────
    /**
     * Cadastro público (sem chave): cria a clínica + o primeiro administrador e já devolve a sessão, no mesmo formato
     * do login. Os médicos são cadastrados depois, em Configurações. Quem fecha o acesso (convite, limite) é o proxy/nginx.
     */
    post("/auth/register-clinic") {
        val body = call.receiveOrNull<RegisterClinicRequest>() ?: return@post call.badJson()
        if (isInvalidNewClinic(body.clinicName, body.userName, body.email, body.password, REGISTER_MIN_PASSWORD)) {
            return@post call.bad(newClinicError(REGISTER_MIN_PASSWORD))
        }
        try {
            api.clinics.bootstrap(
                BootstrapRequest(body.clinicName, body.responsibleName, body.timezone, body.userName, body.email, body.password),
            )
        } catch (e: EmailAlreadyUsedException) {
            return@post call.respond(HttpStatusCode.Conflict, ErrorDto("E-mail já cadastrado"))
        } catch (e: java.time.DateTimeException) {
            return@post call.bad("Fuso horário inválido")
        }
        val session = api.auth.login(body.email, body.password)
            ?: return@post call.respond(HttpStatusCode.InternalServerError, ErrorDto("Clínica criada, mas não foi possível entrar. Faça login."))
        call.respond(HttpStatusCode.Created, session)
    }

    post("/auth/login") {
        val body = call.receiveOrNull<LoginRequest>() ?: return@post call.badJson()
        val key = "login|${body.email.trim().lowercase()}|${call.request.origin.remoteHost}"
        if (api.throttle.isBlocked(key)) return@post call.respond(HttpStatusCode.TooManyRequests, ErrorDto("Muitas tentativas. Aguarde alguns minutos."))
        val result = api.auth.login(body.email, body.password)
        if (result == null) {
            api.throttle.recordFailure(key)
            return@post call.respond(HttpStatusCode.Unauthorized, ErrorDto("E-mail ou senha inválidos"))
        }
        api.throttle.reset(key)
        call.respond(result)
    }

    get("/me") {
        val user = call.requireUser(api.auth) ?: return@get
        call.respond(MeResponse(user, api.auth.clinicsOf(user.id)))
    }

    /** "Meu perfil": altera o nome. */
    put("/me") {
        val user = call.requireUser(api.auth) ?: return@put
        val body = call.receiveOrNull<UpdateProfileRequest>() ?: return@put call.badJson()
        call.respondResult(api.team.updateProfile(user.id, body.name))
    }

    post("/auth/change-password") {
        val user = call.requireUser(api.auth) ?: return@post
        val body = call.receiveOrNull<ChangePasswordRequest>() ?: return@post call.badJson()
        val key = "pwd|${user.id}"
        if (api.throttle.isBlocked(key)) return@post call.respond(HttpStatusCode.TooManyRequests, ErrorDto("Muitas tentativas. Aguarde alguns minutos."))
        val result = api.team.changePassword(user.id, body.currentPassword, body.newPassword)
        if (result is ServiceResult.Err && result.kind == ErrorKind.FORBIDDEN) api.throttle.recordFailure(key) else api.throttle.reset(key)
        call.respondDone(result)
    }

    // ── clínica ──────────────────────────────────────────────────────────────
    get("/clinic") {
        val access = call.requireClinic(api) ?: return@get
        // a chave pública só aparece para administradores
        call.respond(api.settings.clinicDetail(access.clinic.id, includeKey = access.role == schemas.secretaria.ClinicRole.ADMIN)!!)
    }

    put("/clinic") {
        val access = call.requireClinic(api, Access.ADMIN) ?: return@put
        val body = call.receiveOrNull<UpdateClinicRequest>() ?: return@put call.badJson()
        call.respondResult(api.settings.updateClinic(access.clinic.id, body))
    }

    /** Gera nova chave pública do PWA; a antiga deixa de funcionar imediatamente (use se ela vazar). */
    post("/clinic/rotate-key") {
        val access = call.requireClinic(api, Access.ADMIN) ?: return@post
        call.respond(RotateKeyResponse(api.settings.rotatePublicKey(access.clinic.id)))
    }

    // ── atendimento por IA ───────────────────────────────────────────────────
    get("/settings") {
        val access = call.requireClinic(api) ?: return@get
        call.respond(api.settings.settings(access.clinic.id))
    }

    put("/settings") {
        val access = call.requireClinic(api, Access.ADMIN) ?: return@put
        val body = call.receiveOrNull<UpdateSettingsRequest>() ?: return@put call.badJson()
        call.respondResult(api.settings.updateSettings(access.clinic.id, body))
    }

    /** Card "Secretária IA · Online". */
    get("/assistant/status") {
        val access = call.requireClinic(api) ?: return@get
        call.respond(api.settings.assistantStatus(access.clinic.id))
    }

    // ── equipe ───────────────────────────────────────────────────────────────
    get("/team") {
        val access = call.requireClinic(api, Access.WRITE) ?: return@get
        call.respond(api.team.list(access.clinic.id))
    }

    post("/team") {
        val access = call.requireClinic(api, Access.ADMIN) ?: return@post
        val body = call.receiveOrNull<AddTeamMemberRequest>() ?: return@post call.badJson()
        call.respondResult(api.team.add(access.clinic.id, body), HttpStatusCode.Created)
    }

    put("/team/{userId}") {
        val access = call.requireClinic(api, Access.ADMIN) ?: return@put
        val userId = call.idParam("userId") ?: return@put call.bad("userId inválido")
        val body = call.receiveOrNull<UpdateTeamMemberRequest>() ?: return@put call.badJson()
        call.respondResult(api.team.changeRole(access.clinic.id, userId, body.role))
    }

    delete("/team/{userId}") {
        val access = call.requireClinic(api, Access.ADMIN) ?: return@delete
        val userId = call.idParam("userId") ?: return@delete call.bad("userId inválido")
        call.respondDone(api.team.remove(access.clinic.id, userId))
    }

    // ── médicos, especialidades e agenda semanal ─────────────────────────────
    get("/specialties") {
        val access = call.requireClinic(api) ?: return@get
        call.respond(api.catalog.specialties(access.clinic.id))
    }

    get("/doctors") {
        val access = call.requireClinic(api) ?: return@get
        call.respond(api.catalog.doctors(access.clinic.id, includeInactive = call.request.queryParameters["includeInactive"] == "true"))
    }

    post("/doctors") {
        val access = call.requireClinic(api, Access.WRITE) ?: return@post
        val body = call.receiveOrNull<DoctorUpsertRequest>() ?: return@post call.badJson()
        call.respondResult(api.catalog.createDoctor(access.clinic.id, body), HttpStatusCode.Created)
    }

    put("/doctors/{id}") {
        val access = call.requireClinic(api, Access.WRITE) ?: return@put
        val id = call.idParam() ?: return@put call.bad("id inválido")
        val body = call.receiveOrNull<DoctorUpsertRequest>() ?: return@put call.badJson()
        call.respondResult(api.catalog.updateDoctor(access.clinic.id, id, body))
    }

    /** Desativa o médico (recusa se houver consultas futuras). */
    delete("/doctors/{id}") {
        val access = call.requireClinic(api, Access.WRITE) ?: return@delete
        val id = call.idParam() ?: return@delete call.bad("id inválido")
        call.respondResult(api.catalog.deactivateDoctor(access.clinic.id, id))
    }

    get("/doctors/{id}/schedule") {
        val access = call.requireClinic(api) ?: return@get
        val id = call.idParam() ?: return@get call.bad("id inválido")
        call.respondResult(api.catalog.schedule(access.clinic.id, id))
    }

    put("/doctors/{id}/schedule") {
        val access = call.requireClinic(api, Access.WRITE) ?: return@put
        val id = call.idParam() ?: return@put call.bad("id inválido")
        val body = call.receiveOrNull<ScheduleRequest>() ?: return@put call.badJson()
        call.respondResult(api.catalog.replaceSchedule(access.clinic.id, id, body.windows))
    }
}

/** Tamanho mínimo da senha ao criar uma clínica: pelo servidor (`/admin/bootstrap`) e pelo cadastro público. */
private const val BOOTSTRAP_MIN_PASSWORD = 10
private const val REGISTER_MIN_PASSWORD = 6

private fun newClinicError(minPassword: Int) = "Informe clínica, usuário, e-mail válido e senha com $minPassword+ caracteres"

/** Regras mínimas para criar uma clínica (`/admin/bootstrap` e `/auth/register-clinic`). */
private fun isInvalidNewClinic(clinicName: String, userName: String, email: String, password: String, minPassword: Int) =
    clinicName.isBlank() || userName.isBlank() || !email.contains('@') || password.length < minPassword

/** Exige `X-Admin-Key`. Sem chave configurada o recurso fica "inexistente" (404). */
private suspend fun ApplicationCall.requireAdminKey(): Boolean {
    val adminKey = config("secretaria.adminKey", "SECRETARIA_ADMIN_KEY")
    if (adminKey.isNullOrBlank()) {
        respond(HttpStatusCode.NotFound)
        return false
    }
    val provided = request.headers["X-Admin-Key"].orEmpty()
    if (!MessageDigest.isEqual(provided.toByteArray(), adminKey.toByteArray())) {
        respond(HttpStatusCode.Forbidden, ErrorDto("Chave de administração inválida"))
        return false
    }
    return true
}
