package services

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Ponte entre a sessão WebSocket do cliente (app do aluno) e a Gemini Live API.
 * A chave real da API nunca é exposta ao cliente: fica só neste processo, via variável de ambiente.
 *
 * Nomes de modelo/campos do protocolo Live API mudam com frequência — confira a documentação
 * oficial do Gemini Live API antes de colocar em produção.
 */
class GeminiLiveBridge {

    private data class ApiKeyEntry(val label: String, val key: String)

    /** Por que uma tentativa com uma chave terminou — decide o que [bridge] faz a seguir. */
    private enum class SessionOutcome {
        /** O CLIENTE (app do aluno) encerrou a própria conexão — não há mais nada a fazer. */
        ClientDisconnected,

        /** A chave atual falhou/ficou lenta/a Gemini caiu — tenta a próxima chave da lista,
         *  sem derrubar o cliente, reaproveitando o histórico da conversa até aqui. */
        SwitchKey,
    }

    // Ordem de tentativa a cada NOVA chamada (sempre recomeça do início, do zero): as duas
    // chaves gratuitas primeiro, e só se ambas falharem/ficarem lentas é que cai pra paga. As
    // chaves gratuitas são opcionais: se não estiverem configuradas, só a paga é usada
    // (comportamento idêntico ao de antes, sem fallback).
    // 'by lazy': só falha quando a primeira chamada realmente precisar de alguma chave, não
    // na inicialização do servidor (a Megan é opcional e pode não estar configurada ainda).
    // Busca em: system property (arquivo local gemini-credentials.properties em dev) ou
    // variável de ambiente (Heroku Config Vars em produção).
    private val apiKeys: List<ApiKeyEntry> by lazy {
        listOfNotNull(
            readConfig("gemini.apiKeyFree1", "GEMINI_API_KEY_FREE_1")?.let { ApiKeyEntry("gratuita-1", it) },
            readConfig("gemini.apiKeyFree2", "GEMINI_API_KEY_FREE_2")?.let { ApiKeyEntry("gratuita-2", it) },
            readConfig("gemini.apiKey", "GEMINI_API_KEY")?.let { ApiKeyEntry("paga", it) },
        ).ifEmpty {
            error(
                "Nenhuma chave Gemini configurada (gemini.apiKey/gemini.apiKeyFree1/gemini.apiKeyFree2 " +
                    "ou GEMINI_API_KEY/GEMINI_API_KEY_FREE_1/GEMINI_API_KEY_FREE_2)"
            )
        }
    }

    private fun readConfig(propertyName: String, envName: String): String? =
        System.getProperty(propertyName) ?: System.getenv(envName)

    private val model: String = System.getProperty("gemini.liveModel")
        ?: System.getenv("GEMINI_LIVE_MODEL")
        ?: "models/gemini-2.5-flash-native-audio-latest"

    private val voiceName: String = System.getProperty("gemini.liveVoice")
        ?: System.getenv("GEMINI_LIVE_VOICE")
        ?: "Aoede"

    private val client = HttpClient(CIO) {
        install(WebSockets)
    }

    // Se nenhum frame trafegar em NENHUMA direção por esse tempo — nem o cliente nem a Gemini
    // — a ligação é considerada realmente morta (ex: o aluno saiu/fechou a aba) e é encerrada
    // de vez, cliente incluso. Diferente de [responseTimeoutMillis]: aqui os DOIS lados estão
    // quietos, não só a Gemini.
    private val idleTimeoutMillis: Long = System.getProperty("gemini.idleTimeoutMillis")?.toLongOrNull()
        ?: System.getenv("GEMINI_IDLE_TIMEOUT_MILLIS")?.toLongOrNull()
        ?: 45_000L

    // Se o CLIENTE continua mandando áudio normalmente (ligação viva) mas a GEMINI para de
    // responder por esse tempo, consideramos a chave atual "lenta/travada" e trocamos pra
    // próxima da lista (gratuita-1 -> gratuita-2 -> paga), sem derrubar o cliente e sem
    // perder o histórico da conversa — é isso que evita a Megan ficar "pensando" pra sempre.
    private val responseTimeoutMillis: Long = System.getProperty("gemini.responseTimeoutMillis")?.toLongOrNull()
        ?: System.getenv("GEMINI_RESPONSE_TIMEOUT_MILLIS")?.toLongOrNull()
        ?: 9_000L

