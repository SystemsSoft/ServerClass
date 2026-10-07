package schemas.sentinela

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

enum class SentinelaRecordingStatus { RECORDING, COMPLETED, INTERRUPTED }

/** Andamento da transcrição do vídeo (coluna `transcript_status`; nula = nunca foi enfileirada). */
enum class SentinelaTranscriptStatus { PENDING, PROCESSING, DONE, NO_AUDIO, FAILED }

/** Gravação com vídeo salvo cuja transcrição ficou pela metade (ex.: servidor reiniciado no meio). */
data class SentinelaPendingTranscription(val recordingId: String, val s3Key: String)

@Serializable
data class SentinelaLocationDto(
    val lat: Double,
    val lng: Double,
    val accuracy: Double?,
    val speed: Double?,
    val capturedAt: String,
)

@Serializable
data class SentinelaRecordingSummaryDto(
    val id: String,
    val status: String,
    val mimeType: String,
    val sizeBytes: Long,
    val chunkCount: Int,
    val startedAt: String,
    val endedAt: String?,
    val hasVideo: Boolean,
    val startLocation: SentinelaLocationDto?,
    /** Já existe um relatório em PDF guardado desta gravação (o app mostra o selo e abre sem gerar de novo). */
    val hasReport: Boolean = false,
    /** Preenchido só quando a gravação é de outro usuário que compartilhou a conta. */
    val sharedBy: SentinelaRecordingOwnerDto? = null,
)

@Serializable
data class SentinelaRecordingOwnerDto(
    val name: String,
    val email: String,
)

@Serializable
data class SentinelaRecordingDetailDto(
    val recording: SentinelaRecordingSummaryDto,
    val locations: List<SentinelaLocationDto>,
    val videoUrl: String?,
    val transcript: String? = null,
    val transcriptStatus: String? = null,
    /** Quando o relatório guardado foi gerado (ISO-8601 UTC); nulo se ainda não existe. */
    val reportGeneratedAt: String? = null,
)

/** Relatório em PDF guardado no S3 e o que valia quando foi gerado (para saber se ainda pode ser reaproveitado). */
data class SentinelaStoredReport(
    val s3Key: String,
    val generatedAt: Instant,
    /** O PDF já incluía o texto da transcrição. */
    val hasTranscript: Boolean,
    /** Até quando vale o link do vídeo que o QR code do PDF aponta (nulo se o PDF não tem QR). */
    val linkExpiresAt: Instant?,
)

/**
 * Um relatório guardado ainda pode ser reaproveitado? Sim, salvo se mudou algo que o tornaria desatualizado:
 * o link do vídeo (QR code) já expirou, ou a transcrição ficou pronta depois de o PDF ter sido gerado sem ela.
 */
fun SentinelaStoredReport.isReusable(transcriptReadyNow: Boolean, now: Instant = Instant.now()): Boolean {
    if (linkExpiresAt != null && !linkExpiresAt.isAfter(now)) return false
    if (!hasTranscript && transcriptReadyNow) return false
    return true
}

/** Link público do vídeo: quem tem o [token] assiste até [expiresAt] (ISO-8601 UTC). */
@Serializable
data class SentinelaVideoShareLinkDto(
    val token: String,
    val expiresAt: String,
)

/** O que a página pública do link precisa para tocar o vídeo. */
data class SentinelaSharedVideo(val s3Key: String, val startedAt: String)

/** Resposta leve só com a transcrição (o app consulta isso ao pedir "gerar transcrição"). */
@Serializable
data class SentinelaTranscriptDto(
    val status: String?,
    val transcript: String?,
)

/** Uso interno: a chave do S3 nunca vai para o cliente, só a URL pré-assinada. */
data class SentinelaRecordingWithLocations(
    val ownerUid: String,
    val summary: SentinelaRecordingSummaryDto,
    val s3Key: String?,
    val locations: List<SentinelaLocationDto>,
    val transcript: String? = null,
    val transcriptStatus: String? = null,
    val report: SentinelaStoredReport? = null,
    /** Relatório guardado para quem vê a gravação por compartilhamento de conta (sem QR code do vídeo). */
    val sharedReport: SentinelaStoredReport? = null,
)

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
class SentinelaRecordingService(private val database: Database) {

