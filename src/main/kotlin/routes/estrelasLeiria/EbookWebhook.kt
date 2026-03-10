package routes.estrelasLeiria

import com.stripe.model.checkout.Session
import com.stripe.net.Webhook
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

// armazenamento simples para teste (em produção use banco)
val paidSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

fun Application.ebookWebhookRouting() {
    routing {
        post("/ebook") {
            val payload = call.receiveText()
            val sigHeader = call.request.header("Stripe-Signature")
            val webhookSecret = System.getenv("STRIPE_EBOOK_WEBHOOK_SECRET")
                ?: "whsec_ivtBBdcV9yjwOUjhUJLisqwBjWPEmGpE"

            if (sigHeader == null) {
                call.respond(HttpStatusCode.BadRequest, "Missing Stripe-Signature header")
                return@post
            }

            try {
                val event = Webhook.constructEvent(payload, sigHeader, webhookSecret)

                when (event.type) {
                    "checkout.session.completed" -> {
                        val session = event.dataObjectDeserializer.`object`.orElse(null)
                        if (session is Session) {
                            paidSessions.add(session.id)
                        }
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
            if (sessionId != null && paidSessions.contains(sessionId)) {
                call.respondText("success")
            } else {
                call.respondText("pending")
            }
        }
    }
}