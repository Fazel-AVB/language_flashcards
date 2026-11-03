package com.fazelavb.flashcards.core

import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream

/*
 * A small read-only .xlsx reader: just enough of the format to read the topic
 * sheets (cell values, no styles or formulas). The desktop app uses openpyxl;
 * on a phone a full spreadsheet library would be several megabytes for this.
 *
 * Cells come back as: String, NumberCell, Boolean, LocalDate (an ISO date
 * cell), or null. Formula cells give their cached value, like openpyxl's
 * data_only mode.
 */

/** A numeric cell: its value and its text as Excel shows a plain number ("3", "3.5"). */
data class NumberCell(val text: String, val value: Double) {
    companion object {
        fun of(raw: String): NumberCell? {
            val v = raw.trim().toDoubleOrNull() ?: return null
            // openpyxl: no '.' and no exponent -> an int; keep "3", never "3.0".
            val integral = raw.none { it == '.' || it == 'e' || it == 'E' }
            val text = if (integral) raw.trim().toLongOrNull()?.toString() ?: v.toString() else v.toString()
            return NumberCell(text, v)
        }
    }
}

/** One sheet: its name and its rows by 1-based row number, each row's cells by 0-based column. */
class SheetData(val name: String, val rows: Map<Int, Map<Int, Any?>>) {
    fun row(r: Int, width: Int): List<Any?> {
        val cells = rows[r] ?: emptyMap()
        return List(width) { cells[it] }
    }
}

class XlsxException(message: String) : Exception(message)

