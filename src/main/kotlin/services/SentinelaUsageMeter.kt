package services

import schemas.sentinela.SentinelaAvailability
import schemas.sentinela.SentinelaBillingMode

/**
 * Acompanha o tempo de UMA gravação em andamento e decide o que fazer a cada instante (a cada chunk).
 *
 * - Plano pago: a gravação é encerrada quando as horas do plano acabam.
 * - Teste grátis: ao passar dos 30 min grátis a gravação NÃO é interrompida; o usuário é avisado uma vez
 *   e ela segue até o teto de 1 hora grátis. Se ele comprar horas nesse meio tempo, a gravação passa a
 *   usar o plano (o tempo excedente sai da hora comprada) e não é encerrada no teto.
 */
class SentinelaUsageMeter(initial: SentinelaAvailability) {

    sealed interface Decision {
        data object Continue : Decision

        /** Passou do limite grátis; a gravação continua e será encerrada em [cutoffInSeconds] se nada mudar. */
        data class Warn(val cutoffInSeconds: Long) : Decision

        /** O usuário comprou horas: desconte [trialSecondsToCharge] do teste; o resto do tempo já vai para o plano. */
        data class SwitchedToPlan(val trialSecondsToCharge: Long, val planRemainingSeconds: Long) : Decision

        data object Stop : Decision
    }

    var mode: SentinelaBillingMode = initial.mode
        private set

    private var softLimit = initial.remainingSeconds
    private var hardLimit = initial.hardRemainingSeconds
    private var segmentStart = 0L
    private var warned = false

    /** [elapsed]: segundos desde o início da gravação. [refresh]: consulta o saldo atual (só usado após passar do grátis). */
    suspend fun tick(elapsed: Long, refresh: suspend () -> SentinelaAvailability): Decision {
        val segment = elapsed - segmentStart
        if (segment < softLimit) return Decision.Continue
        if (mode == SentinelaBillingMode.PLAN) return Decision.Stop

        val fresh = refresh()
        if (fresh.mode == SentinelaBillingMode.PLAN && fresh.remainingSeconds > 0) {
            val trialCharge = softLimit
            segmentStart += softLimit
            mode = SentinelaBillingMode.PLAN
            softLimit = fresh.remainingSeconds
            hardLimit = fresh.remainingSeconds
            return Decision.SwitchedToPlan(trialCharge, (softLimit - (elapsed - segmentStart)).coerceAtLeast(0))
        }
        if (segment >= hardLimit) return Decision.Stop
        if (!warned) {
            warned = true
            return Decision.Warn(hardLimit - segment)
        }
        return Decision.Continue
    }

    /** O que ainda falta descontar ao terminar a gravação em [elapsed] segundos, ou null se nada. */
    fun pendingCharge(elapsed: Long): Pair<SentinelaBillingMode, Long>? {
        val seconds = (elapsed - segmentStart).coerceAtLeast(0)
        return when (mode) {
            SentinelaBillingMode.TRIAL -> SentinelaBillingMode.TRIAL to seconds.coerceAtMost(hardLimit)
            SentinelaBillingMode.PLAN -> SentinelaBillingMode.PLAN to seconds
            SentinelaBillingMode.NONE -> null
        }
    }
}
