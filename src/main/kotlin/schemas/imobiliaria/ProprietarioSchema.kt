package schemas.imobiliaria

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class Proprietario(
    val id: Int? = null,
    // --- DADOS PESSOAIS ---
    val nome: String,
    val codigo: String? = "AUTO",
    val cpf: String? = null,
    val rg: String? = null,
    val dataNascimento: String? = null,
    val sexoFeminino: Boolean = false,
    val pessoaJuridica: Boolean = false,
    val confidencial: Boolean = false,
    val inativo: Boolean = false,
    val naturalidade: String? = null,
    val nacionalidade: String? = null,
    val profissao: String? = null,
    val estadoCivil: String? = null,

    // --- ENDEREÇO & CONTATO ---
    val endereco: String? = null,
    val numero: String? = null,
    val complemento: String? = null,
    val cep: String? = null,
    val bairro: String? = null,
    val cidade: String? = null,
    val uf: String? = null,
    val telefonePrincipal: String? = null,
    val emailPrincipal: String? = null,
    val recados: String? = null,

    // --- HONORÁRIOS ---
    val honorariosAdm: Double? = null,
    val honorariosVenda: Double? = null,
    val honorarios1Aluguel: Double? = null,
    val diasRepasse: Int? = null,

    // --- CÔNJUGE ---
    val conjugeNome: String? = null,
    val conjugeCpf: String? = null,
    val conjugeRg: String? = null,
    val conjugeNascimento: String? = null,
    val regimeBens: String? = null,

    // --- OUTROS ---
    val siteSenha: String? = null
)

class ProprietarioService(private val db: Database) {

    // Tabela definida DENTRO do Service
    object ProprietariosTable : Table("proprietarios") {
        val id = integer("id").autoIncrement()

        // Dados Pessoais
        val nome = varchar("nome", 255)
        val codigo = varchar("codigo", 50).default("AUTO")
        val cpf = varchar("cpf", 20).nullable()
        val rg = varchar("rg", 20).nullable()
        val dataNascimento = varchar("data_nascimento", 20).nullable()
        val sexoFeminino = bool("sexo_feminino").default(false)
        val pessoaJuridica = bool("pessoa_juridica").default(false)
        val confidencial = bool("confidencial").default(false)
        val inativo = bool("inativo").default(false)
        val naturalidade = varchar("naturalidade", 100).nullable()
        val nacionalidade = varchar("nacionalidade", 100).nullable()
        val profissao = varchar("profissao", 100).nullable()
        val estadoCivil = varchar("estado_civil", 50).nullable()

        // Endereço
        val endereco = varchar("endereco", 255).nullable()
        val numero = varchar("numero", 20).nullable()
        val complemento = varchar("complemento", 100).nullable()
        val cep = varchar("cep", 20).nullable()
        val bairro = varchar("bairro", 100).nullable()
        val cidade = varchar("cidade", 100).nullable()
        val uf = varchar("uf", 5).nullable()
        val telefonePrincipal = varchar("telefone_principal", 50).nullable()
        val emailPrincipal = varchar("email_principal", 100).nullable()
        val recados = text("recados").nullable()

        // Honorários
        val honorariosAdm = double("honorarios_adm").nullable()
        val honorariosVenda = double("honorarios_venda").nullable()
        val honorarios1Aluguel = double("honorarios_1_aluguel").nullable()
        val diasRepasse = integer("dias_repasse").nullable()

        // Cônjuge
        val conjugeNome = varchar("conjuge_nome", 255).nullable()
        val conjugeCpf = varchar("conjuge_cpf", 20).nullable()
        val conjugeRg = varchar("conjuge_rg", 20).nullable()
        val conjugeNascimento = varchar("conjuge_nascimento", 20).nullable()
        val regimeBens = varchar("regime_bens", 100).nullable()

        // Outros
        val siteSenha = varchar("site_senha", 100).nullable()

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(db) {
            SchemaUtils.create(ProprietariosTable)
        }
    }

    // --- CREATE ---
    suspend fun create(prop: Proprietario): Int {
        return dbQuery {
            ProprietariosTable.insert {
                it[nome] = prop.nome
                it[codigo] = prop.codigo ?: "AUTO"
                it[cpf] = prop.cpf
                it[rg] = prop.rg
                it[dataNascimento] = prop.dataNascimento
                it[sexoFeminino] = prop.sexoFeminino
                it[pessoaJuridica] = prop.pessoaJuridica
                it[confidencial] = prop.confidencial
                it[inativo] = prop.inativo
                it[naturalidade] = prop.naturalidade
                it[nacionalidade] = prop.nacionalidade
                it[profissao] = prop.profissao
                it[estadoCivil] = prop.estadoCivil

                it[endereco] = prop.endereco
                it[numero] = prop.numero
                it[complemento] = prop.complemento
                it[cep] = prop.cep
                it[bairro] = prop.bairro
                it[cidade] = prop.cidade
                it[uf] = prop.uf
                it[telefonePrincipal] = prop.telefonePrincipal
                it[emailPrincipal] = prop.emailPrincipal
                it[recados] = prop.recados

                it[honorariosAdm] = prop.honorariosAdm
                it[honorariosVenda] = prop.honorariosVenda
                it[honorarios1Aluguel] = prop.honorarios1Aluguel
                it[diasRepasse] = prop.diasRepasse

                it[conjugeNome] = prop.conjugeNome
                it[conjugeCpf] = prop.conjugeCpf
                it[conjugeRg] = prop.conjugeRg
                it[conjugeNascimento] = prop.conjugeNascimento
                it[regimeBens] = prop.regimeBens

                it[siteSenha] = prop.siteSenha
            }[ProprietariosTable.id]
        }
    }

