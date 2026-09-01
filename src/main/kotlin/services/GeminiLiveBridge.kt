package services

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Ponte entre a sessão WebSocket do cliente (app do aluno) e a Gemini Live API.
 * A chave real da API nunca é exposta ao cliente: fica só neste processo, via variável de ambiente.
 *
 * Nomes de modelo/campos do protocolo Live API mudam com frequência — confira a documentação
 * oficial do Gemini Live API antes de colocar em produção.
 */
class GeminiLiveBridge {

    // 'by lazy': só falha quando a primeira chamada realmente precisar da chave, não na
    // inicialização do servidor (a Megan é opcional e pode não estar configurada ainda).
    // Busca em: system property (arquivo local gemini-credentials.properties em dev) ou
    // variável de ambiente (Heroku Config Vars em produção).
    private val apiKey: String by lazy {
        System.getProperty("gemini.apiKey")
            ?: System.getenv("GEMINI_API_KEY")
            ?: error("GEMINI_API_KEY não configurada (system property gemini.apiKey ou variável de ambiente)")
    }

    private val model: String = System.getProperty("gemini.liveModel")
        ?: System.getenv("GEMINI_LIVE_MODEL")
        ?: "models/gemini-2.5-flash-native-audio-latest"

    private val voiceName: String = System.getProperty("gemini.liveVoice")
        ?: System.getenv("GEMINI_LIVE_VOICE")
        ?: "Aoede"

    private val client = HttpClient(CIO) {
        install(WebSockets)
    }

    /**
     * Abre a sessão upstream com a Gemini Live API e relay bidirecional com [clientSession]
     * até uma das pontas encerrar a conexão. Suspende até a sessão terminar.
     */
    suspend fun bridge(clientSession: DefaultWebSocketServerSession, systemInstruction: String) {
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$apiKey"

        client.webSocket(urlString = url) {
            send(Frame.Text(buildSetupMessage(systemInstruction).toString()))

            coroutineScope {
                val toGemini = launch {
                    try {
                        for (frame in clientSession.incoming) {
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
                        for (frame in incoming) {
                            clientSession.send(frame)
                        }
                    } catch (e: Exception) {
                        println("[Megan] Conexão com a Gemini encerrada: ${e.message}")
                    } finally {
                        runCatching { clientSession.close() }
                    }
                }

                toGemini.join()
                toClient.join()
            }

            println("[Megan] Sessão com a Gemini encerrada, motivo: ${closeReason.await()}")
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
