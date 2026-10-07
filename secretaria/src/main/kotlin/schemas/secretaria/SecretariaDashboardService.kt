package schemas.secretaria

/** Monta, numa só resposta, tudo que a tela inicial do dashboard Flutter precisa. */
class SecretariaDashboardService(
    private val clinics: SecretariaClinicService,
    private val calls: SecretariaCallService,
    private val appointments: SecretariaAppointmentService,
    private val settings: SecretariaSettingsService,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun build(clinicSummary: ClinicSummaryDto, userId: Long, userName: String): DashboardDto? {
        val clinic = clinics.clinic(clinicSummary.id) ?: return null
        val zone = clinic.zone

        // "Hoje" e "ontem" no fuso da clínica, convertidos para instantes UTC.
        val today = java.time.Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()
        val startToday = today.atStartOfDay().toEpochMs(zone)
        val startYesterday = today.minusDays(1).atStartOfDay().toEpochMs(zone)
        val startTomorrow = today.plusDays(1).atStartOfDay().toEpochMs(zone)

        val (callsToday, avgToday) = calls.statsBetween(clinic.id, startToday, startTomorrow)
        val (callsYesterday, avgYesterday) = calls.statsBetween(clinic.id, startYesterday, startToday)
        val apptsToday = appointments.countCreatedBetween(clinic.id, startToday, startTomorrow)
        val apptsYesterday = appointments.countCreatedBetween(clinic.id, startYesterday, startToday)

        return DashboardDto(
            clinic = clinicSummary,
            userName = userName,
            stats = DashboardStatsDto(
                callsToday = stat(callsToday, callsYesterday),
                appointmentsToday = stat(apptsToday, apptsYesterday),
                avgSeconds = stat(avgToday, avgYesterday),
            ),
            activeCall = calls.activeCall(clinic.id),
            recentCalls = calls.recent(clinic.id, limit = 5, offset = 0, onlyEnded = true),
            upcomingAppointments = appointments.upcoming(clinic, limit = 3),
            plan = clinics.planUsage(clinic.id, zone),
            unreadNotifications = clinics.unreadNotifications(clinic.id, userId),
            assistant = settings.assistantStatus(clinic.id),
        )
    }

    /** `changePercent` é sempre positivo; o sentido vem em `trendUp` (o app escolhe a cor). */
    private fun stat(today: Long, yesterday: Long): StatDto {
        val change = ratioChange(today, yesterday)
        return StatDto(today, yesterday, kotlin.math.abs(change), change >= 0)
    }
}
