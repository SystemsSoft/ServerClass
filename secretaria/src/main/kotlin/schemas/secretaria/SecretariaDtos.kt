@file:OptIn(ExperimentalSerializationApi::class)

package schemas.secretaria

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/*
 * Formatos JSON consumidos pelo dashboard Flutter e pelo PWA. Instantes: epoch em ms (UTC) e, quando
 * útil para exibição, o horário local da clínica em ISO ("2026-10-06T09:00"). Enums viram minúsculas.
 */

@Serializable
data class ClinicSummaryDto(val id: Long, val name: String, val responsibleName: String?, val role: String)

@Serializable
data class UserDto(val id: Long, val name: String, val email: String)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class LoginResponse(val token: String, val user: UserDto, val clinics: List<ClinicSummaryDto>)

@Serializable
data class MeResponse(val user: UserDto, val clinics: List<ClinicSummaryDto>)

@Serializable
data class PublicClinicDto(val name: String, val responsibleName: String?, @EncodeDefault val online: Boolean = true)

/** Item da lista pública de clínicas (tela "escolha a clínica" do PWA): [publicKey] é o que abre a chamada. */
@Serializable
data class PublicDirectoryEntryDto(val name: String, val responsibleName: String?, val online: Boolean, val publicKey: String)

@Serializable
data class DoctorDto(val id: Long, val name: String, val specialty: String)

@Serializable
data class SlotDto(
    val doctorId: Long,
    val doctorName: String,
    val specialty: String,
    /** Horário local da clínica, ISO sem fuso: "2026-10-06T09:00". */
    val startLocal: String,
    /** Texto pronto para a IA falar: "terça-feira, 06/10 às 09:00". */
    val label: String,
)

@Serializable
data class AppointmentDto(
    val id: Long,
    val startsAt: Long,
    val startLocal: String,
    val endLocal: String,
    val doctorId: Long,
    val doctorName: String,
    val specialty: String,
    val patientId: Long,
    val patientName: String,
    val patientPhone: String?,
    val status: String,
    val createdBy: String,
)

@Serializable
data class CreateAppointmentRequest(
    val clinicId: Long,
    val doctorId: Long,
    val patientName: String,
    val patientPhone: String,
    /** Horário local da clínica: "2026-10-06T09:00". */
    val startLocal: String,
    val notes: String? = null,
)

@Serializable
data class PatientDto(
    val id: Long,
    val name: String,
    val phone: String?,
    val email: String?,
    val createdAt: Long,
    /** Do cadastro feito no app (nulos para quem não usou o app). */
    @EncodeDefault val cpf: String? = null,
    @EncodeDefault val healthPlan: String? = null,
    @EncodeDefault val hasPhoto: Boolean = false,
)

@Serializable
data class CallDto(
    val id: Long,
    val startedAt: Long,
    val durationSeconds: Int?,
    val patientName: String?,
    val phone: String?,
    val subject: String?,
    /** agendada | remarcada | finalizada | null (em andamento) — enum CallStatus do Flutter. */
    val status: String?,
    val state: String,
    val hasRecording: Boolean,
    val hasTranscript: Boolean,
    /** Paciente da ligação (cadastro do app); a foto vem de GET /patients/{patientId}/photo. */
    @EncodeDefault val patientId: Long? = null,
    @EncodeDefault val cpf: String? = null,
    @EncodeDefault val email: String? = null,
    @EncodeDefault val healthPlan: String? = null,
    @EncodeDefault val hasPhoto: Boolean = false,
    /** Consumo da IA medido na ligação (tokens) e o custo em reais; null = não medido. Chave gratuita: custo 0. */
    @EncodeDefault val aiTokens: Long? = null,
    @EncodeDefault val aiCostBrl: Double? = null,
    @EncodeDefault val aiFreeKey: Boolean = false,
)

@Serializable
data class TranscriptMessageDto(val speaker: String, val content: String, val offsetMs: Long)

@Serializable
data class ActiveCallDto(
    val id: Long,
    val phone: String?,
    val description: String,
    val startedAt: Long,
    val elapsedSeconds: Long,
    @EncodeDefault val patientId: Long? = null,
    @EncodeDefault val patientName: String? = null,
    @EncodeDefault val cpf: String? = null,
    @EncodeDefault val email: String? = null,
    @EncodeDefault val healthPlan: String? = null,
    @EncodeDefault val hasPhoto: Boolean = false,
)

@Serializable
data class StatDto(val today: Long, val yesterday: Long, val changePercent: Int, val trendUp: Boolean)

@Serializable
data class DashboardStatsDto(val callsToday: StatDto, val appointmentsToday: StatDto, val avgSeconds: StatDto)

