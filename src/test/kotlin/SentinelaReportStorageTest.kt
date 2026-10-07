import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import schemas.sentinela.SentinelaRecordingService
import schemas.sentinela.SentinelaRecordingStatus
import schemas.sentinela.SentinelaStoredReport
import schemas.sentinela.isReusable
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SentinelaReportStorageTest {

    private val now = Instant.parse("2026-10-01T12:00:00Z")

    private fun report(
        hasTranscript: Boolean = true,
        linkExpiresAt: Instant? = now.plus(Duration.ofDays(3)),
    ) = SentinelaStoredReport(
        s3Key = "sentinela/uid-1/rec-relatorio.pdf",
        generatedAt = now.minus(Duration.ofHours(1)),
        hasTranscript = hasTranscript,
        linkExpiresAt = linkExpiresAt,
    )

    private fun newService() =
        SentinelaRecordingService(Database.connect("jdbc:h2:mem:${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1", "org.h2.Driver"))

    // ── quando reaproveitar ──────────────────────────────────────────────────

    @Test
    fun `a complete report with a valid link is reused`() {
        assertTrue(report().isReusable(transcriptReadyNow = true, now = now))
    }

    @Test
    fun `the report is regenerated once its qr link has expired`() {
        assertFalse(report(linkExpiresAt = now.minusSeconds(1)).isReusable(transcriptReadyNow = true, now = now))
        assertFalse(report(linkExpiresAt = now).isReusable(transcriptReadyNow = true, now = now))
    }

    @Test
    fun `a report made before the transcript existed is regenerated when it becomes available`() {
        val withoutTranscript = report(hasTranscript = false)
        assertFalse(withoutTranscript.isReusable(transcriptReadyNow = true, now = now))
        // enquanto a transcrição não existe (ou nunca existirá), o mesmo PDF continua valendo
        assertTrue(withoutTranscript.isReusable(transcriptReadyNow = false, now = now))
    }

    @Test
    fun `a report without qr code never expires by link`() {
        assertTrue(report(linkExpiresAt = null).isReusable(transcriptReadyNow = true, now = now.plus(Duration.ofDays(400))))
    }

    // ── persistência ─────────────────────────────────────────────────────────

    @Test
    fun `a saved report is found again, flagged in the list, and can be replaced`() = runBlocking {
        val service = newService()
        val id = service.create("uid-1", "video/webm")
        service.finalize(id, SentinelaRecordingStatus.COMPLETED, "sentinela/uid-1/$id.webm", 10, 1)

        assertNull(service.findById(id)?.report)
        assertFalse(service.listByUid("uid-1").single().hasReport)

        service.saveReport(id, report(hasTranscript = false))
        val first = service.findById(id)?.report
        assertEquals("sentinela/uid-1/rec-relatorio.pdf", first?.s3Key)
        assertEquals(false, first?.hasTranscript)
        assertEquals(now.plus(Duration.ofDays(3)), first?.linkExpiresAt)
        assertTrue(service.listByUid("uid-1").single().hasReport)
        assertTrue(service.findById(id)?.summary?.hasReport == true)

        // gerar de novo (ex.: a transcrição ficou pronta) substitui o anterior
        service.saveReport(id, report(hasTranscript = true, linkExpiresAt = null))
        val second = service.findById(id)?.report
        assertEquals(true, second?.hasTranscript)
        assertNull(second?.linkExpiresAt)
    }

    @Test
    fun `the shared-account report is stored apart from the owner's`() = runBlocking {
        val service = newService()
        val id = service.create("uid-1", "video/webm")
        service.finalize(id, SentinelaRecordingStatus.COMPLETED, "sentinela/uid-1/$id.webm", 10, 1)

        service.saveReport(id, report().copy(s3Key = "sentinela/uid-1/rec-relatorio-compartilhado.pdf", linkExpiresAt = null), forOwner = false)

        val recording = service.findById(id)
        assertNull(recording?.report)
        assertEquals("sentinela/uid-1/rec-relatorio-compartilhado.pdf", recording?.sharedReport?.s3Key)
        assertNull(recording?.sharedReport?.linkExpiresAt)
        // cada um vê na lista o selo do próprio relatório
        assertFalse(service.listByUid("uid-1").single().hasReport)
        assertTrue(service.listByUid("uid-1", viewerIsOwner = false).single().hasReport)

        // o relatório do dono não substitui o do compartilhamento
        service.saveReport(id, report())
        assertEquals("sentinela/uid-1/rec-relatorio.pdf", service.findById(id)?.report?.s3Key)
        assertEquals("sentinela/uid-1/rec-relatorio-compartilhado.pdf", service.findById(id)?.sharedReport?.s3Key)
    }
}
