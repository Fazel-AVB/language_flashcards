package com.fazelavb.flashcards.core

import java.time.Instant
import java.time.ZoneId
import kotlin.math.round

/*
 * What each button does, turned into plain data for the screens. A port of
 * flashcards/service.py without the file handling (data/ does the I/O).
 */

val GRADE_LABELS = mapOf(1 to "needs work", 2 to "shaky", 3 to "getting there", 4 to "solid", 5 to "mastered")
const val MAX_SESSION_SIZE = 500

/** The deck settings on the start screen; remembered between sessions. */
data class DeckOptions(
    val mode: Mode = Mode.DUE,
    val exercise: ExerciseChoice = ExerciseChoice.FLASHCARD,
    val topics: List<String> = emptyList(),
    val direction: Direction = Direction.MIXED,
    val threshold: Int = 2,
    val order: Order = Order.WEIGHTED,
    val repeat: Boolean = false,
    // 20: ~6 min for the first pass at 18 s a card; Anki's long-standing default.
    val size: Int = 20,
    // A quarter of a 20-word session: reviews still dominate, and new words keep coming.
    val newPerSession: Int = 5,
)

data class TopicRow(val topic: String, val words: Int, val due: Int, val new: Int, val graded: Int, val avgConfidence: Double?)

data class Overview(
    val languages: LanguagePair,
    val termCode: String?,
    val nativeCode: String?,
    val words: Int,
    val articleWords: Int,
    val formsWords: Int,
    val topics: List<TopicRow>,
    val due: Int,
    val new: Int,
    val later: Int,
    val nextDue: String?,
    val languageConflicts: List<String>,
    val scheduleProblems: List<String>,
)

data class Scope(val inScope: Int, val drawn: Int, val due: Int, val new: Int)

data class Side(val text: String, val language: String, val rtl: Boolean, val lang: String?, val hint: String = "")

data class Back(
    val term: String,
    val meaning: String,
    val termLanguage: String,
    val nativeLanguage: String,
    val termRtl: Boolean,
    val nativeRtl: Boolean,
    val termLang: String?,
    val nativeLang: String?,
    val articleLabel: String,
    val article: String?,
    val pronunciation: String?,
    val wordType: String?,
    val formsLabel: String,
    val forms: String?,
    val exampleTerm: String?,
    val exampleMeaning: String?,
    val synonyms: String?,
    val notes: String?,
)

data class GradeOption(val grade: Int, val label: String, val confidence: String, val next: String)

/** Everything one exercise needs on screen. */
data class ExerciseView(
    val kind: Kind,
    val options: List<String> = emptyList(),
    val rtl: Boolean = false,
    val lang: String? = null,
    val before: String = "",
    val after: String = "",
    val answerLen: Int = 0,
    val hint: String = "",
    val hintRtl: Boolean = false,
    val hintExample: String = "",
    val say: String = "",
    val tiles: List<String> = emptyList(),
    val end: String = "",
    val label: String = "",
    val prompt: String = "",
)

data class CardView(
    val id: String,
    val turn: Int,
    val topic: String,
    val directionLabel: String,
    val front: Side,
    val confidence: String,
    val schedule: String,
    val back: Back,
    val exercise: ExerciseView,
    val result: CheckResult?,
    val done: Int,
    val total: Int,
    val repeats: Int,
    val grades: List<GradeOption>,
)

data class SummaryRow(
    val topic: String,
    val term: String,
    val meaning: String,
    val termRtl: Boolean,
    val meaningRtl: Boolean,
    val old: String,
    val new: String,
    val next: String,
    val grades: List<Int>,
)

data class Summary(val rows: List<SummaryRow>, val languages: LanguagePair, val cells: Int)

class UserException(message: String) : Exception(message)