@Serializable
data class PlanUsageDto(
    val planName: String,
    val monthlyPrice: Double,
    val includedMinutes: Int,
    val usedMinutes: Int,
    val usageRatio: Double,
    val costPerMinute: Double,
    val estimatedCost: Double,
    val nextBillingDate: String,
    /** Pacotes contratados (minutos e preço já vêm multiplicados por isso). */
    @EncodeDefault val units: Int = 1,
    /** Quantidade que passa a valer na renovação (redução pedida no meio do ciclo); null = sem mudança agendada. */
    @EncodeDefault val scheduledUnits: Int? = null,
    /** Assinatura paga pela Stripe (a clínica gerencia a quantidade e o cartão pelo painel). */
    @EncodeDefault val stripe: Boolean = false,
    /** ativa | inadimplente | cancelada */
    @EncodeDefault val status: String = "ativa",
    /** Ligações bloqueadas: os minutos acabaram ou a assinatura não está ativa. */
    @EncodeDefault val blocked: Boolean = false,
    /** A contratação de pacotes (Stripe) está disponível neste servidor: o painel mostra "Ativar plano". */
    @EncodeDefault val billingEnabled: Boolean = false,
    /** Teste grátis (clínica nova): minutos únicos, sem cobrança nem renovação. */
    @EncodeDefault val trial: Boolean = false,
)

@Serializable
data class AssistantStatusDto(val online: Boolean, val message: String)

@Serializable
data class DashboardDto(
    val clinic: ClinicSummaryDto,
    val userName: String,
    val stats: DashboardStatsDto,
    val activeCall: ActiveCallDto?,
    val recentCalls: List<CallDto>,
    val upcomingAppointments: List<AppointmentDto>,
    val plan: PlanUsageDto?,
    val unreadNotifications: Int,
    @EncodeDefault val assistant: AssistantStatusDto = AssistantStatusDto(true, ""),
)

@Serializable
data class BootstrapDoctor(val name: String, val specialty: String, val crm: String? = null)

@Serializable
data class BootstrapRequest(
    val clinicName: String,
    val responsibleName: String? = null,
    val timezone: String = "America/Sao_Paulo",
    val userName: String,
    val email: String,
    val password: String,
    val doctors: List<BootstrapDoctor> = emptyList(),
)

@Serializable
data class BootstrapResponse(val clinicId: Long, val publicKey: String, val userId: Long)

/** Cadastro público de clínica (tela de login do dashboard): cria a clínica e o primeiro administrador. */
@Serializable
data class RegisterClinicRequest(
    val clinicName: String,
    val responsibleName: String? = null,
    val timezone: String = "America/Sao_Paulo",
    val userName: String,
    val email: String,
    val password: String,
    /** Código do afiliado do link de indicação (`?ref=`); código desconhecido é ignorado. */
    val affiliateCode: String? = null,
)

@Serializable
data class ErrorDto(val error: String)

// ── perfil e senha ───────────────────────────────────────────────────────────

@Serializable
data class UpdateProfileRequest(val name: String)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

// ── clínica e ajustes da IA ──────────────────────────────────────────────────

@Serializable
data class ClinicDetailDto(
    val id: Long,
    val name: String,
    val responsibleName: String?,
    val cnpj: String?,
    val phone: String?,
    val email: String?,
    val address: String?,
    val timezone: String,
    /** Só para administradores. */
    val publicKey: String?,
)

/** Campos ausentes (null) não mudam; "" limpa o campo. */
@Serializable
data class UpdateClinicRequest(
    val name: String? = null,
    val responsibleName: String? = null,
    val cnpj: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    val timezone: String? = null,
)

@Serializable
data class SettingsDto(
    val aiEnabled: Boolean,
    val voice: String?,
    val extraInstructions: String?,
    val availableVoices: List<String>,
)

@Serializable
data class UpdateSettingsRequest(
    val aiEnabled: Boolean? = null,
    /** "" volta para a voz padrão. */
    val voice: String? = null,
    val extraInstructions: String? = null,
)

@Serializable
data class RotateKeyResponse(val publicKey: String)

// ── equipe ───────────────────────────────────────────────────────────────────

@Serializable
data class TeamMemberDto(val userId: Long, val name: String, val email: String, val role: String, val lastLoginAt: Long?)

/** Se o e-mail já existir, o usuário é apenas vinculado à clínica (sem senha); senão `name` e `password` são obrigatórios. */
@Serializable
data class AddTeamMemberRequest(val email: String, val role: String, val name: String? = null, val password: String? = null)

@Serializable
data class UpdateTeamMemberRequest(val role: String)

// ── médicos, especialidades e agenda ─────────────────────────────────────────

@Serializable
data class SpecialtyDto(val id: Long, val name: String)

