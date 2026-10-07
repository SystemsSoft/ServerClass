package services.secretaria

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import schemas.secretaria.SecretariaCallService
import schemas.secretaria.Speaker
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class GeminiKey(val label: String, val key: String)

/** Configuração da ponte. Tudo vem de propriedades/variáveis de ambiente, como nos outros módulos. */
class SecretariaLiveConfig(
    val keys: () -> List<GeminiKey> = ::keysFromConfig,
    val liveUrl: (key: String) -> String = ::defaultLiveUrl,
    val model: String = readConfig("secretaria.liveModel", "SECRETARIA_GEMINI_LIVE_MODEL")
        ?: readConfig("gemini.liveModel", "GEMINI_LIVE_MODEL")
        ?: "models/gemini-2.5-flash-native-audio-latest",
    val voice: String = readConfig("secretaria.liveVoice", "SECRETARIA_GEMINI_LIVE_VOICE")
        ?: readConfig("gemini.liveVoice", "GEMINI_LIVE_VOICE")
        ?: "Aoede",
    /** Duração máxima de uma chamada (controle de custo). */
    val maxCallMillis: Long = readConfig("secretaria.maxCallSeconds", "SECRETARIA_MAX_CALL_SECONDS")?.toLongOrNull()?.times(1000) ?: 600_000L,
    /** Sem nenhum áudio do paciente por esse tempo, o app foi fechado/perdeu conexão: encerra. */
    val idleMillis: Long = 30_000L,
    val setupTimeoutMillis: Long = 10_000L,
    val watchdogIntervalMillis: Long = 2_000L,
) {
    companion object {
        fun readConfig(property: String, env: String): String? = System.getProperty(property) ?: System.getenv(env)

        /** Mesmas chaves do restante do servidor (gemini-credentials.properties / variáveis de ambiente). */
        fun keysFromConfig(): List<GeminiKey> = listOfNotNull(
            readConfig("gemini.apiKeyFree1", "GEMINI_API_KEY_FREE_1")?.let { GeminiKey("gratuita-1", it) },
            readConfig("gemini.apiKeyFree2", "GEMINI_API_KEY_FREE_2")?.let { GeminiKey("gratuita-2", it) },
            readConfig("gemini.apiKey", "GEMINI_API_KEY")?.let { GeminiKey("paga", it) },
        )

        const val OFFICIAL_LIVE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key={key}"

        /**
         * Endereço do Gemini Live. Só para DESENVOLVIMENTO/TESTE: `secretaria.geminiLiveUrl` (ou
         * SECRETARIA_GEMINI_LIVE_URL) aponta o servidor para um Gemini falso, sem gastar cota; `{key}` é
         * substituído pela chave. Em produção, deixe vazio.
         */
        fun defaultLiveUrl(key: String): String =
            (readConfig("secretaria.geminiLiveUrl", "SECRETARIA_GEMINI_LIVE_URL")?.takeIf { it.isNotBlank() } ?: OFFICIAL_LIVE_URL)
                .replace("{key}", key)
    }
}

/**
 * Ponte entre o PWA do paciente e a Gemini Live API, para a SecretárIA.
 *
 * Diferente da ponte da Megan (relé transparente), esta ponte:
 *  - fala o protocolo com a Gemini ela mesma (setup, persona e funções vêm do servidor — o cliente não consegue mudar);
 *  - só aceita do cliente áudio (`realtimeInput`) e o controle `{"type":"end"}`;
 *  - executa as funções da IA (agendar, remarcar...) no banco e devolve o resultado à Gemini;
 *  - grava a transcrição da conversa em segundo plano, sem atrasar o áudio.
 *
 * Protocolo com o PWA: o cliente envia frames de texto `{"realtimeInput":{"audio":{"data":"<base64 PCM16 16kHz>","mimeType":"audio/pcm;rate=16000"}}}`;
 * recebe os frames da Gemini (áudio PCM 24kHz em `serverContent.modelTurn.parts[].inlineData`, transcrições,
 * `interrupted`...) mais mensagens próprias `{"type": "session_ready" | "appointment" | "time_limit" | "error"}`.
 */
