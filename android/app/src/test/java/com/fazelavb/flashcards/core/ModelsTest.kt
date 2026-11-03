package com.fazelavb.flashcards.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class ModelsTest {
    @Test fun blendingAverages() {
        assertEquals(3.0, blendConfidence(null, 3), 0.0)
        assertEquals(3.0, blendConfidence(5.0, 1), 0.0)
        assertEquals(3.5, blendConfidence(3.0, 4), 0.0)
        // 3.25 snaps UP to 3.5 (not banker's rounding down to 3.0).
        assertEquals(3.5, blendConfidence(2.5, 4), 0.0)
        var c: Double? = 1.0
        val path = listOf(5, 5, 5, 5).map { c = blendConfidence(c, it); c }
        assertEquals(listOf(3.0, 4.0, 4.5, 5.0), path)
    }

    @Test fun coercion() {
        assertEquals(5.0, coerceConfidence(NumberCell.of("7"))!!, 0.0)
        assertEquals(3.0, coerceConfidence("2.8")!!, 0.0)
        assertNull(coerceConfidence(" "))
        assertNull(coerceConfidence("abc"))
        assertEquals("3", confidenceToCell(3.0))
        assertEquals("3.5", confidenceToCell(3.5))
    }

    @Test fun languages() {
        assertEquals("de", languageCode("German"))
        assertNull(languageCode("Klingon"))
        assertEquals(true, isRtl("Persian"))
        assertEquals(false, isRtl("German"))
    }

    @Test fun dueIsTheWholeLocalDay() {
        val tz = ZoneId.of("Europe/Berlin")
        val review = Schedule(java.time.Instant.parse("2026-10-03T19:00:00Z"), "Review", null, 3.0, 5.0, NOW)
        assertEquals(DueStatus.DUE, Srs.status(review, NOW, tz))       // same local day, later hour
        val learning = review.copy(state = "Learning", step = 1)
        assertEquals(DueStatus.LATER, Srs.status(learning, NOW, tz))   // steps use the exact time
        assertEquals(DueStatus.NEW, Srs.status(null, NOW, tz))
    }

    @Test fun describeDue() {
        assertEquals("now", Srs.describeDue(NOW, NOW, UTC))
        assertEquals("in 10 min", Srs.describeDue(NOW.plusSeconds(600), NOW, UTC))
        assertEquals("tomorrow", Srs.describeDue(NOW.plusSeconds(86400), NOW, UTC))
        assertEquals("in 4 days", Srs.describeDue(NOW.plusSeconds(4 * 86400), NOW, UTC))
        assertEquals("in 3 months", Srs.describeDue(NOW.plusSeconds(91L * 86400), NOW, UTC))
    }

    @Test fun scheduleCellsRoundTrip() {
        val s = Srs.review(null, 3, NOW, unfuzzed())
        val cells = Srs.scheduleToCells(s).values.map {
            when (it) { is CellWrite.Text -> it.value; is CellWrite.Number -> NumberCell.of(it.value.toString()); CellWrite.Clear -> null }
        }
        val back = Srs.scheduleFromCells(cells)!!
        assertEquals(s.due, back.due)
        assertEquals(s.state, back.state)
        assertEquals(s.step, back.step)
    }
}
