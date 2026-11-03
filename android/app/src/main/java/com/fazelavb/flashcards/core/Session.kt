package com.fazelavb.flashcards.core

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.pow
import kotlin.random.Random

/*
 * Deck building and the review session: which cards, in what order, and what
 * each grade does. A port of flashcards/session.py; no Android and no file I/O
 * here, so the whole review loop is unit-tested on the JVM.
 */

enum class Mode(val label: String, val tip: String) {
    DUE("Due now", "What FSRS says is due, most forgotten first, then new words"),
    TOPIC("Topics", "Only the topics you pick"),
    MIX("All topics", "Every word in the workbook"),
    DIFFICULT("Difficult", "Words at or below a confidence you choose"),
}

enum class Order(val label: String) {
    WEIGHTED("Weighted (weakest likeliest)"),
    LOWEST_CONFIDENCE_FIRST("Lowest confidence first"),
    RANDOM("Random"),
    OLDEST_FIRST("Oldest first"),
}

/** The exercise chosen for a deck: one Kind, or MIXED (one per card showing). */
enum class ExerciseChoice(val label: String, val kind: Kind?) {
    FLASHCARD("Flashcards", Kind.FLASHCARD),
    CHOICE("Multiple choice", Kind.CHOICE),
    BLANK("Fill in the blank", Kind.BLANK),
    RECALL("Type the word", Kind.RECALL),
    LISTEN("Listening", Kind.LISTEN),
    SCRAMBLE("Sentence scramble", Kind.SCRAMBLE),
    ARTICLE("Article (der/die/das)", Kind.ARTICLE),
    FORMS("Forms (plural, past)", Kind.FORMS),
    MIXED("Mixed", null),
}

/** What each choice falls back to, in order, when a card can't do it; flashcard is always possible. */
private val FALLBACK = mapOf(
    Kind.CHOICE to listOf(Kind.CHOICE),
    Kind.BLANK to listOf(Kind.BLANK, Kind.CHOICE),
    Kind.RECALL to listOf(Kind.RECALL),
    Kind.LISTEN to listOf(Kind.LISTEN, Kind.RECALL),
    Kind.SCRAMBLE to listOf(Kind.SCRAMBLE, Kind.CHOICE),
    Kind.ARTICLE to listOf(Kind.ARTICLE, Kind.CHOICE),
    Kind.FORMS to listOf(Kind.FORMS, Kind.CHOICE),
)

data class Exercise(
    val kind: Kind,
    val choice: Choice? = null,      // choice and article
    val blank: Blank? = null,        // blank, recall and listen
    val scramble: Scramble? = null,
    val forms: Forms? = null,
)

/** What check() found; kept for the turn so an answer can't be re-tried. */
@Serializable
data class CheckResult(
    val verdict: Verdict,
    val expected: String,
    val suggested: Int,
    val given: String? = null,
    val picked: Int? = null,
    val order: List<Int>? = null,
)

/** An answer to check: a typed text, an option index, or a scramble's tile order. */
sealed interface Answer {
    data class Typed(val text: String) : Answer
    data class Picked(val index: Int) : Answer
    data class Ordered(val order: List<Int>) : Answer
}

// Sampling weight = (5.5 - score)^2: a word at 1 is 81x likelier than one at 5,
// and an ungraded word outranks everything. The square is not decoration: with
// a linear weight the many strong words win the draw by sheer numbers.
private const val WEIGHT_BASE_MAX = CONFIDENCE_MAX + 0.5
private const val WEIGHT_EXPONENT = 2.0

fun selectionWeight(confidence: Double?): Double =
    (if (confidence == null) WEIGHT_BASE_MAX else WEIGHT_BASE_MAX - confidence).pow(WEIGHT_EXPONENT)

