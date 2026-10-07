package schemas.sentinela

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import schemas.sentinela.SentinelaUserService.SentinelaUserTable
import java.time.Instant

/** Outro usuário do Sentinela envolvido num compartilhamento (quem recebe ou quem compartilha). */
@Serializable
data class SentinelaSharePartyDto(
    val uid: String,
    val name: String,
    val email: String,
    val createdAt: String,
)

@Serializable
data class SentinelaSharesOverviewDto(
    val sharedWith: List<SentinelaSharePartyDto>,
    val sharedWithMe: List<SentinelaSharePartyDto>,
    val maxShares: Int,
)

/**
 * Compartilhamento de conta: o dono ([SentinelaShareTable.ownerUid]) dá ao
 * convidado ([SentinelaShareTable.granteeUid]) acesso de leitura a todas as
 * suas gravações. Não é transitivo: o convidado vê só as gravações do próprio
 * dono, não as que foram compartilhadas com o dono.
 */
@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class SentinelaShareService(private val database: Database) {

    object SentinelaShareTable : Table("sentinela_account_shares") {
        val id = long("id").autoIncrement()
        val ownerUid = varchar("owner_uid", length = 128).index()
        val granteeUid = varchar("grantee_uid", length = 128).index()
        val createdAt = varchar("created_at", length = 30)

        override val primaryKey = PrimaryKey(id)

        init {
            uniqueIndex(ownerUid, granteeUid)
        }
    }

    init {
        transaction(database) {
            SchemaUtils.create(SentinelaShareTable)
            SchemaUtils.createMissingTablesAndColumns(SentinelaShareTable)
        }
    }

    suspend fun create(ownerUid: String, granteeUid: String) {
        dbQuery {
            SentinelaShareTable.insert {
                it[this.ownerUid] = ownerUid
                it[this.granteeUid] = granteeUid
                it[createdAt] = Instant.now().toString()
            }
        }
    }

    suspend fun exists(ownerUid: String, granteeUid: String): Boolean = dbQuery {
        SentinelaShareTable
            .selectAll()
            .where { (SentinelaShareTable.ownerUid eq ownerUid) and (SentinelaShareTable.granteeUid eq granteeUid) }
            .any()
    }

    suspend fun countByOwner(ownerUid: String): Long = dbQuery {
        SentinelaShareTable.selectAll().where { SentinelaShareTable.ownerUid eq ownerUid }.count()
    }

    /** Remove o compartilhamento; retorna false se ele não existia. */
    suspend fun delete(ownerUid: String, granteeUid: String): Boolean = dbQuery {
        SentinelaShareTable.deleteWhere {
            (SentinelaShareTable.ownerUid eq ownerUid) and (SentinelaShareTable.granteeUid eq granteeUid)
        } > 0
    }

    /** Uids dos donos que compartilharam a conta com [granteeUid]. */
    suspend fun ownerUidsSharedWith(granteeUid: String): List<String> = dbQuery {
        SentinelaShareTable
            .selectAll()
            .where { SentinelaShareTable.granteeUid eq granteeUid }
            .map { it[SentinelaShareTable.ownerUid] }
    }

    /** Uids dos convidados que podem ver a conta de [ownerUid]. */
    suspend fun granteeUidsOf(ownerUid: String): List<String> = dbQuery {
        SentinelaShareTable
            .selectAll()
            .where { SentinelaShareTable.ownerUid eq ownerUid }
            .map { it[SentinelaShareTable.granteeUid] }
    }

    suspend fun overview(uid: String, maxShares: Int): SentinelaSharesOverviewDto = dbQuery {
        SentinelaSharesOverviewDto(
            sharedWith = parties(where = SentinelaShareTable.ownerUid, equals = uid, partyColumn = SentinelaShareTable.granteeUid),
            sharedWithMe = parties(where = SentinelaShareTable.granteeUid, equals = uid, partyColumn = SentinelaShareTable.ownerUid),
            maxShares = maxShares,
        )
    }

    private fun parties(
        where: org.jetbrains.exposed.sql.Column<String>,
        equals: String,
        partyColumn: org.jetbrains.exposed.sql.Column<String>,
    ): List<SentinelaSharePartyDto> =
        SentinelaShareTable
            .join(SentinelaUserTable, JoinType.INNER, partyColumn, SentinelaUserTable.uid)
            .selectAll()
            .where { where eq equals }
            .orderBy(SentinelaShareTable.createdAt, SortOrder.ASC)
            .map {
                SentinelaSharePartyDto(
                    uid = it[SentinelaUserTable.uid],
                    name = it[SentinelaUserTable.name],
                    email = it[SentinelaUserTable.email],
                    createdAt = it[SentinelaShareTable.createdAt],
                )
            }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }
}
