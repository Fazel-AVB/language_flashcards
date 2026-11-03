package com.fazelavb.flashcards.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class WritebackTest {
    @Test fun writesOnlyTheGivenCells() {
        val original = germanBytes()
        val data = Workbook.load(original)
        val stift = data.cards.byTerm("Stift")
        val werden = data.cards.byTerm("werden")
        val schedule = Srs.review(null, 3, NOW, unfuzzed())
        val updates = mapOf(
            RowKey(stift.sheet, stift.row) to mapOf("L" to CellWrite.Number(2.5)) + Srs.scheduleToCells(schedule),
            RowKey(werden.sheet, werden.row) to mapOf("L" to CellWrite.Number(4.0)),
            RowKey(stift.sheet, 1) to SRS_HEADERS.mapValues { CellWrite.Text(it.value) },
        )
        val result = Writeback.apply(original, updates)
        assertEquals(2, result.sheetsTouched.size)
        File(System.getProperty("java.io.tmpdir"), "fc_writeback_out.xlsx").writeBytes(result.bytes)

        val after = Workbook.load(result.bytes)
        assertEquals(2.5, after.cards.byTerm("Stift").confidence!!, 0.0)
        assertEquals(4.0, after.cards.byTerm("werden").confidence!!, 0.0)
        assertEquals(schedule.due, after.cards.byTerm("Stift").schedule!!.due)
        assertEquals(schedule.state, after.cards.byTerm("Stift").schedule!!.state)
        assertEquals(3.0, after.cards.byTerm("Bericht").confidence!!, 0.0)  // untouched
        assertEquals(listOf("Verbs", "Adjectives & Adverbs"), after.sheetsMissingSrsHeaders)

        val before = XlsxReader.unzip(original)
        val now = XlsxReader.unzip(result.bytes)
        assertEquals(before.keys.toList(), now.keys.toList())
        val changed = before.keys.filter { !before.getValue(it).contentEquals(now.getValue(it)) }.toSet()
        val paths = XlsxReader.sheetPaths(before)
        // workbook.xml only changes when it lacks fullCalcOnLoad (openpyxl already writes it).
        assertEquals(setOf(paths.getValue("School & Office"), paths.getValue("Verbs")), changed - "xl/workbook.xml")
        assertTrue(now.getValue("xl/workbook.xml").decodeToString().contains("fullCalcOnLoad=\"1\""))
        assertTrue(now.getValue(paths.getValue("Overview")).decodeToString().contains("COUNTA"))
    }

    @Test fun refusesOffGridConfidence() {
        val stift = germanCards().byTerm("Stift")
        for (bad in listOf(2.8, 0.0, 6.0)) {
            try {
                Writeback.apply(germanBytes(), mapOf(RowKey(stift.sheet, stift.row) to mapOf("L" to CellWrite.Number(bad))))
                fail("accepted $bad")
            } catch (_: WritebackException) {}
        }
    }

    @Test fun refusesFormulaCells() {
        try {
            Writeback.apply(germanBytes(), mapOf(RowKey("Overview", 2) to mapOf("A" to CellWrite.Text("x"))))
            fail()
        } catch (e: WritebackException) {
            assertTrue(e.message!!.contains("formula"))
        }
    }

    @Test fun emptyUpdateIsANoOp() {
        val b = germanBytes()
        assertTrue(Writeback.apply(b, emptyMap()).bytes === b)
    }

    @Test fun fullCalcOnLoad() {
        assertEquals("""<calcPr calcId="1" fullCalcOnLoad="1"/>""", Writeback.setFullCalcOnLoad("""<calcPr calcId="1"/>"""))
        assertEquals("""<x></sheets><calcPr calcId="0" fullCalcOnLoad="1"/></x>""", Writeback.setFullCalcOnLoad("<x></sheets></x>"))
    }
}
