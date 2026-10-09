package schemas.secretaria

enum class ErrorKind(val httpStatus: Int) {
    INVALID(400),
    FORBIDDEN(403),
    NOT_FOUND(404),
    CONFLICT(409),
    /** Recurso não configurado no servidor (ex.: pagamento sem a chave da Stripe). */
    UNAVAILABLE(503),
}

/** Resultado de uma operação de serviço; as rotas traduzem [Err] para o status HTTP de [ErrorKind]. */
sealed class ServiceResult<out T> {
    data class Ok<T>(val value: T) : ServiceResult<T>()
    data class Err(val kind: ErrorKind, val message: String) : ServiceResult<Nothing>()
}

internal fun invalid(message: String) = ServiceResult.Err(ErrorKind.INVALID, message)
internal fun notFound(message: String) = ServiceResult.Err(ErrorKind.NOT_FOUND, message)
internal fun conflict(message: String) = ServiceResult.Err(ErrorKind.CONFLICT, message)

/** Janelas semanais padrão de um médico novo: segunda a sexta, 08–12 e 14–18, consultas de 30 min. */
internal val DEFAULT_WINDOWS: List<ScheduleWindow> = (1..5).flatMap { weekday ->
    listOf(ScheduleWindow(weekday, 8 * 60, 12 * 60, 30), ScheduleWindow(weekday, 14 * 60, 18 * 60, 30))
}