    /**
     * Abre a sessão upstream com a Gemini Live API e relay bidirecional com [clientSession]
     * até o cliente encerrar a conexão (ou todas as chaves configuradas falharem). Suspende
     * até então.
     *
     * Tenta as chaves configuradas em ordem (gratuitas primeiro, paga por último) — não só na
     * conexão inicial, mas durante toda a ligação: se a chave em uso ficar lenta ou a Gemini
     * cair sozinha, troca pra próxima chave AUTOMATICAMENTE, mantendo a conexão do cliente
     * aberta o tempo todo (o aluno não percebe nada, exceto um pequeno silêncio) e reaproveitando
     * o [ConversationHistory] acumulado até ali, pra Megan continuar a conversa de onde parou em
     * vez de se reapresentar do zero. Só desiste de vez se o próprio cliente encerrar a conexão,
     * ou se TODAS as chaves configuradas falharem/ficarem lentas em sequência.
     */
    suspend fun bridge(clientSession: DefaultWebSocketServerSession, systemInstruction: String) {
        val history = ConversationHistory()
        var lastError: Throwable? = null
        var keyIndex = 0

        while (keyIndex < apiKeys.size) {
            val entry = apiKeys[keyIndex]
            val outcome = try {
                attemptSession(clientSession, systemInstruction, entry, history)
            } catch (e: Exception) {
                lastError = e
                MeganLog.d("[Megan] Falha ao conectar com a chave '${entry.label}': ${e.message} — tentando próxima chave, se houver.")
                SessionOutcome.SwitchKey
            }

            when (outcome) {
                SessionOutcome.ClientDisconnected -> return
                SessionOutcome.SwitchKey -> keyIndex++
            }
        }

        MeganLog.d("[Megan] Todas as chaves configuradas falharam/ficaram lentas. Última falha: ${lastError?.message}")
        runCatching {
            clientSession.send(Frame.Text(buildJsonObject {
                put("type", "error")
                put("message", "Não foi possível iniciar a chamada com a Megan agora. Tente novamente em instantes.")
            }.toString()))
        }
        runCatching { clientSession.close() }
    }

