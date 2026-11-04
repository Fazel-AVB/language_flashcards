package com.fazelavb.flashcards.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ProgressTest {
    private fun sched(state: String, stability: Double) = Schedule(NOW, state, if (state == "Review") null else 0, stability, 5.0, NOW.minusSeconds(86400))

    @Test fun stages() {
        assertEquals(MemoryStage.NEW, memoryStage(null))
        assertEquals(MemoryStage.LEARNING, memoryStage(sched("Learning", 2.0)))
        assertEquals(MemoryStage.LEARNING, memoryStage(sched("Relearning", 40.0)))
        assertEquals(MemoryStage.SHORT_TERM, memoryStage(sched("Review", 20.9)))
        assertEquals(MemoryStage.LONG_TERM, memoryStage(sched("Review", 21.0)))
    }

    @Test fun breakdownCountsEveryWord() {
        val data = Workbook.load(persianBytes())
        val b = Progress.breakdown(data.cards, data.topics, NOW)
        assertEquals(data.cards.size, b.total)
        assertEquals(1, b.counts[MemoryStage.SHORT_TERM])  // "anew": Review, stability 4.2
        assertTrue(b.recallNow!! in 0.0..1.0)
        assertEquals(data.topics, b.byTopic.map { it.first })
        assertNull(Progress.breakdown(germanCards(), emptyList(), NOW).recallNow)  // nothing studied yet
    }

    @Test fun activeTimeCapsIdleGaps() {
        val t0 = 1_000_000L
        // 30 s, then a 10-minute pause (counted as 2 min), then 20 s.
        assertEquals(30 + 120 + 20L, Progress.activeSeconds(t0, listOf(t0 + 30_000, t0 + 630_000, t0 + 650_000)))
    }

    @Test fun recordOfASession() {
        val stift = germanCards().byTerm("Stift")
        val s = ReviewSession.start(listOf(stift), germanCards(), Direction.TERM_TO_NATIVE, true, 1,
            ExerciseChoice.FLASHCARD, false, "h", unfuzzed(), UTC, startedAt = NOW)
        assertNull(Progress.record(s, NOW, saved = true))  // nothing graded
        s.grade(1, NOW.plusSeconds(20))
        s.grade(4, NOW.plusSeconds(50))
        val r = Progress.record(s, NOW.plusSeconds(60), saved = true)!!
        assertEquals(1, r.words)
        assertEquals(2, r.grades)
        assertEquals(2.5, r.avgGrade, 0.0)
        assertEquals(0.5, r.recalledShare, 0.0)
        assertEquals(50L, r.activeSeconds)
    }

    @Test fun streakAndDailyMinutes() {
        val today = LocalDate.of(2026, 11, 4)
        val days = setOf(today.minusDays(1), today.minusDays(2), today.minusDays(4))
        assertEquals(2, Progress.streak(days, today))            // yesterday and the day before
        assertEquals(3, Progress.streak(days + today, today))
        assertEquals(0, Progress.streak(setOf(today.minusDays(3)), today))
        val m = Progress.dailyMinutes(mapOf(today.toString() to 600L), today, 3)
        assertEquals(listOf(0.0, 0.0, 10.0), m.map { it.second })
        assertEquals(today, m.last().first)
    }
}
