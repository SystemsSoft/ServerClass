package routes.estrelasLeiria

import com.stripe.model.checkout.Session
import com.stripe.net.Webhook
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.ktor.ext.inject
import schemas.estrelasLeiria.EbookPaidSessionService

fun Application.ebookWebhookRouting() {
    val ebookService: EbookPaidSessionService by inject()

    routing {
        post("/ebook") {
            val payload = call.receiveText()
            println(">>> Ebook Webhook recebido, payload size: ${payload.length}")

            val sigHeader = call.request.headers["Stripe-Signature"]
                ?: run {
                    println(">>> ERRO: Stripe-Signature header ausente")
                    return@post call.respond(HttpStatusCode.BadRequest, "Missing Stripe-Signature header")
                }

            val webhookSecret = System.getenv("STRIPE_EBOOK_WEBHOOK_SECRET")
                ?: run {
                    println(">>> ERRO: STRIPE_EBOOK_WEBHOOK_SECRET não configurado")
                    return@post call.respond(HttpStatusCode.InternalServerError, "Webhook secret not configured")
                }

            println(">>> Webhook secret presente: ${webhookSecret.take(8)}...")

            try {
                val event = Webhook.constructEvent(payload, sigHeader, webhookSecret)
                println(">>> Evento recebido: ${event.type}")

                if (event.type == "checkout.session.completed") {
                    val sessionObj = event.dataObjectDeserializer.`object`.orElse(null)

                    val sessionId = if (sessionObj is Session) {
                        sessionObj.id
                    } else {
                        println(">>> Deserializador retornou null, extraindo do raw JSON...")
                        try {
                            val dataJson = Json.parseToJsonElement(payload)
                                .jsonObject["data"]
                                ?.jsonObject?.get("object")
                                ?.jsonObject?.get("id")
                                ?.jsonPrimitive?.content
                            println(">>> session_id extraído do raw JSON: $dataJson")
                            dataJson
                        } catch (ex: Exception) {
                            println(">>> ERRO ao extrair session_id do raw JSON: ${ex.message}")
                            null
                        }
                    }

                    if (sessionId != null) {
                        ebookService.register(sessionId)
                        println(">>> Sessão paga registada: $sessionId")
                    } else {
                        println(">>> ERRO: não foi possível obter session_id")
                    }
                }
                call.respond(HttpStatusCode.OK)
            } catch (e: Exception) {
                println(">>> Ebook Webhook ERRO [${e.javaClass.simpleName}]: ${e.message}")
                call.respond(HttpStatusCode.BadRequest, "Webhook error: ${e.message}")
            }
        }

        get("/ebook-status") {
            val sessionId = call.request.queryParameters["session_id"]
                ?: return@get call.respondText("pending")
            call.respondText(if (ebookService.isPaid(sessionId)) "success" else "pending")
        }
    }
}