    // --- READ ALL ---
    suspend fun readAll(): List<Proprietario> {
        return dbQuery {
            ProprietariosTable.selectAll().map { toProprietario(it) }
        }
    }


    // --- UPDATE ---
    suspend fun update(id: Int, prop: Proprietario): Boolean {
        return dbQuery {
            ProprietariosTable.update({ ProprietariosTable.id eq id }) {
                it[nome] = prop.nome
                // Nota: Geralmente não atualizamos o código se for "AUTO", mas aqui deixei editável
                if (prop.codigo != null) it[codigo] = prop.codigo

                it[cpf] = prop.cpf
                it[rg] = prop.rg
                it[dataNascimento] = prop.dataNascimento
                it[sexoFeminino] = prop.sexoFeminino
                it[pessoaJuridica] = prop.pessoaJuridica
                it[confidencial] = prop.confidencial
                it[inativo] = prop.inativo
                it[naturalidade] = prop.naturalidade
                it[nacionalidade] = prop.nacionalidade
                it[profissao] = prop.profissao
                it[estadoCivil] = prop.estadoCivil

                it[endereco] = prop.endereco
                it[numero] = prop.numero
                it[complemento] = prop.complemento
                it[cep] = prop.cep
                it[bairro] = prop.bairro
                it[cidade] = prop.cidade
                it[uf] = prop.uf
                it[telefonePrincipal] = prop.telefonePrincipal
                it[emailPrincipal] = prop.emailPrincipal
                it[recados] = prop.recados

                it[honorariosAdm] = prop.honorariosAdm
                it[honorariosVenda] = prop.honorariosVenda
                it[honorarios1Aluguel] = prop.honorarios1Aluguel
                it[diasRepasse] = prop.diasRepasse

                it[conjugeNome] = prop.conjugeNome
                it[conjugeCpf] = prop.conjugeCpf
                it[conjugeRg] = prop.conjugeRg
                it[conjugeNascimento] = prop.conjugeNascimento
                it[regimeBens] = prop.regimeBens

                it[siteSenha] = prop.siteSenha
            } > 0 // Retorna true se pelo menos 1 linha foi afetada
        }
    }

    // --- DELETE ---
    suspend fun delete(id: Int): Boolean {
        return dbQuery {
            ProprietariosTable.deleteWhere { ProprietariosTable.id eq id } > 0
        }
    }

    // --- Mapeador Auxiliar (Row -> Objeto) ---
    private fun toProprietario(row: ResultRow): Proprietario = Proprietario(
        id = row[ProprietariosTable.id],
        nome = row[ProprietariosTable.nome],
        codigo = row[ProprietariosTable.codigo],
        cpf = row[ProprietariosTable.cpf],
        rg = row[ProprietariosTable.rg],
        dataNascimento = row[ProprietariosTable.dataNascimento],
        sexoFeminino = row[ProprietariosTable.sexoFeminino],
        pessoaJuridica = row[ProprietariosTable.pessoaJuridica],
        confidencial = row[ProprietariosTable.confidencial],
        inativo = row[ProprietariosTable.inativo],
        naturalidade = row[ProprietariosTable.naturalidade],
        nacionalidade = row[ProprietariosTable.nacionalidade],
        profissao = row[ProprietariosTable.profissao],
        estadoCivil = row[ProprietariosTable.estadoCivil],

        endereco = row[ProprietariosTable.endereco],
        numero = row[ProprietariosTable.numero],
        complemento = row[ProprietariosTable.complemento],
        cep = row[ProprietariosTable.cep],
        bairro = row[ProprietariosTable.bairro],
        cidade = row[ProprietariosTable.cidade],
        uf = row[ProprietariosTable.uf],
        telefonePrincipal = row[ProprietariosTable.telefonePrincipal],
        emailPrincipal = row[ProprietariosTable.emailPrincipal],
        recados = row[ProprietariosTable.recados],

        honorariosAdm = row[ProprietariosTable.honorariosAdm],
        honorariosVenda = row[ProprietariosTable.honorariosVenda],
        honorarios1Aluguel = row[ProprietariosTable.honorarios1Aluguel],
        diasRepasse = row[ProprietariosTable.diasRepasse],

        conjugeNome = row[ProprietariosTable.conjugeNome],
        conjugeCpf = row[ProprietariosTable.conjugeCpf],
        conjugeRg = row[ProprietariosTable.conjugeRg],
        conjugeNascimento = row[ProprietariosTable.conjugeNascimento],
        regimeBens = row[ProprietariosTable.regimeBens],

        siteSenha = row[ProprietariosTable.siteSenha]
    )

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, db = db) { block() }
}