package services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import schemas.sentinela.SentinelaRecordingService
import schemas.sentinela.SentinelaTranscriptStatus
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Transcreve o áudio das gravações do Sentinela com FFmpeg + Gemini ([GeminiAudioTranscriber])
 * e grava o texto em `sentinela_recordings.transcript`.
 *
 * Fluxo por gravação: FFmpeg lê o vídeo do S3 (URL pré-assinada) e extrai só o áudio, comprimido
 * (AAC mono 16 kHz) → o áudio sobe para o Gemini → o texto vai para o banco. Roda em background,
 * então a gravação nunca espera pela transcrição.
 *
 * Configuração: `ffmpeg.bin` / `FFMPEG_BIN` (padrão: `ffmpeg` no PATH). A chave e o modelo do
 * Gemini estão documentados em [GeminiAudioTranscriber].
 */
class SentinelaTranscriptionService(
    private val recordingService: SentinelaRecordingService,
    private val transcriber: GeminiAudioTranscriber,
) {

    private companion object {
        const val FFMPEG_TIMEOUT_MINUTES = 30L
        const val LOG_TAIL_CHARS = 1_500
        const val MAX_PARALLEL_JOBS = 3
        const val AUDIO_MIME_TYPE = "audio/aac"

        /** Procura o binário no PATH e nos locais comuns (apps abertos fora do terminal não herdam o PATH do shell). */
        fun resolveBinary(name: String, override: String?): String {
            if (override != null) return override
            val dirs = System.getenv("PATH").orEmpty().split(File.pathSeparator) +
                listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")
            return dirs.filter { it.isNotBlank() }
                .map { File(it, name) }
                .firstOrNull { it.canExecute() }
                ?.path
                ?: name
        }
    }

    private class ProcessResult(val exitCode: Int, val output: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** O trabalho pesado roda no Google; o limite só evita rajadas de FFmpeg/uploads simultâneos. */
    private val gate = Semaphore(MAX_PARALLEL_JOBS)

    private val runningProcesses = ConcurrentHashMap.newKeySet<Process>()

    private val ffmpegBin = resolveBinary(
        "ffmpeg",
        (System.getProperty("ffmpeg.bin") ?: System.getenv("FFMPEG_BIN"))?.takeIf { it.isNotBlank() },
    )

    init {
        // Sem isto, reiniciar o servidor (deploy) deixaria o FFmpeg órfão rodando.
        Runtime.getRuntime().addShutdownHook(Thread { runningProcesses.forEach { it.destroyForcibly() } })
        checkFfmpeg()
    }

    /** Avisa no log, logo ao subir, se o FFmpeg não roda: sem ele nenhuma transcrição funciona. */
    private fun checkFfmpeg() {
        scope.launch {
            val ok = runCatching {
                val process = ProcessBuilder(ffmpegBin, "-version").redirectErrorStream(true).start()
                process.inputStream.readBytes()
                process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0
            }.getOrDefault(false)
            if (ok) println("[Sentinela] FFmpeg disponível ($ffmpegBin)")
            else println("[Sentinela] ATENÇÃO: FFmpeg não encontrado ($ffmpegBin). As transcrições vão falhar até ele ser instalado.")
        }
    }

    /** Enfileira a transcrição do vídeo já salvo no S3. Não bloqueia nem falha quem chama. */
    fun enqueue(recordingId: String, s3Key: String) {
        scope.launch {
            runCatching { recordingService.setTranscriptStatus(recordingId, SentinelaTranscriptStatus.PENDING) }
            gate.withPermit { transcribe(recordingId, s3Key) }
        }
    }

    /** Retoma o que ficou PENDING/PROCESSING quando o processo anterior caiu. Chamar uma vez na inicialização. */
    fun resumePending() {
        scope.launch {
            val pending = runCatching { recordingService.findPendingTranscriptions() }
                .onFailure { println("[Sentinela] Falha ao buscar transcrições pendentes: ${it.message}") }
                .getOrNull()
                .orEmpty()
            if (pending.isNotEmpty()) println("[Sentinela] Retomando ${pending.size} transcrição(ões) pendente(s)")
            pending.forEach { enqueue(it.recordingId, it.s3Key) }
        }
    }

    private suspend fun transcribe(recordingId: String, s3Key: String) {
        val workDir = withContext(Dispatchers.IO) { Files.createTempDirectory("sentinela-transcribe-").toFile() }
        try {
            recordingService.setTranscriptStatus(recordingId, SentinelaTranscriptStatus.PROCESSING)
            println("[Sentinela] Transcrevendo gravação $recordingId")

            val audio = File(workDir, "audio.aac")
            val videoUrl = SentinelaS3Uploader.presignedGetUrl(s3Key)
            val extract = runCommand(
                command = listOf(
                    ffmpegBin, "-nostdin", "-y", "-loglevel", "error",
                    "-i", videoUrl,
                    "-map", "0:a:0", "-vn", "-ac", "1", "-ar", "16000", "-c:a", "aac", "-b:a", "32k", "-f", "adts",
                    audio.path,
                ),
                workDir = workDir,
                timeoutMinutes = FFMPEG_TIMEOUT_MINUTES,
            )
            val noAudio = extract.output.contains("matches no streams") || (extract.exitCode == 0 && audio.length() == 0L)
            if (noAudio) {
                recordingService.setTranscriptStatus(recordingId, SentinelaTranscriptStatus.NO_AUDIO)
                println("[Sentinela] Gravação $recordingId sem trilha de áudio, nada a transcrever")
                return
            }
            if (extract.exitCode != 0) error("FFmpeg falhou (${extract.exitCode}): ${extract.output.replace(videoUrl, "<s3-url>")}")

            val result = transcriber.transcribe(audio, AUDIO_MIME_TYPE)
            if (result.truncated) println("[Sentinela] Transcrição da gravação $recordingId foi cortada pelo limite de saída do modelo")
            recordingService.saveTranscript(recordingId, result.text)
            println("[Sentinela] Transcrição da gravação $recordingId concluída (${result.text.length} caracteres)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("[Sentinela] Transcrição da gravação $recordingId falhou: ${e.message}")
            runCatching { recordingService.setTranscriptStatus(recordingId, SentinelaTranscriptStatus.FAILED) }
        } finally {
            withContext(Dispatchers.IO) { workDir.deleteRecursively() }
        }
    }

    /** Executa o comando esperando o fim (ou o timeout); stdout+stderr vão para arquivo para não travar o pipe. */
    private suspend fun runCommand(command: List<String>, workDir: File, timeoutMinutes: Long): ProcessResult =
        withContext(Dispatchers.IO) {
            val log = File.createTempFile("process", ".log", workDir)
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start()
            runningProcesses += process
            try {
                if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                    process.destroyForcibly()
                    error("${File(command.first()).name} excedeu $timeoutMinutes min")
                }
                ProcessResult(process.exitValue(), log.readText().takeLast(LOG_TAIL_CHARS).trim())
            } finally {
                runningProcesses -= process
                if (process.isAlive) process.destroyForcibly()
            }
        }
}