object Decks {
    /**
     * Filter and order the cards for one session; [limit] caps it at that many
     * unique words, applied after ordering (so with WEIGHTED the cut IS the draw).
     *
     * DUE: due words (lowest recall first), then new words; [newPerSession]
     * slots are kept for new words even when more are due.
     */
    fun build(
        cards: List<Card>,
        mode: Mode,
        topics: List<String> = emptyList(),
        difficultThreshold: Int = 2,
        order: Order = Order.WEIGHTED,
        seed: Long = 0,
        limit: Int? = null,
        now: Instant,
        zone: ZoneId,
        newPerSession: Int = 0,
    ): List<Card> {
        require(limit == null || limit >= 1) { "limit must be >= 1" }
        require(newPerSession >= 0) { "newPerSession must be >= 0" }
        var selected = cards
        if (topics.isNotEmpty() && mode != Mode.MIX) {
            val wanted = topics.toSet()
            selected = selected.filter { it.topic in wanted }
        }
        if (mode == Mode.DIFFICULT) selected = selected.filter { it.confidence != null && it.confidence <= difficultThreshold }
        val ordered = if (mode == Mode.DUE) dueFirst(selected, order, seed, now, zone, limit, newPerSession)
        else order(selected, order, seed)
        return if (limit == null) ordered else ordered.take(limit)
    }

    private fun dueFirst(cards: List<Card>, order: Order, seed: Long, now: Instant, zone: ZoneId, limit: Int?, newPerSession: Int): List<Card> {
        val scheduler = Srs.makeScheduler(fuzz = false)
        // Most forgotten first, so a session cut short by the limit still covers the words closest to being lost.
        val due = cards.filter { Srs.status(it.schedule, now, zone) == DueStatus.DUE }
            .sortedWith(compareBy({ Srs.retrievability(it.schedule, now, scheduler) ?: 0.0 }, { it.schedule!!.due }))
        val new = order(cards.filter { it.schedule == null }, order, seed)
        if (limit == null) return due + new
        // Unused reserved slots go back to due words, unused due slots to more new words.
        val reserved = minOf(newPerSession, new.size, limit)
        val takenDue = due.take(limit - reserved)
        return takenDue + new.take(limit - takenDue.size)
    }

    fun order(cards: List<Card>, order: Order, seed: Long): List<Card> {
        val rng = Random(seed)
        return when (order) {
            // Efraimidis-Spirakis: key = U^(1/w); the largest keys are a weighted sample WITHOUT replacement.
            Order.WEIGHTED -> {
                val keys = cards.associate { it.id to rng.nextDouble().pow(1.0 / selectionWeight(it.confidence)) }
                cards.sortedByDescending { keys.getValue(it.id) }
            }
            Order.RANDOM -> cards.shuffled(rng)
            Order.OLDEST_FIRST -> cards.sortedWith(compareBy({ it.dateAdded == null }, { it.dateAdded ?: LocalDate.MAX }))
            Order.LOWEST_CONFIDENCE_FIRST -> {
                val jitter = cards.associate { it.id to rng.nextDouble() }
                cards.sortedWith(compareBy({ it.confidence ?: -1.0 }, { jitter.getValue(it.id) }))
            }
        }
    }

    /**
     * The next word to re-drill, favouring the weakest NOW (in-session scores),
     * never the one just finished unless it is the only one left.
     */
    fun pickNextRepeat(scores: Map<String, Double?>, exclude: String?, seed: Long, draw: Int): String? {
        if (scores.isEmpty()) return null
        val ids = scores.keys.filter { it != exclude }.ifEmpty { scores.keys.toList() }
        val rng = seededRandom("$seed:repeat:$draw")
        val weights = ids.map { selectionWeight(scores[it]) }
        var x = rng.nextDouble() * weights.sum()
        for ((i, w) in weights.withIndex()) {
            x -= w
            if (x < 0) return ids[i]
        }
        return ids.last()
    }

    /** The side shown for one card; MIXED is fixed per (seed, card) so a card keeps its side until re-drawn. */
    fun pickDirection(pref: Direction, cardId: String, seed: Long): Direction {
        if (pref != Direction.MIXED) return pref
        return if (seededRandom("$seed:$cardId").nextDouble() < 0.5) Direction.TERM_TO_NATIVE else Direction.NATIVE_TO_TERM
    }
}

