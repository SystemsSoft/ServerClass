package schemas.secretaria

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal suspend fun <T> Database.dbQuery(block: Transaction.() -> T): T =
    newSuspendedTransaction(Dispatchers.IO, this) { block() }

internal val LOCAL_ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

fun Long.toLocalDateTime(zone: ZoneId): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(this), zone)

fun Long.toLocalIso(zone: ZoneId): String = toLocalDateTime(zone).format(LOCAL_ISO)

fun LocalDateTime.toEpochMs(zone: ZoneId): Long = atZone(zone).toInstant().toEpochMilli()

/** Aceita "2026-10-06T09:00" (com ou sem segundos). Retorna null se o formato for inválido. */
fun parseLocalIso(text: String?): LocalDateTime? =
    runCatching { LocalDateTime.parse(text?.trim() ?: return null) }.getOrNull()

/**
 * Normaliza telefone brasileiro para só dígitos com DDI: "(21) 98765-4321" -> "5521987654321".
 * Retorna null se não parecer um telefone válido (10-11 dígitos nacionais).
 */
fun normalizePhone(raw: String?): String? {
    val digits = raw?.filter { it.isDigit() } ?: return null
    val national = if (digits.startsWith("55") && digits.length >= 12) digits.drop(2) else digits
    if (national.length !in 10..11) return null
    return "55$national"
}

fun ratioChange(today: Long, yesterday: Long): Int = when {
    yesterday == 0L -> if (today > 0) 100 else 0
    else -> Math.round((today - yesterday) * 100.0 / yesterday).toInt()
}

private fun nameTokens(name: String): List<String> =
    java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }

/**
 * Compara nomes sem diferenciar acento/maiúscula. Iguais, ou ambos com 2+ palavras e todas as palavras do
 * mais curto presentes no mais longo ("Ana Souza" ~ "Ana Maria Souza"). Só o primeiro nome NÃO basta.
 */
fun namesMatch(a: String, b: String): Boolean {
    val ta = nameTokens(a)
    val tb = nameTokens(b)
    if (ta.isEmpty() || tb.isEmpty()) return false
    if (ta == tb) return true
    if (ta.size < 2 || tb.size < 2) return false
    val (short, long) = if (ta.size <= tb.size) ta to tb else tb to ta
    return long.containsAll(short)
}

/**
 * A especialidade cadastrada [registered] corresponde ao que foi pedido em [query]? Ignora acentos e maiúsculas, aceita
 * parte do nome ("cardio") e as variações do mesmo radical ("cardiologista"/"cardiólogo" ~ Cardiologia, "pediatra" ~
 * Pediatria, "nutricionista" ~ Nutrição, "clínico geral" ~ Clínica geral). Palavras como "médico", "consulta" e
 * "especialista" no pedido são ignoradas. Não é semântico: "médico do coração" → Cardiologia fica a cargo da IA.
 */
fun specialtyMatches(registered: String, query: String): Boolean {
    val r = foldText(registered)
    val q = foldText(query)
    if (q.isEmpty() || r.contains(q)) return true
    val asked = q.split(' ').filter { it.length >= 4 && it !in SPECIALTY_FILLER }
    if (asked.isEmpty()) return false
    val words = r.split(' ')
    return asked.all { a -> words.any { sameRoot(it, a) } }
}

/** Mesmo radical: prefixo comum de pelo menos 5 letras e que só difere nas últimas 4 do mais curto. */
private fun sameRoot(a: String, b: String): Boolean {
    val common = a.zip(b).takeWhile { (x, y) -> x == y }.size
    return common >= maxOf(5, minOf(a.length, b.length) - 4)
}

private fun foldText(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").lowercase()
    .replace(Regex("[^a-z0-9]+"), " ").trim()

private val SPECIALTY_FILLER = setOf(
    "medico", "medica", "medicos", "medicas", "doutor", "doutora", "especialista", "especialistas",
    "especialidade", "consulta", "consultas", "marcar", "agendar", "quero", "preciso",
)

// ── cadastro do paciente no app ──────────────────────────────────────────────

/** CPF só com os 11 dígitos, se for válido (não repetido e com os dígitos verificadores certos); senão null. */
fun normalizeCpf(raw: String?): String? {
    val d = raw?.filter { it.isDigit() } ?: return null
    if (d.length != 11 || d.all { it == d[0] }) return null
    fun check(len: Int): Int {
        val sum = (0 until len).sumOf { (d[it] - '0') * (len + 1 - it) }
        return ((sum * 10) % 11).let { if (it == 10) 0 else it }
    }
    return if (check(9) == d[9] - '0' && check(10) == d[10] - '0') d else null
}

private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

fun isValidEmail(email: String): Boolean = email.length <= 160 && EMAIL.matches(email)

/** "5521987654321" -> "(21) 98765-4321" (para a IA e as telas). */
fun formatPhoneBr(digits: String?): String? {
    val national = digits?.filter { it.isDigit() }?.let { if (it.startsWith("55") && it.length >= 12) it.drop(2) else it } ?: return null
    return when (national.length) {
        11 -> "(${national.take(2)}) ${national.substring(2, 7)}-${national.substring(7)}"
        10 -> "(${national.take(2)}) ${national.substring(2, 6)}-${national.substring(6)}"
        else -> national
    }
}

/** Foto enviada pelo app: base64 puro ou data URL. Retorna bytes + tipo, ou o motivo da recusa. */
sealed class PhotoUpload {
    class Ok(val bytes: ByteArray, val type: String) : PhotoUpload()
    class Invalid(val reason: String) : PhotoUpload()
}

const val MAX_PHOTO_BYTES = 300_000

fun decodePhoto(data: String): PhotoUpload {
    val base64 = data.substringAfter("base64,", data).filterNot { it.isWhitespace() }
    val bytes = runCatching { java.util.Base64.getDecoder().decode(base64) }.getOrNull()
        ?: return PhotoUpload.Invalid("Foto inválida.")
    if (bytes.isEmpty()) return PhotoUpload.Invalid("Foto inválida.")
    if (bytes.size > MAX_PHOTO_BYTES) return PhotoUpload.Invalid("Foto muito grande (máximo de ${MAX_PHOTO_BYTES / 1000} KB).")
    fun startsWith(vararg b: Int) = bytes.size >= b.size && b.indices.all { bytes[it] == b[it].toByte() }
    val type = when {
        startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
        startsWith(0x89, 0x50, 0x4E, 0x47) -> "image/png"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> return PhotoUpload.Invalid("Formato de foto não suportado (use JPEG, PNG ou WebP).")
    }
    return PhotoUpload.Ok(bytes, type)
}
