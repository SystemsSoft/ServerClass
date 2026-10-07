package services

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date

/**
 * Service account do Firebase/Google usada para chamar as APIs do Google (FCM, Firebase Auth) sem o
 * Firebase Admin SDK: assina o JWT da service account com java-jwt e troca por um access token OAuth2.
 *
 * A service account (Firebase Console > Configurações do projeto > Contas de serviço > Gerar
 * nova chave privada) é lida, nesta ordem, de:
 * 1. propriedade `firebase.serviceAccountJson` / env `FIREBASE_SERVICE_ACCOUNT_JSON` (JSON inline);
 * 2. arquivo em `firebase.serviceAccountFile` / env `FIREBASE_SERVICE_ACCOUNT_FILE`
 *    (padrão: `firebase-service-account.json` no diretório de execução, nunca versionado).
 */
object GoogleServiceAccount {

    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"

    class Credentials(
        val projectId: String,
        val clientEmail: String,
        val privateKey: RSAPrivateKey,
    )

    private class CachedToken(val value: String, val expiresAtMillis: Long)

    /** Null quando não há service account configurada (ou ela é inválida). */
    val credentials: Credentials? by lazy { load() }

    private val httpClient = HttpClient(CIO)
    private val mutex = Mutex()
    private val cachedTokens = HashMap<String, CachedToken>()

    private fun load(): Credentials? {
        val inlineJson = System.getProperty("firebase.serviceAccountJson")
            ?: System.getenv("FIREBASE_SERVICE_ACCOUNT_JSON")
        val filePath = System.getProperty("firebase.serviceAccountFile")
            ?: System.getenv("FIREBASE_SERVICE_ACCOUNT_FILE")
            ?: "firebase-service-account.json"

        val raw = inlineJson?.takeIf { it.isNotBlank() }
            ?: File(filePath).takeIf { it.exists() }?.readText()

        if (raw == null) {
            println("[Firebase] Service account não encontrada ($filePath): push e redefinição de senha desativados")
            return null
        }

        return try {
            val json = Json.parseToJsonElement(raw).jsonObject
            val pem = json.getValue("private_key").jsonPrimitive.content
            val der = Base64.getMimeDecoder().decode(
                pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replace("\\s".toRegex(), "")
            )
            Credentials(
                projectId = json.getValue("project_id").jsonPrimitive.content,
                clientEmail = json.getValue("client_email").jsonPrimitive.content,
                privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der)) as RSAPrivateKey,
            ).also { println("[Firebase] Service account carregada (projeto ${it.projectId})") }
        } catch (e: Exception) {
            println("[Firebase] Service account inválida: ${e.message}")
            null
        }
    }

    /** Access token OAuth2 para [scope], reaproveitado até perto de expirar. */
    suspend fun accessToken(account: Credentials, scope: String): String {
        val now = System.currentTimeMillis()
        cachedTokens[scope]?.takeIf { now < it.expiresAtMillis }?.let { return it.value }

        return mutex.withLock {
            val nowInsideLock = System.currentTimeMillis()
            cachedTokens[scope]?.takeIf { nowInsideLock < it.expiresAtMillis }?.let { return@withLock it.value }

            val assertion = JWT.create()
                .withIssuer(account.clientEmail)
                .withSubject(account.clientEmail)
                .withAudience(TOKEN_URL)
                .withClaim("scope", scope)
                .withIssuedAt(Date(nowInsideLock))
                .withExpiresAt(Date(nowInsideLock + 3_600_000))
                .sign(Algorithm.RSA256(null as RSAPublicKey?, account.privateKey))

            val response = httpClient.submitForm(
                url = TOKEN_URL,
                formParameters = parameters {
                    append("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                    append("assertion", assertion)
                },
            )
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).jsonObject
            val token = json["access_token"]?.jsonPrimitive?.content
                ?: error("Falha ao obter access token do Google: $body")
            val expiresIn = json["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L

            cachedTokens[scope] = CachedToken(token, nowInsideLock + (expiresIn - 60) * 1000)
            token
        }
    }
}
