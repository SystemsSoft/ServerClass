import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import schemas.sentinela.SentinelaAvailability
import schemas.sentinela.SentinelaBillingMode
import schemas.sentinela.SentinelaBillingService
import services.SentinelaUsageMeter
import services.SentinelaUsageMeter.Decision
import kotlin.test.Test
import kotlin.test.assertEquals

class SentinelaUsageMeterTest {

    private val trial = SentinelaAvailability(SentinelaBillingMode.TRIAL, remainingSeconds = 1800, hardRemainingSeconds = 3600)
    private val stillTrial = suspend { trial }

    private fun newBilling() =
        SentinelaBillingService(Database.connect("jdbc:h2:mem:${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1", "org.h2.Driver"))

    @Test
    fun `a new account can go up to 1 hour of free recording even though only 30 min are free`() = runBlocking {
        val billing = newBilling()
        val fresh = billing.availability("uid")
        assertEquals(1800, fresh.remainingSeconds)
        assertEquals(3600, fresh.hardRemainingSeconds)

        billing.consume("uid", SentinelaBillingMode.TRIAL, 600)
        val later = billing.availability("uid")
        assertEquals(1200, later.remainingSeconds)
        assertEquals(3000, later.hardRemainingSeconds) // o teto de 1 hora é do total grátis da conta, não de cada gravação

        billing.consume("uid", SentinelaBillingMode.TRIAL, 1200)
        assertEquals(false, billing.availability("uid").canRecord) // 30 min usados: nova gravação exige plano
    }

    @Test
    fun `passing the free limit warns once, keeps recording and stops only at the 1 hour cap`() = runBlocking {
        val meter = SentinelaUsageMeter(trial)
        var refreshes = 0
        val refresh = suspend { refreshes++; trial }

        assertEquals(Decision.Continue, meter.tick(100, refresh))
        assertEquals(Decision.Continue, meter.tick(1799, refresh))
        assertEquals(0, refreshes) // dentro do limite grátis nem consulta o saldo

        assertEquals(Decision.Warn(cutoffInSeconds = 1800), meter.tick(1800, refresh)) // avisa que ultrapassou, sem parar
        assertEquals(Decision.Continue, meter.tick(1803, refresh)) // só avisa uma vez
        assertEquals(Decision.Continue, meter.tick(3599, refresh))
        assertEquals(Decision.Stop, meter.tick(3600, refresh))

        assertEquals(SentinelaBillingMode.TRIAL to 3600L, meter.pendingCharge(3600))
        assertEquals(SentinelaBillingMode.TRIAL to 3600L, meter.pendingCharge(4000)) // nunca cobra além do teto
    }

    @Test
    fun `the warning counts down to the cap of what is left in the account`() = runBlocking {
        val partlyUsed = SentinelaAvailability(SentinelaBillingMode.TRIAL, remainingSeconds = 800, hardRemainingSeconds = 2600)
        val meter = SentinelaUsageMeter(partlyUsed)
        val refresh = suspend { partlyUsed }

        assertEquals(Decision.Warn(cutoffInSeconds = 1800), meter.tick(800, refresh))
        assertEquals(Decision.Stop, meter.tick(2600, refresh))
    }

    @Test
    fun `buying the single plan while over the limit switches to it without cutting the recording`() = runBlocking {
        val meter = SentinelaUsageMeter(trial)
        val purchased = suspend { SentinelaAvailability(SentinelaBillingMode.PLAN, remainingSeconds = 3600) }

        assertEquals(Decision.Warn(1800), meter.tick(1800, stillTrial))
        // aos 2000 s o pagamento foi confirmado: 200 s de excedente saem da hora comprada
        assertEquals(
            Decision.SwitchedToPlan(trialSecondsToCharge = 1800, planRemainingSeconds = 3400),
            meter.tick(2000, purchased),
        )
        assertEquals(SentinelaBillingMode.PLAN, meter.mode)

        assertEquals(Decision.Continue, meter.tick(3700, purchased)) // já passou do teto de 1 h grátis e segue
        assertEquals(Decision.Stop, meter.tick(1800 + 3600, purchased)) // fim da hora comprada
        assertEquals(SentinelaBillingMode.PLAN to 700L, meter.pendingCharge(2500))
    }

    @Test
    fun `a paid plan stops when its hours run out and never asks for the balance before that`() = runBlocking {
        val meter = SentinelaUsageMeter(SentinelaAvailability(SentinelaBillingMode.PLAN, remainingSeconds = 3600))
        var refreshes = 0

        assertEquals(Decision.Continue, meter.tick(3599) { refreshes++; trial })
        assertEquals(Decision.Stop, meter.tick(3600) { refreshes++; trial })
        assertEquals(0, refreshes)
        assertEquals(SentinelaBillingMode.PLAN to 3600L, meter.pendingCharge(3600))
    }

    @Test
    fun `usage is charged to the right balances end to end`() = runBlocking {
        val billing = newBilling()
        val uid = "uid-1"
        val meter = SentinelaUsageMeter(billing.availability(uid))

        assertEquals(Decision.Warn(1800), meter.tick(1800) { billing.availability(uid) })
        billing.grantAvulso(uid) // pagamento confirmado pelo webhook durante a gravação
        val decision = meter.tick(2100) { billing.availability(uid) }
        assertEquals(Decision.SwitchedToPlan(1800, 3300), decision)
        billing.consume(uid, SentinelaBillingMode.TRIAL, (decision as Decision.SwitchedToPlan).trialSecondsToCharge)

        // gravação termina aos 2700 s: 900 s (excedente + depois da compra) saem da hora avulsa
        val (mode, seconds) = meter.pendingCharge(2700)!!
        billing.consume(uid, mode, seconds)

        val status = billing.status(uid)
        assertEquals(1800, status.trialUsedSeconds)
        assertEquals(900, status.planUsedSeconds)
        assertEquals(2700, billing.availability(uid).remainingSeconds) // sobra 45 min da hora avulsa
    }
}
