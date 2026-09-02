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

    val module2: List<MissionDay> = listOf(
        MissionDay(1, "Phrasal verbs with \"get\" (get up, get along, get over, get into)",
            "Ask about the student's morning routine, how they get along with family, or something they got over recently."),
        MissionDay(2, "Small talk expressions (How's it going, What's up, Long time no see)",
            "Greet the student like you haven't talked in a while and catch up casually."),
        MissionDay(3, "Phrasal verbs with \"turn\" (turn on/off, turn up/down, turn out)",
            "Talk about devices, volume, or how a recent plan or situation turned out."),
        MissionDay(4, "Expressions for giving opinions (I think, In my opinion, To be honest)",
            "Ask the student's opinion about a movie, food, or something currently trending."),
        MissionDay(5, "Phrasal verbs with \"look\" (look for, look after, look forward to, look up)",
            "Ask what they're looking forward to, or who/what they look after."),
        MissionDay(6, "Agreeing and disagreeing (I totally agree, I see your point but...)",
            "Bring up a light opinion and get the student to agree or disagree with you."),
        MissionDay(7, "Phrasal verbs with \"put\" (put on, put off, put up with, put away)",
            "Talk about procrastination, getting dressed, or something they put up with."),
        MissionDay(8, "Idioms about time (in the nick of time, take your time, once in a blue moon)",
            "Ask how often they do something, or about a time they almost arrived late."),
        MissionDay(9, "Phrasal verbs with \"take\" (take off, take after, take up, take back)",
            "Talk about a hobby they took up recently, or who they take after in their family."),
        MissionDay(10, "Making plans (How about..., Are you up for..., Let's...)",
            "Plan a fake weekend activity together with the student."),
        MissionDay(11, "Phrasal verbs with \"come\" (come across, come up with, come back, come over)",
            "Talk about an idea they came up with, or invite them to come over sometime."),
        MissionDay(12, "Reacting to news (No way!, That's amazing!, That's a shame)",
            "Tell the student a piece of surprising made-up news and have them react."),
        MissionDay(13, "Phrasal verbs with \"break\" (break down, break up, break out, break into)",
            "Talk about something that broke down recently, or an unexpected event."),
        MissionDay(14, "Idioms about money (break the bank, save for a rainy day, cost an arm and a leg)",
            "Talk about a recent purchase or their saving habits."),
        MissionDay(15, "Phrasal verbs with \"give\" (give up, give away, give back, give in)",
            "Talk about a habit they gave up, or something they'd never give up."),
        MissionDay(16, "Giving advice (You should..., Why don't you..., If I were you...)",
            "Have the student describe a small problem and give them advice."),
        MissionDay(17, "Phrasal verbs with \"run\" (run into, run out of, run away, run over)",
            "Talk about running into someone by chance, or running out of something."),
        MissionDay(18, "Idioms about feelings (under the weather, over the moon, down in the dumps)",
            "Ask how they're feeling today using these expressions."),
        MissionDay(19, "Phrasal verbs with \"go\" (go on, go through, go off, go over)",
            "Talk about how their day is going, or something they need to go over."),
        MissionDay(20, "Describing people (down-to-earth, easy-going, full of energy)",
            "Ask the student to describe a friend or family member."),
        MissionDay(21, "Phrasal verbs with \"check\" (check in, check out, check up on)",
            "Talk about a trip, a hotel, or checking up on someone."),
        MissionDay(22, "Idioms about work and effort (burn the midnight oil, hit the ground running)",
            "Talk about a busy day at work or school."),
        MissionDay(23, "Phrasal verbs with \"work\" (work out, work on, work through)",
            "Talk about exercise, or something they're currently working on."),
        MissionDay(24, "Complaining politely (It's a bit annoying that..., I wish...)",
            "Get the student to complain lightly about traffic, weather, or a busy week."),
        MissionDay(25, "Phrasal verbs with \"hang\" (hang out, hang on, hang up)",
            "Talk about hanging out with friends, or ask them to hang on a second."),
        MissionDay(26, "Idioms about food (piece of cake, spill the beans, bring home the bacon)",
            "Use these while talking about an easy task, a secret, or supporting a family."),
        MissionDay(27, "Phrasal verbs with \"figure\" and \"sort\" (figure out, sort out)",
            "Talk about a problem they figured out or sorted out recently."),
        MissionDay(28, "Apologizing (My bad, I didn't mean to, It won't happen again)",
            "Set up a light scenario where the student apologizes for something small, like being late."),
        MissionDay(29, "Phrasal verbs with \"bring\" (bring up, bring back, bring about)",
            "Talk about a topic someone brought up recently, or a memory something brings back."),
        MissionDay(30, "Idioms about relationships (get along with, see eye to eye, break the ice)",
            "Talk about getting along with coworkers or family, or agreeing with someone."),
    )

    // Ordem de progressão dos módulos: ao terminar o último dia de um módulo, o aluno
    // avança para o próximo desta lista. Para adicionar um module3 no futuro, basta
    // declarar a lista de MissionDay acima e incluir aqui, nesta ordem.
    private val moduleOrder: List<Pair<String, List<MissionDay>>> = listOf(
        "module1" to module1,
        "module2" to module2,
    )

    private val modules: Map<String, List<MissionDay>> = buildMap {
        moduleOrder.forEach { (id, days) ->
            put(id, days)
            put(id.removePrefix("module"), days) // atalho: aceita também só o número ("1", "2")
        }
    }

    fun dayOf(moduleId: String, day: Int): MissionDay? =
        modules[moduleId.lowercase().trim()]?.find { it.day == day }

    fun sizeOf(moduleId: String): Int =
        modules[moduleId.lowercase().trim()]?.size ?: module1.size

    private fun canonicalModuleId(moduleId: String): String? {
        val normalized = moduleId.lowercase().trim()
        return moduleOrder.map { it.first }.find { it == normalized || it == "module$normalized" }
    }

    /** Id do próximo módulo na sequência após [moduleId], ou null se já for o último (ou inválido). */
    fun nextModuleId(moduleId: String): String? {
        val canonical = canonicalModuleId(moduleId) ?: return null
        val idx = moduleOrder.indexOfFirst { it.first == canonical }
        return moduleOrder.getOrNull(idx + 1)?.first
    }
}
