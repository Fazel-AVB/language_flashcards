package com.fazelavb.flashcards.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.roundToInt

/*
 * Spaced repetition: when each word is due again. A port of flashcards/srs.py.
 *
 * Two scores, on purpose:
 *   Confidence (column L) -- your averaged 1-5 self-rating (Models.kt).
 *   Schedule  (M..R)      -- FSRS state; decides WHEN a word comes back.
 * Both are updated by every grade. The schedule lives in the word's own row,
 * so it moves with the row when you sort in Excel and is in every backup.
 */

val SRS_COLUMNS = listOf("M", "N", "O", "P", "Q", "R")
val SRS_HEADERS = linkedMapOf(
    "M" to "Due (FSRS)",
    "N" to "State (FSRS)",
    "O" to "Step (FSRS)",
    "P" to "Stability (FSRS)",
    "Q" to "Difficulty (FSRS)",
    "R" to "Last review (FSRS)",
)
const val SRS_FIRST_COL_INDEX = 13  // M
const val SRS_LAST_COL_INDEX = 18   // R

/**
 * The app's five buttons onto FSRS's four ratings: 3 "getting there" and 4
 * "solid" are both a successful recall (Good); only 1 means not recalled.
 */
fun gradeToRating(grade: Int): Rating = when (grade) {
    1 -> Rating.Again
    2 -> Rating.Hard
    3, 4 -> Rating.Good
    5 -> Rating.Easy
    else -> throw IllegalArgumentException("grade $grade out of range 1..5")
}

/** One word's FSRS state, as stored in columns M..R. */
data class Schedule(
    val due: Instant,
    val state: String,         // "Learning" | "Review" | "Relearning"
    val step: Int?,
    val stability: Double?,
    val difficulty: Double?,
    val lastReview: Instant?,
)

enum class DueStatus { NEW, DUE, LATER }

object Srs {
    /** FSRS's published defaults (retention 0.9, learning steps 1 and 10 min). */
    fun makeScheduler(fuzz: Boolean = true) = FsrsScheduler(enableFuzzing = fuzz)

    /** The schedule after grading 1-5 at [now]; null [schedule] is a new word. */
    fun review(schedule: Schedule?, grade: Int, now: Instant, scheduler: FsrsScheduler): Schedule =
        fromFsrs(scheduler.review(toFsrs(schedule), gradeToRating(grade), now))

    /** Estimated chance (0..1) of recalling the word now; null for a new word. */
    fun retrievability(schedule: Schedule?, now: Instant, scheduler: FsrsScheduler): Double? {
        if (schedule?.lastReview == null) return null
        return scheduler.retrievability(toFsrs(schedule), now)
    }

    /**
     * Review-state words are due for the whole LOCAL calendar day of their due
     * time, as in Anki; (re)learning steps are minutes long and use the exact time.
     */
    fun status(schedule: Schedule?, now: Instant, zone: ZoneId): DueStatus {
        if (schedule == null) return DueStatus.NEW
        if (schedule.state != "Review") return if (!schedule.due.isAfter(now)) DueStatus.DUE else DueStatus.LATER
        return if (!localDate(schedule.due, zone).isAfter(localDate(now, zone))) DueStatus.DUE else DueStatus.LATER
    }

    /** "now", "in 10 min", "in 3 h", "tomorrow", "in 4 days", "in 3 months". */
    fun describeDue(due: Instant, now: Instant, zone: ZoneId): String {
        val delta = Duration.between(now, due)
        if (delta <= Duration.ZERO) return "now"
        if (delta < Duration.ofHours(1)) return "in ${maxOf(1, ceil(delta.seconds / 60.0).toInt())} min"
        val days = java.time.temporal.ChronoUnit.DAYS.between(localDate(now, zone), localDate(due, zone))
        return when {
            days <= 0 -> "in ${ceil(delta.seconds / 3600.0).toInt()} h"
            days == 1L -> "tomorrow"
            days < 60 -> "in $days days"
            days < 730 -> "in ${(days / 30.4).roundToInt()} months"
            else -> "in ${"%.1f".format(java.util.Locale.ROOT, days / 365.25)} years"
        }
    }

