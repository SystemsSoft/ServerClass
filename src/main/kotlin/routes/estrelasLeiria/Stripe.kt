package routes.estrelasLeiria

import com.class_erp.EmailService
import com.google.zxing.BarcodeFormat
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import com.stripe.Stripe
import com.stripe.net.Webhook
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

// NEW: Specific DTO for Check-in Response to handle mixed types
@Serializable
data class CheckInResponse(
    val status: String,
    val mensagem: String? = null,
    val nome: String? = null,
    val tipo: String? = null, // "CANDIDATO" or "ESPECTADOR"
    val categoria: String? = null,
    val foto: String? = null,
    val data_uso: String? = null,
    val quantidade: Int = 0
)

// --- TABLES ---
object PreInscricoesTable : Table("pre_inscricoes_v2") {
    val id = integer("id").autoIncrement()
    val nome = varchar("nome", 255)
    val instagram = varchar("instagram", 100)
    val categoriaId = varchar("categoria_id", 500)
    val descricao = text("descricao")
    val imageData = largeText("image_data")
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
    val imageData = largeText("image_data")
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
    if (stripeKey != null) Stripe.apiKey = stripeKey

    routing {

        // =====================================================================
        // CHECK-IN ROUTE (FIXED SERIALIZATION)
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

                // =========================================================
                // 1. Check INDICADOS (Candidatos)
                // =========================================================
                val indicadoRow = schemas.estrelasLeiria.IndicadoService.IndicadoTable
                    .selectAll()
                    .where { schemas.estrelasLeiria.IndicadoService.IndicadoTable.stripeId eq stripeIdParam }
                    .singleOrNull()

                if (indicadoRow != null) {
                    // --- CORREÇÃO: Ler a quantidade do banco em vez de forçar 1 ---
                    // Se a coluna não existir ainda no banco físico, pode dar erro se não rodar a migration
                    // Certifique-se de que a coluna 'quantidade' existe na tabela 'indicados'
                    val qtdIndicado = indicadoRow[schemas.estrelasLeiria.IndicadoService.IndicadoTable.quantidade]

                    if (indicadoRow[schemas.estrelasLeiria.IndicadoService.IndicadoTable.checkIn]) {
                        return@newSuspendedTransaction CheckInResponse(
                            status = "ERRO_JA_USADO",
                            mensagem = "Este bilhete já foi validado anteriormente!",
                            data_uso = indicadoRow[schemas.estrelasLeiria.IndicadoService.IndicadoTable.checkInDate],
                            nome = indicadoRow[schemas.estrelasLeiria.IndicadoService.IndicadoTable.nome],
                            quantidade = qtdIndicado // <--- USA A QUANTIDADE DO BANCO
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
                            foto = indicadoRow[schemas.estrelasLeiria.IndicadoService.IndicadoTable.imageData],
                            quantidade = qtdIndicado // <--- USA A QUANTIDADE DO BANCO
                        )
                    }
                }

                // =========================================================
                // 2. Check INSCRITOS (Espectadores)
                // =========================================================
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
                            foto = inscritoRow[InscritosTable.imageData],
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

        // ... (Remaining routes unchanged below) ...

        post("/inscricoes") {
            try {
                val dto = call.receive<InscricaoDTO>()
                val novoId = newSuspendedTransaction(Dispatchers.IO, db = databaseEstrelas) {
                    PreInscricoesTable.insert {
                        it[nome] = dto.nome
                        it[instagram] = dto.instagram
                        it[categoriaId] = dto.categoriaId
                        it[descricao] = dto.descricaoDetalhada
                        it[imageData] = dto.imageData
                        it[desejaParticiparVotacao] = dto.desejaParticiparVotacao
                        it[quantidade] = dto.quantidade
                        it[status] = "AGUARDANDO"
                    }[PreInscricoesTable.id]
                }
                println("Pré-inscrição: ID $novoId (Qtd: ${dto.quantidade})")
                call.respond(HttpStatusCode.Created, mapOf("id" to novoId))
            } catch (e: Exception) {
                e.printStackTrace()
                call.respond(HttpStatusCode.BadRequest, "Erro ao salvar: ${e.message}")
            }
        }

        get("/inscricoes/{id}") {
            val idParam = call.parameters["id"]?.toIntOrNull()
            if (idParam == null) { call.respond(HttpStatusCode.BadRequest); return@get }
            val statusAtual = newSuspendedTransaction(Dispatchers.IO, db = databaseEstrelas) {
                PreInscricoesTable.selectAll().where { PreInscricoesTable.id eq idParam }
                    .map { it[PreInscricoesTable.status] }.singleOrNull()
            }
            if (statusAtual != null) call.respond(InscricaoResposta(idParam, statusAtual))
            else call.respond(HttpStatusCode.NotFound)
        }

        get("/ingresso/qrcode/{id}") {
            val idParam = call.parameters["id"]
            if (idParam == null) { call.respond(HttpStatusCode.BadRequest); return@get }
            val stripeId = newSuspendedTransaction(Dispatchers.IO, db = databaseEstrelas) {
                var found = schemas.estrelasLeiria.IndicadoService.IndicadoTable
                    .selectAll().where { schemas.estrelasLeiria.IndicadoService.IndicadoTable.id eq idParam }
                    .map { it[schemas.estrelasLeiria.IndicadoService.IndicadoTable.stripeId] }.singleOrNull()
                if (found == null) {
                    found = InscritosTable.selectAll().where { InscritosTable.id eq idParam }
                        .map { it[InscritosTable.stripeId] }.singleOrNull()
                }
                found
            }
            if (stripeId != null) {
                val bytes = gerarQrCodeBytes(stripeId)
                call.respondBytes(bytes, ContentType.Image.PNG)
            } else {
                call.respond(HttpStatusCode.NotFound, "Ingresso não encontrado")
            }
        }

        post("/stripe-webhook") {
            val payload = call.receiveText()
            val sigHeader = call.request.header("Stripe-Signature")
            val webhookSecret = System.getenv("STRIPE_WEBHOOK_SECRET")
            if (webhookSecret == null) { call.respond(HttpStatusCode.InternalServerError); return@post }

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
                        newSuspendedTransaction(Dispatchers.IO, db = databaseEstrelas) {
                            val row = PreInscricoesTable.selectAll().where { PreInscricoesTable.id eq idTemp }.singleOrNull()
                            if (row != null && row[PreInscricoesTable.status] != "PAGO") {
                                PreInscricoesTable.update({ PreInscricoesTable.id eq idTemp }) { it[status] = "PAGO" }
                                val querVotar = row[PreInscricoesTable.desejaParticiparVotacao]
                                val qtdComprada = row[PreInscricoesTable.quantidade]

                                if (querVotar) {
                                    val novoIndicado = Indicado(
                                        categoriaId = row[PreInscricoesTable.categoriaId],
                                        nome = row[PreInscricoesTable.nome],
                                        instagram = row[PreInscricoesTable.instagram],
                                        imageData = row[PreInscricoesTable.imageData],
                                        descricaoDetalhada = row[PreInscricoesTable.descricao],
                                        desejaParticiparVotacao = true,
                                        stripeId = stripeSessionId,
                                        email = customerEmail,
                                        quantidade = row[PreInscricoesTable.quantidade]
                                    )
                                    indicadoService.create(novoIndicado, UUID.randomUUID().toString())
                                } else {
                                    InscritosTable.insert {
                                        it[id] = UUID.randomUUID().toString()
                                        it[nome] = row[PreInscricoesTable.nome]
                                        it[instagram] = row[PreInscricoesTable.instagram]
                                        it[categoriaId] = row[PreInscricoesTable.categoriaId]
                                        it[descricao] = row[PreInscricoesTable.descricao]
                                        it[imageData] = row[PreInscricoesTable.imageData]
                                        it[desejaParticiparVotacao] = false
                                        it[stripeId] = stripeSessionId
                                        it[email] = customerEmail
                                        it[quantidade] = qtdComprada
                                    }
                                }
                                if (customerEmail != null) {
                                    val qrBytes = gerarQrCodeBytes(stripeSessionId)
                                    thread { emailService.enviarBilhete(customerEmail, row[PreInscricoesTable.nome], qrBytes) }
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

        get("/admin/sincronizar") {
            val listaCompleta = newSuspendedTransaction(Dispatchers.IO, db = databaseEstrelas) {
                val listaIndicados = schemas.estrelasLeiria.IndicadoService.IndicadoTable
                    .selectAll().where { schemas.estrelasLeiria.IndicadoService.IndicadoTable.stripeId.isNotNull() }
                    .map { mapOf("stripeId" to it[schemas.estrelasLeiria.IndicadoService.IndicadoTable.stripeId], "nome" to it[schemas.estrelasLeiria.IndicadoService.IndicadoTable.nome], "categoria" to it[schemas.estrelasLeiria.IndicadoService.IndicadoTable.categoriaId], "status" to "VALIDO_VOTO", "quantidade" to 1) }
                val listaInscritos = InscritosTable
                    .selectAll().where { InscritosTable.stripeId.isNotNull() }
                    .map { mapOf("stripeId" to it[InscritosTable.stripeId], "nome" to it[InscritosTable.nome], "categoria" to it[InscritosTable.categoriaId], "status" to "VALIDO_SIMPLES", "quantidade" to it[InscritosTable.quantidade]) }
                listaIndicados + listaInscritos
            }
            call.respond(listaCompleta)
        }

        get("/admin/validar/{stripeId}") {
            val codigoLido = call.parameters["stripeId"]
            if (codigoLido == null) { call.respond(HttpStatusCode.BadRequest); return@get }
            val bilheteEncontrado = newSuspendedTransaction(Dispatchers.IO, db = databaseEstrelas) {
                var result = schemas.estrelasLeiria.IndicadoService.IndicadoTable.selectAll()
                    .where { schemas.estrelasLeiria.IndicadoService.IndicadoTable.stripeId eq codigoLido }
                    .map { mapOf("nome" to it[schemas.estrelasLeiria.IndicadoService.IndicadoTable.nome], "categoriaId" to it[schemas.estrelasLeiria.IndicadoService.IndicadoTable.categoriaId], "foto" to it[schemas.estrelasLeiria.IndicadoService.IndicadoTable.imageData], "status" to "VALIDO (PARTICIPA VOTAÇÃO)", "quantidade" to 1) }.singleOrNull()
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