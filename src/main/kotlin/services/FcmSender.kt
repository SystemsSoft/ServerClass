package services

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class FcmResult { SENT, INVALID_TOKEN, FAILED, NOT_CONFIGURED }

/**
 * Envia notificações push pelo Firebase Cloud Messaging (API HTTP v1) sem o Firebase Admin SDK,
 * autenticando com a service account de [GoogleServiceAccount]. Sem credencial, o envio é ignorado.
 */
object FcmSender {

    private const val FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"

    private val httpClient = HttpClient(CIO)

    val isConfigured: Boolean get() = GoogleServiceAccount.credentials != null

    /**
     * Envia uma mensagem só de dados (o service worker do app monta a notificação e o clique
     * abre [data]["url"]). Retorna [FcmResult.INVALID_TOKEN] quando o token não existe mais.
     */
    suspend fun sendData(token: String, data: Map<String, String>): FcmResult {
        val account = GoogleServiceAccount.credentials ?: return FcmResult.NOT_CONFIGURED

        return try {
            val payload = buildJsonObject {
                put("message", buildJsonObject {
                    put("token", token)
                    put("data", buildJsonObject { data.forEach { (key, value) -> put(key, value) } })
                    put("webpush", buildJsonObject {
                        put("headers", buildJsonObject {
                            put("Urgency", "high")
                            put("TTL", "300")
                        })
                    })
                })
            }

            val response = httpClient.post("https://fcm.googleapis.com/v1/projects/${account.projectId}/messages:send") {
                header(HttpHeaders.Authorization, "Bearer ${GoogleServiceAccount.accessToken(account, FCM_SCOPE)}")
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }

            val status = response.status.value
            when {
                status in 200..299 -> FcmResult.SENT
                status == 404 || response.bodyAsText().contains("UNREGISTERED") -> FcmResult.INVALID_TOKEN
                else -> {
                    println("[FCM] Falha ao enviar ($status): ${response.bodyAsText()}")
                    FcmResult.FAILED
                }
            }
        } catch (e: Exception) {
            println("[FCM] Erro ao enviar notificação: ${e.message}")
            FcmResult.FAILED
        }
    }
}
