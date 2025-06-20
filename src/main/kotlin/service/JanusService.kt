package com.class_erp.service

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.KSerializer // Importe este
import kotlinx.serialization.json.JsonPrimitive

// --- Data Classes para Comunicação com Janus ---

// 1. Torne JanusBaseRequest uma sealed class e suas subclasses a implementem
@Serializable
sealed class JanusBaseRequestContract { // Renomeado para evitar conflito com a classe base
    abstract val janus: String
    abstract val transaction: String
}

@Serializable
data class JanusSuccessResponse(
    val janus: String,
    val transaction: String,
    val data: JsonObject? = null
)

@Serializable
data class JanusErrorResponse(
    val janus: String,
    val transaction: String,
    val error: JanusErrorData
)

@Serializable
data class JanusErrorData(
    val code: Int,
    val reason: String
)

@Serializable
data class JanusCreateSessionRequest(
    override val janus: String = "create",
    override val transaction: String
) : JanusBaseRequestContract() // Implementa o contrato

@Serializable
data class JanusAttachPluginRequest(
    override val janus: String = "attach",
    val plugin: String,
    override val transaction: String
) : JanusBaseRequestContract() // Implementa o contrato

@Serializable
data class JanusPluginMessageRequest(
    override val janus: String = "message",
    val body: JsonObject,
    val jsep: JsonObject? = null,
    override val transaction: String
) : JanusBaseRequestContract() // Implementa o contrato

// --- Fim das Data Classes ---


class JanusService(
    private val httpClient: HttpClient,
    private val janusBaseUrl: String
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    private fun generateTransactionId(prefix: String = "ktor_tx"): String {
        return "${prefix}_${System.nanoTime()}"
    }

    /**
     * Envia uma requisição POST genérica ao Janus e processa a resposta.
     * @param path O caminho da URL.
     * @param requestBodySerializer O KSerializer para o tipo da requisição.
     * @param requestBody O objeto Kotlin que será serializado para JSON como corpo da requisição.
     * @return JanusSuccessResponse se a operação for bem-sucedida.
     * @throws Exception se ocorrer um erro na comunicação ou na resposta do Janus.
     */
    private suspend fun <T : JanusBaseRequestContract> sendJanusRequest(
        path: String,
        requestBodySerializer: KSerializer<T>, // Agora recebemos o serializador
        requestBody: T
    ): JanusSuccessResponse {
        val fullUrl = "$janusBaseUrl$path"
        val response = httpClient.post(fullUrl) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(requestBodySerializer, requestBody)) // Usamos o serializador aqui
        }

        val jsonElementResponse = response.body<JsonElement>()
        val janusStatus = jsonElementResponse.jsonObject["janus"]?.jsonPrimitive?.content

        return when (janusStatus) {
            "success" -> json.decodeFromJsonElement(JanusSuccessResponse.serializer(), jsonElementResponse)
            "error" -> {
                val errorResponse = json.decodeFromJsonElement(JanusErrorResponse.serializer(), jsonElementResponse)
                throw Exception("Janus API error: ${errorResponse.error.reason} (Code: ${errorResponse.error.code})")
            }
            else -> throw Exception("Unexpected 'janus' status in response: $janusStatus, Body: $jsonElementResponse")
        }
    }


    /**
     * Cria uma nova sessão Janus.
     * @return O ID da sessão criada.
     */
    suspend fun createSession(): Long {
        val transactionId = generateTransactionId("create")
        val requestBody = JanusCreateSessionRequest(transaction = transactionId)
        // 2. Passe o serializador explicitamente
        val successResponse = sendJanusRequest(
            "",
            JanusCreateSessionRequest.serializer(), // Tipo explícito aqui
            requestBody
        )

        return successResponse.data?.get("id")?.jsonPrimitive?.content?.toLong()
            ?: throw IllegalStateException("Janus session ID not found in createSession response.")
    }

    /**
     * Anexa um plugin a uma sessão Janus.
     * @param sessionId O ID da sessão à qual o plugin será anexado.
     * @param pluginName O nome do plugin (ex: "janus.plugin.videoroom").
     * @return O ID do handle do plugin.
     */
    suspend fun attachPlugin(sessionId: Long, pluginName: String): Long {
        val transactionId = generateTransactionId("attach_${pluginName.replace(".", "_")}")
        val requestBody = JanusAttachPluginRequest(plugin = pluginName, transaction = transactionId)
        // 2. Passe o serializador explicitamente
        val successResponse = sendJanusRequest(
            "/$sessionId",
            JanusAttachPluginRequest.serializer(), // Tipo explícito aqui
            requestBody
        )

        return successResponse.data?.get("id")?.jsonPrimitive?.content?.toLong()
            ?: throw IllegalStateException("Janus plugin handle ID not found in attachPlugin response for plugin $pluginName.")
    }

    /**
     * Envia uma mensagem para um handle de plugin Janus.
     * @param sessionId O ID da sessão Janus.
     * @param handleId O ID do handle do plugin.
     * @param pluginData O corpo da mensagem específica do plugin (JsonObject).
     * @param jsep Objeto JSEP opcional (SDP Offer/Answer/Candidate).
     * @return O objeto 'data' da resposta de sucesso do Janus.
     */
    suspend fun sendMessageToPlugin(
        sessionId: Long,
        handleId: Long,
        pluginData: JsonObject,
        jsep: JsonObject? = null
    ): JsonObject? {
        val transactionId = generateTransactionId("msg_${handleId}")
        val requestBody = JanusPluginMessageRequest(
            body = pluginData,
            jsep = jsep,
            transaction = transactionId
        )
        // 2. Passe o serializador explicitamente
        val successResponse = sendJanusRequest(
            "/$sessionId/$handleId",
            JanusPluginMessageRequest.serializer(), // Tipo explícito aqui
            requestBody
        )
        return successResponse.data
    }

    // --- Funções Específicas para o Plugin VideoRoom (Exemplos) ---

    suspend fun createVideoRoom(
        sessionId: Long,
        handleId: Long,
        roomId: Int,
        description: String
    ): JsonObject? {
        val pluginData = buildJsonObject {
            put("request", JsonPrimitive("create"))
            put("room", JsonPrimitive(roomId))
            put("description", JsonPrimitive(description))
            put("publishers", JsonPrimitive(10))
            put("bitrate", JsonPrimitive(128000))
        }
        return sendMessageToPlugin(sessionId, handleId, pluginData)
    }

    suspend fun joinVideoRoomAsPublisher(
        sessionId: Long,
        handleId: Long,
        roomId: Int,
        display: String,
        offer: JsonObject? = null
    ): JsonObject? {
        val pluginData = buildJsonObject {
            put("request", JsonPrimitive("join"))
            put("room", JsonPrimitive(roomId))
            put("ptype", JsonPrimitive("publisher"))
            put("display", JsonPrimitive(display))
        }
        return sendMessageToPlugin(sessionId, handleId, pluginData, offer)
    }
}