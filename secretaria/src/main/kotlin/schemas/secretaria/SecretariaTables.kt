package schemas.secretaria

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction

/*
 * Banco `secretaria_db` da SecretárIA (atendimento por IA de clínicas).
 *
 * Convenções:
 *  - Instantes são gravados como epoch em milissegundos UTC (`*_at`). A tela converte para o fuso
 *    da clínica (ClinicsTable.timezone). Horários de agenda semanal são minutos desde 00:00 locais.
 *  - Enums são gravados pelo nome (ex.: "AGENDADA"); nas respostas JSON viram minúsculas.
 *  - As tabelas são criadas/atualizadas por [SecretariaSchema.create] — mesmo padrão dos outros módulos.
 */

enum class ClinicRole { ADMIN, SECRETARIA, PROFISSIONAL }
enum class CallChannel { PWA, TELEFONE }
enum class CallState { EM_ANDAMENTO, ENCERRADA }
enum class CallOutcome { AGENDADA, REMARCADA, FINALIZADA }
enum class CallIntent { AGENDAMENTO, REMARCACAO, CANCELAMENTO, INFORMACAO, OUTRO }
enum class Speaker { PACIENTE, IA }
enum class AppointmentCreator { IA, USUARIO }
enum class SubscriptionStatus { ATIVA, INADIMPLENTE, CANCELADA }
enum class NotificationType { AGENDAMENTO, CHAMADA, PLANO, SISTEMA }

/** Vozes pré-definidas do Gemini Live aceitas nos ajustes. */
object SecretariaVoices {
    val all = listOf("Aoede", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Zephyr")
}

enum class AppointmentStatus {
    AGENDADO, CONFIRMADO, REMARCADO, CANCELADO, CONCLUIDO, FALTOU;

    /** Ocupa o horário do médico? Cancelado/remarcado liberam o horário. */
    val occupiesSlot: Boolean get() = this != CANCELADO && this != REMARCADO
}

object ClinicsTable : Table("clinics") {
    val id = long("id").autoIncrement()
    val name = varchar("name", 120)
    val responsibleName = varchar("responsible_name", 120).nullable()
    val cnpj = varchar("cnpj", 14).nullable().uniqueIndex()
    val phone = varchar("phone", 20).nullable()
    val email = varchar("email", 160).nullable()
    val address = varchar("address", 255).nullable()
    val timezone = varchar("timezone", 40).default("America/Sao_Paulo")

    /** Chave pública embutida no PWA do paciente: identifica a clínica na chamada, sem login. */
    val publicKey = varchar("public_key", 64).uniqueIndex()
    val active = bool("active").default(true)
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}

/** Ajustes do atendimento por IA de cada clínica (tela Configurações e card "Secretária IA · Online"). */
object ClinicSettingsTable : Table("clinic_settings") {
    val clinicId = reference("clinic_id", ClinicsTable.id)
    val aiEnabled = bool("ai_enabled").default(true)

    /** Voz do Gemini ([SecretariaVoices]); null = padrão do servidor. */
    val voice = varchar("voice", 30).nullable()

    /** Orientações extras da clínica, acrescentadas ao prompt da IA (ex.: convênios aceitos, endereço). */
    val extraInstructions = varchar("extra_instructions", 1000).nullable()
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(clinicId)
}

object UsersTable : Table("users") {
    val id = long("id").autoIncrement()
    val name = varchar("name", 120)
    val email = varchar("email", 160).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val active = bool("active").default(true)
    val lastLoginAt = long("last_login_at").nullable()
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}

/** Um usuário pode ter várias clínicas ("Trocar clínica"); o papel é por clínica. */
object ClinicUsersTable : Table("clinic_users") {
    val clinicId = reference("clinic_id", ClinicsTable.id)
    val userId = reference("user_id", UsersTable.id)
    val role = enumerationByName("role", 20, ClinicRole::class)

    override val primaryKey = PrimaryKey(clinicId, userId)
}

object SpecialtiesTable : Table("specialties") {
    val id = long("id").autoIncrement()
    val name = varchar("name", 80).uniqueIndex()

    override val primaryKey = PrimaryKey(id)
}

object DoctorsTable : Table("doctors") {
    val id = long("id").autoIncrement()
    val clinicId = reference("clinic_id", ClinicsTable.id)
    val specialtyId = reference("specialty_id", SpecialtiesTable.id)
    val name = varchar("name", 120)
    val crm = varchar("crm", 20).nullable()
    val active = bool("active").default(true)

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, clinicId, active)
    }
}

/** Janela semanal de atendimento. weekday: 0 = domingo ... 6 = sábado; horários em minutos locais. */
object DoctorSchedulesTable : Table("doctor_schedules") {
    val id = long("id").autoIncrement()
    val doctorId = reference("doctor_id", DoctorsTable.id)
    val weekday = integer("weekday")
    val startMinute = integer("start_minute")
    val endMinute = integer("end_minute")
    val slotMinutes = integer("slot_minutes").default(30)

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, doctorId, weekday)
    }
}

object PatientsTable : Table("patients") {
    val id = long("id").autoIncrement()
    val clinicId = reference("clinic_id", ClinicsTable.id)
    val name = varchar("name", 120)

