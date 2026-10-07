import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import schemas.sentinela.SentinelaRecordingService
import schemas.sentinela.SentinelaRecordingService.SentinelaRecordingTable
import schemas.sentinela.SentinelaRecordingStatus
import schemas.sentinela.SentinelaTranscriptStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SentinelaTranscriptTest {

    private fun newService(): SentinelaRecordingService =
        SentinelaRecordingService(Database.connect("jdbc:h2:mem:${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1", "org.h2.Driver"))

    @Test
    fun `saves transcript and marks it done`() = runBlocking {
        val service = newService()
        val id = service.create("uid-1", "video/webm")
        service.finalize(id, SentinelaRecordingStatus.COMPLETED, "sentinela/uid-1/$id.webm", 10, 1)

        service.saveTranscript(id, "Estou sendo abordado, enviem ajuda.")

        val row = transaction { SentinelaRecordingTable.selectAll().single() }
        assertEquals("Estou sendo abordado, enviem ajuda.", row[SentinelaRecordingTable.transcript])
        assertEquals(SentinelaTranscriptStatus.DONE.name, row[SentinelaRecordingTable.transcriptStatus])
        // listagem continua funcionando sem carregar o texto; o detalhe traz o texto e o status
        assertEquals(id, service.listByUid("uid-1").single().id)
        val detail = service.findById(id)
        assertEquals(id, detail?.summary?.id)
        assertEquals("Estou sendo abordado, enviem ajuda.", detail?.transcript)
        assertEquals(SentinelaTranscriptStatus.DONE.name, detail?.transcriptStatus)
    }

    @Test
    fun `finds only unfinished transcriptions that have a video`() = runBlocking {
        val service = newService()
        val pending = service.create("uid-1", "video/webm")
        val processing = service.create("uid-1", "video/webm")
        val done = service.create("uid-1", "video/webm")
        val never = service.create("uid-1", "video/webm")
        val noVideo = service.create("uid-1", "video/webm")
        for (id in listOf(pending, processing, done, never)) {
            service.finalize(id, SentinelaRecordingStatus.COMPLETED, "sentinela/uid-1/$id.webm", 10, 1)
        }
        service.finalize(noVideo, SentinelaRecordingStatus.INTERRUPTED, null, 0, 0)
        service.setTranscriptStatus(pending, SentinelaTranscriptStatus.PENDING)
        service.setTranscriptStatus(processing, SentinelaTranscriptStatus.PROCESSING)
        service.saveTranscript(done, "ok")
        service.setTranscriptStatus(noVideo, SentinelaTranscriptStatus.PENDING)

        assertEquals(setOf(pending, processing), service.findPendingTranscriptions().map { it.recordingId }.toSet())
        assertNull(transaction { SentinelaRecordingTable.selectAll().first { it[SentinelaRecordingTable.id] == never } }[SentinelaRecordingTable.transcriptStatus])
    }

    @Test
    fun `requesting a transcription only starts it once and never overrides finished or running ones`() = runBlocking {
        val service = newService()
        val fresh = service.create("uid-1", "video/webm")
        val failed = service.create("uid-1", "video/webm")
        val processing = service.create("uid-1", "video/webm")
        val done = service.create("uid-1", "video/webm")
        service.setTranscriptStatus(failed, SentinelaTranscriptStatus.FAILED)
        service.setTranscriptStatus(processing, SentinelaTranscriptStatus.PROCESSING)
        service.saveTranscript(done, "pronto")

        assertEquals(true, service.requestTranscription(fresh))
        assertEquals(false, service.requestTranscription(fresh)) // segundo clique: já está PENDING
        assertEquals(true, service.requestTranscription(failed)) // falhou antes: tenta de novo
        assertEquals(false, service.requestTranscription(processing))
        assertEquals(false, service.requestTranscription(done))
        assertEquals("pronto", service.findById(done)?.transcript)
    }
}
