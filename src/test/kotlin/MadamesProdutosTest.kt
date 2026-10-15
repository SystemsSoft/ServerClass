import com.class_erp.configureContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import routes.madamesFormosas.madamesProdutosRouting
import schemas.madamesFormosas.Produto
import schemas.madamesFormosas.ProdutoService
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MadamesProdutosTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun novoBanco() = Database.connect(
        "jdbc:h2:mem:madames_${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1",
        driver = "org.h2.Driver",
    )

    @AfterTest
    fun limparToken() {
        System.clearProperty("madames.adminToken")
    }

    @Test
    fun `primeira subida grava o catalogo inicial com fotos em ordem`() = testApplication {
        val service = ProdutoService(novoBanco())
        application {
            configureContentNegotiation()
            madamesProdutosRouting(service)
        }

        val produtos = json.decodeFromString<List<Produto>>(client.get("/madames/produtos").bodyAsText())
        assertEquals(25, produtos.size)
        assertEquals(produtos.map { it.codigo }.sorted(), produtos.map { it.codigo })

        val brt027 = produtos.single { it.codigo == "BRT027" }
        assertEquals(22.38, brt027.preco)
        assertEquals((1..6).map { "BRT027_$it.webp" }, brt027.fotos)

        val pulseiras = json.decodeFromString<List<Produto>>(
            client.get("/madames/produtos?categoria=Pulseiras").bodyAsText()
        )
        assertEquals(setOf("EJ3246", "PL1033", "PUL106", "PUL128"), pulseiras.map { it.codigo }.toSet())
    }

    @Test
    fun `seed nao duplica ao reiniciar`() {
        val banco = novoBanco()
        ProdutoService(banco)
        val service = ProdutoService(banco)
        kotlinx.coroutines.runBlocking { assertEquals(25, service.readAll().size) }
    }

    @Test
    fun `escrita exige token e respeita inativos`() = testApplication {
        System.setProperty("madames.adminToken", "segredo-teste")
        val service = ProdutoService(novoBanco())
        application {
            configureContentNegotiation()
            madamesProdutosRouting(service)
        }

        val novo = """{"codigo":"NOVO1","nome":"Anel","preco":10.5,"categoria":"Anéis","fotos":["https://x/1.webp"]}"""
        val semToken = client.post("/madames/produtos") {
            contentType(ContentType.Application.Json); setBody(novo)
        }
        assertEquals(HttpStatusCode.Unauthorized, semToken.status)

        val criado = client.post("/madames/produtos") {
            bearerAuth("segredo-teste"); contentType(ContentType.Application.Json); setBody(novo)
        }
        assertEquals(HttpStatusCode.Created, criado.status)

        val duplicado = client.post("/madames/produtos") {
            bearerAuth("segredo-teste"); contentType(ContentType.Application.Json); setBody(novo)
        }
        assertEquals(HttpStatusCode.Conflict, duplicado.status)

        val inativo = client.put("/madames/produtos/NOVO1") {
            bearerAuth("segredo-teste"); contentType(ContentType.Application.Json)
            setBody("""{"codigo":"x","nome":"Anel","preco":12,"categoria":"Anéis","fotos":["a.webp","b.webp"],"ativo":false}""")
        }
        assertEquals(HttpStatusCode.OK, inativo.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/madames/produtos/NOVO1").status)
        assertTrue(json.decodeFromString<List<Produto>>(client.get("/madames/produtos").bodyAsText()).none { it.codigo == "NOVO1" })

        val todos = json.decodeFromString<List<Produto>>(
            client.get("/madames/produtos/todos") { bearerAuth("segredo-teste") }.bodyAsText()
        )
        assertEquals(listOf("a.webp", "b.webp"), todos.single { it.codigo == "NOVO1" }.fotos)

        assertEquals(HttpStatusCode.OK, client.delete("/madames/produtos/NOVO1") { bearerAuth("segredo-teste") }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/madames/produtos/NOVO1") { bearerAuth("segredo-teste") }.status)
    }
}
