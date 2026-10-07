package routes.secretaria

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import org.koin.ktor.ext.get
import schemas.secretaria.ClinicInfo
import schemas.secretaria.ClinicRole
import schemas.secretaria.ClinicSummaryDto
import schemas.secretaria.ErrorDto
import schemas.secretaria.PublicClinicDto
import schemas.secretaria.SecretariaAppointmentService
import schemas.secretaria.SecretariaAuthService
import schemas.secretaria.SecretariaCallService
import schemas.secretaria.SecretariaCatalogService
import schemas.secretaria.SecretariaClinicService
import schemas.secretaria.SecretariaDashboardService
import schemas.secretaria.SecretariaPatientService
import schemas.secretaria.SecretariaPlanService
import schemas.secretaria.SecretariaProfileService
import schemas.secretaria.SaveProfileRequest
import schemas.secretaria.CpfRequest
import schemas.secretaria.SecretariaReportService
import schemas.secretaria.SecretariaSettingsService
import schemas.secretaria.SecretariaTeamService
import schemas.secretaria.ServiceResult
import schemas.secretaria.UserDto
import services.secretaria.SecretariaCallHandler
import services.secretaria.SecretariaCallRegistry
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

private fun config(property: String, env: String): String? = System.getProperty(property) ?: System.getenv(env)

/** Serviços usados pelas rotas, resolvidos uma única vez na partida. */
internal class SecretariaApi(
    val auth: SecretariaAuthService,
    val clinics: SecretariaClinicService,
    val calls: SecretariaCallService,
    val appointments: SecretariaAppointmentService,
    val dashboard: SecretariaDashboardService,
    val registry: SecretariaCallRegistry,
    val handler: SecretariaCallHandler,
    val settings: SecretariaSettingsService,
    val team: SecretariaTeamService,
    val catalog: SecretariaCatalogService,
    val patients: SecretariaPatientService,
    val plans: SecretariaPlanService,
    val reports: SecretariaReportService,
    val profiles: SecretariaProfileService,
) {
    val throttle = LoginThrottle()
}

/** Registra todas as rotas do módulo SecretárIA (dashboard da clínica + chamada de voz do PWA). */
fun Application.configureSecretaria() {
    // Resolvidos já na partida (não na primeira requisição): se o banco da SecretárIA não abrir, o erro aparece
    // aqui — e o Application.kt desliga só este módulo, sem derrubar os demais produtos do servidor.
    val api = SecretariaApi(
        get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
    )
    val allowedOrigins = config("secretaria.allowedOrigins", "SECRETARIA_ALLOWED_ORIGINS")
        ?.split(',')?.map { it.trim().trimEnd('/') }?.filter { it.isNotEmpty() }.orEmpty()

    routing {
        publicRoutes(api, allowedOrigins)
        route("/secretaria") {
            managementRoutes(api)
            operationRoutes(api)
            reportRoutes(api)
        }
    }
}

/** PWA do paciente: sem login, identificado pela chave pública da clínica. */
private fun io.ktor.server.routing.Route.publicRoutes(api: SecretariaApi, allowedOrigins: List<String>) {
    /** Tela "escolha a clínica" do PWA: clínicas ativas, cada uma com a chave que abre a chamada. */
    get("/secretaria/public/clinics") {
        call.respond(api.settings.publicDirectory())
    }

    /**
     * Cadastro do paciente no app (cria ou atualiza pelo CPF). Só o CPF identifica o paciente (sem senha): decisão do
     * produto, com o risco descrito no SECRETARIA.md. O CPF vai no corpo, nunca na URL (não fica em log de acesso).
     */
    post("/secretaria/public/patients") {
        val body = call.receiveOrNull<SaveProfileRequest>() ?: return@post call.badJson()
        call.respondResult(api.profiles.save(body))
    }

    /** Consultas do paciente (todas as clínicas) para a aba "Agendamentos" do app. */
    post("/secretaria/public/patients/appointments") {
        val body = call.receiveOrNull<CpfRequest>() ?: return@post call.badJson()
        call.respondResult(api.profiles.appointments(body.cpf))
    }

    get("/secretaria/public/{publicKey}") {
        val clinic = call.parameters["publicKey"]?.let { api.clinics.clinicByPublicKey(it) }
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("Clínica não encontrada"))
        call.respond(PublicClinicDto(clinic.name, clinic.responsibleName, api.settings.assistantStatus(clinic.id).online))
    }

    /**
     * Chamada de voz: wss://…/ws/secretaria/{publicKey}?consent=true
     * `consent=true` confirma que o paciente foi avisado de que fala com uma IA e que a conversa é registrada (LGPD).
     */
    webSocket("/ws/secretaria/{publicKey}") {
        val origin = call.request.headers[HttpHeaders.Origin]?.trimEnd('/')
        if (allowedOrigins.isNotEmpty() && origin !in allowedOrigins) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Origem não permitida."))
            return@webSocket
        }
        if (call.request.queryParameters["consent"] != "true") {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "É preciso aceitar o atendimento por IA e o registro da conversa (consent=true)."))
            return@webSocket
        }
        val clinic = call.parameters["publicKey"]?.let { api.clinics.clinicByPublicKey(it) }
        if (clinic == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Clínica não encontrada."))
            return@webSocket
        }
        api.handler.handle(this, clinic)
    }
}

