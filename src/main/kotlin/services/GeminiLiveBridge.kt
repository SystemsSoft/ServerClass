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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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

    // Ordem de tentativa a cada NOVA sessão (sempre recomeça do início, do zero): as duas
    // chaves gratuitas primeiro, e só se ambas falharem (erro de conexão, limite de uso
    // atingido, etc — qualquer erro antes da sessão realmente trocar áudio) é que cai pra
    // paga. As chaves gratuitas são opcionais: se não estiverem configuradas, só a paga é
    // usada (comportamento idêntico ao de antes, sem fallback).
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

    // Se nenhum frame trafegar em NENHUMA direção por esse tempo, a sessão é considerada
    // travada (ex: a Gemini parou de responder) e é encerrada proativamente — evita ligação
    // presa em "pensando" pra sempre no app, e evita consumo de API sem geração de valor.
    private val idleTimeoutMillis: Long = System.getProperty("gemini.idleTimeoutMillis")?.toLongOrNull()
        ?: System.getenv("GEMINI_IDLE_TIMEOUT_MILLIS")?.toLongOrNull()
        ?: 45_000L

    /**
     * Abre a sessão upstream com a Gemini Live API e relay bidirecional com [clientSession]
     * até uma das pontas encerrar a conexão. Suspende até a sessão terminar.
     *
     * Tenta as chaves configuradas em ordem (gratuitas primeiro, paga por último). Se uma
     * chave falhar — erro de conexão, limite de uso atingido, ou qualquer outro erro — ANTES
     * de qualquer frame real ter sido trocado, tenta a próxima chave automaticamente, sem o
     * aluno perceber. Uma vez que a sessão já trocou algum frame de verdade com uma chave,
     * essa chave "vence" e não troca mais no meio da conversa (evita reiniciar o contexto da
     * Megan no meio de uma ligação já em andamento). Cada NOVA sessão (nova chamada do aluno)
     * sempre recomeça a tentativa do zero, pela primeira chave da lista.
     */
    suspend fun bridge(clientSession: DefaultWebSocketServerSession, systemInstruction: String) {
        var lastError: Throwable? = null

        for (entry in apiKeys) {
            try {
                val hadActivity = attemptSession(clientSession, systemInstruction, entry)
                if (hadActivity) return
                println("[Megan] Chave '${entry.label}' encerrou sem trocar nenhum frame — tentando próxima chave, se houver.")
            } catch (e: Exception) {
                lastError = e
                println("[Megan] Falha ao conectar com a chave '${entry.label}': ${e.message} — tentando próxima chave, se houver.")
            }
        }

        println("[Megan] Todas as chaves configuradas falharam. Última falha: ${lastError?.message}")
        runCatching {
            clientSession.send(Frame.Text(buildJsonObject {
                put("type", "error")
                put("message", "Não foi possível iniciar a chamada com a Megan agora. Tente novamente em instantes.")
            }.toString()))
        }
        runCatching { clientSession.close() }
    }

    /**
     * Uma tentativa de sessão com uma chave específica. Retorna true se algum frame real foi
     * trocado em qualquer direção (mesmo que a sessão depois tenha caído) — nesse caso não faz
     * sentido tentar outra chave, pois a conversa já começou de verdade com esta.
     */
    private suspend fun attemptSession(
        clientSession: DefaultWebSocketServerSession,
        systemInstruction: String,
        entry: ApiKeyEntry,
    ): Boolean {
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=${entry.key}"
        val lastActivityAt = AtomicLong(System.currentTimeMillis())
        val hadActivity = AtomicBoolean(false)

        client.webSocket(urlString = url) {
            send(Frame.Text(buildSetupMessage(systemInstruction).toString()))
            println("[Megan][diag] setup enviado para a Gemini (chave: ${entry.label})")

            coroutineScope {
                val toGemini = launch {
                    try {
                        var audioFrames = 0
                        for (frame in clientSession.incoming) {
                            lastActivityAt.set(System.currentTimeMillis())
                            hadActivity.set(true)
                            logFrame("cliente->gemini", frame, audioFrames) { audioFrames++ }
                            send(frame)
                        }
                    } catch (e: Exception) {
                        println("[Megan] Conexão do cliente encerrada: ${e.message}")
                    } finally {
                        runCatching { close() }
                    }
                }

                val toClient = launch {
                    try {
                        var audioFrames = 0
                        for (frame in incoming) {
                            lastActivityAt.set(System.currentTimeMillis())
                            hadActivity.set(true)
                            logFrame("gemini->cliente", frame, audioFrames) { audioFrames++ }
                            clientSession.send(frame)
                        }
                    } catch (e: Exception) {
                        println("[Megan] Conexão com a Gemini encerrada: ${e.message}")
                    } finally {
                        runCatching { clientSession.close() }
                    }
                }

                val watchdog = launch {
                    while (isActive) {
                        delay(5_000)
                        val idleFor = System.currentTimeMillis() - lastActivityAt.get()
                        if (idleFor >= idleTimeoutMillis) {
                            println("[Megan] Timeout de inatividade (${idleFor}ms sem nenhum frame em nenhuma direção, chave: ${entry.label}) — encerrando sessão.")
                            runCatching {
                                clientSession.send(Frame.Text(buildJsonObject {
                                    put("type", "error")
                                    put("message", "A Megan demorou demais para responder. Tente novamente.")
                                }.toString()))
                            }
                            runCatching { close(CloseReason(CloseReason.Codes.NORMAL, "Timeout de inatividade")) }
                            runCatching { clientSession.close() }
                            break
                        }
                    }
                }

                toGemini.join()
                toClient.join()
                watchdog.cancel()
            }

            println("[Megan] Sessão com a Gemini encerrada (chave: ${entry.label}), motivo: ${closeReason.await()}")
        }

        return hadActivity.get()
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
                        println("[Megan][diag][$direction] $count frames de áudio (texto/realtimeInput) até agora")
                    }
                } else {
                    println("[Megan][diag][$direction] TEXT: ${text.take(500)}")
                }
            }
            is Frame.Binary -> {
                onAudioFrame()
                val count = audioFrameCountBefore + 1
                if (count == 1 || count % 50 == 0) {
                    println("[Megan][diag][$direction] $count frames de áudio binário até agora")
                }
            }
            else -> println("[Megan][diag][$direction] frame tipo=${frame.frameType}")
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
