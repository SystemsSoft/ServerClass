package services

import schemas.sentinela.SentinelaLocationDto
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/** De onde vêm as imagens (tiles) do mapa de fundo. Separado para os testes não dependerem da internet. */
fun interface MapTileSource {
    /** Tile [z]/[x]/[y] do esquema "slippy map", ou null se não foi possível obter. */
    fun tile(z: Int, x: Int, y: Int): BufferedImage?
}

/**
 * Tiles do OpenStreetMap, os mesmos que o app mostra. A política de uso do OSM exige identificar a
 * aplicação no User-Agent e permite uso leve: um relatório busca só uma dúzia de tiles.
 * O mapa gerado precisa exibir a atribuição "© OpenStreetMap contributors".
 */
class OpenStreetMapTiles : MapTileSource {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    override fun tile(z: Int, x: Int, y: Int): BufferedImage? = runCatching {
        val request = HttpRequest.newBuilder(URI.create("https://tile.openstreetmap.org/$z/$x/$y.png"))
            .timeout(Duration.ofSeconds(8))
            .header("User-Agent", "SentinelaReport/1.0 (relatorio PDF de gravacao)")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() == 200) ImageIO.read(ByteArrayInputStream(response.body())) else null
    }.getOrNull()
}

/** Imagem PNG do mapa e quantos tiles de fundo foram obtidos (se 0, só a rota aparece, sobre fundo liso). */
class RouteMapImage(val png: ByteArray, val tilesLoaded: Int, val tilesTotal: Int)

/**
 * Desenha o percurso sobre o mapa: encaixa todos os pontos no quadro, monta os tiles de fundo e
 * traça a rota com o início (verde) e o fim (vermelho). Só desenha formas, nunca texto, para não
 * depender de fontes do sistema em servidores sem interface gráfica.
 */
object SentinelaRouteMap {
    private const val TILE_SIZE = 256
    private const val MAX_ZOOM = 17
    private const val FIT_PADDING_PX = 70
    private val START_COLOR = Color(0x2E, 0xCC, 0x71)
    private val END_COLOR = Color(0xE7, 0x4C, 0x3C)
    private val ROUTE_COLOR = Color(0x1B, 0x8A, 0x4A)
    private val BACKGROUND = Color(0xE5, 0xE7, 0xEB)

    fun render(
        points: List<SentinelaLocationDto>,
        tiles: MapTileSource,
        width: Int = 960,
        height: Int = 540,
    ): RouteMapImage? {
        if (points.isEmpty()) return null
        System.setProperty("java.awt.headless", "true")

        val zoom = fittingZoom(points, width, height)
        val center = worldPixel(
            (points.minOf { it.lat } + points.maxOf { it.lat }) / 2.0,
            (points.minOf { it.lng } + points.maxOf { it.lng }) / 2.0,
            zoom,
        )
        // Canto superior esquerdo da imagem, em pixels do mundo inteiro naquele zoom.
        val left = center.first - width / 2.0
        val top = center.second - height / 2.0

        val tilesPerSide = 2.0.pow(zoom).toInt()
        val columns = floor(left / TILE_SIZE).toInt()..floor((left + width - 1) / TILE_SIZE).toInt()
        val rows = (floor(top / TILE_SIZE).toInt()..floor((top + height - 1) / TILE_SIZE).toInt())
            .filter { it in 0 until tilesPerSide }
        val wanted = columns.flatMap { column -> rows.map { row -> column to row } }

        val executor = Executors.newFixedThreadPool(6)
        val fetched = try {
            wanted.map { (column, row) ->
                CompletableFuture.supplyAsync(
                    { tiles.tile(zoom, Math.floorMod(column, tilesPerSide), row) },
                    executor,
                )
            }.map { it.join() }
        } finally {
            executor.shutdown()
        }

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.color = BACKGROUND
            g.fillRect(0, 0, width, height)

            wanted.zip(fetched).forEach { (position, tile) ->
                if (tile != null) {
                    g.drawImage(
                        tile,
                        (position.first * TILE_SIZE - left).toInt(),
                        (position.second * TILE_SIZE - top).toInt(),
                        null,
                    )
                }
            }

            val canvas = points.map { point ->
                val (x, y) = worldPixel(point.lat, point.lng, zoom)
                (x - left) to (y - top)
            }
            if (canvas.size > 1) {
                val path = Path2D.Double().apply {
                    moveTo(canvas.first().first, canvas.first().second)
                    canvas.drop(1).forEach { lineTo(it.first, it.second) }
                }
                g.stroke = BasicStroke(9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.color = Color.WHITE
                g.draw(path)
                g.stroke = BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.color = ROUTE_COLOR
                g.draw(path)
            }
            marker(g, canvas.first(), START_COLOR)
            if (canvas.size > 1) marker(g, canvas.last(), END_COLOR)
        } finally {
            g.dispose()
        }

        val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        return RouteMapImage(png, tilesLoaded = fetched.count { it != null }, tilesTotal = wanted.size)
    }

    private fun marker(g: java.awt.Graphics2D, at: Pair<Double, Double>, color: Color) {
        val outer = 13.0
        val inner = 9.0
        g.color = Color.WHITE
        g.fill(Ellipse2D.Double(at.first - outer, at.second - outer, outer * 2, outer * 2))
        g.color = color
        g.fill(Ellipse2D.Double(at.first - inner, at.second - inner, inner * 2, inner * 2))
    }

    /** Maior zoom em que todos os pontos cabem no quadro (com folga nas bordas). */
    private fun fittingZoom(points: List<SentinelaLocationDto>, width: Int, height: Int): Int {
        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }
        val minLng = points.minOf { it.lng }
        val maxLng = points.maxOf { it.lng }
        for (zoom in MAX_ZOOM downTo 1) {
            val topLeft = worldPixel(maxLat, minLng, zoom)
            val bottomRight = worldPixel(minLat, maxLng, zoom)
            val fitsWidth = abs(bottomRight.first - topLeft.first) <= width - 2 * FIT_PADDING_PX
            val fitsHeight = abs(bottomRight.second - topLeft.second) <= height - 2 * FIT_PADDING_PX
            if (fitsWidth && fitsHeight) return zoom
        }
        return 1
    }

    /** Coordenada (lat, lng) em pixels do mapa-múndi inteiro no [zoom] (projeção Web Mercator, como o OSM). */
    private fun worldPixel(lat: Double, lng: Double, zoom: Int): Pair<Double, Double> {
        val worldSize = TILE_SIZE * 2.0.pow(zoom)
        val latRad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        val x = (lng + 180.0) / 360.0 * worldSize
        val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * worldSize
        return x to y
    }
}
