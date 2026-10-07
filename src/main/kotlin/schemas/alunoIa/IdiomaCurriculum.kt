package schemas.alunoIa

/** Registro central: cada [Idioma] suportado pela Megan aponta para o seu [LanguageCurriculum]. */
object IdiomaCurriculum {

    private val porIdioma: Map<Idioma, LanguageCurriculum> = mapOf(
        Idioma.INGLES to MissionFluencyCurriculum,
        Idioma.ESPANHOL to MissionFluencySpanishCurriculum,
        Idioma.ALEMAO to MissionFluencyGermanCurriculum,
        Idioma.FRANCES to MissionFluencyFrenchCurriculum,
        Idioma.HOLANDES to MissionFluencyDutchCurriculum,
    )

    fun forIdioma(idioma: Idioma): LanguageCurriculum = porIdioma.getValue(idioma)

    fun forCodigo(codigo: String?): LanguageCurriculum = forIdioma(Idioma.fromCodigo(codigo))
}
