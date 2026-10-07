package services

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * Transcrição de áudio via Gemini (Files API + generateContent, REST). Usa a chave própria
 * do Sentinela, separada da chave da Megan/tradução.
 *
 * Configuração (propriedade do sistema ou variável de ambiente):
 * - `gemini.apiKeySentinela` / `GEMINI_API_KEY_SENTINELA` — chave (use uma chave de projeto pago:
 *   no nível gratuito o Google pode usar o conteúdo enviado para melhorar os modelos)
 * - `gemini.transcribeModel` / `GEMINI_TRANSCRIBE_MODEL` — modelo (padrão: [DEFAULT_MODEL])
 *
 * O áudio enviado e o texto retornado são dados pessoais de uma situação de risco: nada disso
 * é logado, e o arquivo é apagado do Google assim que a transcrição termina.
 */
class GeminiAudioTranscriber {

    class Transcription(val text: String, val truncated: Boolean)

    private companion object {
        const val DEFAULT_MODEL = "models/gemini-3.1-flash-lite"
        const val BASE_URL = "https://generativelanguage.googleapis.com"
        const val API_KEY_HEADER = "x-goog-api-key"
        const val PROCESSING_POLL_ATTEMPTS = 60
        const val PROCESSING_POLL_DELAY_MS = 2_000L
        const val MAX_OUTPUT_TOKENS = 65_536
        const val ERROR_BODY_CHARS = 300

        const val SYSTEM_PROMPT =
            "Você transcreve gravações de áudio. Transcreva fielmente, palavra por palavra, toda a fala do áudio " +
                "no idioma em que foi falada (em geral português do Brasil). Responda somente com a transcrição em " +
                "texto corrido, sem títulos, sem marcação de tempo e sem comentários. Se houver mais de uma pessoa " +
                "falando, coloque cada fala em uma linha. Se não houver nenhuma fala, responda apenas: [sem fala]"
    }

    private val apiKey: String by lazy {
        // trim(): o Properties do Java preserva espaço no fim da linha, e uma chave com espaço é rejeitada (401).
        (System.getProperty("gemini.apiKeySentinela") ?: System.getenv("GEMINI_API_KEY_SENTINELA"))
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: error("Chave do Gemini não configurada (gemini.apiKeySentinela / GEMINI_API_KEY_SENTINELA)")
    }

