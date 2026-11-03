package com.fazelavb.flashcards.core

import java.text.Normalizer
import kotlin.random.Random

/*
 * Exercises built from the workbook itself: multiple choice, fill in the blank,
 * type the word, listening, sentence scramble, the German article drill and
 * the forms drill. A port of flashcards/exercises.py.
 *
 * An exercise only changes HOW you answer a card. After checking, the app
 * proposes a grade (SUGGESTED_GRADE) and you confirm or override it with the
 * usual 1-5 buttons, so confidence and FSRS work exactly as for a flashcard.
 */

enum class Kind(val label: String) {
    FLASHCARD("Flashcards"),
    CHOICE("Multiple choice"),
    BLANK("Fill in the blank"),
    RECALL("Type the word"),
    LISTEN("Listening"),
    SCRAMBLE("Sentence scramble"),
    ARTICLE("Article (der/die/das)"),
    FORMS("Forms (plural, past)"),
}

enum class Verdict { CORRECT, CLOSE, WRONG }

val ARTICLES = listOf("der", "die", "das")

/**
 * Proposed grade per outcome. A wrong article, form or word order proposes 2,
 * not 1: those mistakes don't mean the word itself is forgotten.
 */
fun suggestedGrade(kind: Kind, verdict: Verdict): Int = when (verdict) {
    Verdict.CORRECT -> 4
    Verdict.CLOSE -> 3
    Verdict.WRONG -> if (kind in setOf(Kind.SCRAMBLE, Kind.ARTICLE, Kind.FORMS)) 2 else 1
}

const val N_OPTIONS = 4
// Under 4 words there is no order to get wrong; over 16 it is a puzzle about the sentence.
const val SCRAMBLE_MIN_WORDS = 4
const val SCRAMBLE_MAX_WORDS = 16
// A blank covering most of the sentence is not an exercise ("Idioms & Sentences" sheets).
const val MAX_BLANK_SHARE = 0.6

/** Pick the answer among [options]. */
data class Choice(val options: List<String>, val answerIndex: Int, val answerSide: String) // "native" | "term" | "article"

/** The example sentence with the word cut out: before + ____ + after. */
data class Blank(val before: String, val after: String, val answer: String, val accepted: List<String>)

/** The example's words, shuffled; [end] is the final punctuation shown fixed after the last tile. */
data class Scramble(val tiles: List<String>, val answer: List<String>, val end: String)

/** Produce one grammatical form: "Plural", "Perfekt (er/sie/es …)", "Past participle". */
data class Forms(val label: String, val answer: String, val accepted: List<String>, val partial: List<String>)

/** A Random from a string key, identical on every run and device (unlike String.hashCode, 64 bits). */
fun seededRandom(key: String): Random {
    var h = -0x340d631b7bdddcdbL  // FNV-1a 64-bit offset basis
    for (b in key.toByteArray(Charsets.UTF_8)) {
        h = h xor (b.toLong() and 0xff)
        h *= 0x100000001b3L
    }
    return Random(h)
}

object Exercises {
    // ------------------------------------------------------------ multiple choice

    /**
     * The answer plus three distractors from [pool], same language pair,
     * preferring the same word type, then the same topic. Null when the pool
     * has no other distinct answer.
     */
    fun makeChoice(card: Card, pool: List<Card>, direction: Direction, rng: Random): Choice? {
        val side = if (direction == Direction.TERM_TO_NATIVE) "native" else "term"
        val answer = side(card, side).ifEmpty { return null }
        val seen = hashSetOf(canon(answer))
        val candidates = pool.filter { it.id != card.id && it.languages == card.languages }.shuffled(rng)
            // Stable sort after the shuffle: random within each tier, best tier first.
            .sortedWith(compareBy({ !sameType(it, card) }, { it.topic != card.topic }))
        val distractors = ArrayList<String>()
        for (c in candidates) {
            val text = side(c, side)
            if (text.isNotEmpty() && seen.add(canon(text))) distractors.add(text)
            if (distractors.size == N_OPTIONS - 1) break
        }
        if (distractors.isEmpty()) return null
        val options = (distractors + answer).shuffled(rng)
        return Choice(options, options.indexOf(answer), side)
    }

    fun checkChoice(choice: Choice, picked: Int) = if (picked == choice.answerIndex) Verdict.CORRECT else Verdict.WRONG

    // ------------------------------------------------------------ sentence scramble

    /**
     * Shuffle the words of the example sentence (column G). Commas stay on
     * their word; the final ./?/! is cut off and shown fixed. The capital on
     * the first word is kept: in German it can't be told from a noun's.
     */
    fun makeScramble(card: Card, rng: Random): Scramble? {
        val sentence = (card.exampleTerm ?: "").trim()
        val end = Regex("[.!?…]+$").find(sentence)?.value ?: ""
        val words = splitWords(sentence.substring(0, sentence.length - end.length))
        if (words.size !in SCRAMBLE_MIN_WORDS..SCRAMBLE_MAX_WORDS) return null
        repeat(10) {
            val tiles = words.shuffled(rng)
            if (tiles != words) return Scramble(tiles, words, end)
        }
        return null  // e.g. every word identical
    }