// ── autenticação e autorização ───────────────────────────────────────────────

/** READ: qualquer papel · WRITE: admin e secretária · ADMIN: só administrador. */
internal enum class Access { READ, WRITE, ADMIN }

internal class ClinicAccess(val user: UserDto, val clinic: ClinicInfo, val role: ClinicRole, val summary: ClinicSummaryDto)

/** Extrai e valida o `Authorization: Bearer <jwt>`. Responde 401 e retorna null se faltar/for inválido. */
internal suspend fun ApplicationCall.requireUser(auth: SecretariaAuthService): UserDto? {
    val token = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
    val user = token?.takeIf { it.isNotEmpty() }?.let { auth.userIdFromToken(it) }?.let { auth.user(it) }
    if (user == null) respond(HttpStatusCode.Unauthorized, ErrorDto("Não autenticado"))
    return user
}

/**
 * Autentica e confirma que o usuário pertence à clínica de `?clinicId=` (ou de [clinicId], quando vem no corpo)
 * com o nível de [access] pedido. Responde 400/401/403/404 e retorna null quando não passa.
 */
internal suspend fun ApplicationCall.requireClinic(api: SecretariaApi, access: Access = Access.READ, clinicId: Long? = null): ClinicAccess? {
    val user = requireUser(api.auth) ?: return null
    val id = clinicId ?: request.queryParameters["clinicId"]?.toLongOrNull()
    if (id == null) {
        respond(HttpStatusCode.BadRequest, ErrorDto("Informe o clinicId"))
        return null
    }
    val summary = api.auth.clinicsOf(user.id).firstOrNull { it.id == id }
    if (summary == null) {
        // Mesma resposta para "não existe" e "não é sua": não revela quais clínicas existem.
        respond(HttpStatusCode.Forbidden, ErrorDto("Sem acesso a esta clínica"))
        return null
    }
    val role = ClinicRole.valueOf(summary.role.uppercase())
    val allowed = when (access) {
        Access.READ -> true
        Access.WRITE -> role != ClinicRole.PROFISSIONAL
        Access.ADMIN -> role == ClinicRole.ADMIN
    }
    if (!allowed) {
        respond(HttpStatusCode.Forbidden, ErrorDto("Seu perfil não permite esta ação"))
        return null
    }
    val clinic = api.clinics.clinic(id)
    if (clinic == null) {
        respond(HttpStatusCode.NotFound, ErrorDto("Clínica não encontrada"))
        return null
    }
    return ClinicAccess(user, clinic, role, summary)
}

// ── utilidades de rota ───────────────────────────────────────────────────────

internal suspend inline fun <reified T : Any> ApplicationCall.receiveOrNull(): T? = runCatching { receive<T>() }.getOrNull()

internal suspend fun ApplicationCall.badJson() = respond(HttpStatusCode.BadRequest, ErrorDto("JSON inválido"))

internal suspend fun ApplicationCall.bad(message: String) = respond(HttpStatusCode.BadRequest, ErrorDto(message))

/** Traduz [ServiceResult] para HTTP: Ok → [okStatus] + corpo; Err → status do erro + `{"error": …}`. */
internal suspend inline fun <reified T : Any> ApplicationCall.respondResult(result: ServiceResult<T>, okStatus: HttpStatusCode = HttpStatusCode.OK) {
    when (result) {
        is ServiceResult.Ok -> respond(okStatus, result.value)
        is ServiceResult.Err -> respond(HttpStatusCode.fromValue(result.kind.httpStatus), ErrorDto(result.message))
    }
}

/** Para operações sem corpo de resposta: Ok → 204. */
internal suspend fun ApplicationCall.respondDone(result: ServiceResult<Unit>) {
    when (result) {
        is ServiceResult.Ok -> respond(HttpStatusCode.NoContent)
        is ServiceResult.Err -> respond(HttpStatusCode.fromValue(result.kind.httpStatus), ErrorDto(result.message))
    }
}

internal fun ApplicationCall.idParam(name: String = "id"): Long? = parameters[name]?.toLongOrNull()

/** Data AAAA-MM-DD de um parâmetro; ausente → [default]; inválida → null (o chamador responde 400). */
internal fun ApplicationCall.dateParam(name: String, default: LocalDate): LocalDate? =
    request.queryParameters[name]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: if (request.queryParameters[name] == null) default else null

/** Bloqueia temporariamente uma chave (e-mail+IP, ou usuário) após 5 falhas em 15 minutos. */
internal class LoginThrottle(private val maxFailures: Int = 5, private val windowMs: Long = 15 * 60_000L) {
    private val failures = ConcurrentHashMap<String, MutableList<Long>>()

    fun isBlocked(key: String): Boolean {
        val list = failures[key] ?: return false
        synchronized(list) {
            list.removeAll { it < System.currentTimeMillis() - windowMs }
            return list.size >= maxFailures
        }
    }

    fun recordFailure(key: String) {
        val list = failures.computeIfAbsent(key) { mutableListOf() }
        synchronized(list) { list.add(System.currentTimeMillis()) }
    }

    fun reset(key: String) {
        failures.remove(key)
    }
}
