package routes.estrelasLeiria

import com.stripe.Stripe
import com.stripe.model.checkout.Session
import com.stripe.net.Webhook
import com.stripe.param.checkout.SessionCreateParams
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

val paidSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

fun Application.ebookWebhookRouting() {
    Stripe.apiKey = System.getenv("STRIPE_SECRET_KEY")

    routing {
        // 1) Rota que o FRONT chama para iniciar checkout (retorna corpo JSON)
        post("/create-checkout-session") {
            try {
                val frontUrl = System.getenv("FRONT_URL") ?: "https://rafael-melo.netlify.app"
                val priceId = System.getenv("STRIPE_PRICE_ID")
                    ?: return@post call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "STRIPE_PRICE_ID não configurado")
                    )

                val params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .setSuccessUrl("$frontUrl/#/download?session_id={CHECKOUT_SESSION_ID}")
                    .setCancelUrl("$frontUrl/#/")
                    .addLineItem(
                        SessionCreateParams.LineItem.builder()
                            .setPrice(priceId)
                            .setQuantity(1)
                            .build()
                    )
                    .build()

                val session = Session.create(params)

                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "session_id" to session.id,
                        "checkout_url" to session.url
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Falha ao criar checkout", "detail" to (e.message ?: "erro desconhecido"))
                )
            }
        }

        // 2) Webhook da Stripe (NÃO é para o front chamar)
        post("/ebook") {
            val payload = call.receiveText()
            val sigHeader = call.request.headers["Stripe-Signature"]
            val webhookSecret = System.getenv("STRIPE_EBOOK_WEBHOOK_SECRET")

            if (sigHeader == null) {
                println(">>> Ebook Webhook ERRO: Header Stripe-Signature ausente")
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing Stripe-Signature header"))
                return@post
            }

            if (webhookSecret == null) {
                println(">>> Ebook Webhook ERRO: Variável STRIPE_EBOOK_WEBHOOK_SECRET não definida")
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Webhook secret not configured"))
                return@post
            }

            try {
                val event = Webhook.constructEvent(payload, sigHeader, webhookSecret)
                println(">>> Ebook Webhook evento recebido: ${event.type}")

                if (event.type == "checkout.session.completed") {
                    val obj = event.dataObjectDeserializer.`object`.orElse(null)
                    if (obj is Session) {
                        paidSessions.add(obj.id)
                        println(">>> Sessão paga registada: ${obj.id}")
                    }
                }

                call.respond(HttpStatusCode.OK, mapOf("received" to true))
            } catch (e: Exception) {
                println(">>> Ebook Webhook ERRO [${e.javaClass.simpleName}]: ${e.message}")
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Webhook error", "detail" to (e.message ?: "")))
            }
        }

        // 3) Front consulta status com session_id
        get("/ebook-status") {
            val sessionId = call.request.queryParameters["session_id"]
            if (sessionId != null && paidSessions.contains(sessionId)) {
                call.respondText("success")
            } else {
                call.respondText("pending")
            }
        }
    }
}