    object SentinelaRecordingTable : Table("sentinela_recordings") {
        val id = varchar("id", length = 36)
        val uid = varchar("uid", length = 128).index()
        val status = varchar("status", length = 20)
        val mimeType = varchar("mime_type", length = 100)
        val s3Key = varchar("s3_key", length = 300).nullable()
        val sizeBytes = long("size_bytes").default(0)
        val chunkCount = integer("chunk_count").default(0)
        val startedAt = varchar("started_at", length = 30)
        val endedAt = varchar("ended_at", length = 30).nullable()
        val transcript = largeText("transcript").nullable()
        val transcriptStatus = varchar("transcript_status", length = 20).nullable()
        val shareToken = varchar("share_token", length = 64).nullable().uniqueIndex()
        val shareExpiresAt = varchar("share_expires_at", length = 30).nullable()
        val reportS3Key = varchar("report_s3_key", length = 300).nullable()
        val reportGeneratedAt = varchar("report_generated_at", length = 30).nullable()
        val reportHasTranscript = bool("report_has_transcript").default(false)
        val reportLinkExpiresAt = varchar("report_link_expires_at", length = 30).nullable()
        val sharedReportS3Key = varchar("shared_report_s3_key", length = 300).nullable()
        val sharedReportGeneratedAt = varchar("shared_report_generated_at", length = 30).nullable()
        val sharedReportHasTranscript = bool("shared_report_has_transcript").default(false)

        override val primaryKey = PrimaryKey(id)
    }

