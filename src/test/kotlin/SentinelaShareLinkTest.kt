import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import routes.sentinela.sentinelaPublicVideoRouting
import schemas.sentinela.SentinelaRecordingService
import schemas.sentinela.SentinelaRecordingService.SentinelaRecordingTable
import schemas.sentinela.SentinelaRecordingStatus
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SentinelaShareLinkTest {

    private fun newService(): SentinelaRecordingService =
        SentinelaRecordingService(Database.connect("jdbc:h2:mem:${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1", "org.h2.Driver"))

    private suspend fun SentinelaRecordingService.recordingWithVideo(): String {
        val id = create("uid-1", "video/webm")
        finalize(id, SentinelaRecordingStatus.COMPLETED, "sentinela/uid-1/$id.webm", 10, 1)
        return id
    }

    @Test
    fun `reuses the same link while it is valid`() = runBlocking {
        val service = newService()
        val id = service.recordingWithVideo()

        val first = service.ensureShareLink(id, Duration.ofDays(7))
        val second = service.ensureShareLink(id, Duration.ofDays(7))

        assertEquals(first, second)
        assertTrue(first.token.length >= 40)
        assertTrue(Instant.parse(first.expiresAt).isAfter(Instant.now().plus(Duration.ofDays(6))))
    }

    @Test
    fun `finds the video by token and issues a new token after expiry`() = runBlocking {
        val service = newService()
        val id = service.recordingWithVideo()
        val link = service.ensureShareLink(id, Duration.ofDays(7))

        assertEquals("sentinela/uid-1/$id.webm", service.findSharedVideo(link.token)?.s3Key)
        assertNull(service.findSharedVideo("token-que-nao-existe-de-jeito-nenhum"))

        transaction {
            SentinelaRecordingTable.update(where = { SentinelaRecordingTable.id eq id }) {
                it[shareExpiresAt] = Instant.now().minusSeconds(60).toString()
            }
        }
        assertNull(service.findSharedVideo(link.token))
        assertNotEquals(link.token, service.ensureShareLink(id, Duration.ofDays(7)).token)
    }

    @Test
    fun `public page answers 404 with privacy headers for an unknown link`() = testApplication {
        val service = newService()
        application { sentinelaPublicVideoRouting(service) }

        val response = client.get("/sentinela/v/${"a".repeat(43)}")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("no-store", response.headers["Cache-Control"])
        assertEquals("no-referrer", response.headers["Referrer-Policy"])
        assertNotNull(response.headers["X-Robots-Tag"])
    }
}
