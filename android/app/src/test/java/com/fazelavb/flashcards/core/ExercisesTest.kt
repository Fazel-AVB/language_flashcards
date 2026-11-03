package com.fazelavb.flashcards.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Ported from the desktop app's tests/test_exercises.py. */
class ExercisesTest {
    private val cards = germanCards()
    private val base = cards[0]

    @Test fun blankFindsTheWord() {
        val cases = listOf(
            Triple("Stift", "Ich schreibe mit einem Stift.", "Stift"),
            Triple("riesig", "ein riesiges Haus", "riesiges"),
            Triple("schreiben", "Ich schreibe einen Bericht.", "schreibe"),
            Triple("sich freuen (auf)", "Ich will mich freuen.", "freuen"),
            Triple("lash out at someone", "Stress made him lash out at his team.", "lash out at"),
            Triple("to get whiplash", "You will get whiplash from this news.", "get whiplash"),
            Triple("the jealousy that gnaws you", "Don't let the jealousy that gnaws you destroy your peace.", "the jealousy that gnaws you"),
        )
        for ((term, sentence, answer) in cases) {
            val b = Exercises.makeBlank(base.copy(term = term, exampleTerm = sentence))
            assertNotNull(term, b)
            assertEquals(term, answer, b!!.answer)
            assertEquals(sentence, b.before + b.answer + b.after)
        }
    }

    @Test fun blankNotPossible() {
        assertNull(Exercises.makeBlank(base.copy(term = "gehen", exampleTerm = "Ich ging nach Hause.")))
        assertNull(Exercises.makeBlank(base.copy(term = "anew", exampleTerm = "anew")))
        assertNull(Exercises.makeBlank(base.copy(term = "Stift", exampleTerm = null)))
    }

    @Test fun typedAnswers() {
        val b = Blank("Die Milch ist im ", ".", "Kühlschrank", emptyList())
        assertEquals(Verdict.CORRECT, Exercises.checkTyped("kühlschrank", b))
        assertEquals(Verdict.CORRECT, Exercises.checkTyped(" Kuehlschrank. ", b))
        assertEquals(Verdict.CLOSE, Exercises.checkTyped("Kuhlschrank", b))
        assertEquals(Verdict.CLOSE, Exercises.checkTyped("Kühlschank", b))
        assertEquals(Verdict.WRONG, Exercises.checkTyped("Schrank", b))
        assertEquals(Verdict.WRONG, Exercises.checkTyped("", b))
        val infl = Blank("ein ", " Haus", "riesiges", listOf("riesig"))
        assertEquals(Verdict.CORRECT, Exercises.checkTyped("riesig", infl))
        assertEquals(Verdict.CORRECT, Exercises.checkTyped("يك", Blank("", " است", "یک", emptyList())))
    }

    @Test fun choiceOptions() {
        val stift = cards.byTerm("Stift")
        val c = Exercises.makeChoice(stift, cards, Direction.TERM_TO_NATIVE, Random(1))!!
        assertEquals("pen", c.options[c.answerIndex])
        assertEquals(4, c.options.toSet().size)
        val reverse = Exercises.makeChoice(stift, cards, Direction.NATIVE_TO_TERM, Random(1))!!
        assertEquals("Stift", reverse.options[reverse.answerIndex])
        assertNull(Exercises.makeChoice(cards[0], listOf(cards[0]), Direction.TERM_TO_NATIVE, Random(0)))
    }

    @Test fun choicePrefersSameWordType() {
        val nouns = (cards + cards).mapIndexed { i, c -> c.copy(wordType = "Noun", row = 100 + i, term = "N$i", meaning = "n$i") }
        val verbs = (cards + cards).mapIndexed { i, c -> c.copy(wordType = "Verb", row = 200 + i, term = "V$i", meaning = "v$i") }
        val target = cards[0].copy(wordType = "Noun")
        val c = Exercises.makeChoice(target, nouns + verbs, Direction.TERM_TO_NATIVE, Random(3))!!
        assertTrue(c.options.all { it.startsWith("n") || it == target.meaning })
    }