    /**
     * Uma tentativa de sessão com uma chave específica. Retorna [SessionOutcome.ClientDisconnected]
     * só quando o PRÓPRIO CLIENTE encerrou a conexão (nesse caso não sobra nada a fazer); qualquer
     * outro motivo de término — a Gemini caiu, ficou lenta, ou a chave nem conectou — retorna
     * [SessionOutcome.SwitchKey], deixando [bridge] tentar a próxima chave sem derrubar o cliente.
     */
    private suspend fun attemptSession(
        clientSession: DefaultWebSocketServerSession,
        systemInstruction: String,
        entry: ApiKeyEntry,
        history: ConversationHistory,
    ): SessionOutcome {
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=${entry.key}"
        val lastFromClientAt = AtomicLong(System.currentTimeMillis())
        val lastFromGeminiAt = AtomicLong(System.currentTimeMillis())
        val clientDisconnected = AtomicBoolean(false)

        client.webSocket(urlString = url) {
            send(Frame.Text(buildSetupMessage(systemInstruction).toString()))
            MeganLog.d("[Megan][diag] setup enviado para a Gemini (chave: ${entry.label})")

            // Primeira vez da ligação: "Hi!" (a Megan puxa assunto sozinha). Depois de uma
            // troca de chave no meio da conversa: um resumo do que já foi dito, pra ela
            // continuar de onde parou em vez de se reapresentar como se fosse uma ligação nova.
            send(Frame.Text(history.buildPrimingMessage().toString()))

            coroutineScope {
                val toGemini = launch {
                    try {
                        var audioFrames = 0
                        for (frame in clientSession.incoming) {
                            lastFromClientAt.set(System.currentTimeMillis())
                            logFrame("cliente->gemini", frame, audioFrames) { audioFrames++ }
                            try {
                                send(frame)
                            } catch (e: Exception) {
                                // O upstream já foi fechado por nós (troca de chave em
                                // andamento) — não é o cliente que caiu, só ignora e segue
                                // esperando o próximo frame (ou o cancelamento abaixo).
                            }
                        }
                        // O for só termina sozinho quando clientSession.incoming realmente
                        // fecha — ou seja, o CLIENTE encerrou a própria conexão.
                        clientDisconnected.set(true)
                    } catch (e: CancellationException) {
                        throw e // cancelado de propósito (troca de chave) — não é o cliente saindo.
                    } catch (e: Exception) {
                        MeganLog.d("[Megan] Conexão do cliente encerrada: ${e.message}")
                        clientDisconnected.set(true)
                    } finally {
                        runCatching { close() }
                    }
                }

                val toClient = launch {
                    try {
                        var audioFrames = 0
                        for (frame in incoming) {
                            lastFromGeminiAt.set(System.currentTimeMillis())
                            history.absorb(frame)
                            logFrame("gemini->cliente", frame, audioFrames) { audioFrames++ }
                            clientSession.send(frame)
                        }
                    } catch (e: Exception) {
                        MeganLog.d("[Megan] Conexão com a Gemini encerrada: ${e.message}")
                    }
                    // Não encerra clientSession aqui de propósito: se foi a Gemini que caiu (e
                    // não o cliente), queremos tentar a próxima chave mantendo o aluno na
                    // linha — ver o watchdog abaixo e o retorno de attemptSession.
                }

                val watchdog = launch {
                    while (isActive) {
                        delay(2_000)
                        val now = System.currentTimeMillis()
                        val idleAmbos = now - maxOf(lastFromClientAt.get(), lastFromGeminiAt.get())
                        val semRespostaDaGemini = now - lastFromGeminiAt.get()
                        val clienteAindaAtivo = (now - lastFromClientAt.get()) < idleTimeoutMillis

                        if (idleAmbos >= idleTimeoutMillis) {
                            MeganLog.d(
                                "[Megan] Timeout de inatividade total (${idleAmbos}ms sem nenhum frame em " +
                                    "nenhuma direção, chave: ${entry.label}) — encerrando a ligação de vez."
                            )
                            clientDisconnected.set(true) // ninguém mais responde, nem o aluno — encerra tudo.
                            runCatching {
                                clientSession.send(Frame.Text(buildJsonObject {
                                    put("type", "error")
                                    put("message", "A Megan demorou demais para responder. Tente novamente.")
                                }.toString()))
                            }
                            runCatching { close(CloseReason(CloseReason.Codes.NORMAL, "Timeout de inatividade")) }
                            runCatching { clientSession.close() }
                            toGemini.cancel()
                            break
                        }

                        if (semRespostaDaGemini >= responseTimeoutMillis && clienteAindaAtivo) {
                            MeganLog.d(
                                "[Megan] Gemini sem responder há ${semRespostaDaGemini}ms (chave: ${entry.label}), " +
                                    "mas o cliente continua ativo — trocando de chave sem derrubar a ligação."
                            )
                            runCatching { close(CloseReason(CloseReason.Codes.NORMAL, "Resposta lenta — trocando de chave")) }
                            toGemini.cancel()
                            break
                        }
                    }
                }

                toGemini.join()
                toClient.join()
                watchdog.cancel()
            }

            MeganLog.d(
                "[Megan] Sessão com a Gemini encerrada (chave: ${entry.label}), motivo: ${closeReason.await()} " +
                    "— último frame da Gemini há ${System.currentTimeMillis() - lastFromGeminiAt.get()}ms."
            )
        }

        return if (clientDisconnected.get()) SessionOutcome.ClientDisconnected else SessionOutcome.SwitchKey
    }

    /**
     * Log de diagnóstico do relay: mensagens de controle/protocolo (ex: setupComplete,
     * turnComplete, erros) são logadas por inteiro, já que são raras e cruciais pra depurar
     * travamentos. Frames de áudio só têm a contagem resumida a cada 50, pra confirmar que o
     * áudio está fluindo sem inundar o log — isso vale tanto pra frames binários quanto pros
     * frames de TEXTO que carregam áudio em base64 (o cliente manda o áudio de entrada como
     * JSON `realtimeInput.audio.data`, não como frame binário; só a resposta da Gemini vem
     * em binário — sem esse tratamento, cada pedaço de áudio do cliente vira uma linha de log
     * cheia de base64).
     */
    private inline fun logFrame(direction: String, frame: Frame, audioFrameCountBefore: Int, onAudioFrame: () -> Unit) {
        when (frame) {
            is Frame.Text -> {
                val text = String(frame.data, Charsets.UTF_8)
                if (text.contains("\"realtimeInput\"")) {
                    onAudioFrame()
                    val count = audioFrameCountBefore + 1
                    if (count == 1 || count % 50 == 0) {
                        MeganLog.d("[Megan][diag][$direction] $count frames de áudio (texto/realtimeInput) até agora")
                    }
                } else {
                    MeganLog.d("[Megan][diag][$direction] TEXT: ${text.take(500)}")
                }
            }
            is Frame.Binary -> {
                onAudioFrame()
                val count = audioFrameCountBefore + 1
                if (count == 1 || count % 50 == 0) {
                    MeganLog.d("[Megan][diag][$direction] $count frames de áudio binário até agora")
                }
            }
            else -> MeganLog.d("[Megan][diag][$direction] frame tipo=${frame.frameType}")
        }
    }

