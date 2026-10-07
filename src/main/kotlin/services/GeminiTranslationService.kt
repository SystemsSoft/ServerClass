package services

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Tradução de texto via Gemini generateContent REST API — chamada simples de texto,
 * sem sessão Live. Usa a mesma chave/credencial já configurada para a Megan.
 *
 * O texto/tradução trafegados aqui são conteúdo de fala do aluno/Megan (dado pessoal
 * de sessão de voz) — não devem ser logados em texto claro (mesma regra de LGPD do bridge de voz).
 */
class GeminiTranslationService {

    private val apiKey: String by lazy {
        System.getProperty("gemini.apiKey")
            ?: System.getenv("GEMINI_API_KEY")
            ?: error("GEMINI_API_KEY não configurada (system property gemini.apiKey ou variável de ambiente)")
    }

    // "models/gemini-2.5-flash" foi descontinuado (a API passou a responder 404 pedindo
    // migração) — troque via system property/env var se este modelo também for desativado.
    // Confira o catálogo atual em https://ai.google.dev/gemini-api/docs/models antes de mudar.
    private val model: String = System.getProperty("gemini.translateModel")
        ?: System.getenv("GEMINI_TRANSLATE_MODEL")
        ?: "models/gemini-3.6-flash"

    private val client = HttpClient(CIO)

    suspend fun translate(text: String, targetLang: String): String {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "Texto vazio." }

        val targetLabel = if (targetLang.equals("pt-BR", ignoreCase = true)) "português do Brasil" else targetLang

        val requestBody = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") {
                        addJsonObject { put("text", trimmed) }
                    }
                }
            }
            putJsonObject("systemInstruction") {
                putJsonArray("parts") {
                    addJsonObject {
                        put(
                            "text",
                            "Traduza o texto do usuário para $targetLabel. Responda apenas com a tradução, sem aspas e sem explicações."
                        )
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", 0.2)
            }
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/$model:generateContent?key=$apiKey"

        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(requestBody.toString())
        }

        if (!response.status.isSuccess()) {
            error("Falha na API de tradução (status ${response.status.value}).")
        }

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val translation = json["candidates"]
            ?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("text")?.jsonPrimitive?.content
            ?.trim()

        return translation.takeUnless { it.isNullOrEmpty() } ?: error("Resposta de tradução vazia.")
    }
}
