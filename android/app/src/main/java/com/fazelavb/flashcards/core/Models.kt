package com.fazelavb.flashcards.core

import java.time.LocalDate
import kotlin.math.floor

/*
 * The card model, the positional column contract and the language pair.
 * A port of flashcards/models.py; the rules and their reasons are the same.
 *
 * Every topic sheet shares one layout, columns A..L with the header on row 1.
 * Column POSITIONS are the contract; the labels of D and E differ per sheet
 * and are only shown as captions. The one thing read from a label: A1 and B1
 * hold the bare language names ("German" / "English"), which is how one app
 * serves any language pair. Columns M..R hold the FSRS schedule (Srs.kt).
 *
 * Card.sheet + Card.row are the write-back address used by Writeback.kt.
 */

/** 1-based column of the confidence value; anchors write-back. */
const val CONFIDENCE_COL = "L"
const val CONFIDENCE_COL_INDEX = 12

const val CONFIDENCE_MIN = 1
const val CONFIDENCE_MAX = 5

/**
 * Grades are averaged into the stored score, so results are snapped to this
 * grid; repeated averaging would otherwise walk off into 2.8125.
 */
const val CONFIDENCE_STEP = 0.5

/**
 * Sheets skipped by name. Load-bearing: a "Card Pool" dashboard has "Topic"
 * in A1 and "Confidence" in L1, so it passes the structural test.
 */
private val NON_TOPIC_NAME = Regex(
    """\b(overview|dashboard|flashcards?|card\s*pool|summary|stats)\b""", RegexOption.IGNORE_CASE,
)

/** Scripts written right to left, by the language name in A1/B1 (display only). */
private val RTL_LANGUAGES = setOf("persian", "farsi", "arabic", "hebrew", "urdu", "pashto", "dari", "kurdish")

/** Language name -> BCP 47 primary tag, for picking a text-to-speech voice. */
val LANGUAGE_CODES = mapOf(
    "arabic" to "ar", "chinese" to "zh", "danish" to "da", "dari" to "fa", "dutch" to "nl",
    "english" to "en", "farsi" to "fa", "finnish" to "fi", "french" to "fr", "german" to "de",
    "greek" to "el", "hebrew" to "he", "hindi" to "hi", "italian" to "it", "japanese" to "ja",
    "korean" to "ko", "norwegian" to "nb", "persian" to "fa", "polish" to "pl",
    "portuguese" to "pt", "russian" to "ru", "spanish" to "es", "swedish" to "sv",
    "turkish" to "tr", "ukrainian" to "uk", "urdu" to "ur",
)

enum class Direction { MIXED, TERM_TO_NATIVE, NATIVE_TO_TERM }

/** Which language is learned (column A) and which glosses it (column B). */
data class LanguagePair(val term: String, val native: String) {
    fun frontLabel(direction: Direction): String =
        if (direction == Direction.NATIVE_TO_TERM) "$native → $term" else "$term → $native"
}

data class Card(
    val topic: String,
    val term: String,                // column A
    val meaning: String,             // column B
    val wordType: String? = null,
    val articleAux: String? = null,
    val forms: String? = null,
    val pronunciation: String? = null,
    val exampleTerm: String? = null,
    val exampleMeaning: String? = null,
    val synonyms: String? = null,
    val notes: String? = null,
    val dateAdded: LocalDate? = null,
    val confidence: Double? = null,  // half-steps; grades are averaged in
    val languages: LanguagePair,
    val sheet: String,               // source sheet (== topic); write-back never guesses
    val row: Int,                    // 1-based Excel row
    val articleLabel: String = "Article / Auxiliary",
    val formsLabel: String = "Forms",
    val schedule: Schedule? = null,  // FSRS state from M..R; null = never scheduled
) {
    /** Stable key: sheet!row is unique and survives re-filtering. */
    val id: String get() = "$sheet!$row"
}

/** Columns A and B filled (the language names) and L mentions confidence. Not sufficient alone. */
fun isTopicHeader(header: List<Any?>): Boolean {
    if (header.size < CONFIDENCE_COL_INDEX) return false
    val term = clean(header[0])
    val native = clean(header[1])
    val confidence = clean(header[CONFIDENCE_COL_INDEX - 1])
    return term != null && native != null && "confidence" in (confidence ?: "").lowercase()
}