    @Test fun recallAcceptsArticle() {
        val r = Exercises.makeRecall(cards.byTerm("Stift"))!!
        assertEquals(Verdict.CORRECT, Exercises.checkTyped("der Stift", r))
        assertEquals(Verdict.CLOSE, Exercises.checkTyped("Stft", r))
        assertEquals(Verdict.WRONG, Exercises.checkTyped("Kuli", r))
        val lash = Exercises.makeRecall(base.copy(term = "lash out at someone", articleAux = null))!!
        assertEquals(Verdict.CORRECT, Exercises.checkTyped("lash out at", lash))
        assertNull(Exercises.makeRecall(base.copy(meaning = "")))
        assertNotNull(Exercises.makeListen(base.copy(meaning = "")))
    }

    @Test fun articleOf() {
        assertEquals("die", Exercises.articleOf(base.copy(articleAux = " Die ")))
        assertNull(Exercises.articleOf(base.copy(articleAux = "der/das")))
        assertNull(Exercises.articleOf(base.copy(articleAux = "sein")))
    }

    @Test fun scramble() {
        val s = Exercises.makeScramble(base.copy(exampleTerm = "Die Milch ist im Kühlschrank."), Random(0))!!
        assertEquals(listOf("Die", "Milch", "ist", "im", "Kühlschrank"), s.answer)
        assertEquals(".", s.end)
        fun orderFor(words: List<String>): List<Int> {
            val left = s.tiles.indices.toMutableList()
            return words.map { w -> left.first { s.tiles[it] == w }.also { left.remove(it) } }
        }
        assertEquals(Verdict.CORRECT, Exercises.checkScramble(s, orderFor(s.answer)))
        assertEquals(Verdict.CLOSE, Exercises.checkScramble(s, orderFor(listOf("Die", "Milch", "im", "ist", "Kühlschrank"))))
        assertEquals(Verdict.WRONG, Exercises.checkScramble(s, orderFor(s.answer.reversed())))
        for (bad in listOf(null, "Zu kurz hier.", "Wort Wort Wort Wort.", (0..16).joinToString(" ") { "w$it" })) {
            assertNull(bad, Exercises.makeScramble(base.copy(exampleTerm = bad), Random(0)))
        }
    }

    @Test fun formsDrill() {
        val f = Exercises.makeForms(base.copy(wordType = "Noun", forms = "die Stifte"))!!
        assertEquals(Verdict.CORRECT, Exercises.checkForms("Stifte", f))
        assertEquals(Verdict.CORRECT, Exercises.checkForms(" STIFTE ", f))
        assertEquals(Verdict.WRONG, Exercises.checkForms("die Stiften", f))
        val v = Exercises.makeForms(base.copy(wordType = "Noun", forms = "die Väter"))!!
        assertEquals(Verdict.CORRECT, Exercises.checkForms("Vaeter", v))
        assertEquals(Verdict.WRONG, Exercises.checkForms("Vater", v))
        val p = Exercises.makeForms(base.copy(wordType = "Verb (regular, weak)", forms = "redet – redete – hat geredet"))!!
        assertEquals("hat geredet", p.answer)
        assertEquals(Verdict.CORRECT, Exercises.checkForms("er hat geredet", p))
        assertEquals(Verdict.CLOSE, Exercises.checkForms("geredet", p))
        assertEquals(Verdict.WRONG, Exercises.checkForms("ist geredet", p))
        val en = Workbook.load(persianBytes()).cards[0]
        val pp = Exercises.makeForms(en.copy(wordType = "Verb phrase", forms = "lean – leaned/leant – leaned/leant"))!!
        assertEquals("Past participle", pp.label)
        assertEquals(Verdict.CORRECT, Exercises.checkForms("leant", pp))
        for ((type, forms) in listOf("Noun" to "— (no plural)", "Noun" to "nur Plural", "Noun / Verb" to "reprieves; reprieve – reprieved",
                "Adjective / Adverb" to "riesiger – am riesigsten", "Verb" to "geht – ging", "Verb" to "redet – redete – geredet",
                null to "die Stifte", "Noun" to null)) {
            assertNull("$type $forms", Exercises.makeForms(base.copy(wordType = type, forms = forms)))
        }
    }
}
