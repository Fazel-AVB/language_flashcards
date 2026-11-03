package com.fazelavb.flashcards.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.round

/*
 * Surgical write-back of review results into the .xlsx. A port of
 * flashcards/writeback.py; read that module's docstring for the full reasoning.
 *
 * In short: the workbook's dashboards use dynamic-array formulas, rich-value
 * metadata and an Office add-in part that a spreadsheet library would drop on
 * save. So the file is treated as a zip, only the given cells of the affected
 * topic-sheet XML are edited (numbers as <v>, text as inline strings, so
 * sharedStrings.xml is untouched), and every other member is copied as is.
 *
 * This works on bytes: the caller (data/WorkbookRepository) reads the file,
 * checks it has not changed (hash), keeps a backup, and writes the result back.
 */

sealed interface CellWrite {
    data class Number(val value: Double) : CellWrite
    data class Text(val value: String) : CellWrite
    data object Clear : CellWrite
}

/** Which cell: sheet name and 1-based row. */
data class RowKey(val sheet: String, val row: Int)

class WritebackException(message: String) : Exception(message)

data class WriteResult(val bytes: ByteArray, val cellsWritten: Int, val sheetsTouched: List<String>)

object Writeback {
    private const val WORKBOOK_XML = "xl/workbook.xml"

    /** Returns the new workbook bytes. An empty update leaves the bytes as they are. */
    fun apply(original: ByteArray, updates: Map<RowKey, Map<String, CellWrite>>): WriteResult {
        val nonEmpty = updates.filterValues { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) return WriteResult(original, 0, emptyList())
        for ((key, cols) in nonEmpty) for ((col, value) in cols) validate(key, col, value)

        val parts = XlsxReader.unzip(original)
        val sheetPaths = XlsxReader.sheetPaths(parts)
        val bySheet = LinkedHashMap<String, MutableList<Pair<Int, Map<String, CellWrite>>>>()
        for ((key, cols) in nonEmpty) {
            if (key.sheet !in sheetPaths) throw WritebackException("Sheet not found in workbook: '${key.sheet}'")
            bySheet.getOrPut(key.sheet) { ArrayList() }.add(key.row to cols)
        }

        val replacements = HashMap<String, String>()
        for ((sheet, changes) in bySheet) {
            val member = sheetPaths.getValue(sheet)
            var xml = parts[member]?.decodeToString() ?: throw WritebackException("Missing sheet part: $member")
            for ((row, cols) in changes) {
                // Column order, so each inserted cell lands after the previous one.
                for (col in cols.keys.sortedBy { columnIndex(it) }) xml = editCellValue(xml, row, col, cols.getValue(col), sheet)
            }
            replacements[member] = xml
        }
        parts[WORKBOOK_XML]?.let { replacements[WORKBOOK_XML] = setFullCalcOnLoad(it.decodeToString()) }

        return WriteResult(
            bytes = rewriteZip(original, replacements),
            cellsWritten = nonEmpty.values.sumOf { it.size },
            sheetsTouched = bySheet.keys.sorted(),
        )
    }

    private fun validate(key: RowKey, col: String, value: CellWrite) {
        if (!Regex("[A-Z]{1,3}").matches(col)) throw WritebackException("${key.sheet}!row ${key.row}: bad column '$col'")
        if (col == CONFIDENCE_COL && key.row > 1) {
            val v = (value as? CellWrite.Number)?.value
                ?: throw WritebackException("${key.sheet}!row ${key.row}: confidence is not a number")
            if (v < CONFIDENCE_MIN || v > CONFIDENCE_MAX) throw WritebackException("${key.sheet}!row ${key.row}: confidence $v out of range 1..5")
            if (abs(v / CONFIDENCE_STEP - round(v / CONFIDENCE_STEP)) > 1e-9) {
                throw WritebackException("${key.sheet}!row ${key.row}: confidence $v is not a multiple of $CONFIDENCE_STEP")
            }
        } else if (value is CellWrite.Number && !value.value.isFinite()) {
            throw WritebackException("${key.sheet}!$col${key.row}: ${value.value} cannot be stored in a cell")
        }
    }