fun isTopicSheet(name: String, header: List<Any?>): Boolean =
    !NON_TOPIC_NAME.containsMatchIn(name) && isTopicHeader(header)

/** The pair from A1/B1, verbatim: a decorated header shows up as a wrong label, not a silent mis-split. */
fun languagePairFromHeader(header: List<Any?>): LanguagePair =
    LanguagePair(term = clean(header.getOrNull(0)) ?: "Term", native = clean(header.getOrNull(1)) ?: "Meaning")

fun isRtl(language: String?): Boolean = (language ?: "").trim().lowercase() in RTL_LANGUAGES

fun languageCode(language: String?): String? = LANGUAGE_CODES[(language ?: "").trim().lowercase()]

/**
 * Clamp to [1, 5] and round to the half-step grid. Ties go UP (floor(x + 0.5)),
 * not to even: banker's rounding sends 3.25 down but 3.75 up.
 */
fun snapConfidence(value: Double): Double {
    val stepped = floor(value / CONFIDENCE_STEP + 0.5) * CONFIDENCE_STEP
    return stepped.coerceIn(CONFIDENCE_MIN.toDouble(), CONFIDENCE_MAX.toDouble())
}

/** A raw cell as a confidence on the grid, or null. Out-of-range values are clamped, not dropped. */
fun coerceConfidence(value: Any?): Double? {
    val n = when (value) {
        null -> return null
        is Number -> value.toDouble()
        is NumberCell -> value.value
        is String -> value.trim().ifEmpty { return null }.toDoubleOrNull() ?: return null
        else -> return null
    }
    return snapConfidence(n)
}

/** New confidence after a grade: the mean of the old score and the grade; an ungraded word takes the grade. */
fun blendConfidence(old: Double?, grade: Int): Double {
    require(grade in CONFIDENCE_MIN..CONFIDENCE_MAX) { "grade $grade out of range 1..5" }
    if (old == null) return grade.toDouble()
    return snapConfidence((old + grade) / 2)
}

/** The cell text: "3" for 3.0, "3.5" for 3.5 (never "3.0"). */
fun confidenceToCell(value: Double): String =
    if (value == floor(value)) value.toLong().toString() else value.toString()

fun formatConfidence(value: Double?): String = if (value == null) "ungraded" else confidenceToCell(value)

/** A Card from one data row, or null when column A is empty. */
fun parseRow(topic: String, headers: List<Any?>, cells: List<Any?>, excelRow: Int, languages: LanguagePair): Card? {
    fun cell(i: Int) = cells.getOrNull(i)
    val term = clean(cell(0)) ?: return null
    return Card(
        topic = topic,
        term = term,
        meaning = clean(cell(1)) ?: "",
        wordType = clean(cell(2)),
        articleAux = clean(cell(3)),
        forms = clean(cell(4)),
        pronunciation = clean(cell(5)),
        exampleTerm = clean(cell(6)),
        exampleMeaning = clean(cell(7)),
        synonyms = clean(cell(8)),
        notes = clean(cell(9)),
        dateAdded = asDate(cell(10)),
        confidence = coerceConfidence(cell(11)),
        languages = languages,
        sheet = topic,
        row = excelRow,
        articleLabel = clean(headers.getOrNull(3)) ?: "Article / Auxiliary",
        formsLabel = clean(headers.getOrNull(4)) ?: "Forms",
    )
}

/** Trimmed text, or null for blank; numbers as Excel shows them ("3", not "3.0"). */
fun clean(value: Any?): String? = when (value) {
    null -> null
    is String -> value.trim().ifEmpty { null }
    is NumberCell -> value.text
    is Boolean -> if (value) "True" else "False"
    else -> value.toString()
}

/** Column K: an Excel date serial (or an ISO date cell). Text dates are not guessed at. */
private fun asDate(value: Any?): LocalDate? = when (value) {
    is NumberCell -> excelSerialToDate(value.value)
    is LocalDate -> value
    else -> null
}

/** Excel's day 0 is 1899-12-30 (the 1900 leap-year bug folded in). */
fun excelSerialToDate(serial: Double): LocalDate? =
    if (serial < 1 || serial > 2958465) null else LocalDate.of(1899, 12, 30).plusDays(floor(serial).toLong())