@Serializable
data class DoctorDetailDto(
    val id: Long,
    val name: String,
    val specialty: String,
    val crm: String?,
    val active: Boolean,
    /** Máximo de consultas por dia; null = sem limite. */
    @EncodeDefault val maxPerDay: Int? = null,
)

@Serializable
data class DoctorUpsertRequest(
    val name: String,
    val specialty: String,
    val crm: String? = null,
    val active: Boolean? = null,
    /** Consultas por dia: 1 a 200 = limite; 0 = tirar o limite; ausente = manter como está. */
    val maxPerDay: Int? = null,
)

/** weekday: 0 = domingo ... 6 = sábado; start/end no formato "HH:mm", horário local da clínica. */
@Serializable
data class ScheduleWindowDto(val weekday: Int, val start: String, val end: String, @EncodeDefault val slotMinutes: Int = 30)

@Serializable
data class ScheduleRequest(val windows: List<ScheduleWindowDto>)

// ── pacientes ────────────────────────────────────────────────────────────────

@Serializable
data class PatientPageDto(val items: List<PatientDto>, val total: Long)

@Serializable
data class PatientDetailDto(
    val patient: PatientDto,
    val upcoming: List<AppointmentDto>,
    val history: List<AppointmentDto>,
    val callsCount: Long,
)

@Serializable
data class UpsertPatientRequest(val name: String, val phone: String? = null, val email: String? = null)

// ── chamadas ─────────────────────────────────────────────────────────────────

@Serializable
data class CallPageDto(val items: List<CallDto>, val total: Long)

@Serializable
data class CallDetailDto(
    val call: CallDto,
    val transcript: List<TranscriptMessageDto>,
    val appointments: List<AppointmentDto>,
    val recordingUrl: String?,
)

@Serializable
data class RecordingDto(val url: String)

// ── agendamentos ─────────────────────────────────────────────────────────────

/** confirmado | concluido | faltou */
@Serializable
data class UpdateAppointmentStatusRequest(val status: String)

@Serializable
data class RescheduleRequest(val startLocal: String)

// ── notificações ─────────────────────────────────────────────────────────────

@Serializable
data class NotificationDto(val id: Long, val type: String, val title: String, val body: String?, val read: Boolean, val createdAt: Long)

@Serializable
data class NotificationPageDto(val items: List<NotificationDto>, val unread: Int)

// ── plano ────────────────────────────────────────────────────────────────────

@Serializable
data class PlanDto(val id: Long, val name: String, val monthlyPrice: Double, val includedMinutes: Int, val costPerMinute: Double)

@Serializable
data class DayUsageDto(val date: String, val calls: Int, val minutes: Double)

@Serializable
data class PlanDetailDto(
    val usage: PlanUsageDto,
    val status: String,
    val periodStart: String,
    val usageByDay: List<DayUsageDto>,
    val availablePlans: List<PlanDto>,
    @EncodeDefault val billing: BillingInfoDto = BillingInfoDto(),
)

/** Contratação por pacotes (Stripe): o que o painel mostra para escolher a quantidade. */
@Serializable
data class BillingInfoDto(
    /** false = a Stripe não está configurada no servidor (o painel esconde a contratação). */
    @EncodeDefault val enabled: Boolean = false,
    @EncodeDefault val unitMinutes: Int = 200,
    @EncodeDefault val unitPrice: Double = 49.90,
    @EncodeDefault val maxUnits: Int = 50,
)

@Serializable
data class BillingUnitsRequest(val units: Int, val returnUrl: String? = null)

@Serializable
data class BillingReturnRequest(val returnUrl: String? = null)

/** Endereço da Stripe para onde o painel leva a clínica (pagamento ou portal do cliente). */
@Serializable
data class BillingRedirectDto(val url: String)

@Serializable
data class BillingChangeDto(val units: Int, val scheduledUnits: Int?, val message: String)

@Serializable
data class CreatePlanRequest(val name: String, val monthlyPrice: Double, val includedMinutes: Int, val costPerMinute: Double)

@Serializable
data class AssignPlanRequest(val clinicId: Long, val planId: Long)

// ── relatórios ───────────────────────────────────────────────────────────────

@Serializable
data class DayCountDto(val date: String, val calls: Int, val minutes: Double, val appointments: Int)

@Serializable
data class LabelCountDto(val label: String, val count: Int)