    private fun buildSetupMessage(systemInstruction: String) = buildJsonObject {
        putJsonObject("setup") {
            put("model", model)
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add("AUDIO") }
                putJsonObject("speechConfig") {
                    putJsonObject("voiceConfig") {
                        putJsonObject("prebuiltVoiceConfig") {
                            put("voiceName", voiceName)
                        }
                    }
                }
            }
            putJsonObject("systemInstruction") {
                putJsonArray("parts") {
                    addJsonObject { put("text", systemInstruction) }
                }
            }
            putJsonObject("inputAudioTranscription") {}
            putJsonObject("outputAudioTranscription") {}
        }
    }
}

/**
 * Acumula a transcrição da conversa (lado do aluno e da Megan) a partir dos frames que a
 * Gemini manda de volta (`serverContent.inputTranscription`/`outputTranscription`, finalizados
 * a cada `turnComplete`) — é o "cache da sessão": quando uma troca de chave acontece no meio da
 * ligação (ver [GeminiLiveBridge.attemptSession]), esse histórico vira a mensagem de "retomada"
 * mandada pra próxima chave, pra Megan continuar a conversa de onde parou em vez de se
 * reapresentar do zero.
 *
 * Métodos são `@Synchronized` porque [absorb] roda na coroutine que lê da Gemini e
 * [buildPrimingMessage] roda ao abrir a próxima tentativa — em threads potencialmente diferentes.
 */
class ConversationHistory {
    private val turns = mutableListOf<Pair<String, String>>()
    private val userBuffer = StringBuilder()
    private val modelBuffer = StringBuilder()

    /** Só guarda as últimas rodadas — o resumo não precisa (nem deve) crescer sem limite. */
    private val maxTurnsToReplay = 24

    @Synchronized
    fun absorb(frame: Frame) {
        if (frame !is Frame.Text) return
        val json = runCatching {
            Json.parseToJsonElement(String(frame.data, Charsets.UTF_8)).jsonObject
        }.getOrNull() ?: return
        val serverContent = json["serverContent"]?.jsonObject ?: return

        serverContent["inputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
            ?.let { userBuffer.append(it) }
        serverContent["outputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
            ?.let { modelBuffer.append(it) }

        if (serverContent["turnComplete"]?.jsonPrimitive?.booleanOrNull == true) {
            if (userBuffer.isNotEmpty()) {
                turns.add("Aluno" to userBuffer.toString())
                userBuffer.clear()
            }
            if (modelBuffer.isNotEmpty()) {
                turns.add("Megan" to modelBuffer.toString())
                modelBuffer.clear()
            }
        }
    }

    /**
     * Mensagem `clientContent` pra mandar assim que uma sessão com a Gemini abre. Na primeira
     * chave tentada (histórico ainda vazio) é só "Hi!", pra Megan puxar assunto normalmente —
     * igual sempre foi. Numa troca de chave no meio da ligação, é um resumo do que já foi dito.
     */
    @Synchronized
    fun buildPrimingMessage(): JsonObject {
        val text = if (turns.isEmpty()) {
            "Hi!"
        } else {
            buildString {
                append(
                    "(A ligação precisou trocar de conexão no meio da conversa — não é uma chamada nova. " +
                        "Aqui está um resumo do que já foi dito até agora; continue a conversa naturalmente " +
                        "a partir daqui, sem se reapresentar nem repetir o que já foi dito:\n",
                )
                for ((role, content) in turns.takeLast(maxTurnsToReplay)) {
                    append(role).append(": ").append(content).append('\n')
                }
                append(')')
            }
        }
        return buildJsonObject {
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
    }
}
