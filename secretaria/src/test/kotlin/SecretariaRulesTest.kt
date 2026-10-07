import schemas.secretaria.ScheduleWindow
import schemas.secretaria.SecretariaPasswords
import schemas.secretaria.SecretariaSlots
import schemas.secretaria.SecretariaTokens
import schemas.secretaria.namesMatch
import schemas.secretaria.normalizePhone
import schemas.secretaria.ratioChange
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretariaRulesTest {

    private val monToFri = (1..5).flatMap { d ->
        listOf(ScheduleWindow(d, 8 * 60, 12 * 60, 30), ScheduleWindow(d, 14 * 60, 18 * 60, 30))
    }

    @Test
    fun `gera os horarios do dia respeitando as janelas e o tamanho do slot`() {
        val monday = LocalDate.of(2026, 10, 5)
        val slots = SecretariaSlots.generate(monday, monToFri)
        assertEquals(16, slots.size) // 8 de manhã + 8 à tarde
        assertEquals(LocalDateTime.of(2026, 10, 5, 8, 0), slots.first())
        assertEquals(LocalDateTime.of(2026, 10, 5, 17, 30), slots.last())
        assertFalse(slots.contains(LocalDateTime.of(2026, 10, 5, 12, 0))) // almoço
    }

    @Test
    fun `fim de semana nao tem horarios`() {
        assertTrue(SecretariaSlots.generate(LocalDate.of(2026, 10, 10), monToFri).isEmpty()) // sábado
        assertTrue(SecretariaSlots.generate(LocalDate.of(2026, 10, 11), monToFri).isEmpty()) // domingo
    }

    @Test
    fun `windowFor so aceita inicio alinhado e dentro da janela`() {
        val ok = LocalDateTime.of(2026, 10, 6, 9, 30)
        assertNotNull(SecretariaSlots.windowFor(ok, monToFri))
        assertNull(SecretariaSlots.windowFor(LocalDateTime.of(2026, 10, 6, 9, 15), monToFri)) // desalinhado
        assertNull(SecretariaSlots.windowFor(LocalDateTime.of(2026, 10, 6, 11, 45), monToFri)) // estoura a janela
        assertNull(SecretariaSlots.windowFor(LocalDateTime.of(2026, 10, 6, 12, 0), monToFri)) // almoço
        assertNull(SecretariaSlots.windowFor(LocalDateTime.of(2026, 10, 11, 9, 0), monToFri)) // domingo
        assertNull(SecretariaSlots.windowFor(LocalDateTime.of(2026, 10, 6, 9, 0, 30), monToFri)) // com segundos
    }

    @Test
    fun `rotulo em portugues para a IA falar`() {
        val label = SecretariaSlots.label(LocalDateTime.of(2026, 10, 6, 9, 0))
        assertTrue(label.contains("06/10") && label.contains("09:00"), label)
    }

    @Test
    fun `normaliza telefones brasileiros`() {
        assertEquals("5521987654321", normalizePhone("(21) 98765-4321"))
        assertEquals("5521987654321", normalizePhone("+55 21 98765-4321"))
        assertEquals("552133334444", normalizePhone("2133334444"))
        assertNull(normalizePhone("12345"))
        assertNull(normalizePhone(null))
    }

    @Test
    fun `nomes - ignora acento e caixa, exige sobrenome`() {
        assertTrue(namesMatch("Ana Souza", "ana souza"))
        assertTrue(namesMatch("José da Conceição", "Jose da Conceicao"))
        assertTrue(namesMatch("Ana Souza", "Ana Maria Souza"))
        assertFalse(namesMatch("Ana Souza", "Ana")) // só o primeiro nome não basta
        assertTrue(namesMatch("Ana", "Ana")) // mas nomes idênticos casam
        assertFalse(namesMatch("Ana Souza", "Ana Lima"))
        assertFalse(namesMatch("", "Ana Souza"))
    }

    @Test
    fun `variacao percentual vs ontem`() {
        assertEquals(20, ratioChange(18, 15))
        assertEquals(-18, ratioChange(82, 100))
        assertEquals(100, ratioChange(3, 0))
        assertEquals(0, ratioChange(0, 0))
    }

    @Test
    fun `senha - hash verifica e rejeita`() {
        val hash = SecretariaPasswords.hash("senha-segura-123")
        assertTrue(hash.startsWith("pbkdf2_sha256$"))
        assertTrue(SecretariaPasswords.verify("senha-segura-123", hash))
        assertFalse(SecretariaPasswords.verify("outra-senha", hash))
        assertFalse(SecretariaPasswords.verify("x", "lixo"))
        assertTrue(SecretariaPasswords.hash("a") != SecretariaPasswords.hash("a")) // sal aleatório
    }

    @Test
    fun `jwt - emite, valida e rejeita adulterado ou expirado`() {
        val tokens = SecretariaTokens("segredo-de-teste-com-mais-de-32-caracteres!!")
        val token = tokens.issue(42)
        assertEquals(42L, tokens.verify(token))
        assertNull(tokens.verify(token + "x"))
        assertNull(tokens.verify("nada"))
        assertNull(SecretariaTokens("outro-segredo-de-teste-com-mais-de-32-caracteres").verify(token))
        assertNull(SecretariaTokens("segredo-de-teste-com-mais-de-32-caracteres!!", ttlMillis = -1000).let { it.verify(it.issue(1)) })
    }
}

class SecretariaLiveUrlTest {
    @kotlin.test.AfterTest
    fun cleanup() {
        System.clearProperty("secretaria.geminiLiveUrl")
    }

    @Test
    fun `por padrao usa o Gemini oficial com a chave`() {
        val url = services.secretaria.SecretariaLiveConfig.defaultLiveUrl("abc123")
        assertTrue(url.startsWith("wss://generativelanguage.googleapis.com/"), url)
        assertTrue(url.endsWith("?key=abc123"), url)
    }

    @Test
    fun `pode apontar para um Gemini falso em desenvolvimento`() {
        System.setProperty("secretaria.geminiLiveUrl", "ws://127.0.0.1:9100/live?k={key}")
        assertEquals("ws://127.0.0.1:9100/live?k=xyz", services.secretaria.SecretariaLiveConfig.defaultLiveUrl("xyz"))
        System.setProperty("secretaria.geminiLiveUrl", "  ") // em branco = oficial
        assertTrue(services.secretaria.SecretariaLiveConfig.defaultLiveUrl("k").startsWith("wss://generativelanguage"))
    }
}

/** O servidor em produção usa um Json que NÃO escreve valores padrão; o contrato com os apps não pode depender disso. */
class SecretariaJsonContractTest {
    private val production = kotlinx.serialization.json.Json { encodeDefaults = false; isLenient = true; ignoreUnknownKeys = true }

    @Test
    fun `campos com valor padrao continuam presentes na resposta`() {
        val pub = production.encodeToString(schemas.secretaria.PublicClinicDto.serializer(), schemas.secretaria.PublicClinicDto("Clínica", null, true))
        assertTrue(pub.contains("\"online\":true"), pub)
        val window = production.encodeToString(schemas.secretaria.ScheduleWindowDto.serializer(), schemas.secretaria.ScheduleWindowDto(1, "08:00", "12:00"))
        assertTrue(window.contains("\"slotMinutes\":30"), window)
    }
}
