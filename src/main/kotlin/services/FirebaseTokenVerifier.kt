package services

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey

/** Resultado de um ID Token do Firebase verificado com sucesso. */
data class FirebaseVerifiedUser(
    val uid: String,
    val email: String?,
    val name: String?,
    val picture: String?,
)

/**
 * Verifica ID Tokens do Firebase Authentication sem depender do Firebase Admin SDK
 * (que exigiria uma service account key gerada no Console). Em vez disso, valida a
 * assinatura RS256 usando os certificados públicos que o próprio Google publica —
 * método documentado oficialmente em:
 * https://firebase.google.com/docs/auth/admin/verify-id-tokens#verify_id_tokens_using_a_third-party_jwt_library
 */
object FirebaseTokenVerifier {

    private const val CERTS_URL =
        "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com"

    private val projectId: String =
        System.getProperty("firebase.projectId") ?: System.getenv("FIREBASE_PROJECT_ID") ?: "sentinela-877d7"

    private val issuer = "https://securetoken.google.com/$projectId"

    private val httpClient = HttpClient(CIO)
    private val mutex = Mutex()

    private var cachedCerts: Map<String, RSAPublicKey> = emptyMap()
    private var cacheExpiresAtMillis: Long = 0

    private suspend fun publicKeyFor(kid: String): RSAPublicKey? {
        refreshCertsIfNeeded()
        return cachedCerts[kid]
    }

    /** Busca os certificados públicos do Google e respeita o Cache-Control retornado. */
    private suspend fun refreshCertsIfNeeded() {
        val now = System.currentTimeMillis()
        if (cachedCerts.isNotEmpty() && now < cacheExpiresAtMillis) return

        mutex.withLock {
            val nowInsideLock = System.currentTimeMillis()
            if (cachedCerts.isNotEmpty() && nowInsideLock < cacheExpiresAtMillis) return

            val response: HttpResponse = httpClient.get(CERTS_URL)
            val cacheControl = response.headers["Cache-Control"] ?: ""
            val maxAgeSeconds = Regex("max-age=(\\d+)")
                .find(cacheControl)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()
                ?: 3600L

            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val certFactory = CertificateFactory.getInstance("X.509")

            cachedCerts = json.entries.associate { (kid, pemValue) ->
                val pem = pemValue.jsonPrimitive.content
                val cert = certFactory.generateCertificate(
                    ByteArrayInputStream(pem.toByteArray(Charsets.UTF_8))
                ) as X509Certificate
                kid to (cert.publicKey as RSAPublicKey)
            }
            cacheExpiresAtMillis = nowInsideLock + (maxAgeSeconds * 1000)
        }
    }

    /**
     * Verifica assinatura, issuer, audience e validade de um ID Token do Firebase.
     * Lança [IllegalArgumentException] (mensagem amigável) se o token for inválido.
     */
    suspend fun verifyIdToken(idToken: String): FirebaseVerifiedUser {
        val unverified = try {
            JWT.decode(idToken)
        } catch (e: Exception) {
            throw IllegalArgumentException("Token malformado")
        }

        val kid = unverified.keyId
            ?: throw IllegalArgumentException("Token sem 'kid' no cabeçalho")

        val publicKey = publicKeyFor(kid)
            ?: throw IllegalArgumentException("Chave pública do Firebase não encontrada para este token")

        val algorithm = Algorithm.RSA256(publicKey, null)
        val verifier = JWT.require(algorithm)
            .withIssuer(issuer)
            .withAudience(projectId)
            .build()

        val verified = try {
            verifier.verify(idToken)
        } catch (e: JWTVerificationException) {
            throw IllegalArgumentException("Token inválido ou expirado")
        }

        val uid = verified.subject
        if (uid.isNullOrBlank()) {
            throw IllegalArgumentException("Token sem 'sub' (uid)")
        }

        val authTime = verified.getClaim("auth_time").asLong()
        val nowSeconds = System.currentTimeMillis() / 1000
        if (authTime != null && authTime > nowSeconds + 60) {
            throw IllegalArgumentException("Token com 'auth_time' no futuro")
        }

        return FirebaseVerifiedUser(
            uid = uid,
            email = verified.getClaim("email").asString(),
            name = verified.getClaim("name").asString(),
            picture = verified.getClaim("picture").asString(),
        )
    }
}
