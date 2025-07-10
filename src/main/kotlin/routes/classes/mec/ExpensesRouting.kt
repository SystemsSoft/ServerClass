package routes.classes // Consider changing to 'routes.financial' or 'routes.expenses' for better organization

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import schemas.mec.Expense
import schemas.mec.ExpenseDto
import schemas.mec.ExpenseService

fun Application.expensesRouting(expenseService: ExpenseService) { // Renamed parameter for clarity
    routing {
        // Route to create a new expense
        post("/expenses") {
            try {
                val expense = call.receive<Expense>()
                val id = expenseService.create(expense)
                call.respond(HttpStatusCode.Created, id) // Use HttpStatusCode.Created for successful creation
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.BadRequest, "Error processing JSON for expense: ${e.message}")
            }
        }

        // Route to read all expenses
        get("/expenses") {
            try {
                val expenses = expenseService.readAll()
                call.respond(HttpStatusCode.OK, expenses)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Error fetching expenses: ${e.message}")
            }
        }

        // Route to update an existing expense
        put("/expenses") {
            try {
                val expense = call.receive<ExpenseDto>()
                expenseService.update(expense.id, expense)
                call.respond(HttpStatusCode.OK, "Expense updated successfully!")
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.InternalServerError, "Error updating expense: ${e.message}")
            }
        }

        delete("/expenses/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid expense ID")
                return@delete
            }
            expenseService.delete(id)
            call.respond(HttpStatusCode.OK, "Expense deleted successfully!")
        }
    }
}
