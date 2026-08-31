package schemas.alunoIa

data class MissionDay(
    val day: Int,
    val topic: String,
    val conversationSeed: String,
)

object MissionFluencyCurriculum {

    val module1: List<MissionDay> = listOf(
        MissionDay(1, "Definite and indefinite articles",
            "Ask about the things on the student's desk, in their bag or around their room right now."),
        MissionDay(2, "Possessive adjectives",
            "Talk about family members — their jobs, their habits, their things ('my brother's car', 'her job')."),
        MissionDay(3, "Present continuous",
            "Ask what's happening right now around the student, or what people in their house are doing at this moment."),
        MissionDay(4, "Present simple",
            "Talk about the student's daily routine — what they usually do on a normal weekday."),
        MissionDay(5, "Telling the time",
            "Ask about the student's schedule today — what time they woke up, have lunch, or need to leave for something."),
        MissionDay(6, "Adverbs of frequency",
            "Ask how often the student does things — exercise, cook, watch a show, see friends (always/usually/sometimes/never)."),
        MissionDay(7, "Word order in questions",
            "Invite the student to interview you (Megan) back — get them to ask you questions about your life, city, hobbies."),
        MissionDay(8, "How much and how many",
            "Talk about shopping, prices, or quantities — how much something costs, how many people live in their city."),
        MissionDay(9, "Can / can't (modal)",
            "Talk about skills and abilities — things the student can and can't do, like cook, swim, drive, play an instrument."),
        MissionDay(10, "Past simple: be",
            "Ask where the student was yesterday, how their weekend was, how they were feeling this morning."),
        MissionDay(11, "Past simple: regular verbs",
            "Ask what the student worked on, studied, or watched yesterday or last weekend."),
        MissionDay(12, "Past simple: affirmative, negative, questions",
            "Dig into something specific that happened recently — ask follow-up questions and let them correct/deny things that aren't true."),
        MissionDay(13, "Past simple: irregular verbs",
            "Ask about a trip, a meal, or an event — what they ate, went, saw, did, had recently."),
        MissionDay(14, "There is/are, there was/were",
            "Ask the student to describe a place — their room, their city, or a party/event they went to."),
        MissionDay(15, "Object pronouns",
            "Talk about relationships and favors — asking someone for help, giving something to someone, telling someone news."),
        MissionDay(16, "Future with going to (plans and predictions)",
            "Ask about the student's plans for the weekend, next vacation, or predictions about something coming up."),
    )

    private val modules: Map<String, List<MissionDay>> = mapOf(
        "module1" to module1,
        "1" to module1,
    )

    fun dayOf(moduleId: String, day: Int): MissionDay? =
        modules[moduleId.lowercase().trim()]?.find { it.day == day }
}
