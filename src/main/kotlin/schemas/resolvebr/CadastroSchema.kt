package schemas.resolvebr

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

// =================================================================
// 1. MODELOS DE DADOS
// =================================================================

@Serializable
data class Cadastro(
    val email: String,
    val nome: String,
    val descricao: String,
    val categoria: String,
    val endereco: String,
    val telefone: String,
    val senha: String,
    val instagram: String? = null,
)

@Serializable
data class CadastroDto(
    val id: String,
    val email: String,
    val nome: String,
    val descricao: String,
    val categoria: String,
    val endereco: String,
    val telefone: String,
    val instagram: String? = null,
    val criadoEm: String,
    val atualizadoEm: String,
    // senha NÃO é retornada por segurança
)

@Serializable
data class LoginRequest(
    val email: String,
    val senha: String,
)

@Serializable
data class CadastroPatch(
    val email: String? = null,
    val nome: String? = null,
    val descricao: String? = null,
    val categoria: String? = null,
    val endereco: String? = null,
    val telefone: String? = null,
    val instagram: String? = null,
    val senha: String? = null,
)

// =================================================================
// 2. SERVICE
// =================================================================

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class CadastroService(private val database: Database) {

    // ── TABELA ────────────────────────────────────────────────────
    object CadastrosTable : Table("cadastros") {
        val id           = varchar("id", 36)
        val email        = varchar("email", 200)
        val nome         = varchar("nome", 200)
        val descricao    = text("descricao")
        val categoria    = varchar("categoria", 200)
        val endereco     = varchar("endereco", 500)
        val telefone     = varchar("telefone", 50)
        val senha        = varchar("senha", 200)
        val instagram    = varchar("instagram", 200).nullable()
        val criadoEm     = varchar("criado_em", 50)
        val atualizadoEm = varchar("atualizado_em", 50)

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(CadastrosTable)
            SchemaUtils.createMissingTablesAndColumns(CadastrosTable)
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }

    private fun now(): String =
        java.time.Instant.now().toString()

    // ── MAPEAMENTO (senha excluída do DTO) ────────────────────────
    private fun toDto(row: ResultRow) = CadastroDto(
        id           = row[CadastrosTable.id],
        email        = row[CadastrosTable.email],
        nome         = row[CadastrosTable.nome],
        descricao    = row[CadastrosTable.descricao],
        categoria    = row[CadastrosTable.categoria],
        endereco     = row[CadastrosTable.endereco],
        telefone     = row[CadastrosTable.telefone],
        instagram    = row[CadastrosTable.instagram],
        criadoEm     = row[CadastrosTable.criadoEm],
        atualizadoEm = row[CadastrosTable.atualizadoEm],
    )

    // ── CREATE ────────────────────────────────────────────────────
    suspend fun create(cadastro: Cadastro, id: String): CadastroDto {
        val ts = now()
        return dbQuery {
            CadastrosTable.insert {
                it[CadastrosTable.id]           = id
                it[CadastrosTable.email]        = cadastro.email
                it[CadastrosTable.nome]         = cadastro.nome
                it[CadastrosTable.descricao]    = cadastro.descricao
                it[CadastrosTable.categoria]    = cadastro.categoria
                it[CadastrosTable.endereco]     = cadastro.endereco
                it[CadastrosTable.telefone]     = cadastro.telefone
                it[CadastrosTable.senha]        = cadastro.senha
                it[CadastrosTable.instagram]    = cadastro.instagram
                it[CadastrosTable.criadoEm]     = ts
                it[CadastrosTable.atualizadoEm] = ts
            }
            CadastrosTable.selectAll()
                .where { CadastrosTable.id eq id }
                .single()
                .let { toDto(it) }
        }
    }

    // ── READ ALL (com filtros + paginação) ────────────────────────
    suspend fun readAll(
        categoria: String? = null,
        cidade: String? = null,
        page: Int = 1,
        limit: Int = 20,
    ): List<CadastroDto> = dbQuery {
        var query = CadastrosTable.selectAll()
        if (categoria != null) query = query.andWhere { CadastrosTable.categoria eq categoria }
        if (cidade != null)    query = query.andWhere { CadastrosTable.endereco like "%$cidade%" }

        query
            .orderBy(CadastrosTable.criadoEm, SortOrder.DESC)
            .limit(limit).offset(((page - 1) * limit).toLong())
            .map { toDto(it) }
    }

    // ── READ BY ID ────────────────────────────────────────────────
    suspend fun readById(id: String): CadastroDto? = dbQuery {
        CadastrosTable.selectAll()
            .where { CadastrosTable.id eq id }
            .singleOrNull()
            ?.let { toDto(it) }
    }

    // ── READ BY EMAIL ─────────────────────────────────────────────
    suspend fun readByEmail(email: String): CadastroDto? = dbQuery {
        CadastrosTable.selectAll()
            .where { CadastrosTable.email eq email }
            .singleOrNull()
            ?.let { toDto(it) }
    }

    // ── UPDATE (completo) ─────────────────────────────────────────
    suspend fun update(id: String, cadastro: Cadastro): CadastroDto? = dbQuery {
        val updated = CadastrosTable.update({ CadastrosTable.id eq id }) {
            it[CadastrosTable.email]        = cadastro.email
            it[CadastrosTable.nome]         = cadastro.nome
            it[CadastrosTable.descricao]    = cadastro.descricao
            it[CadastrosTable.categoria]    = cadastro.categoria
            it[CadastrosTable.endereco]     = cadastro.endereco
            it[CadastrosTable.telefone]     = cadastro.telefone
            it[CadastrosTable.senha]        = cadastro.senha
            it[CadastrosTable.instagram]    = cadastro.instagram
            it[CadastrosTable.atualizadoEm] = now()
        }
        if (updated == 0) return@dbQuery null
        CadastrosTable.selectAll()
            .where { CadastrosTable.id eq id }
            .single()
            .let { toDto(it) }
    }

    // ── PATCH (parcial) ───────────────────────────────────────────
    suspend fun patch(id: String, fields: CadastroPatch): CadastroDto? = dbQuery {
        val updated = CadastrosTable.update({ CadastrosTable.id eq id }) {
            fields.email?.let     { v -> it[CadastrosTable.email]     = v }
            fields.nome?.let      { v -> it[CadastrosTable.nome]      = v }
            fields.descricao?.let { v -> it[CadastrosTable.descricao] = v }
            fields.categoria?.let { v -> it[CadastrosTable.categoria] = v }
            fields.endereco?.let  { v -> it[CadastrosTable.endereco]  = v }
            fields.telefone?.let  { v -> it[CadastrosTable.telefone]  = v }
            fields.instagram?.let { v -> it[CadastrosTable.instagram] = v }
            fields.senha?.let     { v -> it[CadastrosTable.senha]     = v }
            it[CadastrosTable.atualizadoEm] = now()
        }
        if (updated == 0) return@dbQuery null
        CadastrosTable.selectAll()
            .where { CadastrosTable.id eq id }
            .single()
            .let { toDto(it) }
    }

    // ── DELETE ────────────────────────────────────────────────────
    suspend fun delete(id: String): Boolean = dbQuery {
        CadastrosTable.deleteWhere { CadastrosTable.id eq id } > 0
    }

    // ── LOGIN ─────────────────────────────────────────────────────
    // Verifica email + senha e retorna o CadastroDto se válido, null se inválido.
    suspend fun login(email: String, senha: String): CadastroDto? = dbQuery {
        val row = CadastrosTable.selectAll()
            .where { CadastrosTable.email eq email }
            .singleOrNull() ?: return@dbQuery null

        val senhaHash = row[CadastrosTable.senha]
        if (senha != senhaHash) return@dbQuery null

        toDto(row)
    }
}
