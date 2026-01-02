package routes.estrelasLeiria

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.util.UUID
import com.lowagie.text.*
import com.lowagie.text.pdf.PdfWriter
import java.awt.Color
import com.lowagie.text.pdf.*
import java.io.ByteArrayOutputStream
import java.net.URL

// DTO Simplificado (Só nome e quantidade fixa)
@Serializable
data class CortesiaDTO(
    val nome: String,
    val quantidade: Int = 1
)

fun Application.cortesiaRouting(
    database: Database
    // Removemos o emailService daqui pois não vamos enviar e-mail
) {
    var LIMITE_MAXIMO_CORTESIAS = 20

    routing {
        staticResources("/oferta", "static")

        post("/cortesia/baixar") {
            try {
                val dados = call.receive<CortesiaDTO>()

                // Gera código único
                val codigoBilhete = "CORTESIA_" + UUID.randomUUID().toString().substring(0, 8).uppercase()

                // Transação do Banco de Dados
                newSuspendedTransaction(Dispatchers.IO, db = database) {
                    // 1. Checa Limite
                    val totalJaEmitidos = InscritosTable.selectAll()
                        .where { InscritosTable.categoriaId eq "CORTESIA" }
                        .count()

                    if ((totalJaEmitidos + 1) > LIMITE_MAXIMO_CORTESIAS) {
                        throw IllegalStateException("Limite de cortesias esgotado!")
                    }

                    // 2. Salva no Banco (Com e-mail fictício pois é obrigatório no banco)
                    InscritosTable.insert {
                        it[id] = UUID.randomUUID().toString()
                        it[nome] = dados.nome
                        it[categoriaId] = "CORTESIA"
                        it[descricao] = "Cortesia Baixada em PDF"
                        it[imageData] = ""
                        it[desejaParticiparVotacao] = false
                        it[stripeId] = codigoBilhete
                        it[email] = "cortesia_download@sem.email" // E-mail fictício
                        it[instagram] = ""
                        it[quantidade] = 1
                        it[checkIn] = false
                    }
                }

                // 3. GERAÇÃO DO PDF E QR CODE
                val qrBytes = gerarQrCodeBytes(codigoBilhete)

                // =================================================================
                // ATENÇÃO: AQUI VOCÊ CHAMA SUA FUNÇÃO DE GERAR PDF
                // Use a mesma lógica que usava no EmailService para criar o anexo
                // =================================================================
                val pdfBytes = gerarPdfEmBytes(dados.nome, qrBytes)

                // 4. Retorna o Arquivo PDF para o navegador baixar
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "bilhete.pdf").toString()
                )
                call.respondBytes(pdfBytes, ContentType.Application.Pdf)

            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.Conflict, mapOf("message" to e.message))
            } catch (e: Exception) {
                e.printStackTrace()
                call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Erro: ${e.message}"))
            }
        }
    }
}



// ============================================================================
// CLASSE PARA PINTAR O FUNDO (CORRIGIDA)
// ============================================================================
class BackgroundEvent(private val backgroundColor: Color) : PdfPageEventHelper() {
    override fun onEndPage(writer: PdfWriter, document: Document) {
        val canvas = writer.directContentUnder
        val rect = document.pageSize

        canvas.setColorFill(backgroundColor)

        // CORREÇÃO AQUI: Usamos as propriedades sem () ou os métodos get...()
        canvas.rectangle(
            rect.left,   // ou rect.getLeft()
            rect.bottom, // ou rect.getBottom()
            rect.width,  // ou rect.getWidth()
            rect.height  // ou rect.getHeight()
        )

        canvas.fill()
    }
}