class SecretariaLiveBridge(
    private val tools: SecretariaToolExecutor,
    private val calls: SecretariaCallService,
    private val config: SecretariaLiveConfig = SecretariaLiveConfig(),
) {
    private val log = LoggerFactory.getLogger("Secretaria.Bridge")
    private val http = HttpClient(CIO) { install(WebSockets) }

    private sealed class Attempt {
        object Finished : Attempt()
        data class NotEstablished(val error: Throwable) : Attempt()
    }

    private data class Transcript(val speaker: Speaker, val text: String, val offsetMs: Long)

    suspend fun run(clientSession: DefaultWebSocketServerSession, ctx: SecretariaCallContext, instruction: String, voice: String? = null) {
        val keys = config.keys()
        if (keys.isEmpty()) {
            log.error("Nenhuma chave Gemini configurada (gemini.apiKey / GEMINI_API_KEY...)")
            clientSession.sendControl("error", "O atendimento por IA não está configurado no servidor.")
            return
        }

        // Transcrição gravada em segundo plano: o banco remoto não pode atrasar o áudio.
        val queue = Channel<Transcript>(Channel.UNLIMITED)
        val buffer = TranscriptBuffer(ctx.startedAt) { queue.trySend(it) }
        val writer = clientSession.launch {
            for (t in queue) {
                runCatching { calls.appendMessage(ctx.callId, t.speaker, t.text, t.offsetMs) }
                    .onFailure { log.warn("Falha ao gravar transcrição (chamada {}): {}", ctx.callId, it.message) }
            }
        }

        try {
            var lastError: Throwable? = null
            for (entry in keys) {
                when (val result = attempt(entry, clientSession, ctx, instruction, voice, buffer)) {
                    Attempt.Finished -> return
                    is Attempt.NotEstablished -> {
                        lastError = result.error
                        log.warn("Chave '{}' não conectou (chamada {}): {}", entry.label, ctx.callId, result.error.message)
                    }
                }
            }
            log.error("Nenhuma chave Gemini conectou (chamada {}): {}", ctx.callId, lastError?.message)
            clientSession.sendControl("error", "Não foi possível iniciar o atendimento agora. Tente novamente em instantes.")
        } finally {
            withContext(NonCancellable) {
                buffer.flushAll()
                queue.close()
                withTimeoutOrNull(5_000) { writer.join() }
            }
        }
    }

    private suspend fun attempt(
        entry: GeminiKey,
        clientSession: DefaultWebSocketServerSession,
        ctx: SecretariaCallContext,
        instruction: String,
        voice: String?,
        buffer: TranscriptBuffer,
    ): Attempt {
        var established = false
        try {
            http.webSocket(urlString = config.liveUrl(entry.key)) {
                send(Frame.Text(GeminiLiveMessages.setup(config.model, voice ?: config.voice, instruction, SecretariaToolExecutor.declarations).toString()))
                val ready = withTimeoutOrNull(config.setupTimeoutMillis) { awaitSetupComplete() } ?: false
                check(ready) { "Gemini não confirmou o setup" }
                established = true

                clientSession.send(Frame.Text(buildJsonObject {
                    put("type", "session_ready")
                    put("callId", ctx.callId)
                }.toString()))
                send(Frame.Text(GeminiLiveMessages.userText(SecretariaPersona.GREETING).toString()))
                relay(this, clientSession, ctx, buffer)
            }
            return Attempt.Finished
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!established) return Attempt.NotEstablished(e)
            log.error("Sessão Gemini caiu no meio da chamada {}: {}", ctx.callId, e.message)
            runCatching { clientSession.sendControl("error", "A conexão com a assistente caiu. Ligue novamente.") }
            return Attempt.Finished
        }
    }

    private suspend fun DefaultClientWebSocketSession.awaitSetupComplete(): Boolean {
        for (frame in incoming) {
            val bytes = frame.bytesOrNull() ?: continue
            if (GeminiLiveMessages.parse(bytes)?.setupComplete == true) return true
        }
        return false
    }

    private suspend fun relay(
        upstream: DefaultClientWebSocketSession,
        clientSession: DefaultWebSocketServerSession,
        ctx: SecretariaCallContext,
        buffer: TranscriptBuffer,
    ) = coroutineScope {
        val startedAt = System.currentTimeMillis()
        val lastClientAt = AtomicLong(startedAt)
        val endedBy = AtomicReference<String?>(null)

        val toGemini = launch {
            try {
                for (frame in clientSession.incoming) {
                    lastClientAt.set(System.currentTimeMillis())
                    if (frame !is Frame.Text || frame.data.size > MAX_CLIENT_FRAME_BYTES) continue
                    val text = frame.readText()
                    when {
                        GeminiLiveMessages.isClientAudio(text) -> upstream.send(Frame.Text(text))
                        GeminiLiveMessages.clientControlType(text) == "end" -> break
                    }
                }
            } finally {
                endedBy.compareAndSet(null, "client")
            }
        }

        val toClient = launch {
            try {
                for (frame in upstream.incoming) {
                    val bytes = frame.bytesOrNull() ?: continue
                    val message = GeminiLiveMessages.parse(bytes)
                    if (message != null) {
                        buffer.onMessage(message)
                        if (message.toolCalls.isNotEmpty()) runTools(message.toolCalls, ctx, upstream, clientSession)
                        if (message.internalOnly) continue
                    }
                    clientSession.send(if (frame is Frame.Text) Frame.Text(String(bytes, Charsets.UTF_8)) else Frame.Binary(true, bytes))
                }
            } finally {
                endedBy.compareAndSet(null, "gemini")
            }
        }

        val watchdog = launch {
            while (isActive) {
                delay(config.watchdogIntervalMillis)
                val now = System.currentTimeMillis()
                if (now - startedAt >= config.maxCallMillis) {
                    runCatching { clientSession.sendControl("time_limit", "O tempo máximo da chamada foi atingido.") }
                    endedBy.compareAndSet(null, "time_limit")
                    return@launch
                }
                if (now - lastClientAt.get() >= config.idleMillis) {
                    endedBy.compareAndSet(null, "idle")
                    return@launch
                }
            }
        }

        select<Unit> {
            toGemini.onJoin {}
            toClient.onJoin {}
            watchdog.onJoin {}
        }
        toGemini.cancel(); toClient.cancel(); watchdog.cancel()

        if (endedBy.get() == "gemini") {
            runCatching { clientSession.sendControl("error", "A assistente encerrou a conexão. Ligue novamente.") }
        }
        log.info("Chamada {} encerrada por: {}", ctx.callId, endedBy.get())
    }

    private suspend fun runTools(
        toolCalls: List<GeminiToolCall>,
        ctx: SecretariaCallContext,
        upstream: DefaultClientWebSocketSession,
        clientSession: DefaultWebSocketServerSession,
    ) {
        val results = toolCalls.map { call ->
            val result = tools.execute(ctx, call.name, call.args)
            result.uiEvent?.let { clientSession.send(Frame.Text(it.toString())) }
            Triple(call.id, call.name, result.response)
        }
        upstream.send(Frame.Text(GeminiLiveMessages.toolResponse(results).toString()))
    }

    private fun Frame.bytesOrNull(): ByteArray? = when (this) {
        is Frame.Text -> data
        is Frame.Binary -> data
        else -> null
    }

    private suspend fun DefaultWebSocketServerSession.sendControl(type: String, message: String) {
        send(Frame.Text(buildJsonObject { put("type", type); put("message", message) }.toString()))
    }

    /**
     * Junta os pedaços de transcrição da Gemini em falas completas. Ordem: o que o paciente disse vem antes
     * da resposta da IA; fecha a fala em `turnComplete` ou `interrupted`.
     */
    private class TranscriptBuffer(private val startedAt: Long, private val sink: (Transcript) -> Unit) {
        private val user = StringBuilder()
        private val model = StringBuilder()

        @Synchronized
        fun onMessage(message: GeminiServerMessage) {
            message.inputText?.let { user.append(it) }
            message.outputText?.let {
                if (user.isNotEmpty()) flush(Speaker.PACIENTE, user)
                model.append(it)
            }
            if (message.turnComplete || message.interrupted) flushAll()
        }

        @Synchronized
        fun flushAll() {
            if (user.isNotEmpty()) flush(Speaker.PACIENTE, user)
            if (model.isNotEmpty()) flush(Speaker.IA, model)
        }

        private fun flush(speaker: Speaker, text: StringBuilder) {
            sink(Transcript(speaker, text.toString().trim(), System.currentTimeMillis() - startedAt))
            text.clear()
        }
    }

    private companion object {
        const val MAX_CLIENT_FRAME_BYTES = 256 * 1024
    }
}
