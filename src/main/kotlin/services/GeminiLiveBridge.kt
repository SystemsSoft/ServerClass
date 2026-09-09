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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.put
import java.util.Base64
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

    // Conta a partir do instante em que existe uma "pergunta pendente" pra Gemini responder —
    // o aluno acabou de terminar de falar (VAD por energia, ver [ClientSpeechDetector]) OU o
    // próprio servidor acabou de mandar a saudação/retomada (ver [attemptSession]) — e não do
    // simples fato de não ter chegado nenhum frame da Gemini. Sem essa distinção, o watchdog
    // trocava de chave mesmo com o aluno em silêncio normal (ele não tinha dito nada, a Gemini
    // não tinha nada pra responder — não é "travada"), que foi exatamente o relato do usuário:
    // às vezes a troca acontecia por lentidão real da Gemini, às vezes só porque o aluno ainda
    // não tinha falado. Se o aluno continua mandando áudio normalmente (ligação viva) mas a
    // GEMINI demora mais que esse tempo pra responder a algo que já foi dito, consideramos a
    // chave atual "lenta/travada" e trocamos pra próxima da lista (gratuita-1 -> gratuita-2 ->
    // paga), sem derrubar o cliente e sem perder o histórico da conversa.
    //
    // 22s (não 9s, valor inicial que usamos e que se mostrou baixo demais): logs reais de
    // produção (08/09/2026) mostraram a Gemini demorando ROTINEIRAMENTE entre 9 e 11s pra
    // responder mesmo quando está tudo normal — com o limiar em 9s, isso disparava a troca de
    // chave em praticamente toda resposta um pouco mais lenta, esgotando as 3 chaves em
    // sequência e forçando o app a reconectar do zero (o loop relatado pelo usuário). 22s dá
    // margem real acima dessa faixa observada; ainda assim é só uma estimativa — reavaliar se
    // aparecerem trocas de chave nos logs com valores bem acima de ~11s (sinal de que 22s
    // ainda está curto) ou se a Megan ficar "pensando" por mais de 22s sem nenhuma troca
    // acontecer (sinal de que dá pra baixar).
    private val responseTimeoutMillis: Long = System.getProperty("gemini.responseTimeoutMillis")?.toLongOrNull()
        ?: System.getenv("GEMINI_RESPONSE_TIMEOUT_MILLIS")?.toLongOrNull()
        ?: 22_000L

    // A Gemini não tem noção de tempo real decorrido — pedir "por volta dos X minutos" no
    // system instruction (ver MeganPersona.kt) é só uma referência narrativa que ela tenta
    // seguir contando trocas de fala, não um relógio de verdade; numa conversa mais compacta
    // ela pode se despedir bem antes do tempo real pretendido. Por isso o AVISO de despedida
    // é decidido aqui pelo relógio real do servidor (contado desde o início da LIGAÇÃO — ver
    // [bridge] — não desde cada tentativa de chave) e mandado pra Gemini como uma mensagem
    // (ver [attemptSession]), do mesmo jeito que já fazemos com a saudação inicial ("Hi!") e o
    // resumo de retomada após troca de chave.
    private val windDownAfterMillis: Long = System.getProperty("gemini.windDownAfterMillis")?.toLongOrNull()
        ?: System.getenv("GEMINI_WIND_DOWN_AFTER_MILLIS")?.toLongOrNull()
        ?: 7 * 60_000L

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
        // Início real da LIGAÇÃO (não de cada tentativa de chave) — é a partir daqui que o
        // aviso de despedida (ver [windDownAfterMillis]) conta o tempo, pra uma troca de chave
        // no meio da ligação não reiniciar o relógio nem duplicar o aviso.
        val callStartedAt = System.currentTimeMillis()
        val windDownSent = AtomicBoolean(false)

        while (keyIndex < apiKeys.size) {
            val entry = apiKeys[keyIndex]
            val outcome = try {
                attemptSession(clientSession, systemInstruction, entry, history, callStartedAt, windDownSent)
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
        callStartedAt: Long,
        windDownSent: AtomicBoolean,
    ): SessionOutcome {
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=${entry.key}"
        val lastFromClientAt = AtomicLong(System.currentTimeMillis())
        val lastFromGeminiAt = AtomicLong(System.currentTimeMillis())
        val clientDisconnected = AtomicBoolean(false)

        // 0L = não há nenhuma "pergunta pendente" no momento (aluno em silêncio normal, ou a
        // Gemini já respondeu tudo que foi dito) — só != 0L enquanto existir algo que a Gemini
        // ainda não respondeu (ver comentário de [responseTimeoutMillis] e [ClientSpeechDetector]
        // logo abaixo).
        val awaitingGeminiResponseSince = AtomicLong(0L)
        val speechDetector = ClientSpeechDetector()

        // Numa troca de chave no meio da ligação, o [systemInstruction] original (ver
        // MeganPersona.kt) ainda manda a Megan "estruturar a ligação" com saudação +
        // explicação de abertura — isso tem prioridade MAIOR que um simples turno de usuário
        // com o resumo do histórico, então sem isso aqui embaixo a Megan reiniciava a ligação
        // do zero mesmo recebendo o resumo (foi exatamente o que o teste real mostrou: o
        // histórico ia junto, mas a persona insistia em reabrir a chamada de qualquer jeito).
        val effectiveInstruction = if (history.hasContent()) {
            systemInstruction + "\n\n" +
                "IMPORTANT — this is a mid-call reconnect, not a new call: you already greeted " +
                "the student and started the lesson before a brief technical hiccup. The very " +
                "next user message is a summary of everything said so far in this call. Do NOT " +
                "greet the student again, do NOT restart your opening explanation, do NOT " +
                "introduce today's topic again as if it were new — just continue the " +
                "conversation naturally from exactly where that summary leaves off."
        } else {
            systemInstruction
        }

        client.webSocket(urlString = url) {
            send(Frame.Text(buildSetupMessage(effectiveInstruction).toString()))
            MeganLog.d("[Megan][diag] setup enviado para a Gemini (chave: ${entry.label}, retomando histórico: ${history.hasContent()})")

            // Primeira vez da ligação: "Hi!" (a Megan puxa assunto sozinha). Depois de uma
            // troca de chave no meio da conversa: um resumo do que já foi dito, pra ela
            // continuar de onde parou em vez de se reapresentar como se fosse uma ligação nova.
            send(Frame.Text(history.buildPrimingMessage().toString()))
            // Essa mensagem em si já é uma "pergunta pendente": o servidor está esperando a
            // Gemini reagir (saudação inicial ou retomada) — conta como início do timeout de
            // resposta, do mesmo jeito que o fim de uma fala do aluno conta (ver abaixo).
            awaitingGeminiResponseSince.set(System.currentTimeMillis())

            coroutineScope {
                val toGemini = launch {
                    try {
                        var audioFrames = 0
                        for (frame in clientSession.incoming) {
                            lastFromClientAt.set(System.currentTimeMillis())
                            logFrame("cliente->gemini", frame, audioFrames) { audioFrames++ }
                            extractClientAudioPcm(frame)?.let { pcm ->
                                if (speechDetector.onAudioChunk(pcm)) {
                                    // Transição falando -> silêncio sustentado: o aluno acabou
                                    // de terminar de falar/perguntar algo — só AGORA existe uma
                                    // resposta pendente da Gemini pra valer (ver [responseTimeoutMillis]).
                                    awaitingGeminiResponseSince.set(System.currentTimeMillis())
                                    MeganLog.d(
                                        "[Megan][vad] Aluno terminou de falar (chave: ${entry.label}) — " +
                                            "aguardando resposta da Gemini a partir de agora."
                                    )
                                }
                            }
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
                            if (frameIsGeminiResponseContent(frame)) {
                                // A Gemini respondeu de verdade (áudio, transcrição da própria
                                // fala dela, ou fim de turno) — a "pergunta pendente" foi
                                // atendida, zera o timeout de resposta. Não conta a transcrição
                                // do que o ALUNO disse (inputTranscription): isso só confirma
                                // que a Gemini ouviu, não que ela respondeu.
                                awaitingGeminiResponseSince.set(0L)
                            }
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
                        val awaitingSince = awaitingGeminiResponseSince.get()
                        // Só existe demora "de verdade" a medir enquanto houver uma pergunta
                        // pendente (aluno acabou de falar, ou o servidor acabou de mandar a
                        // saudação/retomada) — ver comentário de [responseTimeoutMillis].
                        // awaitingSince == 0L quer dizer "aluno em silêncio normal, nada a
                        // responder" e nunca deve, por si só, disparar troca de chave.
                        val aguardandoRespostaPendente = awaitingSince != 0L
                        val demoraDaRespostaPendente = if (aguardandoRespostaPendente) now - awaitingSince else 0L

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

                        if (aguardandoRespostaPendente && demoraDaRespostaPendente >= responseTimeoutMillis) {
                            MeganLog.d(
                                "[Megan] Gemini sem responder há ${demoraDaRespostaPendente}ms desde que o aluno " +
                                    "falou/perguntou algo (chave: ${entry.label}) — trocando de chave sem " +
                                    "derrubar a ligação."
                            )
                            runCatching { close(CloseReason(CloseReason.Codes.NORMAL, "Resposta lenta — trocando de chave")) }
                            toGemini.cancel()
                            break
                        }
                    }
                }

                // Avisa a Megan pra começar a se despedir quando o tempo REAL da ligação (não
                // a percepção dela) atingir [windDownAfterMillis] — ver o comentário de lá.
                // `callStartedAt` vem de [bridge], não muda numa troca de chave, então uma
                // troca no meio da ligação não adia nem duplica o aviso: se ele já foi mandado
                // numa tentativa anterior, [windDownSent] garante que essa tentativa não manda
                // de novo; se ainda não foi, o delay é recalculado com o tempo real já passado.
                val windDownNudge = launch {
                    if (windDownSent.get()) return@launch
                    val remaining = windDownAfterMillis - (System.currentTimeMillis() - callStartedAt)
                    if (remaining > 0) delay(remaining)
                    if (windDownSent.compareAndSet(false, true)) {
                        MeganLog.d(
                            "[Megan] Marca real de ${windDownAfterMillis / 60_000} min atingida (chave: " +
                                "${entry.label}) — avisando a Megan pra começar a se despedir."
                        )
                        runCatching {
                            send(Frame.Text(buildJsonObject {
                                putJsonObject("clientContent") {
                                    putJsonArray("turns") {
                                        addJsonObject {
                                            put("role", "user")
                                            putJsonArray("parts") {
                                                addJsonObject {
                                                    put(
                                                        "text",
                                                        "(System note — not something the student said: this call has " +
                                                            "now really reached its wind-down point. Start warmly " +
                                                            "wrapping up the call now, as instructed in your system " +
                                                            "prompt — do not wait any longer.)",
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    put("turnComplete", true)
                                }
                            }.toString()))
                        }
                    }
                }

                toGemini.join()
                toClient.join()
                watchdog.cancel()
                windDownNudge.cancel()
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

    /** Extrai o PCM16 cru de um frame `realtimeInput.audio.data` (áudio do cliente/aluno), ou
     *  `null` se o frame não for esse tipo — usado pelo [ClientSpeechDetector] no watchdog de
     *  resposta (ver [responseTimeoutMillis]). */
    private fun extractClientAudioPcm(frame: Frame): ByteArray? {
        if (frame !is Frame.Text) return null
        val text = String(frame.data, Charsets.UTF_8)
        if (!text.contains("\"realtimeInput\"")) return null
        val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val base64 = json["realtimeInput"]?.jsonObject
            ?.get("audio")?.jsonObject
            ?.get("data")?.jsonPrimitive?.contentOrNull ?: return null
        return runCatching { Base64.getDecoder().decode(base64) }.getOrNull()
    }

    /** true só quando o frame da GEMINI carrega uma resposta de verdade — áudio da Megan
     *  (binário, ou `modelTurn.parts` em texto), a transcrição da própria fala dela
     *  (`outputTranscription`), ou fim de turno (`turnComplete`). Propositalmente NÃO conta
     *  `inputTranscription` (transcrição do que o ALUNO disse): isso só confirma que a Gemini
     *  ouviu, não que ela respondeu — ver uso em [attemptSession]. */
    private fun frameIsGeminiResponseContent(frame: Frame): Boolean {
        if (frame is Frame.Binary) return true
        if (frame !is Frame.Text) return false
        val json = runCatching { Json.parseToJsonElement(String(frame.data, Charsets.UTF_8)).jsonObject }.getOrNull()
            ?: return false
        val serverContent = json["serverContent"]?.jsonObject ?: return false

        val temTranscricaoDaMegan = serverContent["outputTranscription"]?.jsonObject
            ?.get("text")?.jsonPrimitive?.contentOrNull?.isNotEmpty() == true
        val temAudioDaMegan = serverContent["modelTurn"]?.jsonObject
            ?.get("parts")?.jsonArray?.isNotEmpty() == true
        val turnComplete = serverContent["turnComplete"]?.jsonPrimitive?.booleanOrNull == true

        return temTranscricaoDaMegan || temAudioDaMegan || turnComplete
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
 * Réplica, do lado do servidor, do VAD (voice activity detection) por energia RMS que já roda
 * no cliente (ver `pcm-worklet.js` no app) — mesmos limiares, sobre os mesmos chunks PCM16
 * (~100ms) que o cliente manda como `realtimeInput.audio.data`. Existe pra dar ao watchdog de
 * [GeminiLiveBridge.attemptSession] um jeito de saber quando o aluno REALMENTE terminou de
 * falar/perguntar algo, em vez de só olhar "chegou algum frame do cliente" — o microfone manda
 * áudio (inclusive silêncio) o tempo todo, então a mera chegada de frames nunca serviu pra
 * distinguir "aluno esperando resposta" de "aluno quieto, nada a responder". Roda inteiramente
 * no processo do servidor: não depende de nenhuma mudança no app.
 *
 * Uma instância por tentativa de sessão ([ClientSpeechDetector] não é `@Synchronized`: só é
 * chamada a partir da coroutine `toGemini`, nunca concorrentemente).
 */
private class ClientSpeechDetector {
    private val silenceThreshold = 0.02
    private val silenceChunksToEnd = 5
    private var silentChunkCount = 0
    private var speaking = false

    /** Processa um chunk de áudio do cliente (PCM16 little-endian); retorna `true` só no exato
     *  instante em que a fala termina (transição falando -> silêncio sustentado). */
    fun onAudioChunk(pcm16: ByteArray): Boolean {
        val sampleCount = pcm16.size / 2
        if (sampleCount == 0) return false

        var sumSquares = 0.0
        for (i in 0 until sampleCount) {
            val lo = pcm16[i * 2].toInt() and 0xFF
            val hi = pcm16[i * 2 + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toDouble() / 32768.0
            sumSquares += sample * sample
        }
        val rms = Math.sqrt(sumSquares / sampleCount)

        if (rms < silenceThreshold) {
            silentChunkCount++
            if (speaking && silentChunkCount >= silenceChunksToEnd) {
                speaking = false
                return true
            }
        } else {
            silentChunkCount = 0
            speaking = true
        }
        return false
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

    /** true assim que existir QUALQUER conteúdo de transcrição acumulado — rodada fechada
     *  (`turns`) OU só o que já chegou da rodada em andamento (`userBuffer`/`modelBuffer`).
     *  Importante não depender só de `turnComplete` ter disparado: numa conversa de voz
     *  contínua/interruptível a Gemini pode levar muito tempo (ou nunca, se a chave trocar no
     *  meio da fala) pra mandar esse sinal — sem isso, uma ligação de vários minutos podia
     *  virar "retomando histórico: false" mesmo já tendo bastante conversa acumulada nos
     *  buffers ainda não fechados. Usado pra decidir entre persona normal e persona de
     *  retomada — ver [GeminiLiveBridge.attemptSession]. */
    @Synchronized
    fun hasContent(): Boolean = turns.isNotEmpty() || userBuffer.isNotEmpty() || modelBuffer.isNotEmpty()

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
     * igual sempre foi. Numa troca de chave no meio da ligação, é um resumo do que já foi dito
     * — incluindo o que ainda estava "em andamento" (sem `turnComplete`) no momento da troca,
     * não só as rodadas já fechadas, pra não perder o fim da conversa numa troca no meio da fala.
     */
    @Synchronized
    fun buildPrimingMessage(): JsonObject {
        val text = if (!hasContent()) {
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
                // Conteúdo que ainda não tinha fechado turno quando a troca aconteceu — sem
                // isso, o finalzinho da conversa (bem o que motivou a lentidão, às vezes) se
                // perdia mesmo com o resto do histórico presente.
                if (userBuffer.isNotEmpty()) append("Aluno (em andamento): ").append(userBuffer).append('\n')
                if (modelBuffer.isNotEmpty()) append("Megan (em andamento): ").append(modelBuffer).append('\n')
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
