package services

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Um ponto de localização enviado por quem está com o Modo Sentinela ativo. */
data class LivePoint(
    val lat: Double,
    val lng: Double,
    val accuracy: Double?,
    val speed: Double?,
    val capturedAt: String,
)

/**
 * Retransmite em tempo real a localização de quem ativou o Modo Sentinela para os convidados
 * que estão com o mapa ao vivo aberto. Fica só em memória (uma instância do servidor): ao
 * reiniciar, as sessões ativas somem e os watchers reconectam vendo "não está ativo".
 *
 * Cada dono tem um canal ([events]); mensagens são JSON já serializado:
 * `snapshot` (ao conectar), `started`, `location` e `stopped`.
 */
object SentinelaLiveHub {

    /** Quantos pontos recentes o watcher recebe ao conectar, para desenhar o trajeto até agora. */
    private const val MAX_TRAIL_POINTS = 300

    private class Session(val recordingId: String, val ownerName: String, val startedAt: String) {
        val trail = ArrayDeque<LivePoint>()
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val channels = ConcurrentHashMap<String, MutableSharedFlow<String>>()

    private fun channel(ownerUid: String): MutableSharedFlow<String> =
        channels.getOrPut(ownerUid) {
            MutableSharedFlow(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        }

    /** Mensagens ao vivo do dono; quem assina deve enviar antes o [snapshot] atual. */
    fun events(ownerUid: String): SharedFlow<String> = channel(ownerUid)

    fun start(ownerUid: String, recordingId: String, ownerName: String) {
        val session = Session(recordingId, ownerName, Instant.now().toString())
        sessions[ownerUid] = session
        channel(ownerUid).tryEmit(buildJsonObject {
            put("type", "started")
            put("ownerName", ownerName)
            put("startedAt", session.startedAt)
        }.toString())
    }

    fun publishLocation(ownerUid: String, recordingId: String, point: LivePoint) {
        val session = sessions[ownerUid]?.takeIf { it.recordingId == recordingId } ?: return
        synchronized(session) {
            session.trail.addLast(point)
            if (session.trail.size > MAX_TRAIL_POINTS) session.trail.removeFirst()
        }
        channel(ownerUid).tryEmit(buildJsonObject {
            put("type", "location")
            putPoint(point)
        }.toString())
    }

    /** Só encerra se a sessão ainda for a [recordingId] (uma nova ativação não é derrubada por uma antiga). */
    fun stop(ownerUid: String, recordingId: String) {
        val session = sessions[ownerUid]?.takeIf { it.recordingId == recordingId } ?: return
        if (!sessions.remove(ownerUid, session)) return
        channel(ownerUid).tryEmit(buildJsonObject { put("type", "stopped") }.toString())
    }

    fun snapshot(ownerUid: String, fallbackOwnerName: String): String {
        val session = sessions[ownerUid]
        return buildJsonObject {
            put("type", "snapshot")
            put("active", session != null)
            put("ownerName", session?.ownerName ?: fallbackOwnerName)
            if (session != null) {
                put("startedAt", session.startedAt)
                val points = synchronized(session) { session.trail.toList() }
                put("trail", JsonArray(points.map { buildJsonObject { putPoint(it) } }))
            }
        }.toString()
    }

    private fun JsonObjectBuilder.putPoint(point: LivePoint) {
        put("lat", point.lat)
        put("lng", point.lng)
        point.accuracy?.let { put("accuracy", it) }
        point.speed?.let { put("speed", it) }
        put("capturedAt", point.capturedAt)
    }
}
