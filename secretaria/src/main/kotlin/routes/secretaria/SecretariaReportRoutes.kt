package routes.secretaria

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import schemas.secretaria.ServiceResult
import java.time.Instant
import java.time.LocalDate

/** Relatórios (menu Relatórios): resumo por período e exportação CSV. Padrão: últimos 30 dias. */
internal fun Route.reportRoutes(api: SecretariaApi) {

    get("/reports/summary") {
        val access = call.requireClinic(api) ?: return@get
        val (from, to) = call.reportPeriod(access.clinic.zone) ?: return@get
        call.respondResult(api.reports.summary(access.clinic, from, to))
    }

    get("/reports/calls.csv") {
        val access = call.requireClinic(api, Access.WRITE) ?: return@get
        val (from, to) = call.reportPeriod(access.clinic.zone) ?: return@get
        call.respondCsv(api.reports.callsCsv(access.clinic, from, to), "chamadas_${from}_$to.csv")
    }

    get("/reports/appointments.csv") {
        val access = call.requireClinic(api, Access.WRITE) ?: return@get
        val (from, to) = call.reportPeriod(access.clinic.zone) ?: return@get
        call.respondCsv(api.reports.appointmentsCsv(access.clinic, from, to), "agendamentos_${from}_$to.csv")
    }
}

/** from/to (AAAA-MM-DD); padrão: de 29 dias atrás até hoje. Responde 400 e retorna null se inválido. */
private suspend fun ApplicationCall.reportPeriod(zone: java.time.ZoneId): Pair<LocalDate, LocalDate>? {
    val today = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone).toLocalDate()
    val from = dateParam("from", today.minusDays(29)) ?: run { bad("Data inicial inválida (use AAAA-MM-DD)."); return null }
    val to = dateParam("to", today) ?: run { bad("Data final inválida (use AAAA-MM-DD)."); return null }
    return from to to
}

/** CSV com BOM UTF-8 (acentos corretos no Excel) e separador `;` (padrão do Excel em português). */
private suspend fun ApplicationCall.respondCsv(result: ServiceResult<String>, fileName: String) {
    when (result) {
        is ServiceResult.Ok -> {
            response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$fileName\"")
            respondText("﻿" + result.value, ContentType.parse("text/csv; charset=UTF-8"))
        }
        is ServiceResult.Err -> respondResult(result)
    }
}
