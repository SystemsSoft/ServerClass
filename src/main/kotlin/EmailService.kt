package com.class_erp

import org.apache.commons.mail.HtmlEmail
import javax.mail.util.ByteArrayDataSource
import java.net.URL

class EmailService {

    private val host = System.getenv("SMTP_HOST") ?: "smtp.gmail.com"
    private val port = System.getenv("SMTP_PORT")?.toInt() ?: 587
    private val username = System.getenv("SMTP_USER") ?: "estrelasleiria@gmail.com"
    private val password = System.getenv("SMTP_PASS") ?: "expp saxd ouku dxqi"

    fun enviarBilhete(destinatario: String, nomeParticipante: String, qrCodeBytes: ByteArray, quantidade: Int) {
        try {
            val email = HtmlEmail()
            email.hostName = host
            email.setSmtpPort(port)
            email.setAuthentication(username, password)
            email.isStartTLSEnabled = true
            email.setCharset("UTF-8")

            email.setFrom(username, "Gala Estrelas de Leiria")
            email.subject = "O Seu Bilhete - Gala Estrelas de Leiria 2025"
            email.addTo(destinatario)

            // --- 1. PREPARAR IMAGENS (Logo e QR Code) ---

            // A. Logo (Resources)
            var logoCid = ""
            try {
                val logoUrl: URL? = this::class.java.classLoader.getResource("logo-estrelas.webp")
                if (logoUrl != null) logoCid = email.embed(logoUrl, "Logo Estrelas")
            } catch (e: Exception) { println("Erro logo: ${e.message}") }

            val logoHtml = if (logoCid.isNotEmpty()) "<img src='cid:$logoCid' alt='Estrelas de Leiria' width='150' style='display:block; border:0; margin-bottom:15px; max-width:150px;' />" else "<h1 style='color:#DAA520;'>ESTRELAS DE LEIRIA</h1>"

            // B. QR Code (Memória) -> Para embutir no CORPO
            val qrDataSource = ByteArrayDataSource(qrCodeBytes, "image/png")
            // O comando 'embed' anexa a imagem de forma oculta e retorna o ID para usar no HTML
            val qrCid = email.embed(qrDataSource, "qrcode_acesso")

            // Variáveis de texto
            val textoPessoas = if (quantidade > 1) "PESSOAS" else "PESSOA"
            val textoBilhetes = if (quantidade > 1) "bilhetes" else "bilhete"

            // --- 2. HTML (Com QR Code visível) ---
            val mensagemHtml = """
                <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head>
                    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
                    <title>Bilhete Estrelas de Leiria</title>
                    <style>
                        body { margin: 0; padding: 0; background-color: #121212; font-family: 'Helvetica Neue', Helvetica, Arial, sans-serif; }
                    </style>
                </head>
                <body style="margin: 0; padding: 0; background-color: #121212;">
                    
                    <table border="0" cellpadding="0" cellspacing="0" width="100%" style="background-color: #121212;">
                        <tr>
                            <td align="center" style="padding: 20px 0;">
                                
                                <table border="0" cellpadding="0" cellspacing="0" width="600" style="background-color: #1a1a1a; border: 1px solid #333333; max-width: 600px;">
                                    
                                    <tr>
                                        <td align="center" style="padding: 40px 20px; background-color: #000000; border-bottom: 2px solid #DAA520;">
                                            $logoHtml
                                            <p style="color: #888888; margin: 5px 0 0 0; font-size: 14px; text-transform: uppercase; letter-spacing: 2px;">Gala de Premiação 2025</p>
                                        </td>
                                    </tr>

                                    <tr>
                                        <td align="center" style="padding: 40px 30px; color: #e0e0e0;">
                                            <h2 style="font-weight: 300; margin-bottom: 20px; color: #ffffff;">Estimado(a) <span style="color: #DAA520;">$nomeParticipante</span>,</h2>
                                            
                                            <p style="line-height: 1.6; font-size: 16px; color: #cccccc;">
                                                A sua presença está confirmada. Este e-mail contém o seu bilhete digital para <strong>$quantidade $textoBilhetes</strong>.
                                            </p>

                                            <table border="0" cellpadding="0" cellspacing="0" width="100%" style="background-color: #252525; border: 1px dashed #DAA520; border-radius: 5px; margin: 30px 0;">
                                                <tr>
                                                    <td align="center" style="padding: 30px 20px;">
                                                        <p style="margin: 0 0 15px 0; font-size: 12px; color: #aaaaaa; text-transform: uppercase; letter-spacing: 1px;">Apresente este código à entrada</p>
                                                        
                                                        <div style="background-color: #FFFFFF; padding: 15px; display: inline-block; border-radius: 8px;">
                                                            <img src="cid:$qrCid" alt="QR Code" width="200" height="200" style="display:block;" />
                                                        </div>
                                                        
                                                        <br>

                                                        <div style="background-color: #DAA520; color: #000000; padding: 8px 20px; border-radius: 20px; font-weight: bold; font-size: 16px; display: inline-block; margin-top: 20px;">
                                                            VÁLIDO PARA: $quantidade $textoPessoas
                                                        </div>
                                                    </td>
                                                </tr>
                                            </table>

                                            <p style="font-size: 14px; color: #888888; margin-top: 30px;">
                                                Sugerimos que aumente o brilho do telemóvel ao apresentar o bilhete.
                                            </p>
                                            
                                            <p style="margin-top: 40px; font-style: italic; color: #e0e0e0;">
                                                Com os melhores cumprimentos,<br>
                                                <strong style="color: #DAA520;">A Organização Estrelas de Leiria</strong>
                                            </p>
                                        </td>
                                    </tr>

                                    <tr>
                                        <td align="center" style="background-color: #000000; padding: 20px; font-size: 12px; color: #666666; border-top: 1px solid #333333;">
                                            <p style="margin: 5px 0;">&copy; 2025 Estrelas de Leiria.</p>
                                        </td>
                                    </tr>
                                </table>
                                
                            </td>
                        </tr>
                    </table>
                </body>
                </html>
            """.trimIndent()

            email.setHtmlMsg(mensagemHtml)

            // --- 3. ANEXO (Backup) ---
            // Além de estar no corpo, mantemos como anexo caso a pessoa queira fazer download
            // Usamos o mesmo dataSource que já criámos
            email.attach(qrDataSource, "Bilhete_Estrelas_Leiria.png", "QR Code de Acesso (Backup)")

            email.send()
            println("📧 E-mail enviado com sucesso para: $destinatario")

        } catch (e: Exception) {
            println("❌ Erro ao enviar e-mail: ${e.message}")
            e.printStackTrace()
        }
    }
}