/** One grade given in the session, for the review log. */
@Serializable
data class ReviewEntry(
    val cardId: String,
    val grade: Int,
    val reviewedAtMillis: Long,
    val confidenceBefore: Double?,
    val confidenceAfter: Double,
    val scheduled: Boolean,     // false: an in-session repeat FSRS did not see
    val dueAfterMillis: Long?,
)

@Serializable
data class ScheduleDto(val due: Long, val state: String, val step: Int?, val stability: Double?, val difficulty: Double?, val lastReview: Long?) {
    fun toSchedule() = Schedule(Instant.ofEpochMilli(due), state, step, stability, difficulty, lastReview?.let(Instant::ofEpochMilli))

    companion object {
        fun of(s: Schedule) = ScheduleDto(s.due.toEpochMilli(), s.state, s.step, s.stability, s.difficulty, s.lastReview?.toEpochMilli())
    }
}

/**
 * Everything that changes during a session, as plain data: saved to disk after
 * every action, so a session survives Android stopping the app in the background.
 */
@Serializable
data class SessionState(
    val deckIds: List<String>,
    val direction: Direction,
    val repeat: Boolean,
    val seed: Long,
    val exercise: ExerciseChoice,
    val canListen: Boolean,
    val workbookHash: String,
    val firstPass: MutableList<String>,
    val pending: MutableList<String> = mutableListOf(),
    var draw: Int = 0,
    var current: String?,
    val confidence: MutableMap<String, Double> = linkedMapOf(),
    val schedules: MutableMap<String, ScheduleDto> = linkedMapOf(),
    val log: MutableList<ReviewEntry> = mutableListOf(),
    var turn: Int = 0,
    var result: CheckResult? = null,
)

/**
 * One review session, from the first card to the summary.
 *
 * Two phases: every drawn word is shown once (the first pass) before any
 * repeat, then words still below 5 come round again, re-weighted on every pick.
 *
 * Each grade updates the word's two scores under different rules:
 *  - Confidence: every grade, by averaging; repeats compound on the session's value.
 *  - FSRS: the word's first grade of the session, and after that only grades
 *    given when FSRS itself has the word due again (a learning step minutes
 *    later). Drilling a word four times in a row is not four spaced recalls.
 */
