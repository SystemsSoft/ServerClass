package services.secretaria

import java.util.concurrent.ConcurrentHashMap

/** Chamadas de voz abertas neste processo, para a equipe poder encerrar uma ("Encerrar ligação"). */
class SecretariaCallRegistry {
    private val active = ConcurrentHashMap<Long, suspend () -> Unit>()

    fun register(callId: Long, terminate: suspend () -> Unit) {
        active[callId] = terminate
    }

    fun unregister(callId: Long) {
        active.remove(callId)
    }

    /** true se a chamada estava aberta neste servidor e foi mandada encerrar. */
    suspend fun terminate(callId: Long): Boolean {
        val action = active[callId] ?: return false
        action()
        return true
    }

    fun isActive(callId: Long): Boolean = active.containsKey(callId)
}