    // ------------------------------------------------------------------ XML editing

    private fun rowRegex(row: Int) = Regex("""(<row r="$row"(?: [^>]*)?>)(.*?)(</row>)""", RegexOption.DOT_MATCHES_ALL)

    internal fun editCellValue(xml0: String, row: Int, col: String, value: CellWrite, sheet: String): String {
        var xml = xml0
        val ref = "$col$row"
        var rowMatch = rowRegex(row).find(xml)
            ?: throw WritebackException("$sheet!$ref: row $row not found in sheet XML")
        var head = rowMatch.groupValues[1]
        val body = rowMatch.groupValues[2]
        val tail = rowMatch.groupValues[3]

        val cellRe = Regex("""<c r="$ref"((?: [^>]*?)?)(?:/>|>(.*?)</c>)""", RegexOption.DOT_MATCHES_ALL)
        val cm = cellRe.find(body)
        val newBody: String
        if (cm != null) {
            var attrs = cm.groupValues[1]
            val inner = cm.groupValues[2]
            if ("<f" in inner || "<f" in attrs) throw WritebackException("$sheet!$ref contains a formula; refusing to overwrite")
            // Drop any t="s" / t="str": the new value sets its own type.
            attrs = attrs.replace(Regex("""\s+t="[^"]*""""), "")
            newBody = body.substring(0, cm.range.first) + cellXml(ref, attrs, col, value) + body.substring(cm.range.last + 1)
        } else {
            if (value == CellWrite.Clear) return xml  // clearing a cell that does not exist
            val style = styleForNewCell(xml, body, col, row)
            newBody = insertCell(body, col, cellXml(ref, style, col, value))
            head = widenSpans(head, columnIndex(col))
            xml = widenDimension(xml, col, row)
            rowMatch = rowRegex(row).find(xml)!!  // the dimension edit may have shifted the row
        }
        return xml.substring(0, rowMatch.range.first) + head + newBody + tail + xml.substring(rowMatch.range.last + 1)
    }

    private fun cellXml(ref: String, attrs: String, col: String, value: CellWrite): String = when (value) {
        CellWrite.Clear -> """<c r="$ref"$attrs/>"""
        // xml:space keeps leading/trailing spaces, which Excel would otherwise trim.
        is CellWrite.Text -> """<c r="$ref"$attrs t="inlineStr"><is><t xml:space="preserve">${escapeXml(value.value)}</t></is></c>"""
        is CellWrite.Number -> """<c r="$ref"$attrs><v>${numberText(col, value.value)}</v></c>"""
    }

    private fun numberText(col: String, v: Double): String {
        if (col == CONFIDENCE_COL) return confidenceToCell(v)  // "3", never "3.0"
        // Python's "%.10g": integers without a decimal point, otherwise up to 10 significant digits.
        if (v == Math.floor(v) && abs(v) < 1e15) return v.toLong().toString()
        return java.math.BigDecimal(v).round(java.math.MathContext(10)).stripTrailingZeros().toPlainString()
    }

    /**
     * Header row: copy the Confidence header's style (L1). Data rows: copy the
     * style of another DATA cell in the same column, never the header's bold fill.
     */
    private fun styleForNewCell(fullXml: String, rowBody: String, col: String, row: Int): String {
        if (row == 1) {
            val m = Regex("""<c r="${CONFIDENCE_COL}1"[^>]*?\bs="(\d+)"""").find(rowBody)
            return if (m != null) """ s="${m.groupValues[1]}"""" else ""
        }
        for (m in Regex("""<c r="$col(\d+)"[^>]*?\bs="(\d+)"""").findAll(fullXml)) {
            if (m.groupValues[1] != "1") return """ s="${m.groupValues[2]}""""
        }
        return ""
    }

    private fun insertCell(body: String, col: String, newCell: String): String {
        val target = columnIndex(col)
        for (cm in Regex("""<c r="([A-Z]+)\d+"(?:[^>]*?)(?:/>|>.*?</c>)""", RegexOption.DOT_MATCHES_ALL).findAll(body)) {
            if (columnIndex(cm.groupValues[1]) > target) return body.substring(0, cm.range.first) + newCell + body.substring(cm.range.first)
        }
        return body + newCell
    }

    /** Grow a row's spans="1:12" hint to cover a new column; multi-range spans are left alone. */
    private fun widenSpans(rowHead: String, colIndex: Int): String {
        val m = Regex("""spans="(\d+):(\d+)"""").find(rowHead) ?: return rowHead
        if (m.groupValues[2].toInt() >= colIndex) return rowHead
        return rowHead.substring(0, m.range.first) + """spans="${m.groupValues[1]}:$colIndex"""" + rowHead.substring(m.range.last + 1)
    }

    /** Grow <dimension ref="A1:L17"/> to include the new cell. */
    private fun widenDimension(xml: String, col: String, row: Int): String {
        val m = Regex("""<dimension ref="([A-Z]+)(\d+)(?::([A-Z]+)(\d+))?"""").find(xml) ?: return xml
        val c0 = m.groupValues[1]
        val r0 = m.groupValues[2].toInt()
        var c1 = m.groupValues[3].ifEmpty { c0 }
        var r1 = m.groupValues[4].ifEmpty { "$r0" }.toInt()
        if (columnIndex(col) > columnIndex(c1)) c1 = col
        r1 = maxOf(r1, row)
        return xml.substring(0, m.range.first) + """<dimension ref="$c0$r0:$c1$r1"""" + xml.substring(m.range.last + 1)
    }

    /** Force a full recalculation on open so the dashboard sheets refresh. */
    internal fun setFullCalcOnLoad(workbookXml: String): String {
        val m = Regex("""<calcPr[^>]*/?>""").find(workbookXml)
        if (m != null) {
            val tag = m.value
            val newTag = when {
                "fullCalcOnLoad" in tag -> tag.replace(Regex("""fullCalcOnLoad="[^"]*""""), """fullCalcOnLoad="1"""")
                tag.endsWith("/>") -> tag.dropLast(2) + """ fullCalcOnLoad="1"/>"""
                else -> tag.dropLast(1) + """ fullCalcOnLoad="1">"""
            }
            return workbookXml.substring(0, m.range.first) + newTag + workbookXml.substring(m.range.last + 1)
        }
        return workbookXml.replaceFirst("</sheets>", """</sheets><calcPr calcId="0" fullCalcOnLoad="1"/>""")
    }

    // ------------------------------------------------------------------ archive

    /** Rewrite the archive, substituting only [replacements]; members keep their order, compression and time. */
    private fun rewriteZip(original: ByteArray, replacements: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream(original.size + 4096)
        ZipInputStream(ByteArrayInputStream(original)).use { zin ->
            ZipOutputStream(out).use { zout ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val data = replacements[e.name]?.toByteArray(Charsets.UTF_8) ?: zin.readBytes()
                    val clone = ZipEntry(e.name)
                    if (e.time != -1L) clone.time = e.time
                    if (e.method == ZipEntry.STORED) {
                        clone.method = ZipEntry.STORED
                        clone.size = data.size.toLong()
                        clone.compressedSize = data.size.toLong()
                        clone.crc = CRC32().apply { update(data) }.value
                    } else {
                        clone.method = ZipEntry.DEFLATED
                    }
                    zout.putNextEntry(clone)
                    zout.write(data)
                    zout.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }
}

fun escapeXml(t: String): String = buildString(t.length) {
    for (c in t) when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        else -> append(c)
    }
}
