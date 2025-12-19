package routes.estrelasLeiria

import com.class_erp.EmailService
import com.google.zxing.BarcodeFormat
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import com.stripe.Stripe
import com.stripe.model.checkout.Session
import com.stripe.net.Webhook
import com.stripe.param.checkout.SessionRetrieveParams
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.koin.core.qualifier.named
import org.koin.ktor.ext.inject
import schemas.estrelasLeiria.Indicado
import schemas.estrelasLeiria.IndicadoService
import services.S3ApiClient // <--- IMPORTANTE: Importar o seu S3ApiClient
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.concurrent.thread

// --- DTOs ---
@Serializable
data class InscricaoDTO(
    val nome: String,
    val instagram: String,
    val categoriaId: String,
    val descricaoDetalhada: String,
    val imageData: String,
    val desejaParticiparVotacao: Boolean,
    val quantidade: Int = 1
)

@Serializable
data class InscricaoResposta(
    val id: Int,
    val status: String
)

@Serializable
data class CheckInResponse(
    val status: String,
    val mensagem: String? = null,
    val nome: String? = null,
    val tipo: String? = null,
    val categoria: String? = null,
    val foto: String? = null,
    val data_uso: String? = null,
    val quantidade: Int = 1
)

// --- TABLES ---
object PreInscricoesTable : Table("pre_inscricoes_v2") {
    val id = integer("id").autoIncrement()
    val nome = varchar("nome", 255)
    val instagram = varchar("instagram", 100)
    val categoriaId = varchar("categoria_id", 500)
    val descricao = text("descricao")
    val imageData = largeText("image_data") // Agora guardará a URL do S3
    val desejaParticiparVotacao = bool("deseja_participar_votacao").default(false)
    val quantidade = integer("quantidade").default(1)
    val status = varchar("status", 20).default("AGUARDANDO")
    override val primaryKey = PrimaryKey(id)
}

object InscritosTable : Table("inscritos") {
    val id = varchar("id", 36)
    val nome = varchar("nome", 255)
    val instagram = varchar("instagram", 100)
    val categoriaId = varchar("categoria_id", 500)
    val descricao = text("descricao")
    val imageData = largeText("image_data") // Agora guardará a URL do S3
    val stripeId = varchar("stripe_id", 100).nullable()
    val email = varchar("email", 200).nullable()
    val checkIn = bool("check_in").default(false)
    val checkInDate = varchar("check_in_date", 50).nullable()
    val desejaParticiparVotacao = bool("deseja_participar_votacao").default(false)
    val quantidade = integer("quantidade").default(1)
    override val primaryKey = PrimaryKey(id)
}

fun gerarQrCodeBytes(conteudo: String): ByteArray {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(conteudo, BarcodeFormat.QR_CODE, 500, 500)
        val outputStream = ByteArrayOutputStream()
        MatrixToImageWriter.writeToStream(bitMatrix, "PNG", outputStream)
        outputStream.toByteArray()
    } catch (e: Exception) {
        ByteArray(0)
    }
}

