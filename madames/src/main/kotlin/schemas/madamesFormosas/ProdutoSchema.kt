package schemas.madamesFormosas

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Produto do catálogo da Madames Formosas.
 *
 * `fotos` guarda, em ordem, o nome do arquivo de cada foto. Um nome simples (ex: "AR185_1.webp")
 * é um asset do site (assets/catalogo/); uma URL completa (http/https) é usada como está.
 * `estoque` é quantas unidades há para vender: com 0 o site mostra "Sem estoque".
 */
@Serializable
data class Produto(
    val codigo: String,
    val nome: String,
    val preco: Double,
    val categoria: String,
    val fotos: List<String>,
    val estoque: Int = 1,
    val ativo: Boolean = true,
)

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class ProdutoService(private val database: Database) {

    object ProdutoTable : Table("produtos") {
        val codigo = varchar("codigo", length = 32)
        val nome = varchar("nome", length = 200)
        val preco = decimal("preco", precision = 10, scale = 2)
        val categoria = varchar("categoria", length = 60).index()
        val ativo = bool("ativo").default(true)
        val estoque = integer("estoque").default(1)

        override val primaryKey = PrimaryKey(codigo)
    }

    object ProdutoFotoTable : Table("produto_fotos") {
        val id = integer("id").autoIncrement()
        val produtoCodigo = varchar("produto_codigo", length = 32)
            .references(ProdutoTable.codigo, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
        val posicao = integer("posicao")
        val arquivo = varchar("arquivo", length = 500)

        override val primaryKey = PrimaryKey(id)

        init {
            uniqueIndex(produtoCodigo, posicao)
        }
    }

    init {
        transaction(database) {
            SchemaUtils.create(ProdutoTable, ProdutoFotoTable)
            SchemaUtils.createMissingTablesAndColumns(ProdutoTable, ProdutoFotoTable)

            // Primeira subida: o banco nasce com o catálogo que antes ficava fixo no site.
            if (ProdutoTable.selectAll().empty()) {
                produtosIniciais.forEach { inserir(it) }
                println("[Madames] Catálogo inicial gravado: ${produtosIniciais.size} produtos.")
            }
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }

    /** Produtos ordenados por código; `incluirInativos` é para a administração. */
    suspend fun readAll(categoria: String? = null, incluirInativos: Boolean = false): List<Produto> = dbQuery {
        val query = ProdutoTable.selectAll()
        if (!incluirInativos) query.andWhere { ProdutoTable.ativo eq true }
        if (categoria != null) query.andWhere { ProdutoTable.categoria eq categoria }
        val rows = query.orderBy(ProdutoTable.codigo).toList()

        val codigos = rows.map { it[ProdutoTable.codigo] }
        val fotos = if (codigos.isEmpty()) emptyMap() else ProdutoFotoTable.selectAll()
            .where { ProdutoFotoTable.produtoCodigo inList codigos }
            .orderBy(ProdutoFotoTable.posicao)
            .groupBy({ it[ProdutoFotoTable.produtoCodigo] }, { it[ProdutoFotoTable.arquivo] })

        rows.map { toProduto(it, fotos[it[ProdutoTable.codigo]].orEmpty()) }
    }

    suspend fun read(codigo: String): Produto? = dbQuery {
        ProdutoTable.selectAll().where { ProdutoTable.codigo eq codigo }.singleOrNull()?.let {
            toProduto(it, fotosDe(codigo))
        }
    }

    /** Retorna false se já existir um produto com o mesmo código. */
    suspend fun create(produto: Produto): Boolean = dbQuery {
        if (ProdutoTable.selectAll().where { ProdutoTable.codigo eq produto.codigo }.empty()) {
            inserir(produto)
            true
        } else {
            false
        }
    }

    /** Substitui os dados e as fotos do produto. Retorna false se ele não existir. */
    suspend fun update(codigo: String, produto: Produto): Boolean = dbQuery {
        val alterados = ProdutoTable.update({ ProdutoTable.codigo eq codigo }) {
            it[nome] = produto.nome
            it[preco] = produto.preco.toPreco()
            it[categoria] = produto.categoria
            it[estoque] = produto.estoque
            it[ativo] = produto.ativo
        }
        if (alterados > 0) {
            ProdutoFotoTable.deleteWhere { produtoCodigo eq codigo }
            inserirFotos(codigo, produto.fotos)
        }
        alterados > 0
    }

    /** Define quantas unidades há em estoque. Retorna false se o produto não existir. */
    suspend fun atualizarEstoque(codigo: String, quantidade: Int): Boolean = dbQuery {
        ProdutoTable.update({ ProdutoTable.codigo eq codigo }) { it[estoque] = quantidade } > 0
    }

    suspend fun delete(codigo: String): Boolean = dbQuery {
        ProdutoFotoTable.deleteWhere { produtoCodigo eq codigo }
        ProdutoTable.deleteWhere { ProdutoTable.codigo eq codigo } > 0
    }

    private fun inserir(produto: Produto) {
        ProdutoTable.insert {
            it[codigo] = produto.codigo
            it[nome] = produto.nome
            it[preco] = produto.preco.toPreco()
            it[categoria] = produto.categoria
            it[estoque] = produto.estoque
            it[ativo] = produto.ativo
        }
        inserirFotos(produto.codigo, produto.fotos)
    }

    private fun inserirFotos(codigo: String, fotos: List<String>) {
        ProdutoFotoTable.batchInsert(fotos.withIndex()) { (index, arquivo) ->
            this[ProdutoFotoTable.produtoCodigo] = codigo
            this[ProdutoFotoTable.posicao] = index
            this[ProdutoFotoTable.arquivo] = arquivo
        }
    }

    private fun fotosDe(codigo: String): List<String> =
        ProdutoFotoTable.selectAll()
            .where { ProdutoFotoTable.produtoCodigo eq codigo }
            .orderBy(ProdutoFotoTable.posicao)
            .map { it[ProdutoFotoTable.arquivo] }

    private fun Double.toPreco(): BigDecimal = BigDecimal.valueOf(this).setScale(2, RoundingMode.HALF_UP)

    private fun toProduto(row: ResultRow, fotos: List<String>) = Produto(
        codigo = row[ProdutoTable.codigo],
        nome = row[ProdutoTable.nome],
        preco = row[ProdutoTable.preco].toDouble(),
        categoria = row[ProdutoTable.categoria],
        fotos = fotos,
        estoque = row[ProdutoTable.estoque],
        ativo = row[ProdutoTable.ativo],
    )
}
