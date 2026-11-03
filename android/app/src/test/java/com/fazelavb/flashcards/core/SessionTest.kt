package com.fazelavb.flashcards.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class SessionTest {
    private val cards = germanCards()

    private fun session(deck: List<Card>, kind: ExerciseChoice = ExerciseChoice.FLASHCARD, repeat: Boolean = false, canListen: Boolean = false) =
        ReviewSession.start(deck, cards, Direction.TERM_TO_NATIVE, repeat, 1, kind, canListen, "h", unfuzzed(), UTC)

    @Test fun dueModeReservesNewWordSlots() {
        val due = (1..10).map { i ->
            cards[0].copy(row = 100 + i, term = "d$i", schedule = Schedule(NOW.minusSeconds(3600), "Review", null, 2.0, 5.0, NOW.minusSeconds(86400 * 3)))
        }
        val new = (1..10).map { i -> cards[0].copy(row = 200 + i, term = "n$i", schedule = null, confidence = null) }
        val later = cards[0].copy(row = 300, schedule = Schedule(NOW.plusSeconds(86400 * 5), "Review", null, 9.0, 5.0, NOW))
        val deck = Decks.build(due + new + later, Mode.DUE, limit = 8, now = NOW, zone = UTC, newPerSession = 3)
        assertEquals(5, deck.count { it.term.startsWith("d") })
        assertEquals(3, deck.count { it.term.startsWith("n") })
        val fewNew = Decks.build(due + new.take(1), Mode.DUE, limit = 8, now = NOW, zone = UTC, newPerSession = 3)
        assertEquals(7, fewNew.count { it.term.startsWith("d") })  // unused new slots go back to due words
        assertTrue(Decks.build(listOf(later), Mode.DUE, now = NOW, zone = UTC).isEmpty())
    }

    @Test fun weightedDrawFavoursWeakWords() {
        val weak = (1..50).map { cards[0].copy(row = 100 + it, confidence = 1.0) }
        val strong = (1..50).map { cards[0].copy(row = 200 + it, confidence = 5.0) }
        var weakDrawn = 0
        for (seed in 0L until 100L) weakDrawn += Decks.build(weak + strong, Mode.MIX, seed = seed, limit = 10, now = NOW, zone = UTC).count { it.confidence == 1.0 }
        assertTrue("weak words drawn: $weakDrawn of 1000", weakDrawn > 900)
    }

    @Test fun difficultAndTopicFilters() {
        assertEquals(listOf("Stift"), Decks.build(cards, Mode.DIFFICULT, difficultThreshold = 2, now = NOW, zone = UTC).map { it.term })
        assertEquals(setOf("werden"), Decks.build(cards, Mode.TOPIC, topics = listOf("Verbs"), now = NOW, zone = UTC).map { it.term }.toSet())
    }

    @Test fun gradingAveragesAndSchedules() {
        val stift = cards.byTerm("Stift")
        val s = session(listOf(stift))
        assertEquals(Kind.FLASHCARD, s.currentExercise().kind)
        val (conf, sched) = s.preview(stift.id, 4, NOW)
        assertEquals(2.5, conf, 0.0)
        assertNotNull(sched)
        s.grade(4, NOW)
        assertTrue(s.finished)
        val updates = s.cellUpdates().getValue(RowKey(stift.sheet, stift.row))
        assertEquals(CellWrite.Number(2.5), updates["L"])
        assertEquals(CellWrite.Text("Learning"), updates["N"])
    }

    @Test fun repeatsDoNotCountTwiceForFsrs() {
        val stift = cards.byTerm("Stift")
        val s = session(listOf(stift), repeat = true)
        s.grade(5, NOW)                          // Easy: straight to Review, days away
        assertFalse(s.finished)                  // confidence 3 < 5: comes round again
        assertNull(s.preview(stift.id, 5, NOW.plusSeconds(60)).second)
        val dueAfterFirst = s.effectiveSchedule(stift.id)!!.due
        s.grade(5, NOW.plusSeconds(60))
        s.grade(5, NOW.plusSeconds(120))
        s.grade(5, NOW.plusSeconds(180))
        assertTrue(s.finished)                   // 3 -> 4 -> 4.5 -> 5
        assertEquals(dueAfterFirst, s.effectiveSchedule(stift.id)!!.due)
        assertEquals(listOf(true, false, false, false), s.state.log.map { it.scheduled })
    }

    @Test fun choiceThenGrade() {
        val stift = cards.byTerm("Stift")
        val s = session(listOf(stift), ExerciseChoice.CHOICE)
        val ex = s.currentExercise()
        assertEquals(Kind.CHOICE, ex.kind)
        val r = s.check(Answer.Picked(ex.choice!!.answerIndex))
        assertEquals(Verdict.CORRECT, r.verdict)
        assertEquals(4, r.suggested)
        assertTrue(s.check(Answer.Picked(0)) === r)   // once per turn
        try { session(listOf(stift), ExerciseChoice.CHOICE).check(Answer.Picked(9)); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun fallbacks() {
        val werden = cards.byTerm("werden")
        val stift = cards.byTerm("Stift").copy(exampleTerm = "Ganz anderer Satz.")
        val s = session(listOf(werden, stift), ExerciseChoice.BLANK)
        assertEquals(Kind.BLANK, s.currentExercise().kind)
        assertEquals(Verdict.CORRECT, s.check(Answer.Typed("werden")).verdict)
        s.grade(4, NOW)
        assertEquals(Kind.CHOICE, s.currentExercise().kind)
        assertEquals(Kind.RECALL, session(listOf(werden), ExerciseChoice.LISTEN, canListen = false).currentExercise().kind)
        assertEquals(Kind.LISTEN, session(listOf(werden), ExerciseChoice.LISTEN, canListen = true).currentExercise().kind)
        val a = session(listOf(cards.byTerm("Stift")), ExerciseChoice.ARTICLE)
        val r = a.check(Answer.Picked(2))
        assertEquals("der", r.expected)
        assertEquals(2, r.suggested)
    }

    @Test fun mixedVariesByTurn() {
        val s = ReviewSession.start(cards + cards.map { it.copy(row = it.row + 50) }, cards, Direction.MIXED, false, 5,
            ExerciseChoice.MIXED, true, "h", unfuzzed(), UTC)
        val kinds = mutableSetOf<Kind>()
        while (!s.finished) { kinds.add(s.currentExercise().kind); s.skip() }
        assertTrue(kinds.toString(), kinds.size >= 2)
    }

    @Test fun stateSurvivesSerialization() {
        val stift = cards.byTerm("Stift")
        val werden = cards.byTerm("werden")
        val s = session(listOf(stift, werden), ExerciseChoice.CHOICE, repeat = true)
        val firstKind = s.currentExercise()
        s.check(Answer.Picked(firstKind.choice!!.answerIndex))
        s.grade(4, NOW)
        val json = Json.encodeToString(SessionState.serializer(), s.state)
        val restored = ReviewSession(listOf(stift, werden), cards, Json.decodeFromString(SessionState.serializer(), json), unfuzzed(), UTC)
        assertEquals(s.state.current, restored.state.current)
        assertEquals(s.currentExercise(), restored.currentExercise())  // the same exercise, deterministically
        assertEquals(s.cellUpdates(), restored.cellUpdates())
    }

    @Test fun engineViews() {
        val data = Workbook.load(germanBytes())
        val engine = Engine(now = { NOW }, zone = UTC, fuzz = false)
        val o = engine.overview(data, 2)
        assertEquals(4, o.new)
        assertEquals(2, o.articleWords)
        assertEquals(3, o.formsWords)  // two plurals and werden's "ist geworden"
        val scope = engine.scope(data, DeckOptions(size = 2, newPerSession = 1))
        assertEquals(4, scope.inScope)
        assertEquals(2, scope.drawn)
        val s = engine.start(data, DeckOptions(exercise = ExerciseChoice.BLANK, mode = Mode.TOPIC, topics = listOf("Verbs"), direction = Direction.TERM_TO_NATIVE), emptySet(), 3)
        val v = engine.cardView(s)
        assertEquals(Kind.BLANK, v.exercise.kind)
        assertEquals("", v.front.text)  // the sentence is the prompt
        assertEquals(5, v.grades.size)
        s.check(Answer.Typed("werden"))
        assertEquals("werden", engine.cardView(s).front.text)
        s.grade(4, NOW)
        val updates = engine.updatesToSave(s, data)
        assertEquals(SRS_HEADERS.keys, updates.getValue(RowKey("Verbs", 1)).keys)
        assertEquals(1, engine.summary(s, data.languages).rows.size)
        try {
            engine.start(data, DeckOptions(exercise = ExerciseChoice.LISTEN), emptySet(), 1); fail()
        } catch (_: UserException) {}
        assertNull(engine.resume(data.copy(hash = "other"), s.state))
    }

    @Test fun reviewLogCsv() {
        assertEquals("\"a,b\"", ReviewLog.csvField("a,b"))
        assertEquals("plain", ReviewLog.csvField("plain"))
        val s = session(listOf(cards.byTerm("Stift")))
        s.grade(1, Instant.parse("2026-10-03T09:00:00Z"))
        val line = ReviewLog.lines("vocab.xlsx", s).single()
        assertTrue(line, line.startsWith("2026-10-03T09:00:00Z,vocab.xlsx,School & Office,2,Stift,1,Again,1,1,1,"))
    }
}
