import com.class_erp.schemas.Acessos
import com.class_erp.schemas.AcessosService
import schemas.ClassesList
import schemas.ClassesListService
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import kotlin.getValue


fun Application.configureRouting() {
    val serviceAcesso by inject<AcessosService>()
    val classesListService by inject<ClassesListService>()

    install(ContentNegotiation) {
        json()
    }

    routing {


        // Create user
        post("/acessos/add") {
            try{
                val user = call.receive<Acessos>()
                val id = serviceAcesso.create(user)
                call.respond(HttpStatusCode.Created, id)
            } catch (e: Throwable){
                call.respond(HttpStatusCode.BadRequest, "Erro ao processar JSON: ${e.message}")
            }
        }

        post("/classes/add") {
            try {
                val classe = call.receive<ClassesList>()
                val id = classesListService.create(classe)
                call.respond(HttpStatusCode.Created, id)
            } catch (e: Throwable) {
                call.respond(HttpStatusCode.BadRequest, "Erro ao processar JSON: ${e.message}")
            }
        }

        // Read user
        get("/acessos/{id}") {

        }

        // Update user
        put("/acessos/{id}") {

        }

        // Delete user
        delete("/acessos/{id}") {

        }
    }
}
