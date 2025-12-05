package com.class_erp

import com.stripe.Stripe
import com.stripe.model.Event
import com.stripe.model.checkout.Session
import com.stripe.net.Webhook
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.Optional // Importante para o Optional do Java

// --- MODELOS ---
@Serializable
data class InscricaoDTO(
    val nome: String,
    val categoriaId: String,
    val descricaoDetalhada: String,
    val imageData: String?
)

@Serializable
data class InscricaoResposta(
    val id: Int,
    val status: String
)

// --- BANCO DE DADOS SIMULADO ---
data class InscricaoEntidade(
    val id: Int,
    val dados: InscricaoDTO,
    var status: String
)

val bancoDeDados = mutableListOf<InscricaoEntidade>()
var ultimoId = 0

fun Application.configureStripeModule() {

    // 1. CARREGAR VARIÁVEIS (Com proteção)
    val stripeKey = System.getenv("STRIPE_API_KEY")
    val webhookSecret = System.getenv("STRIPE_WEBHOOK_SECRET")

    if (stripeKey != null) {
        Stripe.apiKey = stripeKey
    } else {
        println("ERRO: STRIPE_API_KEY não encontrada.")
    }

    routing {

        // ROTA 1: CRIAR INSCRIÇÃO
        post("/inscricoes") {
            try {
                val dados = call.receive<InscricaoDTO>()

                ultimoId++
                val novaInscricao = InscricaoEntidade(
                    id = ultimoId,
                    dados = dados,
                    status = "AGUARDANDO"
                )
                bancoDeDados.add(novaInscricao)

                println("Inscrição criada: ID $ultimoId - Status: AGUARDANDO")
                call.respond(HttpStatusCode.Created, mapOf("id" to ultimoId))

            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Erro ao salvar: ${e.message}")
            }
        }

        // ROTA 2: VERIFICAR STATUS (Polling)
        get("/inscricoes/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            val inscricao = bancoDeDados.find { it.id == id }

            if (inscricao != null) {
                // Retorna o status atual ("AGUARDANDO" ou "PAGO")
                call.respond(InscricaoResposta(inscricao.id, inscricao.status))
            } else {
                // Retorna 404 para o Flutter saber que os dados sumiram
                call.respond(HttpStatusCode.NotFound, "Inscrição não encontrada")
            }
        }




        post("/stripe-webhook") {
                    val payload = call.receiveText()
                    val sigHeader = call.request.header("Stripe-Signature")

                    if (webhookSecret == null) {
                        call.respond(HttpStatusCode.InternalServerError)
                        return@post
                    }

                    try {
                        // 1. Valida a assinatura (Isso continua funcionando bem)
                        val event = Webhook.constructEvent(payload, sigHeader, webhookSecret)

                        if (event.type == "checkout.session.completed") {
                            println("Evento de checkout recebido. Tentando extrair ID...")

                            // 2. PARSE MANUAL DO JSON (Para contornar o erro da lib)
                            // Como a lib falhou em criar o objeto Session, vamos ler o JSON bruto do payload
                            // O payload é uma String JSON. Vamos buscar o campo "client_reference_id"

                            // Usamos uma regex simples ou biblioteca JSON (ex: Gson ou Kotlinx)
                            // Vamos usar Kotlinx.serialization que você já tem no projeto

                            // Estratégia de "Força Bruta" para não depender de libs complexas agora:
                            // O campo aparece assim no JSON: "client_reference_id": "1"

                            val regex = """"client_reference_id":\s*"(\d+)"""".toRegex()
                            val match = regex.find(payload)

                            if (match != null) {
                                val idString = match.groupValues[1]
                                val id = idString.toIntOrNull()

                                if (id != null) {
                                    val inscricao = bancoDeDados.find { it.id == id }
                                    if (inscricao != null) {
                                        inscricao.status = "PAGO"
                                        println(">>> SUCESSO ABSOLUTO: ID $id PAGO! <<<")
                                    } else {
                                        println("ERRO: ID $id não encontrado na memória.")
                                    }
                                }
                            } else {
                                println("Aviso: client_reference_id não encontrado no JSON bruto.")
                                // Debug: Mostra onde deveria estar
                                // println(payload)
                            }
                        }

                        call.respond(HttpStatusCode.OK)

                    } catch (e: Exception) {
                        println("Erro no Webhook: ${e.message}")
                        call.respond(HttpStatusCode.BadRequest)
                    }
                }
    }
}