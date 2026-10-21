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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
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
import schemas.secretaria.TokenUsage
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
    /**
     * Sem ninguém falar (paciente ou IA) por esse tempo, encerra: o app manda áudio o tempo todo, mesmo em silêncio,
     * e a Gemini cobra cada segundo desse áudio.
     */
    val inactivityMillis: Long = readConfig("secretaria.inactivitySeconds", "SECRETARIA_INACTIVITY_SECONDS")?.toLongOrNull()?.times(1000) ?: 180_000L,
    val setupTimeoutMillis: Long = 10_000L,
    val watchdogIntervalMillis: Long = 2_000L,
    /** Quanto esperar a Gemini confirmar o fechamento depois que a conversa acabou; ela às vezes nunca responde. */
    val closeTimeoutMillis: Long = 5_000L,
) {
    companion object {
        fun readConfig(property: String, env: String): String? = System.getProperty(property) ?: System.getenv(env)

        /**
         * Mesmas chaves do restante do servidor (gemini-credentials.properties / variáveis de ambiente), na ordem em que
         * são tentadas: gratuita-1, gratuita-2, paga. `secretaria.geminiKeys` (SECRETARIA_GEMINI_KEYS) restringe a quais
         * usar, separadas por vírgula (ex.: `paga` para medir só o gasto da chave paga). Vazio/ausente = todas, como sempre.
         */
        fun keysFromConfig(): List<GeminiKey> {
            val all = listOfNotNull(
                readConfig("gemini.apiKeyFree1", "GEMINI_API_KEY_FREE_1")?.let { GeminiKey("gratuita-1", it) },
                readConfig("gemini.apiKeyFree2", "GEMINI_API_KEY_FREE_2")?.let { GeminiKey("gratuita-2", it) },
                readConfig("gemini.apiKey", "GEMINI_API_KEY")?.let { GeminiKey("paga", it) },
            )
            val only = readConfig("secretaria.geminiKeys", "SECRETARIA_GEMINI_KEYS")
                ?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }.orEmpty()
            return if (only.isEmpty()) all else all.filter { it.label in only }
        }

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
 * `interrupted`...) mais mensagens próprias `{"type": "session_ready" | "working" | "appointment" | "time_limit" | "hangup" | "error"}`.
 *
 * Falha no meio da ligação (a Gemini derrubou a sessão, uma função quebrou, o modelo só devolve `<ctrl46>`) ou 3 min sem
 * ninguém falar: a sessão com a Gemini é fechada na hora (para de gastar token) e o app recebe `hangup`, pede desculpas
 * com a frase pronta na voz da SecretárIA e desliga.
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

    /** Soma dos tokens que a Gemini informa durante a ligação (usageMetadata) e qual chave atendeu. */
    private class UsageMeter {
        @Volatile var key: String? = null
        private var total = TokenUsage()

        @Synchronized fun add(usage: TokenUsage) { total += usage }

        @Synchronized fun total() = total
    }

    /** [maxCallMillis]: limite desta ligação (o saldo de minutos do plano); nunca passa do limite configurado. */
    suspend fun run(
        clientSession: DefaultWebSocketServerSession,
        ctx: SecretariaCallContext,
        instruction: String,
        voice: String? = null,
        maxCallMillis: Long? = null,
    ) {
        val limitMillis = minOf(config.maxCallMillis, maxCallMillis ?: Long.MAX_VALUE)
        val keys = config.keys()
        if (keys.isEmpty()) {
            log.error("Nenhuma chave Gemini configurada (gemini.apiKey / GEMINI_API_KEY...)")
            clientSession.sendControl("error", "O atendimento por IA não está configurado no servidor.")
            return
        }

        // Transcrição gravada em segundo plano: o banco remoto não pode atrasar o áudio.
        val queue = Channel<Transcript>(Channel.UNLIMITED)
        val buffer = TranscriptBuffer(ctx.startedAt) { queue.trySend(it) }
        val meter = UsageMeter()
        val writer = clientSession.launch {
            for (t in queue) {
                runCatching { calls.appendMessage(ctx.callId, t.speaker, t.text, t.offsetMs) }
                    .onFailure { log.warn("Falha ao gravar transcrição (chamada {}): {}", ctx.callId, it.message) }
            }
        }

        try {
            var lastError: Throwable? = null
            for (entry in keys) {
                when (val result = attempt(entry, clientSession, ctx, instruction, voice, buffer, meter, limitMillis)) {
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
                // custo real: grava os tokens medidos e a chave usada (mesmo se a ligação caiu no meio)
                val usage = meter.total()
                if (usage.reports > 0 || meter.key != null) {
                    runCatching { calls.setUsage(ctx.callId, usage, meter.key) }
                        .onFailure { log.warn("Falha ao gravar o consumo da chamada {}: {}", ctx.callId, it.message) }
                }
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
        meter: UsageMeter,
        limitMillis: Long,
    ): Attempt = coroutineScope {
        // Ao sair do webSocket{} o Ktor espera a Gemini confirmar o Close, sem prazo; ela às vezes nunca confirma
        // e a chamada ficaria "em andamento" para sempre. Acabada a conversa, o fechamento tem prazo.
        val talked = CompletableDeferred<Unit>()
        val connection = async { connect(entry, clientSession, ctx, instruction, voice, buffer, meter, limitMillis, talked) }
        select {
            connection.onAwait { it }
            talked.onAwait {
                withTimeoutOrNull(config.closeTimeoutMillis) { connection.await() } ?: run {
                    log.warn("Chamada {}: a Gemini não confirmou o fechamento em {} ms; conexão abandonada", ctx.callId, config.closeTimeoutMillis)
                    connection.cancel()
                    Attempt.Finished
                }
            }
        }
    }

    private suspend fun connect(
        entry: GeminiKey,
        clientSession: DefaultWebSocketServerSession,
        ctx: SecretariaCallContext,
        instruction: String,
        voice: String?,
        buffer: TranscriptBuffer,
        meter: UsageMeter,
        limitMillis: Long,
        talked: CompletableDeferred<Unit>,
    ): Attempt {
        var established = false
        try {
            http.webSocket(urlString = config.liveUrl(entry.key)) {
                send(Frame.Text(GeminiLiveMessages.setup(config.model, voice ?: config.voice, instruction, SecretariaToolExecutor.declarationsFor(identified = ctx.caller != null)).toString()))
                val ready = withTimeoutOrNull(config.setupTimeoutMillis) { awaitSetupComplete() } ?: false
                check(ready) { "Gemini não confirmou o setup" }
                established = true
                meter.key = entry.label

                clientSession.send(Frame.Text(buildJsonObject {
                    put("type", "session_ready")
                    put("callId", ctx.callId)
                    put("voice", voice ?: config.voice) // o app toca o "só um instante" nesta mesma voz
                }.toString()))
                send(Frame.Text(GeminiLiveMessages.userText(SecretariaPersona.greeting(ctx.caller)).toString()))
                try {
                    relay(this, clientSession, ctx, buffer, meter, limitMillis)
                } finally {
                    talked.complete(Unit)
                }
            }
            return Attempt.Finished
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!established) return Attempt.NotEstablished(e)
            log.error("Sessão Gemini caiu no meio da chamada {}: {}", ctx.callId, e.message)
            runCatching { clientSession.sendHangup(HANGUP_ERROR) }
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
        meter: UsageMeter,
        limitMillis: Long,
    ) = coroutineScope {
        val startedAt = System.currentTimeMillis()
        val lastClientAt = AtomicLong(startedAt)
        val lastTalkAt = AtomicLong(startedAt)
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
            var garbledTurn = false
            var nudges = 0
            try {
                for (frame in upstream.incoming) {
                    val bytes = frame.bytesOrNull() ?: continue
                    val message = GeminiLiveMessages.parse(bytes)
                    if (message != null) {
                        message.usage?.let(meter::add)
                        buffer.onMessage(message)
                        if (message.inputText != null || message.outputText != null || message.hasAudio || message.toolCalls.isNotEmpty()) {
                            lastTalkAt.set(System.currentTimeMillis())
                        }
                        if (message.toolCalls.isNotEmpty() && !runTools(message.toolCalls, ctx, upstream, clientSession)) {
                            endedBy.compareAndSet(null, "tool_error")
                            return@launch
                        }
                        if (message.garbled) garbledTurn = true
                        if (message.turnComplete && garbledTurn) {
                            garbledTurn = false
                            if (nudges >= MAX_GARBLED_NUDGES) {
                                log.warn("Chamada {}: o modelo respondeu com tokens internos (<ctrl..>) de novo; encerrando", ctx.callId)
                                endedBy.compareAndSet(null, "garbled")
                                return@launch
                            }
                            nudges++
                            log.warn("Chamada {}: o modelo respondeu com tokens internos (<ctrl..>); pedindo para continuar", ctx.callId)
                            upstream.send(Frame.Text(GeminiLiveMessages.userText(SecretariaPersona.GARBLED_NUDGE).toString()))
                        }
                        if (message.internalOnly) continue
                        if (message.garbled && !message.hasAudio) continue // as legendas do app não mostram <ctrl46>
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
                if (now - startedAt >= limitMillis) {
                    runCatching { clientSession.sendControl("time_limit", "O tempo máximo da chamada foi atingido.") }
                    endedBy.compareAndSet(null, "time_limit")
                    return@launch
                }
                if (now - lastClientAt.get() >= config.idleMillis) {
                    endedBy.compareAndSet(null, "idle")
                    return@launch
                }
                if (now - lastTalkAt.get() >= config.inactivityMillis) {
                    endedBy.compareAndSet(null, "inactive")
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
            // o motivo que a Gemini manda ao fechar (ex.: 1007 "audio content type not supported") é o que explica a queda
            val reason = withTimeoutOrNull(1_000) { upstream.closeReason.await() }
            log.warn("Chamada {}: a Gemini fechou a sessão. Motivo: {}", ctx.callId, reason ?: "não informado")
        }
        // a sessão com a Gemini fecha logo em seguida (fim do webSocket{}): o app se despede sem gastar mais token
        when (endedBy.get()) {
            "gemini", "tool_error", "garbled" -> runCatching { clientSession.sendHangup(HANGUP_ERROR) }
            "inactive", "idle" -> runCatching { clientSession.sendHangup(HANGUP_IDLE) }
        }
        log.info("Chamada {} encerrada por: {}", ctx.callId, endedBy.get())
    }

    /** Executa as funções e devolve o resultado à Gemini; false se uma delas falhou por erro técnico (a ligação acaba). */
    private suspend fun runTools(
        toolCalls: List<GeminiToolCall>,
        ctx: SecretariaCallContext,
        upstream: DefaultClientWebSocketSession,
        clientSession: DefaultWebSocketServerSession,
    ): Boolean {
        // o app fala uma frase pronta da ação ("só um instante, vou ver os horários"), toca o sinal de espera e mostra
        // "Verificando…": a IA não pode anunciar isso em voz (falar e chamar função na mesma resposta derruba a sessão)
        toolCalls.firstOrNull { it.name != SILENT_TOOL }?.let { call ->
            runCatching { clientSession.send(Frame.Text(buildJsonObject { put("type", "working"); put("tool", call.name) }.toString())) }
        }
        val results = mutableListOf<Triple<String?, String, JsonObject>>()
        for (call in toolCalls) {
            val result = tools.execute(ctx, call.name, call.args)
            if (result.failed) return false
            result.uiEvent?.let { clientSession.send(Frame.Text(it.toString())) }
            results += Triple(call.id, call.name, result.response)
        }
        upstream.send(Frame.Text(GeminiLiveMessages.toolResponse(results).toString()))
        return true
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
                model.append(it.replace(GeminiLiveMessages.CONTROL_TOKEN, ""))
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
        /** Função rápida e invisível para o paciente: não toca o sinal de espera. */
        const val SILENT_TOOL = "registrar_assunto"
        /** Quantas vezes por ligação pedir ao modelo que retome depois de vazar <ctrl..>; na seguinte, a ligação acaba. */
        const val MAX_GARBLED_NUDGES = 1
    }
}

const val HANGUP_ERROR = "error"
const val HANGUP_IDLE = "idle"
const val HANGUP_CLINIC = "clinic"

/**
 * Avisa o app que o servidor está encerrando a ligação ([reason]: [HANGUP_ERROR], [HANGUP_IDLE] ou [HANGUP_CLINIC]):
 * ele para de mandar áudio, se despede com a frase pronta na voz da SecretárIA e desliga.
 */
internal suspend fun DefaultWebSocketServerSession.sendHangup(reason: String) {
    send(Frame.Text(buildJsonObject { put("type", "hangup"); put("reason", reason) }.toString()))
}
