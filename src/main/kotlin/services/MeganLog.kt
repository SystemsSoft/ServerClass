package services

import java.time.Instant

/**
 * Log com timestamp ISO-8601 pros pontos de diagnóstico da chamada com a
 * Megan (GeminiLiveBridge, MeganRouting) — sem timestamp era impossível medir
 * quanto tempo uma sessão durava antes de cair ou trocar de chave.
 */
object MeganLog {
    fun d(message: String) {
        println("${Instant.now()} $message")
    }
}
