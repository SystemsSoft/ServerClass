import com.lowagie.text.pdf.PdfReader
import com.lowagie.text.pdf.parser.PdfTextExtractor
import schemas.sentinela.SentinelaLocationDto
import schemas.sentinela.SentinelaRecordingSummaryDto
import schemas.sentinela.SentinelaRecordingWithLocations
import services.MapTileSource
import services.SentinelaRecordingMetrics
import services.SentinelaReportPdf
import services.SentinelaRouteMap
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SentinelaReportPdfTest {

    /** Tiles falsas: cor lisa, sem rede. */
    private val fakeTiles = MapTileSource { _, _, _ ->
        BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB).also { image ->
            image.createGraphics().apply { color = Color(0xCF, 0xE8, 0xD5); fillRect(0, 0, 256, 256); dispose() }
        }
    }

    private fun point(lat: Double, lng: Double, at: String = "2026-09-28T14:00:00Z") =
        SentinelaLocationDto(lat = lat, lng = lng, accuracy = 5.0, speed = 10.0, capturedAt = at)

    private fun recording(
        locations: List<SentinelaLocationDto>,
        transcript: String? = null,
        transcriptStatus: String? = null,
        s3Key: String? = "sentinela/uid/rec.webm",
    ) = SentinelaRecordingWithLocations(
        ownerUid = "uid",
        summary = SentinelaRecordingSummaryDto(
            id = "rec-1234",
            status = "COMPLETED",
            mimeType = "video/webm",
            sizeBytes = 5_452_595,
            chunkCount = 10,
            startedAt = "2026-09-28T14:00:00Z",
            endedAt = "2026-09-28T14:12:24Z",
            hasVideo = s3Key != null,
            startLocation = locations.firstOrNull(),
        ),
        s3Key = s3Key,
        locations = locations,
        transcript = transcript,
        transcriptStatus = transcriptStatus,
    )

    private fun text(pdf: ByteArray): String {
        val reader = PdfReader(pdf)
        return (1..reader.numberOfPages).joinToString("\n") { PdfTextExtractor(reader).getTextFromPage(it) }
    }

    private val route = listOf(
        point(-23.5614, -46.6559), point(-23.5630, -46.6525), point(-23.5648, -46.6490), point(-23.5670, -46.6440),
    )

    @Test
    fun `distance and average speed follow the route`() {
        // 1 grau de latitude ≈ 111,19 km
        val km = SentinelaRecordingMetrics.distanceKm(listOf(point(0.0, 0.0), point(1.0, 0.0)))
        assertEquals(111.19, km, 0.05)
        assertEquals(60.0, SentinelaRecordingMetrics.averageSpeedKmh(60.0, Duration.ofHours(1)), 0.001)
        assertEquals(0.0, SentinelaRecordingMetrics.averageSpeedKmh(5.0, Duration.ZERO))
        assertEquals(0.0, SentinelaRecordingMetrics.distanceKm(listOf(point(1.0, 1.0))))
    }

    @Test
    fun `report has every requested section`() {
        val pdf = SentinelaReportPdf(fakeTiles).generate(
            SentinelaReportPdf.Input(
                recording = recording(route, transcript = "Estou sendo abordado, enviem ajuda.", transcriptStatus = "DONE"),
                ownerName = "Maria Teste",
                videoLink = "https://api.exemplo.com/sentinela/v/abc123",
                videoLinkExpiresAt = Instant.parse("2026-10-05T14:00:00Z"),
                zone = ZoneOffset.ofHours(-3),
                generatedAt = Instant.parse("2026-09-28T15:00:00Z"),
            ),
        )

        assertEquals("%PDF", String(pdf.copyOfRange(0, 4)))
        val content = text(pdf)
        listOf(
            "Relatório de Gravação", "Titular: Maria Teste", "rec-1234",
            "28/09/2026 11:00:00", "28/09/2026 11:12:24", // 14:00 UTC em UTC-3
            "12min 24s", "DISTÂNCIA", "VELOCIDADE MÉDIA", "5,2 MB",
            "Ponto inicial", "-23.561400, -46.655900", "Ponto final", "-23.567000, -46.644000", "Pontos registrados",
            "OpenStreetMap", "Estou sendo abordado, enviem ajuda.",
            "https://api.exemplo.com/sentinela/v/abc123", "Link válido até 05/10/2026",
        ).forEach { assertTrue(content.contains(it), "PDF sem \"$it\". Texto extraído:\n$content") }
        assertTrue(PdfReader(pdf).getPageContent(1).isNotEmpty())
    }

    @Test
    fun `report still works without locations, transcript or link`() {
        val pdf = SentinelaReportPdf(fakeTiles).generate(
            SentinelaReportPdf.Input(
                recording = recording(emptyList(), transcriptStatus = "FAILED"),
                ownerName = null,
                videoLink = null,
                videoLinkExpiresAt = null,
                zone = ZoneOffset.UTC,
            ),
        )
        val content = text(pdf)
        assertTrue(content.contains("Nenhum ponto de localização"))
        assertTrue(content.contains("Não foi possível gerar a transcrição"))
        assertTrue(content.contains("só é incluído nos relatórios gerados pelo dono"))
    }

    @Test
    fun `map fits all points and draws the route over the tiles`() {
        assertNull(SentinelaRouteMap.render(emptyList(), fakeTiles))

        val map = assertNotNull(SentinelaRouteMap.render(route, fakeTiles))
        assertTrue(map.tilesTotal > 0 && map.tilesLoaded == map.tilesTotal)
        val image = ImageIO.read(ByteArrayInputStream(map.png))
        assertEquals(960, image.width)
        assertEquals(540, image.height)
        // marcador verde de início desenhado em algum lugar da imagem
        val hasStartMarker = (0 until image.height).any { y -> (0 until image.width).any { x -> image.getRGB(x, y) and 0xFFFFFF == 0x2ECC71 } }
        assertTrue(hasStartMarker)

        // ponto único: só o marcador, e sem tiles o fundo liso ainda vale
        val single = assertNotNull(SentinelaRouteMap.render(listOf(point(-23.5, -46.6)), MapTileSource { _, _, _ -> null }))
        assertEquals(0, single.tilesLoaded)
    }
}