class Engine(
    private val now: () -> Instant = { Instant.now() },
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val fuzz: Boolean = true,
) {
    fun overview(data: WorkbookData, threshold: Int): Overview {
        val t = now()
        val status = data.cards.associate { it.id to Srs.status(it.schedule, t, zone) }
        val topics = data.stats(threshold).map { stat ->
            val group = data.cards.filter { it.topic == stat.topic }
            TopicRow(
                topic = stat.topic, words = stat.count,
                due = group.count { status[it.id] == DueStatus.DUE },
                new = group.count { status[it.id] == DueStatus.NEW },
                graded = stat.graded,
                avgConfidence = stat.avgConfidence?.let { round(it * 100) / 100 },
            )
        }
        val later = data.cards.filter { status[it.id] == DueStatus.LATER }.map { it.schedule!!.due }
        return Overview(
            languages = data.languages,
            termCode = languageCode(data.languages.term),
            nativeCode = languageCode(data.languages.native),
            words = data.cards.size,
            articleWords = data.cards.count { Exercises.articleOf(it) != null },
            formsWords = data.cards.count { Exercises.makeForms(it) != null },
            topics = topics,
            due = status.values.count { it == DueStatus.DUE },
            new = status.values.count { it == DueStatus.NEW },
            later = later.size,
            nextDue = later.minOrNull()?.let { Srs.describeDue(it, t, zone) },
            languageConflicts = data.languageConflicts,
            scheduleProblems = data.scheduleProblems,
        )
    }

    /** How many words a deck with these settings draws from, and draws. */
    fun scope(data: WorkbookData, opts: DeckOptions): Scope {
        val pool = build(data, opts, seed = 0, limit = null)
        // Built again with the limit, not sliced: in DUE mode the limit is what reserves new-word slots.
        val drawn = build(data, opts, seed = 0, limit = opts.size)
        val t = now()
        return Scope(pool.size, drawn.size,
            due = drawn.count { Srs.status(it.schedule, t, zone) == DueStatus.DUE },
            new = drawn.count { it.schedule == null })
    }

    /** [voices]: language codes the phone can speak; listening needs the term language among them. */
    fun start(data: WorkbookData, opts: DeckOptions, voices: Set<String>, seed: Long): ReviewSession {
        validate(opts)
        val canListen = languageCode(data.languages.term) in voices
        if (opts.exercise == ExerciseChoice.LISTEN && !canListen) {
            throw UserException("Listening needs a ${data.languages.term} voice on this phone, and there is none. " +
                "See Pronunciation on the start screen for how to add one.")
        }
        val deck = build(data, opts, seed = seed, limit = opts.size)
        if (deck.isEmpty()) throw UserException("No words match these settings.")
        return ReviewSession.start(deck, data.cards, opts.direction, opts.repeat, seed, opts.exercise, canListen,
            data.hash, Srs.makeScheduler(fuzz), zone)
    }

    fun resume(data: WorkbookData, state: SessionState): ReviewSession? {
        if (state.workbookHash != data.hash) return null
        val byId = data.cards.associateBy { it.id }
        if (!state.deckIds.all { it in byId }) return null
        return ReviewSession(state.deckIds.map { byId.getValue(it) }, data.cards, state, Srs.makeScheduler(fuzz), zone)
    }

    private fun validate(opts: DeckOptions) {
        if (opts.mode == Mode.TOPIC && opts.topics.isEmpty()) throw UserException("Pick at least one topic.")
        if (opts.threshold !in CONFIDENCE_MIN..CONFIDENCE_MAX) throw UserException("The difficulty threshold must be between 1 and 5.")
        if (opts.size !in 1..MAX_SESSION_SIZE) throw UserException("Words per session must be between 1 and $MAX_SESSION_SIZE.")
        if (opts.newPerSession !in 0..MAX_SESSION_SIZE) throw UserException("New words per session must be between 0 and $MAX_SESSION_SIZE.")
    }

    private fun build(data: WorkbookData, opts: DeckOptions, seed: Long, limit: Int?): List<Card> {
        // Only words the drill can ask about, so "N words in scope" counts what you'll get.
        val cards = when (opts.exercise) {
            ExerciseChoice.ARTICLE -> data.cards.filter { Exercises.articleOf(it) != null }
            ExerciseChoice.FORMS -> data.cards.filter { Exercises.makeForms(it) != null }
            else -> data.cards
        }
        val topics = if (opts.mode == Mode.MIX) emptyList() else opts.topics
        return Decks.build(cards, opts.mode, topics, opts.threshold, opts.order, seed, limit, now(), zone, opts.newPerSession)
    }

    // ------------------------------------------------------------------ the card on screen

    fun cardView(s: ReviewSession): CardView {
        val card = s.currentCard ?: throw IllegalStateException("the session has ended")
        val t = now()
        val ex = s.currentExercise()
        val pair = card.languages
        // Some exercises fix the side you see: the article drill shows the noun, typing the word its meaning.
        val direction = when (ex.kind) {
            Kind.ARTICLE, Kind.FORMS, Kind.LISTEN -> Direction.TERM_TO_NATIVE
            Kind.RECALL -> Direction.NATIVE_TO_TERM
            else -> s.directionFor(card.id)
        }
        val termSide = { hint: String -> Side(card.term, pair.term, isRtl(pair.term), languageCode(pair.term), hint) }
        val emptyTerm = Side("", pair.term, isRtl(pair.term), languageCode(pair.term))
        var front = if (direction == Direction.TERM_TO_NATIVE) termSide(card.pronunciation ?: "")
        // No pronunciation next to the meaning: it would spell out the answer.
        else Side(card.meaning, pair.native, isRtl(pair.native), languageCode(pair.native))

        val schedule = s.effectiveSchedule(card.id)
        val scheduleText = when (Srs.status(schedule, t, zone)) {
            DueStatus.NEW -> "new word"
            DueStatus.DUE -> "due now"
            DueStatus.LATER -> "next review ${Srs.describeDue(schedule!!.due, t, zone)}"
        }
        val grades = (CONFIDENCE_MIN..CONFIDENCE_MAX).map { g ->
            val (conf, sched) = s.preview(card.id, g, t)
            GradeOption(g, GRADE_LABELS.getValue(g), formatConfidence(conf),
                if (sched == null) "schedule unchanged (repeat)" else "next review ${Srs.describeDue(sched.due, t, zone)}")
        }

        val termRtl = isRtl(pair.term)
        val termLang = languageCode(pair.term)
        var view = ExerciseView(kind = ex.kind)
        ex.choice?.let { c ->
            val langName = if (c.answerSide == "native") pair.native else pair.term
            view = view.copy(options = c.options, rtl = isRtl(langName), lang = languageCode(langName))
        }
        ex.forms?.let { f ->
            // The noun with its article ("der Stift") says which plural is meant.
            val article = Exercises.articleOf(card)
            view = view.copy(label = f.label, prompt = if (article != null) "$article ${card.term}" else card.term,
                answerLen = f.answer.length, rtl = termRtl, lang = termLang)
        }
        ex.scramble?.let { sc ->
            view = view.copy(tiles = sc.tiles, end = sc.end, rtl = termRtl, lang = termLang,
                hint = card.exampleMeaning ?: card.meaning, hintRtl = isRtl(pair.native))
            // The tiles are the prompt; the word on top would only say which tile is "it".
            if (s.result == null) front = emptyTerm
        }
        ex.blank?.let { b ->
            view = view.copy(before = b.before, after = b.after, rtl = termRtl, answerLen = b.answer.length, lang = termLang,
                hint = card.meaning, hintRtl = isRtl(pair.native), hintExample = card.exampleMeaning ?: "")
            // Type the word: the meaning is the front; the word type narrows it down without giving it away.
            if (ex.kind == Kind.RECALL) view = view.copy(hint = card.wordType ?: "", hintRtl = false, hintExample = "")
            // Listening: the meaning would let you write the word without listening at all.
            if (ex.kind == Kind.LISTEN) view = view.copy(say = card.term, hint = card.wordType ?: "", hintRtl = false, hintExample = "")
            if ((ex.kind == Kind.BLANK || ex.kind == Kind.LISTEN) && s.result == null) front = emptyTerm
        }

        return CardView(
            id = card.id,
            turn = s.state.turn,
            topic = card.topic,
            directionLabel = pair.frontLabel(direction),
            front = front,
            confidence = formatConfidence(s.effectiveConfidence(card.id)),
            schedule = scheduleText,
            back = back(card),
            exercise = view,
            result = s.result,
            done = s.gradedIds.size,
            total = s.deck.size,
            repeats = s.state.pending.count { it != card.id },
            grades = grades,
        )
    }

    fun summary(s: ReviewSession, languages: LanguagePair): Summary {
        val t = now()
        val rows = s.gradedIds.map { id ->
            val card = s.cards.getValue(id)
            val sched = s.sessionSchedule(id)
            SummaryRow(
                topic = card.topic, term = card.term, meaning = card.meaning,
                termRtl = isRtl(card.languages.term), meaningRtl = isRtl(card.languages.native),
                old = card.confidence?.let(::formatConfidence) ?: "—",
                new = formatConfidence(s.state.confidence.getValue(id)),
                next = sched?.let { Srs.describeDue(it.due, t, zone) } ?: "unchanged",
                grades = s.state.log.filter { it.cardId == id }.map { it.grade },
            )
        }.sortedWith(compareBy({ it.topic }, { it.term.lowercase() }))
        return Summary(rows, languages, s.cellUpdates().values.sumOf { it.size })
    }

    /** The cells to save, with the schedule headers added to sheets that get their first schedule. */
    fun updatesToSave(s: ReviewSession, data: WorkbookData): Map<RowKey, Map<String, CellWrite>> {
        val updates = LinkedHashMap(s.cellUpdates())
        val touched = updates.filterValues { "M" in it }.keys.map { it.sheet }.toSet()
        for (sheet in touched.intersect(data.sheetsMissingSrsHeaders.toSet())) {
            updates[RowKey(sheet, 1)] = SRS_HEADERS.mapValues { CellWrite.Text(it.value) }
        }
        return updates
    }

    private fun back(card: Card): Back {
        val pair = card.languages
        return Back(
            term = card.term, meaning = card.meaning,
            termLanguage = pair.term, nativeLanguage = pair.native,
            termRtl = isRtl(pair.term), nativeRtl = isRtl(pair.native),
            termLang = languageCode(pair.term), nativeLang = languageCode(pair.native),
            articleLabel = card.articleLabel, article = card.articleAux,
            pronunciation = card.pronunciation, wordType = card.wordType,
            formsLabel = card.formsLabel, forms = card.forms,
            exampleTerm = card.exampleTerm, exampleMeaning = card.exampleMeaning,
            synonyms = card.synonyms, notes = card.notes,
        )
    }
}

/** One line per grade, for tuning FSRS to your own memory later (fsrs Optimizer). */
object ReviewLog {
    val HEADER = listOf("reviewed_at", "workbook", "sheet", "row", "term", "grade", "fsrs_rating",
        "scheduled", "confidence_before", "confidence_after", "due_after")

    fun lines(workbookName: String, s: ReviewSession): List<String> = s.state.log.map { e ->
        val card = s.cards.getValue(e.cardId)
        listOf(
            Srs.formatTime(Instant.ofEpochMilli(e.reviewedAtMillis)), workbookName, card.sheet, card.row.toString(),
            card.term, e.grade.toString(), gradeToRating(e.grade).name, if (e.scheduled) "1" else "0",
            e.confidenceBefore?.let(::confidenceToCell) ?: "", confidenceToCell(e.confidenceAfter),
            e.dueAfterMillis?.let { Srs.formatTime(Instant.ofEpochMilli(it)) } ?: "",
        ).joinToString(",", transform = ::csvField)
    }

    fun csvField(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
}
