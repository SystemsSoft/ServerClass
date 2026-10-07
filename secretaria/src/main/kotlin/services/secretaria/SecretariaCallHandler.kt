package services.secretaria

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import schemas.secretaria.CallChannel
import schemas.secretaria.ClinicInfo
import schemas.secretaria.NotificationType
import schemas.secretaria.SecretariaCallService
import schemas.secretaria.SecretariaClinicService
import schemas.secretaria.SecretariaSettingsService
import java.time.Instant

/** Ciclo de vida de uma chamada do PWA: abre o registro, roda a ponte com a IA e fecha o registro. */
class SecretariaCallHandler(
    private val calls: SecretariaCallService,
    private val clinics: SecretariaClinicService,
    private val settings: SecretariaSettingsService,
    private val bridge: SecretariaLiveBridge,
    private val registry: SecretariaCallRegistry,
    private val model: String,
    /** Chamadas simultâneas por clínica (cada uma consome a cota do Gemini). */
    private val maxConcurrentCalls: Int = 5,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger("Secretaria.Call")

    suspend fun handle(session: DefaultWebSocketServerSession, clinic: ClinicInfo) {
        val config = settings.settings(clinic.id)
        if (!config.aiEnabled) {
            session.send(Frame.Text(buildJsonObject {
                put("type", "error"); put("code", "unavailable"); put("message", "O atendimento por IA está temporariamente indisponível.")
            }.toString()))
            session.close(CloseReason(CloseReason.Codes.NORMAL, "unavailable"))
            return
        }
        if (calls.activeCount(clinic.id) >= maxConcurrentCalls) {
            session.send(Frame.Text(buildJsonObject {
                put("type", "error"); put("code", "busy"); put("message", "Todas as linhas estão ocupadas. Tente novamente em instantes.")
            }.toString()))
            session.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "busy"))
            return
        }

        val started = calls.start(clinic.id, CallChannel.PWA, model)
        val ctx = SecretariaCallContext(clinic, started.id, started.startedAt)
        registry.register(started.id) { session.close(CloseReason(CloseReason.Codes.NORMAL, "Encerrada pela clínica")) }
        log.info("Chamada {} iniciada (clínica {})", started.id, clinic.id)

        try {
            val now = Instant.ofEpochMilli(clock()).atZone(clinic.zone)
            // o que a clínica realmente atende vai na instrução: a IA não cita especialidade que não esteja aqui
            val doctors = runCatching { clinics.doctors(clinic.id) }
                .onFailure { log.warn("Chamada {}: não li os médicos para a persona ({}); a IA vai consultar a função", started.id, it.message) }
                .getOrNull()
            val instruction = SecretariaPersona.systemInstruction(clinic.name, clinic.responsibleName, now, config.extraInstructions, doctors)
            bridge.run(session, ctx, instruction, config.voice)
        } finally {
            withContext(NonCancellable) {
                registry.unregister(started.id)
                runCatching { calls.end(started.id) }.onFailure { log.error("Falha ao encerrar chamada {}: {}", started.id, it.message) }
                runCatching { clinics.notify(clinic.id, NotificationType.CHAMADA, "Chamada encerrada", "Atendimento #${started.id} finalizado pela SecretárIA.") }
            }
            log.info("Chamada {} finalizada", started.id)
        }
    }
}
