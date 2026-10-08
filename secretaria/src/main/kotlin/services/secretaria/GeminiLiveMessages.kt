package services.secretaria

import kotlinx.serialization.json.JsonPrimitive
import schemas.secretaria.TokenUsage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class GeminiToolCall(val id: String?, val name: String, val args: JsonObject)

/** O que importa, para a ponte, de uma mensagem do servidor da Gemini Live API. */
data class GeminiServerMessage(
    val setupComplete: Boolean = false,
    val toolCalls: List<GeminiToolCall> = emptyList(),
    val toolCancellation: Boolean = false,
    val inputText: String? = null,
    val outputText: String? = null,
    val turnComplete: Boolean = false,
    val interrupted: Boolean = false,
    /** Tokens desta mensagem (`usageMetadata`), por tipo; null se a mensagem não trouxer. */
    val usage: TokenUsage? = null,
) {
    /** Mensagens de função são resolvidas pelo servidor — o cliente (PWA) não precisa vê-las. */
    val internalOnly: Boolean get() = toolCalls.isNotEmpty() || toolCancellation || setupComplete
}

/** Protocolo JSON da Gemini Live API (BidiGenerateContent): leitura e montagem de mensagens. */
object GeminiLiveMessages {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A Gemini manda JSON em frames de texto ou binários. Retorna null se os bytes não forem um objeto
     * JSON (tratados como áudio cru e repassados sem interpretar).
     */
    fun parse(bytes: ByteArray): GeminiServerMessage? {
        val root = runCatching { json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject }.getOrNull() ?: return null
        val content = root["serverContent"] as? JsonObject
        val calls = (root["toolCall"] as? JsonObject)?.get("functionCalls")?.jsonArray.orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            GeminiToolCall(obj["id"]?.jsonPrimitive?.contentOrNull, name, obj["args"] as? JsonObject ?: JsonObject(emptyMap()))
        }
        return GeminiServerMessage(
            setupComplete = root.containsKey("setupComplete"),
            toolCalls = calls,
            toolCancellation = root.containsKey("toolCallCancellation"),
            inputText = content?.get("inputTranscription")?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull,
            outputText = content?.get("outputTranscription")?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull,
            turnComplete = content?.get("turnComplete")?.jsonPrimitive?.booleanOrNull == true,
            interrupted = content?.get("interrupted")?.jsonPrimitive?.booleanOrNull == true,
            usage = (root["usageMetadata"] as? JsonObject)?.let(::usageOf),
        )
    }

    /**
     * `usageMetadata` -> tokens por tipo. Usa o detalhamento por modalidade (AUDIO/TEXT); o que não vier detalhado
     * conta como ÁUDIO (o tipo mais caro), para o custo nunca ficar abaixo do real.
     */
    fun usageOf(meta: JsonObject): TokenUsage {
        fun count(key: String) = (meta[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
        fun details(key: String): Map<String, Long> = (meta[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .groupBy({ (it["modality"] as? JsonPrimitive)?.contentOrNull?.uppercase() ?: "AUDIO" }, { (it["tokenCount"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L })
            .mapValues { it.value.sum() }
        val prompt = count("promptTokenCount")
        val response = count("responseTokenCount").takeIf { it > 0 } ?: count("candidatesTokenCount")
        val inDetails = details("promptTokensDetails")
        val outDetails = details("responseTokensDetails").ifEmpty { details("candidatesTokensDetails") }
        val inText = inDetails["TEXT"] ?: 0
        val outText = outDetails["TEXT"] ?: 0
        return TokenUsage(
            inputAudio = maxOf(prompt - inText, inDetails["AUDIO"] ?: 0),
            inputText = inText,
            outputAudio = maxOf(response - outText, outDetails["AUDIO"] ?: 0),
            outputText = outText,
            reports = 1,
        )
    }

    fun setup(model: String, voice: String, instruction: String, functionDeclarations: JsonArray): JsonObject = buildJsonObject {
        putJsonObject("setup") {
            put("model", model)
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add("AUDIO") }
                putJsonObject("speechConfig") {
                    putJsonObject("voiceConfig") { putJsonObject("prebuiltVoiceConfig") { put("voiceName", voice) } }
                }
            }
            putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", instruction) } } }
            putJsonArray("tools") { addJsonObject { put("functionDeclarations", functionDeclarations) } }
            putJsonObject("inputAudioTranscription") {}
            putJsonObject("outputAudioTranscription") {}
        }
    }

    fun userText(text: String): JsonObject = buildJsonObject {
        putJsonObject("clientContent") {
            putJsonArray("turns") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { addJsonObject { put("text", text) } }
                }
            }
            put("turnComplete", true)
        }
    }

    fun toolResponse(results: List<Triple<String?, String, JsonObject>>): JsonObject = buildJsonObject {
        putJsonObject("toolResponse") {
            putJsonArray("functionResponses") {
                for ((id, name, response) in results) {
                    addJsonObject {
                        id?.let { put("id", it) }
                        put("name", name)
                        put("response", response)
                    }
                }
            }
        }
    }

    /**
     * Só aceitamos do cliente mensagens de áudio (`realtimeInput`). Qualquer outra coisa (setup, clientContent,
     * toolResponse...) seria o cliente mexendo na persona ou nas funções — por isso é descartada.
     */
    fun isClientAudio(text: String): Boolean {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return false
        return root.keys == setOf("realtimeInput")
    }

    /** Mensagem de controle do PWA, ex.: {"type":"end"}. */
    fun clientControlType(text: String): String? =
        runCatching { (json.parseToJsonElement(text) as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull }.getOrNull()
}