    object SentinelaRecordingLocationTable : Table("sentinela_recording_locations") {
        val id = long("id").autoIncrement()
        val recordingId = varchar("recording_id", length = 36).index()
        val lat = double("lat")
        val lng = double("lng")
        val accuracy = double("accuracy").nullable()
        val speed = double("speed").nullable()
        val capturedAt = varchar("captured_at", length = 30)

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(SentinelaRecordingTable, SentinelaRecordingLocationTable)
            SchemaUtils.createMissingTablesAndColumns(SentinelaRecordingTable, SentinelaRecordingLocationTable)
        }
    }

    suspend fun create(uid: String, mimeType: String): String {
        val recordingId = UUID.randomUUID().toString()
        dbQuery {
            SentinelaRecordingTable.insert {
                it[id] = recordingId
                it[this.uid] = uid
                it[status] = SentinelaRecordingStatus.RECORDING.name
                it[this.mimeType] = mimeType
                it[startedAt] = Instant.now().toString()
            }
        }
        return recordingId
    }

    suspend fun addLocation(
        recordingId: String,
        lat: Double,
        lng: Double,
        accuracy: Double?,
        speed: Double?,
        capturedAt: String,
    ) {
        dbQuery {
            SentinelaRecordingLocationTable.insert {
                it[this.recordingId] = recordingId
                it[this.lat] = lat
                it[this.lng] = lng
                it[this.accuracy] = accuracy
                it[this.speed] = speed
                it[this.capturedAt] = capturedAt
            }
        }
    }

    suspend fun finalize(
        recordingId: String,
        status: SentinelaRecordingStatus,
        s3Key: String?,
        sizeBytes: Long,
        chunkCount: Int,
    ) {
        dbQuery {
            SentinelaRecordingTable.update(where = { SentinelaRecordingTable.id eq recordingId }) {
                it[this.status] = status.name
                it[this.s3Key] = s3Key
                it[this.sizeBytes] = sizeBytes
                it[this.chunkCount] = chunkCount
                it[endedAt] = Instant.now().toString()
            }
        }
    }

    suspend fun setTranscriptStatus(recordingId: String, status: SentinelaTranscriptStatus) {
        dbQuery {
            SentinelaRecordingTable.update(where = { SentinelaRecordingTable.id eq recordingId }) {
                it[transcriptStatus] = status.name
            }
        }
    }

    suspend fun saveTranscript(recordingId: String, text: String) {
        dbQuery {
            SentinelaRecordingTable.update(where = { SentinelaRecordingTable.id eq recordingId }) {
                it[transcript] = text
                it[transcriptStatus] = SentinelaTranscriptStatus.DONE.name
            }
        }
    }

    /**
     * Link público para assistir ao vídeo. Reaproveita o link atual enquanto ele não expira, para o
     * mesmo endereço poder ser compartilhado mais de uma vez; depois de expirado, gera um novo.
     * Quem chama deve garantir que o usuário é o dono e que a gravação tem vídeo.
     */
    suspend fun ensureShareLink(recordingId: String, validFor: Duration): SentinelaVideoShareLinkDto {
        return dbQuery {
            val row = SentinelaRecordingTable
                .select(SentinelaRecordingTable.shareToken, SentinelaRecordingTable.shareExpiresAt)
                .where { SentinelaRecordingTable.id eq recordingId }
                .single()
            val currentToken = row[SentinelaRecordingTable.shareToken]
            val currentExpiry = row[SentinelaRecordingTable.shareExpiresAt]
            if (currentToken != null && currentExpiry != null && !isExpired(currentExpiry)) {
                return@dbQuery SentinelaVideoShareLinkDto(currentToken, currentExpiry)
            }

            val token = newShareToken()
            val expiresAt = Instant.now().plus(validFor).toString()
            SentinelaRecordingTable.update(where = { SentinelaRecordingTable.id eq recordingId }) {
                it[shareToken] = token
                it[shareExpiresAt] = expiresAt
            }
            SentinelaVideoShareLinkDto(token, expiresAt)
        }
    }

    /** O vídeo do link, ou null se o token não existe, expirou ou a gravação não tem vídeo. */
    suspend fun findSharedVideo(token: String): SentinelaSharedVideo? {
        return dbQuery {
            val row = SentinelaRecordingTable
                .select(SentinelaRecordingTable.s3Key, SentinelaRecordingTable.startedAt, SentinelaRecordingTable.shareExpiresAt)
                .where { SentinelaRecordingTable.shareToken eq token }
                .singleOrNull()
                ?: return@dbQuery null
            val expiresAt = row[SentinelaRecordingTable.shareExpiresAt]
            val s3Key = row[SentinelaRecordingTable.s3Key]
            if (expiresAt == null || isExpired(expiresAt) || s3Key == null) return@dbQuery null
            SentinelaSharedVideo(s3Key, row[SentinelaRecordingTable.startedAt])
        }
    }

    /**
     * Marca a transcrição como PENDING só se ela nunca foi pedida ou falhou; devolve true se foi este
     * chamado que a iniciou. Atômico: cliques repetidos no botão não enfileiram o mesmo trabalho duas vezes.
     */
    suspend fun requestTranscription(recordingId: String): Boolean {
        return dbQuery {
            SentinelaRecordingTable.update(where = {
                (SentinelaRecordingTable.id eq recordingId) and
                    (SentinelaRecordingTable.transcriptStatus.isNull() or
                        (SentinelaRecordingTable.transcriptStatus eq SentinelaTranscriptStatus.FAILED.name))
            }) {
                it[transcriptStatus] = SentinelaTranscriptStatus.PENDING.name
            } > 0
        }
    }

    /**
     * Guarda (ou substitui) o relatório em PDF desta gravação: o do dono ([forOwner]) ou o de quem a vê por
     * compartilhamento de conta, que não tem QR code e por isso nunca expira pelo link.
     */
    suspend fun saveReport(recordingId: String, report: SentinelaStoredReport, forOwner: Boolean = true) {
        dbQuery {
            SentinelaRecordingTable.update(where = { SentinelaRecordingTable.id eq recordingId }) {
                if (forOwner) {
                    it[reportS3Key] = report.s3Key
                    it[reportGeneratedAt] = report.generatedAt.toString()
                    it[reportHasTranscript] = report.hasTranscript
                    it[reportLinkExpiresAt] = report.linkExpiresAt?.toString()
                } else {
                    it[sharedReportS3Key] = report.s3Key
                    it[sharedReportGeneratedAt] = report.generatedAt.toString()
                    it[sharedReportHasTranscript] = report.hasTranscript
                }
            }
        }
    }

    /** Transcrições que ficaram PENDING/PROCESSING (o processo caiu ou foi reiniciado no meio). */
    suspend fun findPendingTranscriptions(): List<SentinelaPendingTranscription> {
        return dbQuery {
            SentinelaRecordingTable
                .select(SentinelaRecordingTable.id, SentinelaRecordingTable.s3Key)
                .where {
                    (SentinelaRecordingTable.transcriptStatus inList listOf(
                        SentinelaTranscriptStatus.PENDING.name,
                        SentinelaTranscriptStatus.PROCESSING.name,
                    )) and SentinelaRecordingTable.s3Key.isNotNull()
                }
                .orderBy(SentinelaRecordingTable.startedAt, SortOrder.ASC)
                .map { SentinelaPendingTranscription(it[SentinelaRecordingTable.id], it[SentinelaRecordingTable.s3Key]!!) }
        }
    }

    /**
     * Gravações do usuário, mais recentes primeiro, com o primeiro ponto de localização de cada uma.
     * [viewerIsOwner] define qual relatório guardado conta para `hasReport`: o do dono ou o do compartilhamento.
     */
    suspend fun listByUid(uid: String, limit: Int = 200, viewerIsOwner: Boolean = true): List<SentinelaRecordingSummaryDto> {
        return dbQuery {
            SentinelaRecordingTable
                .select(summaryColumns)
                .where { SentinelaRecordingTable.uid eq uid }
                .orderBy(SentinelaRecordingTable.startedAt, SortOrder.DESC)
                .limit(limit)
                .map { row -> toSummary(row, firstLocation(row[SentinelaRecordingTable.id]), viewerIsOwner) }
        }
    }

    /** Busca a gravação sem checar acesso — quem chama deve validar o dono ([SentinelaRecordingWithLocations.ownerUid]). */
    suspend fun findById(recordingId: String): SentinelaRecordingWithLocations? {
        return dbQuery {
            val row = SentinelaRecordingTable
                .selectAll()
                .where { SentinelaRecordingTable.id eq recordingId }
                .singleOrNull()
                ?: return@dbQuery null

            val locations = SentinelaRecordingLocationTable
                .selectAll()
                .where { SentinelaRecordingLocationTable.recordingId eq recordingId }
                .orderBy(SentinelaRecordingLocationTable.id, SortOrder.ASC)
                .map(::toLocation)

            SentinelaRecordingWithLocations(
                ownerUid = row[SentinelaRecordingTable.uid],
                summary = toSummary(row, locations.firstOrNull()),
                s3Key = row[SentinelaRecordingTable.s3Key],
                locations = locations,
                transcript = row[SentinelaRecordingTable.transcript],
                transcriptStatus = row[SentinelaRecordingTable.transcriptStatus],
                report = storedReportOf(row),
                sharedReport = sharedReportOf(row),
            )
        }
    }

    private fun isExpired(expiresAt: String): Boolean = !Instant.parse(expiresAt).isAfter(Instant.now())

    /** 256 bits aleatórios em base64 URL-safe: o link é impossível de adivinhar. */
    private fun newShareToken(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Tudo menos a transcrição, que pode ser grande e não faz parte do resumo. */
    private val summaryColumns get() = SentinelaRecordingTable.columns - SentinelaRecordingTable.transcript

    private fun firstLocation(recordingId: String): SentinelaLocationDto? =
        SentinelaRecordingLocationTable
            .selectAll()
            .where { SentinelaRecordingLocationTable.recordingId eq recordingId }
            .orderBy(SentinelaRecordingLocationTable.id, SortOrder.ASC)
            .limit(1)
            .map(::toLocation)
            .firstOrNull()

    private fun toLocation(row: ResultRow) = SentinelaLocationDto(
        lat = row[SentinelaRecordingLocationTable.lat],
        lng = row[SentinelaRecordingLocationTable.lng],
        accuracy = row[SentinelaRecordingLocationTable.accuracy],
        speed = row[SentinelaRecordingLocationTable.speed],
        capturedAt = row[SentinelaRecordingLocationTable.capturedAt],
    )

    private fun toSummary(row: ResultRow, startLocation: SentinelaLocationDto?, viewerIsOwner: Boolean = true) = SentinelaRecordingSummaryDto(
        id = row[SentinelaRecordingTable.id],
        status = row[SentinelaRecordingTable.status],
        mimeType = row[SentinelaRecordingTable.mimeType],
        sizeBytes = row[SentinelaRecordingTable.sizeBytes],
        chunkCount = row[SentinelaRecordingTable.chunkCount],
        startedAt = row[SentinelaRecordingTable.startedAt],
        endedAt = row[SentinelaRecordingTable.endedAt],
        hasVideo = row[SentinelaRecordingTable.s3Key] != null,
        startLocation = startLocation,
        hasReport = row[if (viewerIsOwner) SentinelaRecordingTable.reportS3Key else SentinelaRecordingTable.sharedReportS3Key] != null,
    )

    private fun storedReportOf(row: ResultRow): SentinelaStoredReport? {
        val key = row[SentinelaRecordingTable.reportS3Key] ?: return null
        val generatedAt = row[SentinelaRecordingTable.reportGeneratedAt] ?: return null
        return SentinelaStoredReport(
            s3Key = key,
            generatedAt = Instant.parse(generatedAt),
            hasTranscript = row[SentinelaRecordingTable.reportHasTranscript],
            linkExpiresAt = row[SentinelaRecordingTable.reportLinkExpiresAt]?.let(Instant::parse),
        )
    }

    private fun sharedReportOf(row: ResultRow): SentinelaStoredReport? {
        val key = row[SentinelaRecordingTable.sharedReportS3Key] ?: return null
        val generatedAt = row[SentinelaRecordingTable.sharedReportGeneratedAt] ?: return null
        return SentinelaStoredReport(
            s3Key = key,
            generatedAt = Instant.parse(generatedAt),
            hasTranscript = row[SentinelaRecordingTable.sharedReportHasTranscript],
            linkExpiresAt = null,
        )
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }
}