@Serializable
data class ReportDto(
    val from: String,
    val to: String,
    val totalCalls: Int,
    val totalMinutes: Double,
    val avgSeconds: Int,
    val appointmentsCreated: Int,
    val appointmentsByAi: Int,
    /** Chamadas encerradas que terminaram em agendamento ÷ chamadas encerradas (0 a 1). */
    val conversionRate: Double,
    val byOutcome: List<LabelCountDto>,
    val byIntent: List<LabelCountDto>,
    val bySpecialty: List<LabelCountDto>,
    val byDoctor: List<LabelCountDto>,
    val byStatus: List<LabelCountDto>,
    /** 24 posições (0–23h, hora local da clínica). */
    val callsByHour: List<Int>,
    /** Custo real da IA no período (só ligações medidas), em reais, e por minuto de ligação medida. */
    @EncodeDefault val aiCostBrl: Double = 0.0,
    @EncodeDefault val aiCostPerMinuteBrl: Double? = null,
    @EncodeDefault val aiMeasuredCalls: Int = 0,
    @EncodeDefault val aiFreeKeyCalls: Int = 0,
    val perDay: List<DayCountDto>,
)

// ── app do paciente (PWA): cadastro por CPF e consultas ─────────────────────

/** Cadastro feito no app. [photo]: data URL ou base64 (null = mantém a atual); [removePhoto] apaga a atual. */
@Serializable
data class SaveProfileRequest(
    val cpf: String,
    val name: String,
    val phone: String,
    val email: String,
    val healthPlan: String,
    val photo: String? = null,
    val removePhoto: Boolean = false,
)

@Serializable
data class ProfileDto(
    val cpf: String,
    val name: String,
    val phone: String,
    val email: String,
    val healthPlan: String,
    @EncodeDefault val hasPhoto: Boolean = false,
)

/** Perfil devolvido ao app quando o CPF já tem cadastro (entrar pelo CPF em outro aparelho). [photo]: data URL, se tiver. */
@Serializable
data class ProfileLookupDto(
    val cpf: String,
    val name: String,
    val phone: String,
    val email: String,
    val healthPlan: String,
    @EncodeDefault val photo: String? = null,
)

/** O CPF vai no corpo (POST), nunca na URL: assim não fica nos logs de acesso. */
@Serializable
data class CpfRequest(val cpf: String)

@Serializable
data class PatientAppointmentDto(
    val clinicName: String,
    val doctorName: String,
    val specialty: String,
    /** Horário local da clínica, ex.: "2026-10-06T09:00". */
    val startLocal: String,
    /** "terça-feira, 06/10 às 09:00". */
    val label: String,
    /** agendado | confirmado | concluido | faltou | cancelado */
    val status: String,
)

@Serializable
data class PatientAppointmentsDto(val name: String, val upcoming: List<PatientAppointmentDto>, val history: List<PatientAppointmentDto>)

/** Consumo da IA por chave do Gemini, somando todas as clínicas (rota de administração). */
@Serializable
data class KeyUsageDto(
    val key: String,
    val free: Boolean,
    val calls: Int,
    val minutes: Double,
    val tokens: Long,
    /** Custo em reais pelos preços configurados; 0 para chave gratuita. */
    val costBrl: Double,
    val costPerMinuteBrl: Double?,
)

@Serializable
data class UsageSummaryDto(
    val from: String,
    val to: String,
    val usdBrl: Double,
    val byKey: List<KeyUsageDto>,
    val totalCostBrl: Double,
    /** Ligações do período sem consumo medido (feitas antes da medição, ou que não conectaram). */
    val unmeasuredCalls: Int,
)

/** Foto do paciente para o painel, como data URL ("data:image/jpeg;base64,..."). */
@Serializable
data class PhotoDto(val photo: String)

// ── afiliados (página de gestão do dono do sistema) ──────────────────────────

@Serializable
data class AffiliateRequest(
    val name: String,
    val email: String? = null,
    val phone: String? = null,
    /** Código do link (A–Z, 0–9 e hífen, 3 a 32). Vazio na criação = gerado a partir do nome. */
    val code: String? = null,
    val active: Boolean? = null,
)

@Serializable
data class AffiliateDto(
    val id: Long,
    val code: String,
    val name: String,
    val email: String?,
    val phone: String?,
    val active: Boolean,
    val createdAt: Long,
    /** Link de indicação pronto (null se secretaria.dashboardUrl não estiver configurado). */
    val link: String?,
    val clinics: Int,
    /** Clínicas com plano recorrente ativo (assinatura da Stripe). */
    val payingClinics: Int,
    /** Soma das mensalidades das clínicas pagantes (R$). */
    val monthlyRevenue: Double,
)

@Serializable
data class AffiliateClinicDto(
    val clinicId: Long,
    val name: String,
    val responsibleName: String?,
    val email: String?,
    val createdAt: Long,
    val planName: String?,
    /** Plano recorrente (Stripe) ativo. */
    val recurring: Boolean,
    val trial: Boolean,
    val units: Int,
    val includedMinutes: Int,
    val monthlyPrice: Double,
    /** ativa | inadimplente | cancelada | sem plano */
    val status: String,
    val nextBillingDate: String?,
)

@Serializable
data class AffiliatesPageDto(val affiliates: List<AffiliateDto>, val linkBase: String?)