    private val model: String = (System.getProperty("gemini.transcribeModel") ?: System.getenv("GEMINI_TRANSCRIBE_MODEL"))
        ?.takeIf { it.isNotBlank() }
        ?.let { if (it.startsWith("models/")) it else "models/$it" }
        ?: DEFAULT_MODEL

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 10 * 60_000L
            requestTimeoutMillis = 15 * 60_000L
        }
    }

    private class UploadedFile(val name: String, val uri: String, val mimeType: String)

    suspend fun transcribe(audio: File, mimeType: String): Transcription {
        val uploaded = upload(audio, mimeType)
        try {
            return generate(uploaded)
        } finally {
            // Mesmo se a coroutine foi cancelada (ex.: shutdown), não deixa o áudio no Google.
            withContext(NonCancellable) { runCatching { deleteFile(uploaded.name) } }
        }
    }

    private suspend fun upload(audio: File, mimeType: String): UploadedFile {
        val bytes = withContext(Dispatchers.IO) { audio.readBytes() }

        val start = client.post("$BASE_URL/upload/v1beta/files") {
            header(API_KEY_HEADER, apiKey)
            header("X-Goog-Upload-Protocol", "resumable")
            header("X-Goog-Upload-Command", "start")
            header("X-Goog-Upload-Header-Content-Length", bytes.size.toString())
            header("X-Goog-Upload-Header-Content-Type", mimeType)
            contentType(ContentType.Application.Json)
            setBody("""{"file":{"display_name":"sentinela-audio"}}""")
        }
        checkSuccess(start, "iniciar upload")
        val uploadUrl = start.headers["X-Goog-Upload-URL"] ?: error("Gemini não devolveu a URL de upload")

        val finish = client.post(uploadUrl) {
            header("X-Goog-Upload-Offset", "0")
            header("X-Goog-Upload-Command", "upload, finalize")
            setBody(bytes)
        }
        checkSuccess(finish, "enviar áudio")

        val uploadedJson = Json.parseToJsonElement(finish.bodyAsText()).jsonObject
        var file = parseFile(uploadedJson, mimeType)
        var state = fileState(uploadedJson)

        // Áudio quase sempre já vem ACTIVE; só espera se o Google ainda estiver processando.
        var attempts = 0
        while (state == "PROCESSING" && attempts++ < PROCESSING_POLL_ATTEMPTS) {
            delay(PROCESSING_POLL_DELAY_MS)
            val status = client.get("$BASE_URL/v1beta/${file.name}") { header(API_KEY_HEADER, apiKey) }
            checkSuccess(status, "consultar arquivo")
            val json = Json.parseToJsonElement(status.bodyAsText()).jsonObject
            file = parseFile(json, mimeType)
            state = fileState(json)
        }
        check(state == "ACTIVE") { "Arquivo de áudio não ficou pronto no Gemini (estado $state)" }
        return file
    }

    private suspend fun generate(file: UploadedFile): Transcription {
        val requestBody = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", SYSTEM_PROMPT) } }
            }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject {
                            putJsonObject("fileData") {
                                put("mimeType", file.mimeType)
                                put("fileUri", file.uri)
                            }
                        }
                        addJsonObject { put("text", "Transcreva este áudio.") }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", 0.0)
                put("maxOutputTokens", MAX_OUTPUT_TOKENS)
            }
        }

        val response = client.post("$BASE_URL/v1beta/$model:generateContent") {
            header(API_KEY_HEADER, apiKey)
            contentType(ContentType.Application.Json)
            setBody(requestBody.toString())
        }
        checkSuccess(response, "transcrever")

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val candidate = json["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: error("Gemini não devolveu resultado (${json["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull ?: "sem motivo informado"})")

        val text = candidate["content"]?.jsonObject?.get("parts")?.jsonArray
            ?.map { it.jsonObject }
            ?.filter { it["thought"]?.jsonPrimitive?.contentOrNull != "true" }
            ?.mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString("")
            ?.trim()
            .orEmpty()

        val finishReason = candidate["finishReason"]?.jsonPrimitive?.contentOrNull
        check(text.isNotEmpty() || finishReason == "STOP") { "Gemini encerrou sem texto (finishReason=$finishReason)" }
        return Transcription(text = text, truncated = finishReason == "MAX_TOKENS")
    }

    private suspend fun deleteFile(name: String) {
        client.delete("$BASE_URL/v1beta/$name") { header(API_KEY_HEADER, apiKey) }
    }

    private fun parseFile(json: JsonObject, fallbackMimeType: String): UploadedFile {
        val file = json["file"]?.jsonObject ?: json
        return UploadedFile(
            name = file["name"]?.jsonPrimitive?.contentOrNull ?: error("Resposta do Gemini sem nome do arquivo"),
            uri = file["uri"]?.jsonPrimitive?.contentOrNull ?: error("Resposta do Gemini sem URI do arquivo"),
            mimeType = file["mimeType"]?.jsonPrimitive?.contentOrNull ?: fallbackMimeType,
        )
    }

    private fun fileState(json: JsonObject): String? =
        (json["file"]?.jsonObject ?: json)["state"]?.jsonPrimitive?.contentOrNull

    private suspend fun checkSuccess(response: HttpResponse, action: String) {
        if (response.status.isSuccess()) return
        // O corpo de erro do Gemini descreve o problema (modelo inexistente, cota...) e não contém o áudio nem a chave.
        error("Falha ao $action no Gemini (status ${response.status.value}): ${response.bodyAsText().take(ERROR_BODY_CHARS)}")
    }
}
