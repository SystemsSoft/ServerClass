package routes.sentinela

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import schemas.sentinela.SentinelaRecordingService
import services.SentinelaS3Uploader

/**
 * Página pública do link de compartilhamento do vídeo (`GET /sentinela/v/{token}`), sem login:
 * quem tem o link assiste. O token é aleatório e expira (ver [SentinelaRecordingService.ensureShareLink]).
 * A página toca o vídeo por uma URL pré-assinada nova a cada visita, então o bucket continua privado.
 */
fun Application.sentinelaPublicVideoRouting(recordingService: SentinelaRecordingService) {
    routing {
        get("/sentinela/v/{token}") {
            // Nada de cache nem de vazar o link por Referer: o endereço é a credencial de acesso.
            call.response.header("Cache-Control", "no-store")
            call.response.header("Referrer-Policy", "no-referrer")
            call.response.header("X-Robots-Tag", "noindex, nofollow")

            val token = call.parameters["token"].orEmpty()
            val video = token.takeIf { it.length in 20..64 }?.let { recordingService.findSharedVideo(it) }
            val videoUrl = video?.let {
                runCatching { SentinelaS3Uploader.presignedGetUrl(it.s3Key) }
                    .onFailure { e -> println("[Sentinela] Falha ao assinar vídeo do link compartilhado: ${e.message}") }
                    .getOrNull()
            }

            if (video == null || videoUrl == null) {
                call.respondText(unavailablePage(), ContentType.Text.Html, HttpStatusCode.NotFound)
                return@get
            }
            call.respondText(videoPage(videoUrl, video.startedAt), ContentType.Text.Html)
        }
    }
}

private fun String.escapeHtml(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

private const val PAGE_STYLE = """
  :root { color-scheme: dark; }
  body { margin: 0; min-height: 100vh; display: flex; flex-direction: column; align-items: center; justify-content: center;
         background: #0D1013; color: #F5F6F7; font-family: -apple-system, 'Segoe UI', Roboto, sans-serif; padding: 16px; box-sizing: border-box; }
  main { width: 100%; max-width: 720px; }
  h1 { font-size: 20px; margin: 0 0 4px; }
  p { color: #8B939C; font-size: 14px; margin: 0 0 16px; }
  video { width: 100%; max-height: 75vh; border-radius: 16px; background: #000; border: 1px solid #2A3038; }
  .brand { color: #2ECC71; font-weight: 800; letter-spacing: .3px; }
"""

private fun videoPage(videoUrl: String, startedAt: String): String = """<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>Gravação Sentinela</title>
<style>$PAGE_STYLE</style>
</head>
<body>
<main>
  <h1><span class="brand">Sentinela</span> · Gravação compartilhada</h1>
  <p>Gravada em <time id="when" datetime="${startedAt.escapeHtml()}">${startedAt.escapeHtml()}</time></p>
  <video controls playsinline preload="metadata" src="${videoUrl.escapeHtml()}"></video>
</main>
<script>
  var el = document.getElementById('when');
  var d = new Date(el.getAttribute('datetime'));
  if (!isNaN(d)) el.textContent = d.toLocaleString('pt-BR', { dateStyle: 'long', timeStyle: 'short' });
</script>
</body>
</html>"""

private fun unavailablePage(): String = """<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>Link indisponível</title>
<style>$PAGE_STYLE</style>
</head>
<body>
<main>
  <h1><span class="brand">Sentinela</span></h1>
  <p>Este link é inválido ou já expirou. Peça um novo link a quem compartilhou o vídeo.</p>
</main>
</body>
</html>"""
