package routes.estrelasLeiria

import com.stripe.net.Webhook
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun Application.ebookWebhookRouting() {
    routing {
        post("/ebook") {
            val payload = call.receiveText()
            val sigHeader = call.request.header("Stripe-Signature")
            val webhookSecret = "whsec_ivtBBdcV9yjwOUjhUJLisqwBjWPEmGpE"

            if (sigHeader == null) {
                call.respond(HttpStatusCode.BadRequest, "Missing Stripe-Signature header")
                return@post
            }

            try {
                val event = Webhook.constructEvent(payload, sigHeader, webhookSecret)

                when (event.type) {
                    "payment_intent.succeeded", "checkout.session.completed" -> {
                        call.respond(HttpStatusCode.OK)
                    }
                    else -> {
                        call.respond(HttpStatusCode.OK) // Respond 200 to other events
                    }
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Webhook error: ${e.message}")
            }
        }
    }
}