    /** [order]: tile indices as placed. Compared as text; one swap of neighbours is CLOSE. */
    fun checkScramble(s: Scramble, order: List<Int>): Verdict {
        require(order.sorted() == s.tiles.indices.toList()) { "place every word exactly once" }
        val built = order.map { s.tiles[it] }
        if (built == s.answer) return Verdict.CORRECT
        val diff = built.indices.filter { built[it] != s.answer[it] }
        if (diff.size == 2 && diff[1] == diff[0] + 1 && built[diff[0]] == s.answer[diff[1]] && built[diff[1]] == s.answer[diff[0]]) {
            return Verdict.CLOSE
        }
        return Verdict.WRONG
    }

    // ------------------------------------------------------------ forms drill

    // Column E separates verb forms with a SPACED dash, so "well-known" is never split.
    private val FORM_SEP = Regex("""\s+[–—]\s+""")
    private val GERMAN_PRONOUNS = listOf("er", "sie", "es")

    /** A plural question for nouns, a past-form question for verbs (column C), else null. */
    fun makeForms(card: Card): Forms? {
        val cell = (card.forms ?: "").trim()
        val wordType = (card.wordType ?: "").trim().lowercase()
        if (cell.isEmpty() || cell[0] in "—-–" || ';' in cell || '(' in cell) return null
        val german = languageCode(card.languages.term) == "de"
        val parts = cell.split(FORM_SEP)

        if (wordType.startsWith("noun") && parts.size == 1) {
            if (german && cell.lowercase() in setOf("nur plural", "kein plural")) return null
            val accepted = alternatives(cell)
            // "die Stifte": the plural without its article is right too.
            val bare = accepted.filter { ' ' in it && it.substringBefore(' ').lowercase() in ARTICLES }.map { it.substringAfter(' ') }
            return Forms("Plural", cell, unique(accepted + bare, cell), emptyList())
        }
        if (wordType.startsWith("verb") && parts.size == 3) {
            val third = parts[2].trim()
            if (german) {
                val aux = third.substringBefore(' ')
                val participle = if (' ' in third) third.substringAfter(' ') else ""
                if (aux.lowercase() !in setOf("hat", "ist") || participle.isEmpty()) return null
                return Forms("Perfekt (er/sie/es …)", third, unique(GERMAN_PRONOUNS.map { "$it $third" }, third), listOf(participle))
            }
            return Forms("Past participle", third, unique(alternatives(third), third), emptyList())
        }
        return null
    }

    /** Strict: a one-letter difference IS the grammar being tested. Only case, spacing and ae-for-ä are forgiven. */
    fun checkForms(given: String, forms: Forms): Verdict {
        val g = canon(given)
        if (g.isNotEmpty() && (listOf(forms.answer) + forms.accepted).any { g == canon(it) }) return Verdict.CORRECT
        if (g.isNotEmpty() && forms.partial.any { g == canon(it) }) return Verdict.CLOSE
        return Verdict.WRONG
    }

