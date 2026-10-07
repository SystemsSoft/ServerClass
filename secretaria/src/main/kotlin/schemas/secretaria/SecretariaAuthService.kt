package schemas.secretaria

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Date
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PBKDF2-HMAC-SHA256 (JDK puro, sem dependência extra). Formato: `pbkdf2_sha256$iter$salt$hash` (base64). */
object SecretariaPasswords {
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private val random = SecureRandom()

    // Usado quando o e-mail não existe, para o tempo de resposta não revelar se o usuário existe.
    private val dummy = hash("senha-que-nunca-sera-usada")

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)).encoded

    fun hash(password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val enc = Base64.getEncoder()
        return "pbkdf2_sha256\$$ITERATIONS\$${enc.encodeToString(salt)}\$${enc.encodeToString(derive(password, salt, ITERATIONS))}"
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2_sha256") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val dec = Base64.getDecoder()
        val salt = runCatching { dec.decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { dec.decode(parts[3]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derive(password, salt, iterations), expected)
    }

    /** Gasta o mesmo tempo de uma verificação real. */
    fun burn(password: String) {
        verify(password, dummy)
    }
}

/** JWT HS256 de curta duração para a equipe da clínica (dashboard Flutter). */
class SecretariaTokens(secret: String, private val ttlMillis: Long = 12 * 3_600_000L) {
    private val algorithm = Algorithm.HMAC256(secret)
    private val verifier = JWT.require(algorithm).withIssuer(ISSUER).build()

    fun issue(userId: Long): String =
        JWT.create()
            .withIssuer(ISSUER)
            .withSubject(userId.toString())
            .withExpiresAt(Date(System.currentTimeMillis() + ttlMillis))
            .sign(algorithm)

    /** Retorna o id do usuário, ou null se o token for inválido/expirado. */
    fun verify(token: String): Long? = try {
        verifier.verify(token).subject?.toLongOrNull()
    } catch (_: JWTVerificationException) {
        null
    }

    companion object {
        private const val ISSUER = "secretaria"

        /** Segredo vindo da configuração; sem ele, gera um aleatório (tokens caem a cada reinício). */
        fun fromConfig(): SecretariaTokens {
            val secret = System.getProperty("secretaria.jwtSecret") ?: System.getenv("SECRETARIA_JWT_SECRET")
            if (secret.isNullOrBlank()) {
                println("[Secretaria] AVISO: SECRETARIA_JWT_SECRET não definido — usando segredo temporário (logins caem a cada reinício).")
                return SecretariaTokens(Base64.getEncoder().encodeToString(ByteArray(48).also(SecureRandom()::nextBytes)))
            }
            require(secret.length >= 32) { "SECRETARIA_JWT_SECRET deve ter ao menos 32 caracteres" }
            return SecretariaTokens(secret)
        }
    }
}

class SecretariaAuthService(private val database: Database, private val tokens: SecretariaTokens) {

    /** Valida e-mail/senha. Retorna null em qualquer falha (mesma resposta para não revelar o motivo). */
    suspend fun login(email: String, password: String): LoginResponse? {
        val row = database.dbQuery {
            UsersTable.selectAll()
                .where { (UsersTable.email eq email.trim().lowercase()) and (UsersTable.active eq true) }
                .singleOrNull()
        }
        if (row == null) {
            SecretariaPasswords.burn(password)
            return null
        }
        if (!SecretariaPasswords.verify(password, row[UsersTable.passwordHash])) return null

        val userId = row[UsersTable.id]
        database.dbQuery {
            UsersTable.update({ UsersTable.id eq userId }) { it[lastLoginAt] = System.currentTimeMillis() }
        }
        return LoginResponse(tokens.issue(userId), row.toUserDto(), clinicsOf(userId))
    }

    fun userIdFromToken(token: String): Long? = tokens.verify(token)

    suspend fun user(userId: Long): UserDto? = database.dbQuery {
        UsersTable.selectAll().where { (UsersTable.id eq userId) and (UsersTable.active eq true) }
            .singleOrNull()?.toUserDto()
    }

    suspend fun clinicsOf(userId: Long): List<ClinicSummaryDto> = database.dbQuery {
        (ClinicUsersTable innerJoin ClinicsTable)
            .selectAll()
            .where { (ClinicUsersTable.userId eq userId) and (ClinicsTable.active eq true) }
            .map {
                ClinicSummaryDto(
                    it[ClinicsTable.id], it[ClinicsTable.name], it[ClinicsTable.responsibleName],
                    it[ClinicUsersTable.role].name.lowercase(),
                )
            }
    }

    /** Papel do usuário na clínica, ou null se não pertencer a ela. */
    suspend fun roleIn(userId: Long, clinicId: Long): ClinicRole? = database.dbQuery {
        ClinicUsersTable.selectAll()
            .where { (ClinicUsersTable.userId eq userId) and (ClinicUsersTable.clinicId eq clinicId) }
            .singleOrNull()?.get(ClinicUsersTable.role)
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toUserDto() =
        UserDto(this[UsersTable.id], this[UsersTable.name], this[UsersTable.email])
}