object XlsxReader {
    /** Every zip member by name. Workbooks are small; reading them whole keeps this simple. */
    fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (!e.isDirectory) out[e.name] = zin.readBytes()
                }
            }
        } catch (e: Exception) {
            throw XlsxException("Not an Excel workbook (.xlsx): ${e.message}")
        }
        if (out.isEmpty()) throw XlsxException("Not an Excel workbook (.xlsx): the file is empty or not a zip archive.")
        return out
    }

    /** Sheet name -> zip member path, in workbook order. */
    fun sheetPaths(parts: Map<String, ByteArray>): LinkedHashMap<String, String> {
        val wb = parts["xl/workbook.xml"] ?: throw XlsxException("Not an Excel workbook: xl/workbook.xml is missing.")
        val rels = parts["xl/_rels/workbook.xml.rels"] ?: throw XlsxException("Not an Excel workbook: the sheet list is missing.")
        val targets = HashMap<String, String>()
        XmlPull(rels.decodeToString()).forEachStart { name, attrs ->
            if (name == "Relationship") {
                val id = attrs["Id"]
                val target = attrs["Target"]
                if (id != null && target != null) targets[id] = target
            }
        }
        val out = LinkedHashMap<String, String>()
        XmlPull(wb.decodeToString()).forEachStart { name, attrs ->
            if (name == "sheet") {
                val sheetName = attrs["name"] ?: return@forEachStart
                val rid = attrs["r:id"] ?: attrs.entries.firstOrNull { it.key.endsWith(":id") }?.value ?: return@forEachStart
                var target = targets[rid] ?: return@forEachStart
                target = target.trimStart('/')
                if (!target.startsWith("xl/")) target = "xl/$target"
                out[sheetName] = target
            }
        }
        return out
    }

    fun read(bytes: ByteArray): List<SheetData> {
        val parts = unzip(bytes)
        val shared = parts["xl/sharedStrings.xml"]?.let { sharedStrings(it.decodeToString()) } ?: emptyList()
        return sheetPaths(parts).mapNotNull { (name, path) ->
            parts[path]?.let { SheetData(name, sheetRows(it.decodeToString(), shared)) }
        }
    }

    /** Shared strings; rich-text runs are joined, phonetic hints (rPh) skipped as openpyxl does. */
    fun sharedStrings(xml: String): List<String> {
        val out = ArrayList<String>()
        val p = XmlPull(xml)
        var current: StringBuilder? = null
        var inT = false
        var phonetic = 0
        while (true) {
            when (p.next()) {
                XmlPull.START -> when (p.name) {
                    "si" -> current = StringBuilder()
                    "rPh" -> phonetic++
                    "t" -> inT = phonetic == 0
                }
                XmlPull.END -> when (p.name) {
                    "si" -> { out.add(decodeOoxmlEscapes(current.toString())); current = null }
                    "rPh" -> phonetic--
                    "t" -> inT = false
                }
                XmlPull.TEXT -> if (inT) current?.append(p.text)
                XmlPull.EOF -> return out
            }
        }
    }

    fun sheetRows(xml: String, shared: List<String>): Map<Int, Map<Int, Any?>> {
        val rows = LinkedHashMap<Int, MutableMap<Int, Any?>>()
        val p = XmlPull(xml)
        var rowNum = 0
        var colNum = -1
        var cellType: String? = null
        var value: StringBuilder? = null
        var inline: StringBuilder? = null
        var inV = false
        var inT = false
        var inSheetData = false
        var phonetic = 0
        while (true) {
            when (p.next()) {
                XmlPull.START -> when (p.name) {
                    "sheetData" -> inSheetData = true
                    "row" -> if (inSheetData) {
                        rowNum = p.attrs["r"]?.toIntOrNull() ?: (rowNum + 1)
                        colNum = -1
                        rows.getOrPut(rowNum) { HashMap() }
                    }
                    "c" -> if (inSheetData) {
                        colNum = p.attrs["r"]?.let { columnIndex(it.takeWhile { ch -> ch.isLetter() }) - 1 } ?: (colNum + 1)
                        cellType = p.attrs["t"]
                        value = null
                        inline = null
                    }
                    "v" -> { inV = true; value = StringBuilder() }
                    "is" -> inline = StringBuilder()
                    "rPh" -> phonetic++
                    "t" -> inT = inline != null && phonetic == 0
                }
                XmlPull.END -> when (p.name) {
                    "sheetData" -> inSheetData = false
                    "v" -> inV = false
                    "t" -> inT = false
                    "rPh" -> phonetic--
                    "c" -> if (inSheetData) {
                        rows.getOrPut(rowNum) { HashMap() }[colNum] = cellValue(cellType, value?.toString(), inline?.toString(), shared)
                    }
                }
                XmlPull.TEXT -> {
                    if (inV) value?.append(p.text)
                    else if (inT) inline?.append(p.text)
                }
                XmlPull.EOF -> return rows
            }
        }
    }

    private fun cellValue(type: String?, v: String?, inline: String?, shared: List<String>): Any? = when (type) {
        "s" -> v?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) }
        "inlineStr" -> (inline ?: v)?.let(::decodeOoxmlEscapes)
        "str" -> v
        "b" -> v?.trim()?.let { it == "1" || it.equals("true", ignoreCase = true) }
        "e" -> v  // an error like #N/A, shown as text
        "d" -> v?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() ?: it }
        else -> v?.takeIf { it.isNotBlank() }?.let { NumberCell.of(it) ?: it }
    }
}

private val OOXML_ESCAPE = Regex("_x([0-9A-Fa-f]{4})_")

/** Excel writes control characters in text as _x000D_ and so on; openpyxl decodes them too. */
fun decodeOoxmlEscapes(t: String): String =
    if ("_x" !in t) t else OOXML_ESCAPE.replace(t) { it.groupValues[1].toInt(16).toChar().toString() }

/** "A" -> 1, "L" -> 12, "AA" -> 27. */
fun columnIndex(letters: String): Int {
    var n = 0
    for (ch in letters.uppercase()) n = n * 26 + (ch - 'A' + 1)
    return n
}

fun columnLetters(index: Int): String {
    var n = index
    val sb = StringBuilder()
    while (n > 0) {
        val r = (n - 1) % 26
        sb.append('A' + r)
        n = (n - 1) / 26
    }
    return sb.reverse().toString()
}

