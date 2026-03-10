package routes.estrelasLeiria

import com.stripe.model.checkout.Session
import com.stripe.net.Webhook
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import schemas.estrelasLeiria.EbookPaidSessionService

fun Application.ebookWebhookRouting() {
    val ebookService: EbookPaidSessionService by inject()

    routing {
        // Webhook da Stripe: registra sessões pagas no banco
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
                        ebookService.register(session.id)
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
                ?: return@get call.respondText("pending")
            call.respondText(if (ebookService.isPaid(sessionId)) "success" else "pending")
        }
    }
}