    /** Só dígitos, com DDI (ex.: 5521987654321). */
    val phone = varchar("phone", 20).nullable()
    val email = varchar("email", 160).nullable()
    val consentAt = long("consent_at").nullable()
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, clinicId, phone)
        index(false, clinicId, name)
    }
}

/** Cada clique em "Chamar" no PWA cria uma linha com state = EM_ANDAMENTO. */
object CallsTable : Table("calls") {
    val id = long("id").autoIncrement()
    val clinicId = reference("clinic_id", ClinicsTable.id)
    val patientId = reference("patient_id", PatientsTable.id).nullable()
    val doctorId = reference("doctor_id", DoctorsTable.id).nullable()
    val channel = enumerationByName("channel", 20, CallChannel::class)
    val callerName = varchar("caller_name", 120).nullable()
    val callerPhone = varchar("caller_phone", 20).nullable()
    val state = enumerationByName("state", 20, CallState::class)
    val outcome = enumerationByName("outcome", 20, CallOutcome::class).nullable()
    val intent = enumerationByName("intent", 20, CallIntent::class).nullable()
    val subject = varchar("subject", 160).nullable()
    val summary = varchar("summary", 255).nullable()
    val startedAt = long("started_at")
    val endedAt = long("ended_at").nullable()
    val durationSeconds = integer("duration_seconds").nullable()
    val recordingUrl = varchar("recording_url", 500).nullable()
    val aiModel = varchar("ai_model", 80).nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, clinicId, startedAt)
        index(false, clinicId, state)
    }
}

/** Transcrição da conversa (botão "Ver transcrição"). */
object CallMessagesTable : Table("call_messages") {
    val id = long("id").autoIncrement()
    val callId = reference("call_id", CallsTable.id)
    val speaker = enumerationByName("speaker", 20, Speaker::class)
    val content = text("content")
    val offsetMs = long("offset_ms")

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, callId, offsetMs)
    }
}

object AppointmentsTable : Table("appointments") {
    val id = long("id").autoIncrement()
    val clinicId = reference("clinic_id", ClinicsTable.id)
    val doctorId = reference("doctor_id", DoctorsTable.id)
    val patientId = reference("patient_id", PatientsTable.id)
    val specialtyId = reference("specialty_id", SpecialtiesTable.id)
    val sourceCallId = reference("source_call_id", CallsTable.id).nullable()
    val rescheduledFromId = long("rescheduled_from_id").nullable()
    val startsAt = long("starts_at")
    val endsAt = long("ends_at")
    val status = enumerationByName("status", 20, AppointmentStatus::class)
    val createdBy = enumerationByName("created_by", 20, AppointmentCreator::class)
    val notes = text("notes").nullable()
    val createdAt = long("created_at")

    /**
     * 1 enquanto o horário está ocupado, NULL quando liberado (cancelado/remarcado). Junto do índice
     * único abaixo, o banco impede dois agendamentos ativos do mesmo médico no mesmo horário —
     * mesmo se a IA (ou duas chamadas) tentarem ao mesmo tempo.
     */
    val activeSlot = integer("active_slot").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_appt_doctor_slot", doctorId, startsAt, activeSlot)
        index(false, clinicId, startsAt)
        index(false, clinicId, createdAt)
    }
}

object PlansTable : Table("plans") {
    val id = long("id").autoIncrement()
    val name = varchar("name", 80).uniqueIndex()
    val monthlyPrice = decimal("monthly_price", 10, 2)
    val includedMinutes = integer("included_minutes")
    val costPerMinute = decimal("cost_per_minute", 10, 4)

    override val primaryKey = PrimaryKey(id)
}

/** Uma assinatura por clínica; ao trocar de plano, atualize a linha. */
object SubscriptionsTable : Table("clinic_subscriptions") {
    val id = long("id").autoIncrement()
    val clinicId = reference("clinic_id", ClinicsTable.id).uniqueIndex()
    val planId = reference("plan_id", PlansTable.id)
    val status = enumerationByName("status", 20, SubscriptionStatus::class)
    val periodStart = long("period_start")
    val nextBillingAt = long("next_billing_at")

    override val primaryKey = PrimaryKey(id)
}

object NotificationsTable : Table("notifications") {
    val id = long("id").autoIncrement()
    val clinicId = reference("clinic_id", ClinicsTable.id)

    /** null = para toda a equipe da clínica. */
    val userId = reference("user_id", UsersTable.id).nullable()
    val type = enumerationByName("type", 20, NotificationType::class)
    val title = varchar("title", 160)
    val body = varchar("body", 500).nullable()
    val readAt = long("read_at").nullable()
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, clinicId, readAt)
    }
}

object SecretariaSchema {
    /** Cria as tabelas que faltam e as colunas novas. Idempotente — seguro a cada partida. */
    fun create(database: Database) {
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(
                ClinicsTable, ClinicSettingsTable, UsersTable, ClinicUsersTable, SpecialtiesTable, DoctorsTable,
                DoctorSchedulesTable, PatientsTable, CallsTable, CallMessagesTable,
                AppointmentsTable, PlansTable, SubscriptionsTable, NotificationsTable,
                withLogs = false, // o aviso de "índices extras" do Exposed é só ruído: as FKs já criam seus índices
            )
        }
    }
}
