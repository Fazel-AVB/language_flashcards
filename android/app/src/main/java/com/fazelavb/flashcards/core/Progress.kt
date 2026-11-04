package com.fazelavb.flashcards.core

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * The numbers behind the Progress screen. Everything is computed on the phone
 * from two local sources: the workbook (each word's FSRS schedule says how
 * firmly it is learned) and the app's own history of finished sessions.
 * Nothing is sent anywhere; the app has no internet permission at all.
 */

/**
 * How firmly a word is held, from its FSRS schedule. "Long-term" uses Anki's
 * line for a mature card, 21 days, applied to FSRS stability: the days until
 * your chance of recalling the word drops to 90%.
 */
enum class MemoryStage(val label: String, val help: String) {
    NEW("New", "never studied"),
    LEARNING("Learning", "in the first minutes-long steps, or relearned after a lapse"),
    SHORT_TERM("Short-term", "remembered for under 3 weeks"),
    LONG_TERM("Long-term", "remembered for 3 weeks or more"),
}

const val LONG_TERM_DAYS = 21.0

fun memoryStage(schedule: Schedule?): MemoryStage = when {
    schedule == null -> MemoryStage.NEW
    schedule.state != "Review" -> MemoryStage.LEARNING
    (schedule.stability ?: 0.0) >= LONG_TERM_DAYS -> MemoryStage.LONG_TERM
    else -> MemoryStage.SHORT_TERM
}

data class MemoryBreakdown(
    val counts: Map<MemoryStage, Int>,
    val byTopic: List<Pair<String, Map<MemoryStage, Int>>>,
    /** Mean estimated chance of recalling a studied word right now (FSRS); null if none studied. */
    val recallNow: Double?,
) {
    val total: Int get() = counts.values.sum()
}

/** One finished session, kept in the app's history (data/ProgressStore). */
@Serializable
data class SessionRecord(
    val startedAt: Long,          // epoch millis
    val endedAt: Long,
    val activeSeconds: Long,      // time spent answering, idle gaps capped
    val words: Int,               // distinct words graded
    val grades: Int,              // grades given, repeats included
    val avgGrade: Double,
    val recalledShare: Double,    // share of grades >= 3 (a successful recall for FSRS)
    val exercise: String,
    val saved: Boolean,           // false: the session was discarded, grades not written
)

data class ProgressSummary(val sessions: Int, val streakDays: Int, val avgSessionMinutes: Double?, val avgDailyMinutes: Double?)

object Progress {
    /** A pause longer than this between two answers counts as this long: the phone was put down. */
    const val IDLE_CAP_SECONDS = 120L

    fun breakdown(cards: List<Card>, topics: List<String>, now: Instant, scheduler: FsrsScheduler = Srs.makeScheduler(fuzz = false)): MemoryBreakdown {
        fun count(group: List<Card>) = MemoryStage.entries.associateWith { st -> group.count { memoryStage(it.schedule) == st } }
        val recall = cards.mapNotNull { Srs.retrievability(it.schedule, now, scheduler) }
        return MemoryBreakdown(
            counts = count(cards),
            byTopic = topics.map { t -> t to count(cards.filter { it.topic == t }) },
            recallNow = if (recall.isEmpty()) null else recall.average(),
        )
    }

    /** The history entry for a session that ends now (saved or discarded); null if nothing was graded. */
    fun record(s: ReviewSession, endedAt: Instant, saved: Boolean): SessionRecord? {
        val log = s.state.log
        if (log.isEmpty()) return null
        val grades = log.map { it.grade }
        val start = s.state.startedAtMillis.takeIf { it > 0 } ?: log.first().reviewedAtMillis
        return SessionRecord(
            startedAt = start,
            endedAt = endedAt.toEpochMilli(),
            activeSeconds = activeSeconds(start, log.map { it.reviewedAtMillis }),
            words = log.map { it.cardId }.distinct().size,
            grades = grades.size,
            avgGrade = grades.average(),
            recalledShare = grades.count { it >= 3 }.toDouble() / grades.size,
            exercise = s.state.exercise.label,
            saved = saved,
        )
    }

    /** Seconds from the start to the last answer, each gap capped at IDLE_CAP_SECONDS. */
    fun activeSeconds(startMillis: Long, answerMillis: List<Long>): Long {
        var last = startMillis
        var total = 0L
        for (t in answerMillis.sorted()) {
            total += ((t - last) / 1000).coerceIn(0, IDLE_CAP_SECONDS)
            last = t
        }
        return total
    }

    /** Days in a row, ending today or yesterday, with at least one session. */
    fun streak(days: Set<LocalDate>, today: LocalDate): Int {
        var d = if (today in days) today else today.minusDays(1)
        var n = 0
        while (d in days) { n++; d = d.minusDays(1) }
        return n
    }

    /** Minutes in the app on each of the last [n] days, oldest first. */
    fun dailyMinutes(usageSeconds: Map<String, Long>, today: LocalDate, n: Int): List<Pair<LocalDate, Double>> =
        (n - 1 downTo 0).map { back ->
            val day = today.minusDays(back.toLong())
            day to (usageSeconds[day.toString()] ?: 0L) / 60.0
        }

    fun summary(history: List<SessionRecord>, usageSeconds: Map<String, Long>, today: LocalDate, zone: ZoneId): ProgressSummary {
        val days = history.map { Instant.ofEpochMilli(it.endedAt).atZone(zone).toLocalDate() }.toSet()
        val usedDays = usageSeconds.values.filter { it > 0 }
        return ProgressSummary(
            sessions = history.size,
            streakDays = streak(days, today),
            avgSessionMinutes = if (history.isEmpty()) null else history.map { it.activeSeconds }.average() / 60,
            avgDailyMinutes = if (usedDays.isEmpty()) null else usedDays.average() / 60,
        )
    }
}
