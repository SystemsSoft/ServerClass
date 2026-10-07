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