    /**
     * The six cells M..R. All blank: null (a new word). A partly filled or
     * unreadable schedule throws, so a broken hand edit is reported rather than
     * silently restarting the word.
     */
    fun scheduleFromCells(cells: List<Any?>): Schedule? {
        val v = (0 until 6).map { blankToNull(cells.getOrNull(it)) }
        if (v.all { it == null }) return null
        val (due, state) = v[0] to v[1]
        if (due == null || state == null) throw IllegalArgumentException("due date and state are both required")
        val stateName = FsrsState.entries.firstOrNull { it.name.equals(clean(state), ignoreCase = true) }
            ?: throw IllegalArgumentException("unknown state '${clean(state)}' (expected Learning, Review or Relearning)")
        return Schedule(
            due = parseTime(due),
            state = stateName.name,
            step = v[2]?.let { number(it, "step").toInt() },
            stability = v[3]?.let { number(it, "stability") },
            difficulty = v[4]?.let { number(it, "difficulty") },
            lastReview = v[5]?.let { parseTime(it) },
        )
    }

    /** Column -> value for Writeback: text for times and the state, numbers for the rest, null clears. */
    fun scheduleToCells(s: Schedule): Map<String, CellWrite> = linkedMapOf(
        "M" to CellWrite.Text(formatTime(s.due)),
        "N" to CellWrite.Text(s.state),
        "O" to (s.step?.let { CellWrite.Number(it.toDouble()) } ?: CellWrite.Clear),
        "P" to (s.stability?.let { CellWrite.Number(round4(it)) } ?: CellWrite.Clear),
        "Q" to (s.difficulty?.let { CellWrite.Number(round4(it)) } ?: CellWrite.Clear),
        "R" to (s.lastReview?.let { CellWrite.Text(formatTime(it)) } ?: CellWrite.Clear),
    )

    private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    /** UTC as text, "2026-10-05T09:20:00Z": the Z keeps Excel from turning it into a local date. */
    fun formatTime(t: Instant): String = TIME_FORMAT.format(t)

    fun parseTime(value: Any): Instant {
        if (value is NumberCell) return excelSerialToInstant(value.value)
            ?: throw IllegalArgumentException("unreadable date ${value.text}")
        if (value is LocalDate) return value.atStartOfDay(ZoneOffset.UTC).toInstant()
        val text = value.toString().trim()
        runCatching { return OffsetDateTime.parse(text.replace(' ', 'T')).toInstant() }
        // Naive times are UTC: that is what formatTime writes.
        runCatching { return LocalDateTime.parse(text.replace(' ', 'T')).toInstant(ZoneOffset.UTC) }
        runCatching { return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant() }
        throw IllegalArgumentException("unreadable date '$text'")
    }

    fun toFsrs(schedule: Schedule?): FsrsCard {
        if (schedule == null) return FsrsCard()
        return FsrsCard(
            state = FsrsState.valueOf(schedule.state),
            step = schedule.step,
            stability = schedule.stability,
            difficulty = schedule.difficulty,
            due = schedule.due,
            lastReview = schedule.lastReview,
        )
    }

    private fun fromFsrs(c: FsrsCard) = Schedule(c.due, c.state.name, c.step, c.stability, c.difficulty, c.lastReview)

    private fun localDate(t: Instant, zone: ZoneId): LocalDate = t.atZone(zone).toLocalDate()

    private fun blankToNull(v: Any?): Any? = if (v is String && v.isBlank()) null else v

    private fun number(v: Any, what: String): Double = when (v) {
        is NumberCell -> v.value
        is Number -> v.toDouble()
        else -> v.toString().trim().toDoubleOrNull() ?: throw IllegalArgumentException("unreadable $what '$v'")
    }

    // 4 decimals: FSRS's 16 digits are noise in a cell; 1e-4 days is under ten seconds.
    private fun round4(x: Double) = Math.round(x * 10_000) / 10_000.0
}

private fun excelSerialToInstant(serial: Double): Instant? {
    if (serial < 1 || serial > 2958465) return null
    val millis = Math.round(serial * 86_400_000.0)
    return LocalDate.of(1899, 12, 30).atStartOfDay(ZoneOffset.UTC).toInstant().plusMillis(millis)
}
