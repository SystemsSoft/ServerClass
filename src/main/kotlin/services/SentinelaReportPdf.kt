package services

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.lowagie.text.Anchor
import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.Font
import com.lowagie.text.HeaderFooter
import com.lowagie.text.Image
import com.lowagie.text.PageSize
import com.lowagie.text.Paragraph
import com.lowagie.text.Phrase
import com.lowagie.text.Rectangle
import com.lowagie.text.pdf.PdfPCell
import com.lowagie.text.pdf.PdfPTable
import com.lowagie.text.pdf.PdfWriter
import schemas.sentinela.SentinelaLocationDto
import schemas.sentinela.SentinelaRecordingWithLocations
import schemas.sentinela.SentinelaTranscriptStatus
import java.awt.Color
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Números do trajeto, calculados a partir dos pontos de localização (mesma lógica da tela de detalhe do app). */
object SentinelaRecordingMetrics {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceKm(points: List<SentinelaLocationDto>): Double =
        points.zipWithNext().sumOf { (a, b) -> haversineMeters(a, b) } / 1000.0

    fun averageSpeedKmh(distanceKm: Double, duration: Duration): Double {
        val hours = duration.seconds / 3600.0
        return if (hours <= 0) 0.0 else distanceKm / hours
    }

    private fun haversineMeters(a: SentinelaLocationDto, b: SentinelaLocationDto): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }
}

/**
 * Relatório em PDF de uma gravação do Sentinela: resumo (início, fim, duração, distância, velocidade
 * média, tamanho), geolocalização, mapa do percurso, transcrição e um QR code para o vídeo original.
 */
class SentinelaReportPdf(private val tiles: MapTileSource = OpenStreetMapTiles()) {

    class Input(
        val recording: SentinelaRecordingWithLocations,
        val ownerName: String?,
        /** Link público do vídeo (vira o QR code); null quando não há vídeo ou o usuário não é o dono. */
        val videoLink: String?,
        val videoLinkExpiresAt: Instant?,
        val zone: ZoneId,
        val generatedAt: Instant = Instant.now(),
    )

    private companion object {
        val GREEN = Color(0x1B, 0x8A, 0x4A)
        val TEXT = Color(0x21, 0x25, 0x29)
        val MUTED = Color(0x6C, 0x75, 0x7D)
        val BORDER = Color(0xDE, 0xE2, 0xE6)
        val CARD = Color(0xF6, 0xF8, 0xF7)

        val TITLE = Font(Font.HELVETICA, 22f, Font.BOLD, GREEN)
        val SUBTITLE = Font(Font.HELVETICA, 10f, Font.NORMAL, MUTED)
        val HEADING = Font(Font.HELVETICA, 13f, Font.BOLD, GREEN)
        val LABEL = Font(Font.HELVETICA, 8.5f, Font.NORMAL, MUTED)
        val VALUE = Font(Font.HELVETICA, 12f, Font.BOLD, TEXT)
        val BODY = Font(Font.HELVETICA, 10.5f, Font.NORMAL, TEXT)
        val BODY_MUTED = Font(Font.HELVETICA, 9.5f, Font.NORMAL, MUTED)
        val LINK = Font(Font.HELVETICA, 9.5f, Font.UNDERLINE, Color(0x0B, 0x6B, 0xCB))

        val NUMBERS: Locale = Locale.forLanguageTag("pt-BR")
        val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    }

    fun generate(input: Input): ByteArray {
        val recording = input.recording
        val summary = recording.summary
        val points = recording.locations

        val startedAt = Instant.parse(summary.startedAt)
        val endedAt = summary.endedAt?.let(Instant::parse)
        val duration = Duration.between(startedAt, endedAt ?: input.generatedAt).coerceAtLeast(Duration.ZERO)
        val distanceKm = SentinelaRecordingMetrics.distanceKm(points)
        val averageSpeed = SentinelaRecordingMetrics.averageSpeedKmh(distanceKm, duration)

        val output = ByteArrayOutputStream()
        val document = Document(PageSize.A4, 40f, 40f, 40f, 54f)
        PdfWriter.getInstance(document, output)
        document.addTitle("Relatório de gravação Sentinela")
        document.addCreator("Sentinela")
        document.setFooter(
            HeaderFooter(Phrase("Sentinela · Página ", SUBTITLE), true).apply {
                setBorder(Rectangle.NO_BORDER)
                setAlignment(Element.ALIGN_CENTER)
            },
        )
        document.open()

        document.add(Paragraph("Relatório de Gravação", TITLE))
        document.add(
            Paragraph(
                buildString {
                    append("Sentinela · gerado em ${DATE_TIME.format(input.generatedAt.atZone(input.zone))}")
                    input.ownerName?.takeIf { it.isNotBlank() }?.let { append(" · Titular: $it") }
                    append("\nID da gravação: ${summary.id}")
                },
                SUBTITLE,
            ).apply { setSpacingAfter(14f) },
        )

        document.add(summaryTable(startedAt, endedAt, duration, distanceKm, averageSpeed, summary.sizeBytes, input.zone, summary.status))

        document.add(block(heading("Geolocalização"), locationTable(points)))
        document.add(routeMapSection(points))
        addTranscriptSection(document, recording.transcript, recording.transcriptStatus)
        document.add(videoSection(input.videoLink, input.videoLinkExpiresAt, input.zone, hasVideo = recording.s3Key != null))

        document.close()
        return output.toByteArray()
    }