    private fun alternatives(text: String) = text.split('/').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(text) }

    private fun unique(values: List<String>, answer: String) = values.filter { canon(it) != canon(answer) }.distinct()

    // ------------------------------------------------------------ article drill

    /** The noun's article if column D is exactly der, die or das. */
    fun articleOf(card: Card): String? = (card.articleAux ?: "").trim().lowercase().takeIf { it in ARTICLES }

    fun makeArticle(card: Card): Choice? {
        val a = articleOf(card) ?: return null
        return Choice(ARTICLES, ARTICLES.indexOf(a), "article")
    }

    // ------------------------------------------------------------ type the word, listening

    /** See the meaning, type the word. */
    fun makeRecall(card: Card): Blank? = if (card.meaning.isEmpty()) null else typedWord(card)

    /** Hear the word, type it. Needs no meaning: the word itself is the prompt. */
    fun makeListen(card: Card): Blank? = typedWord(card)

    private fun typedWord(card: Card): Blank? {
        if (card.term.isEmpty()) return null
        val variants = termVariants(card.term)
        val article = articleOf(card)
        // Typing the noun with its article ("die Kerze") is right, not wrong.
        val withArticle = if (article != null) variants.map { "$article $it" } else emptyList()
        val accepted = (variants + withArticle).filter { canon(it) != canon(card.term) }.distinct()
        return Blank("", "", card.term, accepted)
    }

    // ------------------------------------------------------------ fill in the blank

    /** Cut the word out of its example sentence, or null if it isn't in there. */
    fun makeBlank(card: Card): Blank? {
        val sentence = card.exampleTerm ?: ""
        for (variant in termVariants(card.term)) {
            val (start, end) = find(sentence, variant) ?: continue
            if (end - start > MAX_BLANK_SHARE * sentence.trim().length) return null
            val answer = sentence.substring(start, end)
            val accepted = (listOf(card.term) + termVariants(card.term)).filter { canon(it) != canon(answer) }.distinct()
            return Blank(sentence.substring(0, start), sentence.substring(end), answer, accepted)
        }
        return null
    }

    /**
     * CORRECT: same text up to case, spacing, end punctuation and ae/oe/ue/ss;
     * CLOSE: only accents differ, or one typo in a word of 5+ letters.
     */
    fun checkTyped(given: String, blank: Blank): Verdict {
        if (given.isBlank()) return Verdict.WRONG
        val targets = listOf(blank.answer) + blank.accepted
        val g = canon(given)
        if (targets.any { g == canon(it) }) return Verdict.CORRECT
        if (targets.any { fold(g) == fold(canon(it)) }) return Verdict.CLOSE
        if (targets.any { canon(it).length >= 5 && editDistanceLe1(g, canon(it)) }) return Verdict.CLOSE
        return Verdict.WRONG
    }

    private val PLACEHOLDERS = Regex("""(?i)\s(?:someone|somebody|something|someone's|oneself|yourself|sb|sth)(?=\s)""")

    /**
     * Forms of a headword worth looking for in a sentence, longest first:
     * "sich freuen (auf)" -> "sich freuen (auf)", "sich freuen", "freuen".
     */
    fun termVariants(term: String): List<String> {
        val out = mutableListOf(term.trim())
        val noParen = term.replace(Regex("""\s*\([^)]*\)"""), "").trim()
        out.add(noParen)
        for (part in noParen.split(Regex("""\s*[/,;]\s*"""))) {
            out.add(part)
            // German reflexive verbs: the sentence conjugates "sich".
            if (part.lowercase().startsWith("sich ")) out.add(part.substring(5))
            // English dictionary forms: "to get whiplash", "lash out at someone".
            var bare = PLACEHOLDERS.replace(" $part ", " ").trim()
            bare = bare.replace(Regex("^to\\s+", RegexOption.IGNORE_CASE), "")
            if (bare.isNotEmpty()) out.add(bare)
        }
        val seen = HashSet<String>()
        return out.filter { it.isNotEmpty() }.sortedByDescending { it.length }.filter { seen.add(it.lowercase()) }
    }

    // ------------------------------------------------------------ helpers

    private val GERMAN = mapOf('ä' to "ae", 'ö' to "oe", 'ü' to "ue", 'ß' to "ss")
    // Persian typed with Arabic code points (ي ك); the zero-width non-joiner is not a mistake to judge.
    private val PERSIAN = mapOf('ي' to "ی", 'ك' to "ک", '‌' to "")
    private val SPACES = Regex("""(?U)\s+""")
    private val EDGE_PUNCT = Regex("""(?U)^[\W_]+|[\W_]+$""")

    /** Comparison form: NFC, case-folded, ä->ae, Persian letters unified, spaces collapsed, end punctuation dropped. */
    fun canon(text: String): String {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase().replace('ς', 'σ')
        val sb = StringBuilder(folded.length)
        for (ch in folded) sb.append(GERMAN[ch] ?: PERSIAN[ch] ?: ch.toString())
        val t = SPACES.replace(sb.toString(), " ").trim()
        return EDGE_PUNCT.replace(t, "")
    }

    /** Accents removed: "kuhlschrank" == "kühlschrank" here, but not in canon(). */
    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

    /**
     * Span of [term] in [sentence], widened to whole words: the term at a word
     * start ("riesig" blanks "riesiges"), or for one word a word sharing its
     * first len-2 letters (min 4), so "schreiben" finds "schreibe".
     */
    private fun find(sentence: String, term: String): Pair<Int, Int>? {
        if (term.isEmpty() || sentence.isEmpty()) return null
        val m = Regex("""(?iu)(?<![\p{L}\p{N}_])""" + Regex.escape(term)).find(sentence)
        if (m != null) {
            var end = m.range.last + 1
            while (end < sentence.length && (sentence[end].isLetterOrDigit() || sentence[end] == '‌')) end++
            return m.range.first to end
        }
        if (' ' in term) return null
        val t = term.lowercase()
        val need = maxOf(4, t.length - 2)
        if (t.length < need) return null
        for (w in Regex("""[\p{L}\p{N}_]+""").findAll(sentence)) {
            if (w.value.lowercase().take(need) == t.take(need)) return w.range.first to w.range.last + 1
        }
        return null
    }

    private fun editDistanceLe1(a0: String, b0: String): Boolean {
        if (a0 == b0) return true
        if (kotlin.math.abs(a0.length - b0.length) > 1) return false
        val (a, b) = if (a0.length > b0.length) b0 to a0 else a0 to b0
        var i = 0
        while (i < a.length && a[i] == b[i]) i++
        return if (a.length == b.length) a.substring(minOf(i + 1, a.length)) == b.substring(minOf(i + 1, b.length))
        else a.substring(i) == b.substring(i + 1)
    }

    private fun side(card: Card, side: String) = if (side == "native") card.meaning else card.term

    private fun sameType(a: Card, b: Card) =
        !a.wordType.isNullOrEmpty() && a.wordType.trim().lowercase() == (b.wordType ?: "").trim().lowercase()

    private fun splitWords(s: String) = s.trim().split(SPACES).filter { it.isNotEmpty() }
}