// --- ROUTING ---
fun Application.stripeRouting(indicadoService: IndicadoService) {

    val emailService = EmailService()
    val databaseEstrelas by inject<Database>(named("EstrelasLeiriaDB"))

    transaction(databaseEstrelas) {
        SchemaUtils.create(PreInscricoesTable)
        try { SchemaUtils.createMissingTablesAndColumns(PreInscricoesTable) } catch (e: Exception) {}
        SchemaUtils.create(schemas.estrelasLeiria.IndicadoService.IndicadoTable)
        try { SchemaUtils.createMissingTablesAndColumns(schemas.estrelasLeiria.IndicadoService.IndicadoTable) } catch (e: Exception) {}
        SchemaUtils.create(InscritosTable)
        try { SchemaUtils.createMissingTablesAndColumns(InscritosTable) } catch (e: Exception) {}
    }

    val stripeKey = System.getenv("STRIPE_API_KEY")
    // val webhookSecret = System.getenv("STRIPE_WEBHOOK_SECRET") // Removido daqui pois é lido dentro da rota

    if (stripeKey != null) Stripe.apiKey = stripeKey

    routing {

        // =====================================================================
        // CHECK-IN ROUTE (Mantém-se igual, pois lê a URL do banco)
        // =====================================================================
        post("/admin/checkin") {
            val params = call.receive<Map<String, String>>()
            val stripeIdParam = params["stripeId"]

            if (stripeIdParam == null) {
                call.respond(HttpStatusCode.BadRequest, "ID não fornecido")
                return@post
            }

            val resultado = newSuspendedTransaction(Dispatchers.IO, db = databaseEstrelas) {
                val dataHoraAtual = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))

                // 1. Check INDICADOS
                val indicadoRow = schemas.estrelasLeiria.IndicadoService.IndicadoTable
                    .selectAll()
                    .where { schemas.estrelasLeiria.IndicadoService.IndicadoTable.stripeId eq stripeIdParam }
                    .singleOrNull()

                if (indicadoRow != null) {
                    val qtdIndicado = indicadoRow[schemas.estrelasLeiria.IndicadoService.IndicadoTable.quantidade]

                    if (indicadoRow[schemas.estrelasLeiria.IndicadoService.IndicadoTable.checkIn]) {
                        return@newSuspendedTransaction CheckInResponse(
                            status = "ERRO_JA_USADO",
                            mensagem = "Este bilhete já foi validado anteriormente!",
                            data_uso = indicadoRow[schemas.estrelasLeiria.IndicadoService.IndicadoTable.checkInDate],
                            nome = indicadoRow[schemas.estrelasLeiria.IndicadoService.IndicadoTable.nome],
                            quantidade = qtdIndicado
                        )
                    } else {
                        schemas.estrelasLeiria.IndicadoService.IndicadoTable.update({ schemas.estrelasLeiria.IndicadoService.IndicadoTable.stripeId eq stripeIdParam }) {
                            it[checkIn] = true
                            it[checkInDate] = dataHoraAtual
                        }
                        return@newSuspendedTransaction CheckInResponse(
                            status = "SUCESSO",
                            nome = indicadoRow[schemas.estrelasLeiria.IndicadoService.IndicadoTable.nome],
                            tipo = "CANDIDATO",
                            categoria = indicadoRow[schemas.estrelasLeiria.IndicadoService.IndicadoTable.categoriaId],
                            foto = indicadoRow[schemas.estrelasLeiria.IndicadoService.IndicadoTable.imageData], // Retornará a URL
                            quantidade = qtdIndicado
                        )
                    }
                }

                // 2. Check INSCRITOS
                val inscritoRow = InscritosTable
                    .selectAll()
                    .where { InscritosTable.stripeId eq stripeIdParam }
                    .singleOrNull()

                if (inscritoRow != null) {
                    val qtd = inscritoRow[InscritosTable.quantidade]

                    if (inscritoRow[InscritosTable.checkIn]) {
                        return@newSuspendedTransaction CheckInResponse(
                            status = "ERRO_JA_USADO",
                            mensagem = "Bilhete já utilizado!",
                            data_uso = inscritoRow[InscritosTable.checkInDate],
                            nome = inscritoRow[InscritosTable.nome],
                            quantidade = qtd
                        )
                    } else {
                        InscritosTable.update({ InscritosTable.stripeId eq stripeIdParam }) {
                            it[checkIn] = true
                            it[checkInDate] = dataHoraAtual
                        }
                        return@newSuspendedTransaction CheckInResponse(
                            status = "SUCESSO",
                            nome = inscritoRow[InscritosTable.nome],
                            tipo = "ESPECTADOR",
                            categoria = "Bilhete Geral",
                            foto = inscritoRow[InscritosTable.imageData], // Retornará a URL
                            quantidade = qtd
                        )
                    }
                }

                return@newSuspendedTransaction CheckInResponse(
                    status = "ERRO_NAO_ENCONTRADO",
                    mensagem = "QR Code não existe no sistema."
                )
            }

            when (resultado.status) {
                "SUCESSO" -> call.respond(HttpStatusCode.OK, resultado)
                "ERRO_JA_USADO" -> call.respond(HttpStatusCode.Conflict, resultado)
                "ERRO_NAO_ENCONTRADO" -> call.respond(HttpStatusCode.NotFound, resultado)
                else -> call.respond(HttpStatusCode.InternalServerError, resultado)
            }
        }

        // =====================================================================
        // WEBHOOK (QUANTIDADE CORRETA DO STRIPE)
        // =====================================================================
        post("/stripe-webhook") {
            val payload = call.receiveText()
            val sigHeader = call.request.header("Stripe-Signature")
            val webhookSecret = System.getenv("STRIPE_WEBHOOK_SECRET")

            if (webhookSecret == null) {
                call.respond(HttpStatusCode.InternalServerError); return@post
            }

            try {
                val event = Webhook.constructEvent(payload, sigHeader, webhookSecret)

                if (event.type == "checkout.session.completed") {
                    val regexRef = """"client_reference_id":\s*"(\d+)"""".toRegex()
                    val regexStripeId = """"id":\s*"(cs_[a-zA-Z0-9_]+)"""".toRegex()
                    val regexEmail = """"email":\s*"([^"]+)"""".toRegex()

                    val matchRef = regexRef.find(payload)
                    val matchStripe = regexStripeId.find(payload)
                    val matchEmail = regexEmail.find(payload)

                    val stripeSessionId = matchStripe?.groupValues?.get(1)
                    val customerEmail = matchEmail?.groupValues?.get(1)
                    val idTemp = matchRef?.groupValues?.get(1)?.toIntOrNull()

                    if (idTemp != null && stripeSessionId != null) {

                        // --- BUSCA A QUANTIDADE REAL NA API DO STRIPE ---
                        var quantidadeReal = 1
                        try {
                            val params = SessionRetrieveParams.builder()
                                .addExpand("line_items")
                                .build()

                            val session = Session.retrieve(stripeSessionId, params, null)
                            val lineItem = session.lineItems.data.firstOrNull()
                            if (lineItem != null && lineItem.quantity != null) {
                                quantidadeReal = lineItem.quantity.toInt()
                            }
                            println(">>> Stripe API: Quantidade detectada = $quantidadeReal")
                        } catch (ex: Exception) {
                            println(">>> Erro ao buscar quantidade no Stripe: ${ex.message}. Usando 1.")
                        }

                        newSuspendedTransaction(Dispatchers.IO, db = databaseEstrelas) {
                            val row = PreInscricoesTable.selectAll()
                                .where { PreInscricoesTable.id eq idTemp }
                                .singleOrNull()

                            if (row != null && row[PreInscricoesTable.status] != "PAGO") {
                                PreInscricoesTable.update({ PreInscricoesTable.id eq idTemp }) {
                                    it[status] = "PAGO"
                                }

                                val querVotar = row[PreInscricoesTable.desejaParticiparVotacao]
                                val nomeParticipante = row[PreInscricoesTable.nome]
                                val qtdFinal = quantidadeReal

                                // AQUI: row[PreInscricoesTable.imageData] já contém a URL do S3
                                // pois foi salva no endpoint POST /inscricoes
                                val fotoUrlS3 = row[PreInscricoesTable.imageData]

                                if (querVotar) {
                                    val novoIndicado = Indicado(
                                        categoriaId = row[PreInscricoesTable.categoriaId],
                                        nome = row[PreInscricoesTable.nome],
                                        instagram = row[PreInscricoesTable.instagram],
                                        imageData = fotoUrlS3, // Passa a URL
                                        descricaoDetalhada = row[PreInscricoesTable.descricao],
                                        desejaParticiparVotacao = true,
                                        stripeId = stripeSessionId,
                                        email = customerEmail,
                                        quantidade = qtdFinal
                                    )
                                    indicadoService.create(novoIndicado, UUID.randomUUID().toString())

                                } else {
                                    InscritosTable.insert {
                                        it[id] = UUID.randomUUID().toString()
                                        it[nome] = row[PreInscricoesTable.nome]
                                        it[instagram] = row[PreInscricoesTable.instagram]
                                        it[categoriaId] = row[PreInscricoesTable.categoriaId]
                                        it[descricao] = row[PreInscricoesTable.descricao]
                                        it[imageData] = fotoUrlS3 // Passa a URL
                                        it[desejaParticiparVotacao] = false
                                        it[stripeId] = stripeSessionId
                                        it[email] = customerEmail
                                        it[quantidade] = qtdFinal
                                    }
                                }

                                if (customerEmail != null) {
                                    val qrBytes = gerarQrCodeBytes(stripeSessionId)
                                    thread {
                                        emailService.enviarBilhete(
                                            destinatario = customerEmail,
                                            nomeParticipante = nomeParticipante,
                                            qrCodeBytes = qrBytes,
                                            quantidade = qtdFinal
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                call.respond(HttpStatusCode.OK)
            } catch (e: Exception) {
                println("Erro Webhook: ${e.message}")
                call.respond(HttpStatusCode.BadRequest)
            }
        }

        // =====================================================================
        // POST /inscricoes (ALTERADO PARA UPLOAD S3)
        // =====================================================================
        post("/inscricoes") {
            try {
                val dto = call.receive<InscricaoDTO>()

                // 1. Gera um ID para o arquivo
                val fileId = UUID.randomUUID().toString()

                // 2. Faz o Upload da imagem (Base64) para o S3
                // O método detecta a extensão e retorna a URL pública
                val s3Url = S3ApiClient.uploadImage(fileId, dto.imageData)

                // 3. Salva no banco com a URL
                val novoId = newSuspendedTransaction(Dispatchers.IO, db = databaseEstrelas) {
                    PreInscricoesTable.insert {
                        it[nome] = dto.nome
                        it[instagram] = dto.instagram
                        it[categoriaId] = dto.categoriaId
                        it[descricao] = dto.descricaoDetalhada
                        it[imageData] = s3Url // <-- Salva a URL em vez do Base64
                        it[desejaParticiparVotacao] = dto.desejaParticiparVotacao
                        it[quantidade] = 1
                        it[status] = "AGUARDANDO"
                    }[PreInscricoesTable.id]
                }
                call.respond(HttpStatusCode.Created, mapOf("id" to novoId))
            } catch (e: Exception) {
                // Log para debug
                e.printStackTrace()
                call.respond(HttpStatusCode.BadRequest, "Erro: ${e.message}")
            }
        }

        // --- OUTRAS ROTAS ---

        get("/inscricoes/{id}") {
            val idParam = call.parameters["id"]?.toIntOrNull()
            if (idParam == null) { call.respond(HttpStatusCode.BadRequest); return@get }
            val statusAtual = newSuspendedTransaction(Dispatchers.IO, db = databaseEstrelas) {
                PreInscricoesTable.selectAll().where { PreInscricoesTable.id eq idParam }.map { it[PreInscricoesTable.status] }.singleOrNull()
            }
            if (statusAtual != null) call.respond(InscricaoResposta(idParam, statusAtual))
            else call.respond(HttpStatusCode.NotFound)
        }

        get("/ingresso/qrcode/{id}") {
            val idParam = call.parameters["id"]
            if (idParam == null) { call.respond(HttpStatusCode.BadRequest); return@get }
            val stripeId = newSuspendedTransaction(Dispatchers.IO, db = databaseEstrelas) {
                var found = schemas.estrelasLeiria.IndicadoService.IndicadoTable.selectAll().where { schemas.estrelasLeiria.IndicadoService.IndicadoTable.id eq idParam }.map { it[schemas.estrelasLeiria.IndicadoService.IndicadoTable.stripeId] }.singleOrNull()
                if (found == null) found = InscritosTable.selectAll().where { InscritosTable.id eq idParam }.map { it[InscritosTable.stripeId] }.singleOrNull()
                found
            }
            if (stripeId != null) {
                val bytes = gerarQrCodeBytes(stripeId)
                call.respondBytes(bytes, ContentType.Image.PNG)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        get("/admin/sincronizar") {
            val listaCompleta = newSuspendedTransaction(Dispatchers.IO, db = databaseEstrelas) {
                val l1 = schemas.estrelasLeiria.IndicadoService.IndicadoTable.selectAll().where { schemas.estrelasLeiria.IndicadoService.IndicadoTable.stripeId.isNotNull() }.map { mapOf("stripeId" to it[schemas.estrelasLeiria.IndicadoService.IndicadoTable.stripeId], "nome" to it[schemas.estrelasLeiria.IndicadoService.IndicadoTable.nome], "quantidade" to it[schemas.estrelasLeiria.IndicadoService.IndicadoTable.quantidade]) }
                val l2 = InscritosTable.selectAll().where { InscritosTable.stripeId.isNotNull() }.map { mapOf("stripeId" to it[InscritosTable.stripeId], "nome" to it[InscritosTable.nome], "quantidade" to it[InscritosTable.quantidade]) }
                l1 + l2
            }
            call.respond(listaCompleta)
        }

        get("/admin/validar/{stripeId}") {
            val codigoLido = call.parameters["stripeId"]
            if (codigoLido == null) { call.respond(HttpStatusCode.BadRequest); return@get }
            val bilheteEncontrado = newSuspendedTransaction(Dispatchers.IO, db = databaseEstrelas) {
                var result = schemas.estrelasLeiria.IndicadoService.IndicadoTable.selectAll()
                    .where { schemas.estrelasLeiria.IndicadoService.IndicadoTable.stripeId eq codigoLido }
                    .map { mapOf("nome" to it[schemas.estrelasLeiria.IndicadoService.IndicadoTable.nome], "categoriaId" to it[schemas.estrelasLeiria.IndicadoService.IndicadoTable.categoriaId], "foto" to it[schemas.estrelasLeiria.IndicadoService.IndicadoTable.imageData], "status" to "VALIDO (PARTICIPA VOTAÇÃO)", "quantidade" to it[schemas.estrelasLeiria.IndicadoService.IndicadoTable.quantidade]) }.singleOrNull()
                if (result == null) {
                    result = InscritosTable.selectAll().where { InscritosTable.stripeId eq codigoLido }
                        .map { mapOf("nome" to it[InscritosTable.nome], "categoriaId" to it[InscritosTable.categoriaId], "foto" to it[InscritosTable.imageData], "status" to "VALIDO (NÃO PARTICIPA DA VOTAÇÃO)", "quantidade" to it[InscritosTable.quantidade]) }.singleOrNull()
                }
                result
            }
            if (bilheteEncontrado != null) call.respond(HttpStatusCode.OK, bilheteEncontrado)
            else call.respond(HttpStatusCode.NotFound, mapOf("status" to "INVALIDO"))
        }
    }
}