    /** Mantém os elementos juntos na mesma página (título nunca fica sozinho no fim de uma página). */
    private fun block(vararg elements: Element): PdfPTable = PdfPTable(1).apply {
        setWidthPercentage(100f)
        setKeepTogether(true)
        elements.forEach { element ->
            addCell(
                PdfPCell().apply {
                    addElement(element)
                    setBorder(Rectangle.NO_BORDER)
                    setPadding(0f)
                },
            )
        }
    }

    private fun heading(text: String) = Paragraph(text, HEADING).apply {
        setSpacingBefore(18f)
        setSpacingAfter(8f)
        setKeepTogether(true)
    }

    private fun summaryTable(
        startedAt: Instant,
        endedAt: Instant?,
        duration: Duration,
        distanceKm: Double,
        averageSpeedKmh: Double,
        sizeBytes: Long,
        zone: ZoneId,
        status: String,
    ): PdfPTable {
        val table = PdfPTable(3).apply {
            setWidthPercentage(100f)
            setSpacingAfter(2f)
        }
        listOf(
            "INÍCIO" to DATE_TIME.format(startedAt.atZone(zone)),
            "FIM" to (endedAt?.let { DATE_TIME.format(it.atZone(zone)) } ?: "—"),
            "DURAÇÃO" to formatDuration(duration),
            "DISTÂNCIA" to String.format(NUMBERS, "%.1f km", distanceKm),
            "VELOCIDADE MÉDIA" to String.format(NUMBERS, "%.0f km/h", averageSpeedKmh),
            "TAMANHO DO VÍDEO" to formatBytes(sizeBytes),
        ).forEach { (label, value) -> table.addCell(card(label, value)) }
        // Situação da gravação ocupa a linha inteira, discreta.
        table.addCell(
            PdfPCell(Phrase("Situação: ${statusLabel(status)}", BODY_MUTED)).apply {
                setColspan(3)
                setBorder(Rectangle.NO_BORDER)
                setPaddingTop(6f)
            },
        )
        return table
    }

    private fun card(label: String, value: String) = PdfPCell().apply {
        addElement(Paragraph(label, LABEL))
        addElement(Paragraph(value, VALUE).apply { setSpacingBefore(2f) })
        setBorder(Rectangle.BOX)
        setBorderColor(BORDER)
        setBackgroundColor(CARD)
        setPadding(9f)
    }

    private fun locationTable(points: List<SentinelaLocationDto>): PdfPTable {
        val table = PdfPTable(floatArrayOf(1.1f, 3f)).apply { setWidthPercentage(100f) }
        fun row(label: String, point: SentinelaLocationDto?) {
            table.addCell(plainCell(Phrase(label, BODY_MUTED)))
            table.addCell(
                plainCell(
                    if (point == null) {
                        Phrase("Não registrada", BODY)
                    } else {
                        Phrase().apply {
                            add(com.lowagie.text.Chunk(String.format(Locale.US, "%.6f, %.6f   ", point.lat, point.lng), BODY))
                            add(
                                Anchor("abrir no Google Maps", LINK).apply {
                                    setReference(String.format(Locale.US, "https://www.google.com/maps?q=%.6f,%.6f", point.lat, point.lng))
                                },
                            )
                        }
                    },
                ),
            )
        }
        row("Ponto inicial", points.firstOrNull())
        row("Ponto final", points.takeIf { it.size > 1 }?.last())
        table.addCell(plainCell(Phrase("Pontos registrados", BODY_MUTED)))
        table.addCell(plainCell(Phrase(points.size.toString(), BODY)))
        return table
    }

    private fun plainCell(content: Phrase) = PdfPCell(content).apply {
        setBorder(Rectangle.BOTTOM)
        setBorderColor(BORDER)
        setPaddingTop(5f)
        setPaddingBottom(6f)
        setPaddingLeft(0f)
    }

