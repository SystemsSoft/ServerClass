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
import java.io.ByteArrayOutputStream
import java.util.UUID
import com.lowagie.text.*
import com.lowagie.text.pdf.PdfWriter
import com.lowagie.text.pdf.draw.LineSeparator
import java.awt.Color

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
    var LIMITE_MAXIMO_CORTESIAS = 1

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



fun gerarPdfEmBytes(nomeParticipante: String, qrCodeImage: ByteArray): ByteArray {
    val outputStream = ByteArrayOutputStream()

    try {
        // 1. Criação do Documento (Tamanho A5 é bom para bilhetes)
        val document = Document(PageSize.A5, 30f, 30f, 30f, 30f)
        PdfWriter.getInstance(document, outputStream)
        document.open()

        // --- DEFINIÇÃO DE CORES E FONTES ---
        val corDourada = Color(218, 165, 32) // #DAA520
        val corPreta = Color(0, 0, 0)
        val corCinza = Color(80, 80, 80)

        val fonteTitulo = Font(Font.HELVETICA, 24f, Font.BOLD, corDourada)
        val fonteSubtitulo = Font(Font.HELVETICA, 12f, Font.NORMAL, corCinza)
        val fonteNome = Font(Font.HELVETICA, 18f, Font.BOLD, corPreta)
        val fonteTexto = Font(Font.HELVETICA, 12f, Font.NORMAL, corPreta)
        val fonteRodape = Font(Font.HELVETICA, 10f, Font.ITALIC, corCinza)

        // --- CONSTRUÇÃO DO CONTEÚDO ---

        // 1. Cabeçalho
        val titulo = Paragraph("ESTRELAS DE LEIRIA", fonteTitulo)
        titulo.alignment = Element.ALIGN_CENTER
        document.add(titulo)

        val sub = Paragraph("Gala de Premiação 2026", fonteSubtitulo)
        sub.alignment = Element.ALIGN_CENTER
        sub.spacingAfter = 20f
        document.add(sub)

        // Linha separadora
        document.add(LineSeparator(1f, 100f, corDourada, Element.ALIGN_CENTER, -2f))
        document.add(Paragraph(" ")) // Espaço vazio

        // 2. Saudação e Nome
        val saudacao = Paragraph("Bilhete Cortesia para:", fonteTexto)
        saudacao.alignment = Element.ALIGN_CENTER
        saudacao.spacingBefore = 10f
        document.add(saudacao)

        val nome = Paragraph(nomeParticipante.uppercase(), fonteNome)
        nome.alignment = Element.ALIGN_CENTER
        nome.spacingAfter = 20f
        document.add(nome)

        // 3. QR Code (Centralizado)
        try {
            val qrImage = Image.getInstance(qrCodeImage)
            qrImage.scaleToFit(200f, 200f) // Tamanho do QR Code
            qrImage.alignment = Element.ALIGN_CENTER
            qrImage.border = Image.BOX
            qrImage.borderColor = corDourada
            qrImage.borderWidth = 2f
            qrImage.spacingAfter = 20f
            document.add(qrImage)
        } catch (e: Exception) {
            document.add(Paragraph("[Erro ao carregar QR Code]", fonteTexto))
        }

        // 4. Detalhes
        val detalhes = Paragraph("VÁLIDO PARA 1 PESSOA", Font(Font.HELVETICA, 14f, Font.BOLD, corPreta))
        detalhes.alignment = Element.ALIGN_CENTER
        document.add(detalhes)

        document.add(Paragraph(" "))

        val dataLocal = Paragraph("07 de Fevereiro de 2026\nAuditório da Igreja dos Pastorinhos", fonteTexto)
        dataLocal.alignment = Element.ALIGN_CENTER
        document.add(dataLocal)

        // 5. Rodapé
        document.add(Paragraph(" "))
        document.add(LineSeparator(0.5f, 80f, corCinza, Element.ALIGN_CENTER, -2f))

        val footer = Paragraph("Apresente este documento à entrada.\nwww.estrelasdeleiria.pt", fonteRodape)
        footer.alignment = Element.ALIGN_CENTER
        footer.spacingBefore = 10f
        document.add(footer)

        document.close()

    } catch (e: Exception) {
        e.printStackTrace()
        // Em caso de erro grave, retorna array vazio ou gera um PDF de erro básico
        return ByteArray(0)
    }

    return outputStream.toByteArray()
}