class ReviewSession(
    deck: List<Card>,
    /** Where multiple-choice distractors come from: the whole workbook, so a 5-word deck still gets varied options. */
    val pool: List<Card>,
    val state: SessionState,
    private val scheduler: FsrsScheduler = Srs.makeScheduler(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    val cards: Map<String, Card> = deck.associateBy { it.id }
    val deck: List<Card> = state.deckIds.map { cards.getValue(it) }

    // Previews use an unfuzzed copy: a button whose "next review" changed between two looks would read as a bug.
    private val previewScheduler = scheduler.withoutFuzz()
    private var exercise: Exercise? = null

    init {
        require(state.deckIds.isNotEmpty()) { "cannot start a session with no cards" }
    }

    companion object {
        fun start(
            deck: List<Card>, pool: List<Card>, direction: Direction, repeat: Boolean, seed: Long,
            exercise: ExerciseChoice, canListen: Boolean, workbookHash: String,
            scheduler: FsrsScheduler = Srs.makeScheduler(), zone: ZoneId = ZoneId.systemDefault(),
        ): ReviewSession {
            require(deck.isNotEmpty()) { "cannot start a session with no cards" }
            val ids = deck.map { it.id }
            val state = SessionState(ids, direction, repeat, seed, exercise, canListen, workbookHash,
                firstPass = ids.toMutableList(), current = ids.first())
            return ReviewSession(deck, pool, state, scheduler, zone)
        }
    }

    // --- reading ---------------------------------------------------------------

    val finished get() = state.current == null
    val currentCard: Card? get() = state.current?.let { cards[it] }
    val result: CheckResult? get() = state.result

    /** Graded words in the order first graded. */
    val gradedIds: List<String> get() = state.log.map { it.cardId }.distinct()

    fun effectiveConfidence(id: String): Double? = state.confidence[id] ?: cards.getValue(id).confidence
    fun effectiveSchedule(id: String): Schedule? = state.schedules[id]?.toSchedule() ?: cards.getValue(id).schedule
    fun sessionSchedule(id: String): Schedule? = state.schedules[id]?.toSchedule()
    fun directionFor(id: String) = Decks.pickDirection(state.direction, id, state.seed)

    fun countsForSchedule(id: String, now: Instant): Boolean {
        val s = state.schedules[id] ?: return true
        return Srs.status(s.toSchedule(), now, zone) == DueStatus.DUE
    }

    /** What [grade] would set, for the grade buttons; the schedule is null when it would not reach FSRS. */
    fun preview(id: String, grade: Int, now: Instant): Pair<Double, Schedule?> {
        val confidence = blendConfidence(effectiveConfidence(id), grade)
        if (!countsForSchedule(id, now)) return confidence to null
        return confidence to Srs.review(effectiveSchedule(id), grade, now, previewScheduler)
    }

    // --- exercises -------------------------------------------------------------

    /** The exercise for the card showing now; fixed for the whole turn. */
    fun currentExercise(): Exercise {
        val id = requireCurrent()
        exercise?.let { return it }
        val card = cards.getValue(id)
        val rng = seededRandom("${state.seed}:exercise:${state.turn}:$id")
        val chosen = state.exercise.kind
        val wanted = if (chosen == null) FALLBACK.keys else FALLBACK[chosen].orEmpty().toSet()
        val made = linkedMapOf(Kind.FLASHCARD to Exercise(Kind.FLASHCARD))
        if (Kind.BLANK in wanted) Exercises.makeBlank(card)?.let { made[Kind.BLANK] = Exercise(Kind.BLANK, blank = it) }
        if (Kind.RECALL in wanted) Exercises.makeRecall(card)?.let { made[Kind.RECALL] = Exercise(Kind.RECALL, blank = it) }
        if (Kind.LISTEN in wanted && state.canListen) Exercises.makeListen(card)?.let { made[Kind.LISTEN] = Exercise(Kind.LISTEN, blank = it) }
        if (Kind.SCRAMBLE in wanted) Exercises.makeScramble(card, rng)?.let { made[Kind.SCRAMBLE] = Exercise(Kind.SCRAMBLE, scramble = it) }
        if (Kind.FORMS in wanted) Exercises.makeForms(card)?.let { made[Kind.FORMS] = Exercise(Kind.FORMS, forms = it) }
        if (Kind.ARTICLE in wanted) Exercises.makeArticle(card)?.let { made[Kind.ARTICLE] = Exercise(Kind.ARTICLE, choice = it) }
        if (Kind.CHOICE in wanted) Exercises.makeChoice(card, pool, directionFor(id), rng)?.let { made[Kind.CHOICE] = Exercise(Kind.CHOICE, choice = it) }
        val ex = if (chosen == null) {
            made.getValue(made.keys.sortedBy { it.name }.random(rng))
        } else {
            made.getValue(FALLBACK[chosen].orEmpty().firstOrNull { it in made } ?: Kind.FLASHCARD)
        }
        exercise = ex
        return ex
    }

    /** Check an answer; once per turn. Throws IllegalArgumentException for an answer that doesn't fit. */
    fun check(answer: Answer): CheckResult {
        val ex = currentExercise()
        state.result?.let { return it }
        val r = when (ex.kind) {
            Kind.CHOICE, Kind.ARTICLE -> {
                val picked = (answer as? Answer.Picked)?.index ?: throw IllegalArgumentException("pick one of the options")
                val choice = ex.choice!!
                require(picked in choice.options.indices) { "pick one of the options" }
                val v = Exercises.checkChoice(choice, picked)
                CheckResult(v, choice.options[choice.answerIndex], suggestedGrade(ex.kind, v), picked = picked)
            }
            Kind.SCRAMBLE -> {
                val order = (answer as? Answer.Ordered)?.order ?: throw IllegalArgumentException("place every word exactly once")
                val s = ex.scramble!!
                val v = Exercises.checkScramble(s, order)
                CheckResult(v, s.answer.joinToString(" ") + s.end, suggestedGrade(ex.kind, v),
                    given = order.joinToString(" ") { s.tiles[it] } + s.end, order = order)
            }
            Kind.FORMS -> {
                val given = (answer as? Answer.Typed)?.text ?: ""
                val v = Exercises.checkForms(given, ex.forms!!)
                CheckResult(v, ex.forms.answer, suggestedGrade(ex.kind, v), given = given)
            }
            Kind.BLANK, Kind.RECALL, Kind.LISTEN -> {
                val given = (answer as? Answer.Typed)?.text ?: ""
                val v = Exercises.checkTyped(given, ex.blank!!)
                CheckResult(v, ex.blank.answer, suggestedGrade(ex.kind, v), given = given)
            }
            Kind.FLASHCARD -> throw IllegalArgumentException("a flashcard has nothing to check")
        }
        state.result = r
        return r
    }

    // --- actions ---------------------------------------------------------------

    fun grade(grade: Int, now: Instant) {
        val id = requireCurrent()
        val before = effectiveConfidence(id)
        val new = blendConfidence(before, grade)
        val scheduled = countsForSchedule(id, now)
        if (scheduled) state.schedules[id] = ScheduleDto.of(Srs.review(effectiveSchedule(id), grade, now, scheduler))
        state.confidence[id] = new
        state.log.add(ReviewEntry(id, grade, now.toEpochMilli(), before, new, scheduled, state.schedules[id]?.due))
        // Mastery is the STORED score, not the button: one 5 on a weak word lands below 5 and comes round again.
        advance(repeatCurrent = state.repeat && new < CONFIDENCE_MAX)
    }

    /** Move on without grading; a skipped repeat is done for this session. */
    fun skip() {
        requireCurrent()
        advance(repeatCurrent = false)
    }

    fun end() {
        state.current = null
    }

    /**
     * The cells to write: confidence only where it actually changed, the
     * schedule for every word FSRS reviewed.
     */
    fun cellUpdates(): Map<RowKey, Map<String, CellWrite>> {
        val out = LinkedHashMap<RowKey, Map<String, CellWrite>>()
        for (id in gradedIds) {
            val card = cards.getValue(id)
            val cols = LinkedHashMap<String, CellWrite>()
            state.schedules[id]?.let { cols.putAll(Srs.scheduleToCells(it.toSchedule())) }
            val conf = state.confidence.getValue(id)
            if (conf != card.confidence) cols[CONFIDENCE_COL] = CellWrite.Number(conf)
            if (cols.isNotEmpty()) out[RowKey(card.sheet, card.row)] = cols
        }
        return out
    }

    private fun requireCurrent(): String = state.current ?: throw IllegalStateException("the session has ended")

    private fun advance(repeatCurrent: Boolean) {
        val id = state.current
        state.turn += 1
        exercise = null
        state.result = null
        if (state.firstPass.firstOrNull() == id) state.firstPass.removeAt(0)
        if (repeatCurrent) {
            if (id != null && id !in state.pending) state.pending.add(id)
        } else {
            state.pending.remove(id)  // reached 5 (or skipped): done for this session
        }
        state.current = when {
            state.firstPass.isNotEmpty() -> state.firstPass.first()
            state.pending.isNotEmpty() -> {
                state.draw += 1
                Decks.pickNextRepeat(state.pending.associateWith { effectiveConfidence(it) }, id, state.seed, state.draw)
            }
            else -> null
        }
    }
}
