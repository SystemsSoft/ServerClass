package schemas.alunoIa

/**
 * Idiomas de curso que a Megan sabe ensinar. [codigo] é o valor persistido na coluna
 * `idioma` de [AlunoIaService.AlunoIaTable] e aceito nas rotas públicas (ex:
 * GET /curriculo?idioma=espanhol). [nomeEmIngles] é usado dentro do prompt da Megan
 * (MeganPersona.kt), que é sempre escrito em inglês independente do idioma alvo.
 */
enum class Idioma(val codigo: String, val nomeEmIngles: String) {
    INGLES("ingles", "English"),
    ESPANHOL("espanhol", "Spanish"),
    ALEMAO("alemao", "German"),
    FRANCES("frances", "French"),
    HOLANDES("holandes", "Dutch");

    companion object {
        val DEFAULT = INGLES

        fun fromCodigo(codigo: String?): Idioma =
            entries.find { it.codigo == codigo?.lowercase()?.trim() } ?: DEFAULT
    }
}
