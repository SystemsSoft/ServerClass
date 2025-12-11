package com.class_erp

import org.apache.commons.mail.HtmlEmail
import javax.mail.util.ByteArrayDataSource
import java.net.URL

class EmailService {

    private val host = System.getenv("SMTP_HOST") ?: "smtp.gmail.com"
    private val port = System.getenv("SMTP_PORT")?.toInt() ?: 587
    private val username = System.getenv("SMTP_USER") ?: "estrelasleiria@gmail.com"
    private val password = System.getenv("SMTP_PASS") ?: "expp saxd ouku dxqi"

    fun enviarBilhete(destinatario: String, nomeParticipante: String, qrCodeBytes: ByteArray) {
        try {
            val email = HtmlEmail()
            email.hostName = host
            email.setSmtpPort(port)
            email.setAuthentication(username, password)
            email.isStartTLSEnabled = true
            email.setCharset("UTF-8")

            email.setFrom(username, "Gala Estrelas de Leiria")
            email.subject = "Confirmação de Presença - Gala Estrelas de Leiria 2025"
            email.addTo(destinatario)

            // --- 1. CARREGAR LOGÓTIPO DOS RESOURCES ---
            // Isto procura o arquivo "logo.png" dentro da pasta src/main/resources
            val logoResourceUrl: URL? = this::class.java.classLoader.getResource("logo-estrelas.webp")

            var logoCid = ""

            if (logoResourceUrl != null) {
                // Anexa a imagem interna e gera o CID
                logoCid = email.embed(logoResourceUrl, "Logo Estrelas")
            } else {
                println("⚠️ Aviso: logo.png não encontrado nos resources.")
            }

            // --- 2. CORPO DO EMAIL ---
            val mensagemHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                    <style>
                        body { margin: 0; padding: 0; font-family: 'Helvetica Neue', Helvetica, Arial, sans-serif; background-color: #121212; }
                        .container { max-width: 600px; margin: 0 auto; background-color: #1a1a1a; border: 1px solid #333; }
                        .header { background-color: #000000; padding: 40px 20px; text-align: center; border-bottom: 2px solid #DAA520; }
                        .content { padding: 40px 30px; color: #e0e0e0; text-align: center; }
                        .gold-text { color: #DAA520; }
                        .ticket-box { background-color: #252525; border: 1px dashed #DAA520; padding: 20px; margin: 20px 0; border-radius: 5px; }
                        .footer { background-color: #000000; padding: 20px; text-align: center; font-size: 12px; color: #666; border-top: 1px solid #333; }
                        .logo-img { max-width: 150px; height: auto; margin-bottom: 15px; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <div class="header">
                            ${if (logoCid.isNotEmpty()) "<img src='cid:$logoCid' alt='Estrelas de Leiria' class='logo-img'>" else ""}
                            
                            <h1 class="gold-text" style="margin:0; letter-spacing: 2px; text-transform: uppercase; font-size: 22px;">Estrelas de Leiria</h1>
                            <p style="color: #888; margin: 5px 0 0 0; font-size: 14px;">GALA DE PREMIAÇÃO 2025</p>
                        </div>

                        <div class="content">
                            <h2 style="font-weight: 300; margin-bottom: 20px;">Estimado(a) <span class="gold-text">$nomeParticipante</span>,</h2>
                            
                            <p style="line-height: 1.6; font-size: 16px;">
                                É com enorme prazer que confirmamos a sua presença na nossa noite de celebração.
                                A sua inscrição foi registada com sucesso.
                            </p>

                            <div class="ticket-box">
                                <p style="margin: 0; font-size: 14px; color: #aaa;">ESTE É O SEU ACESSO EXCLUSIVO</p>
                                <h3 style="margin: 10px 0; color: #fff;">BILHETE DIGITAL</h3>
                                <p style="font-size: 14px; line-height: 1.5;">
                                    Encontra em anexo o ficheiro contendo o seu <strong>QR Code</strong>.<br>
                                    Queira, por favor, apresentá-lo à entrada do evento para validação.
                                </p>
                            </div>

                            <p style="font-size: 14px; color: #888; margin-top: 30px;">
                                Prepare-se para uma noite memorável de talento e reconhecimento.
                            </p>
                            
                            <p style="margin-top: 40px; font-style: italic;">
                                Com os melhores cumprimentos,<br>
                                <strong class="gold-text">A Organização Estrelas de Leiria</strong>
                            </p>
                        </div>

                        <div class="footer">
                            <p>&copy; 2025 Estrelas de Leiria. Todos os direitos reservados.</p>
                            <p>Leiria, Portugal</p>
                        </div>
                    </div>
                </body>
                </html>
            """.trimIndent()

            email.setHtmlMsg(mensagemHtml)

            // --- ANEXAR O QR CODE ---
            val dataSource = ByteArrayDataSource(qrCodeBytes, "image/png")
            email.attach(dataSource, "Bilhete_Estrelas_Leiria.png", "QR Code de Acesso")

            // Envia
            email.send()
            println("📧 E-mail enviado com sucesso para: $destinatario")

        } catch (e: Exception) {
            println("❌ Erro ao enviar e-mail: ${e.message}")
            e.printStackTrace()
        }
    }
}