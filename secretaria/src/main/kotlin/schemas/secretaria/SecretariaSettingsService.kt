package schemas.secretaria

import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.security.SecureRandom
import java.time.ZoneId
import java.util.Base64

/** Dados da clínica, ajustes do atendimento por IA e status do assistente (tela Configurações). */
class SecretariaSettingsService(
    private val database: Database,
    private val clock: () -> Long = System::currentTimeMillis,
    /** true se há chave do Gemini configurada no servidor. */
    private val geminiConfigured: () -> Boolean = { true },
) {
    private val random = SecureRandom()

    // ── dados da clínica ─────────────────────────────────────────────────────

    suspend fun clinicDetail(clinicId: Long, includeKey: Boolean): ClinicDetailDto? = database.dbQuery {
        ClinicsTable.selectAll().where { ClinicsTable.id eq clinicId }.singleOrNull()?.toDetail(includeKey)
    }

    suspend fun updateClinic(clinicId: Long, request: UpdateClinicRequest): ServiceResult<ClinicDetailDto> {
        val name = request.name?.trim()
        if (name != null && (name.isEmpty() || name.length > 120)) return invalid("Nome da clínica inválido (1 a 120 caracteres).")
        val timezone = request.timezone?.trim()?.let { tz ->
            runCatching { ZoneId.of(tz) }.getOrNull()?.id ?: return invalid("Fuso horário inválido.")
        }
        val cnpj = request.cnpj?.let { raw ->
            val digits = raw.filter { it.isDigit() }
            if (raw.isNotBlank() && digits.length != 14) return invalid("CNPJ deve ter 14 dígitos.")
            digits.ifEmpty { null }
        }
        val email = request.email?.trim()
        if (!email.isNullOrEmpty() && (!email.contains('@') || email.length > 160)) return invalid("E-mail inválido.")
        for ((label, value, max) in listOf(
            Triple("Responsável", request.responsibleName, 120), Triple("Telefone", request.phone, 20), Triple("Endereço", request.address, 255),
        )) {
            if ((value?.trim()?.length ?: 0) > max) return invalid("$label deve ter no máximo $max caracteres.")
        }

        return try {
            database.dbQuery {
                ClinicsTable.update({ ClinicsTable.id eq clinicId }) {
                    name?.let { v -> it[ClinicsTable.name] = v }
                    request.responsibleName?.let { v -> it[responsibleName] = v.trim().ifEmpty { null } }
                    request.cnpj?.let { _ -> it[ClinicsTable.cnpj] = cnpj }
                    request.phone?.let { v -> it[phone] = v.trim().ifEmpty { null } }
                    request.email?.let { v -> it[ClinicsTable.email] = v.trim().ifEmpty { null } }
                    request.address?.let { v -> it[address] = v.trim().ifEmpty { null } }
                    timezone?.let { v -> it[ClinicsTable.timezone] = v }
                }
                ServiceResult.Ok(ClinicsTable.selectAll().where { ClinicsTable.id eq clinicId }.single().toDetail(includeKey = true))
            }
        } catch (e: ExposedSQLException) {
            conflict("Já existe uma clínica com esse CNPJ.")
        }
    }

    /** Gera uma nova chave pública para o PWA; a antiga deixa de funcionar na hora (use se ela vazar). */
    suspend fun rotatePublicKey(clinicId: Long): String {
        val key = newPublicKey()
        database.dbQuery { ClinicsTable.update({ ClinicsTable.id eq clinicId }) { it[publicKey] = key } }
        return key
    }

    fun newPublicKey(): String = ByteArray(24).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    // ── atendimento por IA ───────────────────────────────────────────────────

    suspend fun settings(clinicId: Long): SettingsDto = database.dbQuery { loadSettings(clinicId) }

    suspend fun updateSettings(clinicId: Long, request: UpdateSettingsRequest): ServiceResult<SettingsDto> {
        val voice = request.voice?.trim()
        if (!voice.isNullOrEmpty() && voice !in SecretariaVoices.all) return invalid("Voz inválida. Opções: ${SecretariaVoices.all.joinToString()}.")
        val extra = request.extraInstructions?.replace(Regex("[\\p{Cntrl}&&[^\\n\\t]]"), " ")?.trim()
        if ((extra?.length ?: 0) > 1000) return invalid("As orientações extras podem ter no máximo 1000 caracteres.")

        return database.dbQuery {
            val now = clock()
            val exists = !ClinicSettingsTable.selectAll().where { ClinicSettingsTable.clinicId eq clinicId }.empty()
            if (!exists) {
                ClinicSettingsTable.insert {
                    it[ClinicSettingsTable.clinicId] = clinicId
                    it[aiEnabled] = request.aiEnabled ?: true
                    it[ClinicSettingsTable.voice] = voice?.ifEmpty { null }
                    it[extraInstructions] = extra?.ifEmpty { null }
                    it[updatedAt] = now
                }
            } else {
                ClinicSettingsTable.update({ ClinicSettingsTable.clinicId eq clinicId }) {
                    request.aiEnabled?.let { v -> it[aiEnabled] = v }
                    request.voice?.let { _ -> it[ClinicSettingsTable.voice] = voice?.ifEmpty { null } }
                    request.extraInstructions?.let { _ -> it[extraInstructions] = extra?.ifEmpty { null } }
                    it[updatedAt] = now
                }
            }
            ServiceResult.Ok(loadSettings(clinicId))
        }
    }

    /** Estado do card "Secretária IA · Online". */
    suspend fun assistantStatus(clinicId: Long): AssistantStatusDto {
        val s = settings(clinicId)
        return when {
            !s.aiEnabled -> AssistantStatusDto(false, "Atendimento por IA desativado pela clínica.")
            !geminiConfigured() -> AssistantStatusDto(false, "Serviço de IA indisponível no momento.")
            else -> AssistantStatusDto(true, "Atendendo seus pacientes 24h por dia.")
        }
    }

    /**
     * Lista pública das clínicas ativas para o paciente escolher quem chamar (PWA). Cada item traz a chave pública,
     * que é o que abre a chamada. Numa só consulta (sem uma por clínica) e limitada a [limit] itens.
     */
    suspend fun publicDirectory(limit: Int = 200): List<PublicDirectoryEntryDto> {
        val gemini = geminiConfigured()
        return database.dbQuery {
            (ClinicsTable leftJoin ClinicSettingsTable).selectAll()
                .where { ClinicsTable.active eq true }
                .orderBy(ClinicsTable.name)
                .limit(limit)
                .map {
                    PublicDirectoryEntryDto(
                        name = it[ClinicsTable.name],
                        responsibleName = it[ClinicsTable.responsibleName],
                        // sem linha de ajustes = IA ligada (o padrão), igual a loadSettings
                        online = gemini && (it.getOrNull(ClinicSettingsTable.aiEnabled) ?: true),
                        publicKey = it[ClinicsTable.publicKey],
                    )
                }
        }
    }

    private fun Transaction.loadSettings(clinicId: Long): SettingsDto {
        val row = ClinicSettingsTable.selectAll().where { ClinicSettingsTable.clinicId eq clinicId }.singleOrNull()
        return SettingsDto(
            aiEnabled = row?.get(ClinicSettingsTable.aiEnabled) ?: true,
            voice = row?.get(ClinicSettingsTable.voice),
            extraInstructions = row?.get(ClinicSettingsTable.extraInstructions),
            availableVoices = SecretariaVoices.all,
        )
    }

    private fun ResultRow.toDetail(includeKey: Boolean) = ClinicDetailDto(
        id = this[ClinicsTable.id],
        name = this[ClinicsTable.name],
        responsibleName = this[ClinicsTable.responsibleName],
        cnpj = this[ClinicsTable.cnpj],
        phone = this[ClinicsTable.phone],
        email = this[ClinicsTable.email],
        address = this[ClinicsTable.address],
        timezone = this[ClinicsTable.timezone],
        publicKey = if (includeKey) this[ClinicsTable.publicKey] else null,
    )
}
