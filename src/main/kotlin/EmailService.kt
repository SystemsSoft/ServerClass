package com.class_erp

import org.apache.commons.mail.HtmlEmail
import javax.mail.util.ByteArrayDataSource
import java.net.URL

class EmailService {

    // =========================================================================
    // CONFIGURAÇÕES (Variáveis de Ambiente)
    // =========================================================================
    private val host = System.getenv("SMTP_HOST") ?: "smtp.gmail.com"
    private val port = System.getenv("SMTP_PORT")?.toInt() ?: 587
    private val username = System.getenv("SMTP_USER") ?: "contato@estrelasdeleiria.pt"
    // Dica: Se usar Gmail, lembre-se de usar a "Senha de App" e não a sua senha normal
    private val password = System.getenv("SMTP_PASS") ?: "rgcz dusq pfxz nxos"

    // Links Oficiais
    private val siteUrl = "https://www.estrelasdeleiria.pt"

    // Link de Busca do Google Maps apontando para o local do evento
    private val mapsUrl = "https://www.google.com/maps/search/?api=1&query=Auditório+da+Igreja+dos+Pastorinhos+Gândara+dos+Olivais+Leiria"

    /**
     * Envia o bilhete digital com QR Code e detalhes do evento.
     */
    fun enviarBilhete(destinatario: String, nomeParticipante: String, qrCodeBytes: ByteArray, quantidade: Int) {
        try {
            // 1. Configuração do E-mail
            val email = HtmlEmail()
            email.hostName = host
            email.setSmtpPort(port)
            email.setAuthentication(username, password)
            email.isStartTLSEnabled = true
            email.setCharset("UTF-8")

            email.setFrom(username, "Gala Estrelas de Leiria")
            email.subject = "O Seu Bilhete - Gala Estrelas de Leiria 2026"
            email.addTo(destinatario)

            // 2. Processamento de Imagens (CID)

            // A. Logótipo (Tenta carregar dos resources, senão usa texto)
            var logoHtml = "<h1 style='color:#DAA520; margin:0;'>ESTRELAS DE LEIRIA</h1>"
            try {
                // Certifique-se que o arquivo 'logo-estrelas.webp' está na pasta src/main/resources
                val logoUrl: URL? = this::class.java.classLoader.getResource("logo-estrelas.webp")
                if (logoUrl != null) {
                    val logoCid = email.embed(logoUrl, "Logo Estrelas")
                    // HTML da imagem com Link
                    logoHtml = """
                        <a href="$siteUrl" target="_blank" style="text-decoration:none; border:0; outline:none;">
                            <img src="cid:$logoCid" alt="Estrelas de Leiria" width="150" style="display:block; border:0; margin-bottom:15px; max-width:150px;" />
                        </a>
                    """.trimIndent()
                } else {
                    println("⚠️ Aviso: logo.png não encontrado em src/main/resources")
                }
            } catch (e: Exception) {
                println("⚠️ Erro ao processar logo: ${e.message}")
            }

            // B. QR Code (Vem da Memória/Bytes)
            val qrDataSource = ByteArrayDataSource(qrCodeBytes, "image/png")
            val qrCid = email.embed(qrDataSource, "qrcode_acesso")

            // 3. Lógica de Textos
            val textoPessoas = if (quantidade > 1) "PESSOAS" else "PESSOA"
            val textoBilhetes = if (quantidade > 1) "bilhetes" else "bilhete"

            // 4. Construção do HTML (Template de E-mail Responsivo/Escuro)
            val mensagemHtml = """
                <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head>
                    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
                    <title>Bilhete Estrelas de Leiria</title>
                    <style>
                        body { margin: 0; padding: 0; background-color: #121212; font-family: 'Helvetica Neue', Helvetica, Arial, sans-serif; }
                        a { text-decoration: none; color: #DAA520; }
                        img { display: block; border: 0; }
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
                                            <p style="color: #888888; margin: 5px 0 0 0; font-size: 14px; text-transform: uppercase; letter-spacing: 2px;">Gala de Premiação 2026</p>
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
                                                        
                                                        <table border="0" cellpadding="0" cellspacing="0" style="background-color: #FFFFFF; border-radius: 8px;">
                                                            <tr>
                                                                <td style="padding: 15px;">
                                                                    <img src="cid:$qrCid" alt="QR Code" width="200" height="200" style="display:block;" />
                                                                </td>
                                                            </tr>
                                                        </table>
                                                        
                                                        <br>

                                                        <div style="background-color: #DAA520; color: #000000; padding: 10px 25px; border-radius: 50px; font-weight: bold; font-size: 16px; display: inline-block; margin-top: 15px;">
                                                            VÁLIDO PARA: $quantidade $textoPessoas
                                                        </div>
                                                    </td>
                                                </tr>
                                            </table>
                                            
                                            <table border="0" cellpadding="0" cellspacing="0" width="100%" style="background-color: #222222; border-radius: 8px; margin-bottom: 30px; border-left: 4px solid #DAA520;">
                                                <tr>
                                                    <td style="padding: 20px;">
                                                        <table border="0" cellpadding="0" cellspacing="0" width="100%">
                                                            <tr>
                                                                <td style="padding-bottom: 15px; border-bottom: 1px solid #333333;">
                                                                    <p style="color: #DAA520; font-size: 11px; font-weight: bold; margin: 0 0 5px 0; text-transform: uppercase; letter-spacing: 1px;">DATA DO EVENTO</p>
                                                                    <p style="color: #ffffff; font-size: 16px; margin: 0;">📅 <strong>07 de Fevereiro de 2026</strong></p>
                                                                </td>
                                                            </tr>
                                                            <tr>
                                                                <td style="padding-top: 15px;">
                                                                    <p style="color: #DAA520; font-size: 11px; font-weight: bold; margin: 0 0 5px 0; text-transform: uppercase; letter-spacing: 1px;">LOCALIZAÇÃO</p>
                                                                    <p style="color: #ffffff; font-size: 16px; margin: 0 0 4px 0;">📍 <strong>Auditório da Igreja dos Pastorinhos</strong></p>
                                                                    <p style="color: #aaaaaa; font-size: 14px; margin: 0 0 15px 0;">Gândara dos Olivais – Leiria</p>
                                                                    
                                                                    <a href="$mapsUrl" target="_blank" style="display: inline-block; color: #DAA520; font-size: 12px; font-weight: bold; text-decoration: none; border: 1px solid #DAA520; padding: 8px 15px; border-radius: 4px;">
                                                                        🗺️ VER NO GOOGLE MAPS
                                                                    </a>
                                                                </td>
                                                            </tr>
                                                        </table>
                                                    </td>
                                                </tr>
                                            </table>

                                            <p style="font-size: 14px; color: #888888; margin-top: 20px;">
                                                Para mais informações sobre o programa e regulamento, visite o nosso site.
                                            </p>
                                            
                                            <a href="$siteUrl" target="_blank" style="background-color: #DAA520; color: #000000; padding: 12px 30px; text-decoration: none; font-weight: bold; border-radius: 4px; display: inline-block; margin-top: 10px; font-size: 14px;">
                                                VISITAR SITE OFICIAL
                                            </a>

                                            <p style="margin-top: 40px; font-style: italic; color: #e0e0e0;">
                                                Com os melhores cumprimentos,<br>
                                                <strong style="color: #DAA520;">A Organização Estrelas de Leiria</strong>
                                            </p>
                                        </td>
                                    </tr>

                                    <tr>
                                        <td align="center" style="background-color: #000000; padding: 30px 20px 20px 20px; font-size: 12px; color: #666666; border-top: 1px solid #333333;">
                                            
                                            <div style="border-bottom: 1px solid #222222; padding-bottom: 20px; margin-bottom: 20px;">
                                                <p style="color: #999999; font-weight: bold; font-size: 11px; margin-bottom: 8px; text-transform: uppercase;">
                                                    ⚠️ Aviso Importante – Segurança
                                                </p>
                                                <p style="font-size: 10px; line-height: 1.5; color: #666666; margin: 0; text-align: justify;">
                                                    A equipa do Estrelas de Leiria não entra em contacto a solicitar dados pessoais, códigos, palavras-passe, pagamentos adicionais ou qualquer outra informação sensível, seja por Instagram, e-mail, telefone, WhatsApp ou qualquer outro meio.<br><br>
                                                    Toda a comunicação oficial ocorre exclusivamente através dos canais institucionais do Estrelas de Leiria e do e-mail utilizado no momento da aquisição do bilhete.
                                                    Em caso de contacto suspeito, não responda, não partilhe informações e reporte imediatamente a situação.
                                                </p>
                                            </div>

                                            <p style="margin: 5px 0;">&copy; 2025 Estrelas de Leiria. Todos os direitos reservados.</p>
                                            <p style="margin: 5px 0;">
                                                <a href="$siteUrl" target="_blank" style="color: #888888; text-decoration: none;">www.estrelasdeleiria.pt</a>
                                            </p>
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

            // 5. Anexar QR Code (Backup)
            email.attach(qrDataSource, "Bilhete_Estrelas_Leiria.png", "QR Code de Acesso (Backup)")

            // 6. Enviar
            email.send()
            println("📧 E-mail enviado com sucesso para: $destinatario (Qtd: $quantidade)")

        } catch (e: Exception) {
            println("❌ Erro ao enviar e-mail: ${e.message}")
            e.printStackTrace()
        }
    }
}