/**
 * A minimal XML pull tokenizer for the well-formed XML that Excel writes:
 * start/end tags with attributes, text with entities, CDATA; comments,
 * processing instructions and the DOCTYPE are skipped. Pure Kotlin, so the
 * reader runs in plain JVM unit tests.
 */
class XmlPull(private val s: String) {
    companion object {
        const val START = 1
        const val END = 2
        const val TEXT = 3
        const val EOF = 4
    }

    var name: String = ""
        private set
    var text: String = ""
        private set
    var attrs: Map<String, String> = emptyMap()
        private set

    private var i = 0
    private var pendingEnd: String? = null

    fun next(): Int {
        pendingEnd?.let { name = it; pendingEnd = null; return END }
        while (true) {
            if (i >= s.length) return EOF
            if (s[i] != '<') {
                val end = s.indexOf('<', i).let { if (it < 0) s.length else it }
                text = unescapeXml(s.substring(i, end))
                i = end
                return TEXT
            }
            when {
                s.startsWith("<?", i) -> i = indexAfter("?>")
                s.startsWith("<!--", i) -> i = indexAfter("-->")
                s.startsWith("<![CDATA[", i) -> {
                    val end = s.indexOf("]]>", i)
                    text = s.substring(i + 9, if (end < 0) s.length else end)
                    i = if (end < 0) s.length else end + 3
                    return TEXT
                }
                s.startsWith("<!", i) -> i = indexAfter(">")
                s.startsWith("</", i) -> {
                    val end = s.indexOf('>', i)
                    name = localName(s.substring(i + 2, end).trim())
                    i = end + 1
                    return END
                }
                else -> return startTag()
            }
        }
    }

    /** Calls [block] for every start tag; for simple attribute scans. */
    fun forEachStart(block: (String, Map<String, String>) -> Unit) {
        while (true) {
            when (next()) {
                START -> block(name, attrs)
                EOF -> return
            }
        }
    }

    private fun startTag(): Int {
        var j = i + 1
        while (j < s.length && !s[j].isWhitespace() && s[j] != '>' && s[j] != '/') j++
        val raw = s.substring(i + 1, j)
        val map = HashMap<String, String>()
        while (true) {
            while (j < s.length && s[j].isWhitespace()) j++
            if (j >= s.length) break
            if (s[j] == '>') { j++; break }
            if (s[j] == '/') {
                j = s.indexOf('>', j) + 1
                pendingEnd = localName(raw)
                break
            }
            val eq = s.indexOf('=', j)
            val key = s.substring(j, eq).trim()
            var q = eq + 1
            while (s[q].isWhitespace()) q++
            val quote = s[q]
            val close = s.indexOf(quote, q + 1)
            map[key] = unescapeXml(s.substring(q + 1, close))
            j = close + 1
        }
        i = j
        name = localName(raw)
        attrs = map
        return START
    }

    private fun indexAfter(token: String): Int = s.indexOf(token, i).let { if (it < 0) s.length else it + token.length }

    // Excel writes some parts with a prefix (x:row); the reader only cares about local names.
    private fun localName(raw: String) = raw.substringAfter(':')
}

fun unescapeXml(t: String): String {
    if ('&' !in t) return t
    val sb = StringBuilder(t.length)
    var i = 0
    while (i < t.length) {
        val c = t[i]
        if (c == '&') {
            val semi = t.indexOf(';', i)
            if (semi > i) {
                val ent = t.substring(i + 1, semi)
                val rep = when {
                    ent == "amp" -> "&"
                    ent == "lt" -> "<"
                    ent == "gt" -> ">"
                    ent == "quot" -> "\""
                    ent == "apos" -> "'"
                    ent.startsWith("#x") -> ent.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                    ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                    else -> null
                }
                if (rep != null) {
                    sb.append(rep)
                    i = semi + 1
                    continue
                }
            }
        }
        sb.append(c)
        i++
    }
    return sb.toString()
}
