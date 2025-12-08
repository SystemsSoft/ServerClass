package com.class_erp


import org.apache.commons.mail.HtmlEmail
import org.apache.commons.mail.EmailAttachment
import java.io.File
import java.io.FileOutputStream
import javax.mail.util.ByteArrayDataSource

class EmailService {

    // Pegando variáveis de ambiente (Configurar no Heroku/PC)
    private val host = System.getenv("SMTP_HOST") ?: "smtp.gmail.com"
    private val port = System.getenv("SMTP_PORT")?.toInt() ?: 587
    private val username = System.getenv("SMTP_USER") ?: "estrelasleiria@gmail.com"
    private val password = System.getenv("SMTP_PASS") ?: "expp saxd ouku dxqi" // Use App Password se for Gmail

    fun enviarBilhete(destinatario: String, nomeParticipante: String, qrCodeBytes: ByteArray) {
        try {
            val email = HtmlEmail()
            email.hostName = host
            email.setSmtpPort(port)
            email.setAuthentication(username, password)
            email.isStartTLSEnabled = true
            // email.isSSLOnConnect = true // Use se a porta for 465

            email.setFrom(username, "Estrelas de Leiria")
            email.subject = "O seu Bilhete - Estrelas de Leiria 2025"
            email.addTo(destinatario)

            // Corpo do E-mail (HTML Básico)
            val mensagemHtml = """
                <html>
                    <body>
                        <h2 style="color: #DAA520;">Olá, $nomeParticipante!</h2>
                        <p>A sua inscrição para o <strong>Estrelas de Leiria</strong> foi confirmada com sucesso.</p>
                        <p>Em anexo encontra-se o seu <strong>QR Code de Acesso</strong>.</p>
                        <p>Por favor, apresente este código na entrada do evento.</p>
                        <br>
                        <p><em>Equipa Estrelas de Leiria</em></p>
                    </body>
                </html>
            """.trimIndent()

            email.setHtmlMsg(mensagemHtml)

            // --- ANEXAR O QR CODE ---
            // Cria um DataSource direto da memória (sem salvar arquivo no disco)
            val dataSource = ByteArrayDataSource(qrCodeBytes, "image/png")

            email.attach(dataSource, "bilhete_estrelas.png", "QR Code de Acesso")

            // Envia
            email.send()
            println("📧 E-mail enviado com sucesso para: $destinatario")

        } catch (e: Exception) {
            println("❌ Erro ao enviar e-mail: ${e.message}")
            e.printStackTrace()
        }
    }
}