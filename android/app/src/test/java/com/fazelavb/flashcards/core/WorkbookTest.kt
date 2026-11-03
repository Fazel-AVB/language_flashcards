package com.fazelavb.flashcards.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

class WorkbookTest {
    @Test fun readsTopicSheetsOnly() {
        val data = Workbook.load(germanBytes())
        assertEquals(listOf("School & Office", "Verbs", "Adjectives & Adverbs"), data.topics)  // no Overview, no Card Pool
        assertEquals(LanguagePair("German", "English"), data.languages)
        assertEquals(4, data.cards.size)
        val stift = data.cards.byTerm("Stift")
        assertEquals("School & Office!2", stift.id)
        assertEquals("pen", stift.meaning)
        assertEquals("die Stifte", stift.forms)
        assertEquals(LocalDate.of(2026, 9, 3), stift.dateAdded)
        assertEquals(1.0, stift.confidence!!, 0.0)
        assertEquals("Article", stift.articleLabel)
        assertEquals("Plural / Verb forms", data.cards.byTerm("werden").formsLabel)
        assertNull(data.cards.byTerm("werden").confidence)
        assertEquals(5.0, data.cards.byTerm("riesig").confidence!!, 0.0)  // 7 clamped
        assertEquals(data.topics, data.sheetsMissingSrsHeaders)
    }

    @Test fun persianWorkbookWithScheduleAndGaps() {
        val data = Workbook.load(persianBytes())
        assertEquals(LanguagePair("English", "Persian"), data.languages)
        assertEquals("جذابیت", data.cards.byTerm("charm").meaning)
        val anew = data.cards.byTerm("anew")
        assertEquals(5, anew.row)  // the blank row 4 keeps the numbering
        assertEquals(2.5, anew.confidence!!, 0.0)
        val s = anew.schedule!!
        assertEquals("Review", s.state)
        assertEquals(java.time.Instant.parse("2026-10-15T09:20:00Z"), s.due)
        assertEquals(4.2, s.stability!!, 1e-12)
        assertNull(s.step)
        assertNull(data.cards.byTerm("broken").schedule)
        assertEquals(1, data.scheduleProblems.size)
        assertTrue(data.scheduleProblems[0].startsWith("Emotions & Mind!M6"))
    }

    @Test fun rejectsNonWorkbooks() {
        try {
            Workbook.load("hello".toByteArray())
            fail()
        } catch (e: WorkbookException) {
            assertTrue(e.message!!.contains("Excel"))
        }
    }

    @Test fun xmlTokenizer() {
        val p = XmlPull("""<?xml version="1.0"?><a x="1 &amp; 2"><b/>t&lt;x<![CDATA[<raw>]]></a>""")
        val seen = mutableListOf<String>()
        while (true) {
            when (p.next()) {
                XmlPull.START -> seen.add("<${p.name} ${p.attrs}")
                XmlPull.END -> seen.add("</${p.name}")
                XmlPull.TEXT -> seen.add(p.text)
                XmlPull.EOF -> break
            }
        }
        assertEquals(listOf("<a {x=1 & 2}", "<b {}", "</b", "t<x", "<raw>", "</a"), seen)
    }

    @Test fun excelStyleSharedStrings() {
        // As Excel writes them: rich-text runs, a phonetic hint to skip, an escaped control character.
        val shared = XlsxReader.sharedStrings(
            """<sst xmlns="x" count="3"><si><t>Haus</t></si><si><r><rPr><b/></rPr><t>der </t></r><r><t xml:space="preserve">Stift</t></r></si>""" +
                """<si><t>漢字</t><rPh sb="0" eb="2"><t>かんじ</t></rPh></si><si><t>a_x000D_b</t></si></sst>""")
        assertEquals(listOf("Haus", "der Stift", "漢字", "a\rb"), shared)
        val rows = XlsxReader.sheetRows(
            """<worksheet><dimension ref="A1:L3"/><sheetData><row r="2" spans="1:12"><c r="A2" t="s"><v>1</v></c>""" +
                """<c r="B2" s="3"><v>3.5</v></c><c r="C2" t="b"><v>1</v></c><c r="D2" t="str"><f>A1</f><v>x</v></c></row>""" +
                """<row r="3"><c r="L3"><v>4</v></c></row></sheetData></worksheet>""", shared)
        assertEquals("der Stift", rows.getValue(2)[0])
        assertEquals(NumberCell("3.5", 3.5), rows.getValue(2)[1])
        assertEquals(true, rows.getValue(2)[2])
        assertEquals("x", rows.getValue(2)[3])
        assertEquals("4", clean(rows.getValue(3)[11]))
    }
}
