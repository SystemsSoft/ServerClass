package routes.estrelasLeiria

import com.stripe.model.checkout.Session
import com.stripe.net.Webhook
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

private val paidSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

fun Application.ebookWebhookRouting() {
    routing {
        // Webhook da Stripe: registra sessões pagas
        post("/ebook") {
            val payload = call.receiveText()
            val sigHeader = call.request.headers["Stripe-Signature"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing Stripe-Signature header")
            val webhookSecret = System.getenv("STRIPE_EBOOK_WEBHOOK_SECRET")
                ?: return@post call.respond(HttpStatusCode.InternalServerError, "Webhook secret not configured")

            try {
                val event = Webhook.constructEvent(payload, sigHeader, webhookSecret)
                if (event.type == "checkout.session.completed") {
                    val session = event.dataObjectDeserializer.`object`.orElse(null)
                    if (session is Session) {
                        paidSessions.add(session.id)
                        println(">>> Sessão paga registada: ${session.id}")
                    }
                }
                call.respond(HttpStatusCode.OK)
            } catch (e: Exception) {
                println(">>> Ebook Webhook ERRO [${e.javaClass.simpleName}]: ${e.message}")
                call.respond(HttpStatusCode.BadRequest, "Webhook error: ${e.message}")
            }
        }

        // Front consulta se o pagamento foi aprovado
        get("/ebook-status") {
            val sessionId = call.request.queryParameters["session_id"]
            call.respondText(if (sessionId != null && paidSessions.contains(sessionId)) "success" else "pending")
        }
    }
}