// ============================================================================
// FUNÇÃO DE GERAÇÃO DO PDF
// ============================================================================
fun gerarPdfEmBytes(nomeParticipante: String, qrCodeImage: ByteArray): ByteArray {
    val outputStream = ByteArrayOutputStream()

    try {
        // 1. Criação do Documento (A4 sem margens)
        val document = Document(PageSize.A4, 0f, 0f, 0f, 0f)
        val writer = PdfWriter.getInstance(document, outputStream)

        // --- CORES ---
        val corFundo = Color(18, 18, 18) // #121212
        val corDourada = Color(218, 165, 32) // #DAA520
        val corTextoBranco = Color(255, 255, 255) // #FFFFFF
        val corTextoCinza = Color(204, 204, 204) // #CCCCCC

        // --- REGISTRAR O EVENTO DE FUNDO ---
        writer.pageEvent = BackgroundEvent(corFundo)

        document.open()

        // --- FONTES ---
        val fontTitulo = Font(Font.HELVETICA, 14f, Font.BOLD, corDourada)
        val fontSubtitulo = Font(Font.HELVETICA, 10f, Font.NORMAL, corTextoCinza)
        val fontSaudacao = Font(Font.HELVETICA, 16f, Font.NORMAL, corTextoBranco)
        val fontNome = Font(Font.HELVETICA, 16f, Font.BOLD, corDourada)
        val fontTexto = Font(Font.HELVETICA, 12f, Font.NORMAL, corTextoCinza)
        val fontDestaque = Font(Font.HELVETICA, 12f, Font.BOLD, corTextoBranco)
        val fontLabel = Font(Font.HELVETICA, 10f, Font.BOLD, corDourada)
        val fontBotao = Font(Font.HELVETICA, 12f, Font.BOLD, Color.BLACK)
        val fontRodape = Font(Font.HELVETICA, 9f, Font.NORMAL, corTextoCinza)
        val fontAvisoTitulo = Font(Font.HELVETICA, 10f, Font.BOLD, corDourada)
        val fontAvisoTexto = Font(Font.HELVETICA, 8f, Font.NORMAL, corTextoCinza)

        // --- TABELA PRINCIPAL ---
        val mainTable = PdfPTable(1)
        mainTable.widthPercentage = 100f
        mainTable.defaultCell.border = Rectangle.NO_BORDER
        // Sem cor de fundo aqui, pois o BackgroundEvent já pintou a página
        mainTable.defaultCell.setPadding(20f)

        // --- 1. LOGO E TÍTULO ---
        val headerCell = PdfPCell()
        headerCell.border = Rectangle.NO_BORDER
        headerCell.horizontalAlignment = Element.ALIGN_CENTER
        headerCell.paddingBottom = 30f

        try {
            val logoUrl = URL("https://repo-estrelas-leiria.s3.us-east-1.amazonaws.com/logo.png")
            val logo = Image.getInstance(logoUrl)
            logo.scaleToFit(120f, 120f)
            logo.alignment = Element.ALIGN_CENTER
            headerCell.addElement(logo)
        } catch (e: Exception) {
            headerCell.addElement(Paragraph("ESTRELAS DE LEIRIA", fontTitulo))
        }

        val tituloGala = Paragraph("GALA DE PREMIAÇÃO 2026", fontSubtitulo)
        tituloGala.alignment = Element.ALIGN_CENTER
        tituloGala.spacingBefore = 5f
        headerCell.addElement(tituloGala)
        mainTable.addCell(headerCell)

        // --- 2. SAUDAÇÃO E TEXTO ---
        val bodyCell = PdfPCell()
        bodyCell.border = Rectangle.NO_BORDER
        bodyCell.paddingLeft = 40f
        bodyCell.paddingRight = 40f
        bodyCell.horizontalAlignment = Element.ALIGN_CENTER

        val saudacao = Paragraph()
        saudacao.add(Chunk("Estimado(a) ", fontSaudacao))
        saudacao.add(Chunk(nomeParticipante, fontNome))
        saudacao.add(Chunk(",", fontSaudacao))
        saudacao.alignment = Element.ALIGN_CENTER
        bodyCell.addElement(saudacao)

        val textoConfirmacao = Paragraph("A sua presença está confirmada. Este documento é o seu bilhete digital para 1 pessoa.", fontTexto)
        textoConfirmacao.alignment = Element.ALIGN_CENTER
        textoConfirmacao.spacingBefore = 15f
        textoConfirmacao.spacingAfter = 30f
        bodyCell.addElement(textoConfirmacao)

        // --- 3. CAIXA DO QR CODE ---
        val qrBoxTable = PdfPTable(1)
        qrBoxTable.widthPercentage = 60f
        qrBoxTable.defaultCell.border = Rectangle.BOX
        qrBoxTable.defaultCell.borderColor = corDourada
        qrBoxTable.defaultCell.borderWidth = 1f
        qrBoxTable.defaultCell.backgroundColor = Color(37, 37, 37)
        qrBoxTable.defaultCell.setPadding(20f)
        qrBoxTable.defaultCell.horizontalAlignment = Element.ALIGN_CENTER

        qrBoxTable.addCell(Paragraph("APRESENTE ESTE CÓDIGO À ENTRADA", fontLabel).apply {
            alignment = Element.ALIGN_CENTER
            spacingAfter = 15f
        })

        try {
            val qrImage = Image.getInstance(qrCodeImage)
            qrImage.scaleToFit(180f, 180f)
            qrImage.alignment = Element.ALIGN_CENTER
            qrBoxTable.addCell(qrImage)
        } catch (e: Exception) {
            qrBoxTable.addCell(Paragraph("[QR Code]", fontTexto))
        }

        // Botão de Validade
        val validadeTable = PdfPTable(1)
        validadeTable.widthPercentage = 80f
        validadeTable.setSpacingBefore(20f)

        val validadeCell = PdfPCell(Paragraph("VÁLIDO PARA: 1 PESSOA", fontBotao))
        validadeCell.backgroundColor = corDourada
        validadeCell.horizontalAlignment = Element.ALIGN_CENTER
        validadeCell.verticalAlignment = Element.ALIGN_MIDDLE
        validadeCell.border = Rectangle.NO_BORDER
        validadeCell.setPadding(10f)
        validadeCell.paddingTop = 8f
        validadeCell.paddingBottom = 8f

        validadeTable.addCell(validadeCell)

        val buttonContainer = PdfPTable(1)
        buttonContainer.widthPercentage = 100f
        buttonContainer.defaultCell.border = Rectangle.NO_BORDER
        buttonContainer.defaultCell.horizontalAlignment = Element.ALIGN_CENTER
        buttonContainer.addCell(validadeTable)

        qrBoxTable.addCell(buttonContainer)
        bodyCell.addElement(qrBoxTable)
        mainTable.addCell(bodyCell)

        // --- 4. DATA E LOCAL ---
        val infoCell = PdfPCell()
        infoCell.border = Rectangle.NO_BORDER
        infoCell.setPadding(40f)
        infoCell.paddingTop = 30f

        infoCell.addElement(Paragraph("DATA DO EVENTO", fontLabel).apply { spacingAfter = 5f })
        infoCell.addElement(Paragraph("📅 07 de Fevereiro de 2026", fontDestaque).apply { spacingAfter = 20f })

        infoCell.addElement(Paragraph("LOCALIZAÇÃO", fontLabel).apply { spacingAfter = 5f })
        infoCell.addElement(Paragraph("📍 Auditório da Igreja dos Pastorinhos", fontDestaque))
        infoCell.addElement(Paragraph("Gândara dos Olivais – Leiria", fontTexto).apply { spacingAfter = 15f })

        val mapsTable = PdfPTable(1)
        mapsTable.widthPercentage = 40f
        mapsTable.horizontalAlignment = Element.ALIGN_LEFT

        val mapsLink = Chunk("🗺️ VER NO GOOGLE MAPS", fontDestaque)
        mapsLink.setAnchor("https://www.google.com/maps/search/?api=1&query=Auditório+da+Igreja+dos+Pastorinhos+Gândara+dos+Olivais+Leiria")

        val mapsCell = PdfPCell(Paragraph(mapsLink))
        mapsCell.borderColor = corDourada
        mapsCell.borderWidth = 1f
        // Sem cor de fundo para usar o fundo da página
        mapsCell.horizontalAlignment = Element.ALIGN_CENTER
        mapsCell.setPadding(8f)
        mapsTable.addCell(mapsCell)

        infoCell.addElement(mapsTable)
        mainTable.addCell(infoCell)

        // --- 5. RODAPÉ ---
        val footerCell = PdfPCell()
        footerCell.border = Rectangle.NO_BORDER
        footerCell.setPadding(40f)
        footerCell.horizontalAlignment = Element.ALIGN_CENTER

        footerCell.addElement(Paragraph("Para mais informações sobre o programa e regulamento, visite o nosso site.", fontTexto).apply {
            alignment = Element.ALIGN_CENTER
            spacingAfter = 20f
        })

        val siteTable = PdfPTable(1)
        siteTable.widthPercentage = 50f
        siteTable.horizontalAlignment = Element.ALIGN_CENTER

        val siteLink = Chunk("VISITAR SITE OFICIAL", fontBotao)
        siteLink.setAnchor("https://www.estrelasdeleiria.pt")

        val siteCell = PdfPCell(Paragraph(siteLink))
        siteCell.backgroundColor = corDourada
        siteCell.horizontalAlignment = Element.ALIGN_CENTER
        siteCell.verticalAlignment = Element.ALIGN_MIDDLE
        siteCell.border = Rectangle.NO_BORDER
        siteCell.setPadding(12f)
        siteTable.addCell(siteCell)

        footerCell.addElement(siteTable)
        footerCell.addElement(Paragraph("Com os melhores cumprimentos,\nA Organização Estrelas de Leiria", fontRodape).apply {
            alignment = Element.ALIGN_CENTER
            spacingBefore = 30f
            spacingAfter = 30f
        })
        mainTable.addCell(footerCell)

        // --- 6. AVISO DE SEGURANÇA ---
        val securityCell = PdfPCell()
        securityCell.border = Rectangle.TOP
        securityCell.borderColor = Color(51, 51, 51)
        securityCell.setPadding(30f)
        securityCell.horizontalAlignment = Element.ALIGN_CENTER

        securityCell.addElement(Paragraph("⚠️ AVISO IMPORTANTE – SEGURANÇA", fontAvisoTitulo).apply {
            alignment = Element.ALIGN_CENTER
            spacingAfter = 10f
        })
        val avisoTexto = "A equipa do Estrelas de Leiria não entra em contacto a solicitar dados pessoais, códigos, palavras-passe, pagamentos adicionais ou qualquer outra informação sensível.\n\nToda a comunicação oficial ocorre exclusivamente através dos canais institucionais do Estrelas de Leiria e do e-mail utilizado no momento da aquisição do bilhete."
        securityCell.addElement(Paragraph(avisoTexto, fontAvisoTexto).apply {
            alignment = Element.ALIGN_JUSTIFIED
        })

        mainTable.addCell(securityCell)

        document.add(mainTable)
        document.close()

    } catch (e: Exception) {
        e.printStackTrace()
        return ByteArray(0)
    }

    return outputStream.toByteArray()
}