    private fun routeMapSection(points: List<SentinelaLocationDto>): PdfPTable {
        val map = runCatching { SentinelaRouteMap.render(points, tiles) }.getOrNull()
        if (map == null) {
            return block(
                heading("Percurso"),
                Paragraph("Nenhum ponto de localização foi registrado nesta gravação.", BODY_MUTED),
            )
        }
        val image = Image.getInstance(map.png).apply {
            scaleToFit(515f, 320f)
            setAlignment(Image.ALIGN_CENTER)
            setBorder(Rectangle.BOX)
            setBorderColor(BORDER)
            setBorderWidth(0.8f)
        }
        val legend = buildString {
            append("Verde: ponto inicial · Vermelho: ponto final")
            if (map.tilesLoaded > 0) append(" · Mapa © OpenStreetMap contributors")
            if (map.tilesLoaded < map.tilesTotal) append(" · Parte do mapa de fundo não pôde ser carregada")
        }
        return block(heading("Percurso"), image, Paragraph(legend, SUBTITLE).apply { setSpacingBefore(4f) })
    }

    /** O texto pode ser longo e atravessar páginas; só o título é mantido junto do primeiro parágrafo. */
    private fun addTranscriptSection(document: Document, transcript: String?, status: String?) {
        val lines = transcript.orEmpty().replace("\r\n", "\n").split(Regex("\n+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) {
            document.add(block(heading("Transcrição"), Paragraph(transcriptUnavailableMessage(status), BODY_MUTED)))
            return
        }
        fun paragraph(line: String) = Paragraph(line, BODY).apply { setLeading(15f); setSpacingAfter(4f) }
        document.add(block(heading("Transcrição"), paragraph(lines.first())))
        lines.drop(1).forEach { document.add(paragraph(it)) }
    }

    private fun transcriptUnavailableMessage(status: String?) = when (status) {
        SentinelaTranscriptStatus.PENDING.name, SentinelaTranscriptStatus.PROCESSING.name ->
            "A transcrição ainda estava sendo gerada quando este relatório foi criado."
        SentinelaTranscriptStatus.NO_AUDIO.name -> "Esta gravação não tem áudio para transcrever."
        SentinelaTranscriptStatus.FAILED.name -> "Não foi possível gerar a transcrição desta gravação."
        SentinelaTranscriptStatus.DONE.name -> "Nenhuma fala foi identificada nesta gravação."
        else -> "Esta gravação não tem transcrição."
    }

    private fun videoSection(link: String?, expiresAt: Instant?, zone: ZoneId, hasVideo: Boolean): PdfPTable {
        if (link == null) {
            return block(
                heading("Vídeo original"),
                Paragraph(
                    if (hasVideo) "O link do vídeo só é incluído nos relatórios gerados pelo dono da gravação."
                    else "Esta gravação não tem vídeo.",
                    BODY_MUTED,
                ),
            )
        }
        val table = PdfPTable(floatArrayOf(1f, 3.2f)).apply { setWidthPercentage(100f) }
        table.addCell(
            PdfPCell(Image.getInstance(qrCode(link)).apply { scaleToFit(120f, 120f) }).apply {
                setBorder(Rectangle.NO_BORDER)
                setHorizontalAlignment(Element.ALIGN_LEFT)
            },
        )
        table.addCell(
            PdfPCell().apply {
                setBorder(Rectangle.NO_BORDER)
                setVerticalAlignment(Element.ALIGN_MIDDLE)
                addElement(Paragraph("Escaneie o QR code ou acesse o link para assistir ao vídeo original.", BODY))
                addElement(
                    Paragraph().apply {
                        setSpacingBefore(6f)
                        add(Anchor(link, LINK).apply { setReference(link) })
                    },
                )
                expiresAt?.let {
                    addElement(
                        Paragraph("Link válido até ${DATE.format(it.atZone(zone))}.", BODY_MUTED).apply { setSpacingBefore(6f) },
                    )
                }
            },
        )
        return block(heading("Vídeo original"), table)
    }

    private fun qrCode(content: String): ByteArray {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            480,
            480,
            mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
        return ByteArrayOutputStream().also { ImageIO.write(MatrixToImageWriter.toBufferedImage(matrix), "png", it) }.toByteArray()
    }

    private fun statusLabel(status: String) = when (status) {
        "COMPLETED" -> "Concluída"
        "INTERRUPTED" -> "Interrompida"
        "RECORDING" -> "Em andamento"
        else -> status
    }

    /** Ex.: "12min 24s", "1h 05min" — igual ao app. */
    private fun formatDuration(duration: Duration): String {
        val hours = duration.toHours()
        val minutes = duration.toMinutesPart()
        val seconds = duration.toSecondsPart()
        return when {
            hours > 0 -> "${hours}h ${minutes.toString().padStart(2, '0')}min"
            minutes > 0 -> "${minutes}min ${seconds}s"
            else -> "${seconds}s"
        }
    }

    private fun formatBytes(bytes: Long): String {
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> String.format(NUMBERS, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(NUMBERS, "%.1f MB", bytes / mb)
            bytes >= kb -> String.format(NUMBERS, "%.0f KB", bytes / kb)
            else -> "$bytes B"
        }
    }
}
