package com.fazelavb.flashcards.core

import java.security.MessageDigest

/*
 * Read the workbook into Cards (read-only). A port of flashcards/workbook.py.
 *
 * Only topic sheets are read (Models.isTopicSheet). `hash` is the content
 * fingerprint captured at load: Writeback refuses to save if the file changed
 * since, so an edit made elsewhere (Excel on the laptop, through OneDrive) is
 * never overwritten. The desktop app compares modification times; a hash is
 * used here because cloud storage apps do not report those reliably.
 */

class WorkbookException(message: String) : Exception(message)

data class TopicStat(
    val topic: String,
    val count: Int,
    val graded: Int,
    val avgConfidence: Double?,
    val difficult: Int,
)

data class WorkbookData(
    val hash: String,
    val cards: List<Card>,
    val languages: LanguagePair,
    val topics: List<String>,
    /** Sheets whose A1/B1 disagree with the first topic sheet's pair. */
    val languageConflicts: List<String> = emptyList(),
    /** "Sheet!M7: reason" for every row whose schedule could not be read (treated as new). */
    val scheduleProblems: List<String> = emptyList(),
    /** Sheets whose M1..R1 do not carry the schedule headers yet; the first save adds them. */
    val sheetsMissingSrsHeaders: List<String> = emptyList(),
) {
    fun stats(difficultThreshold: Int): List<TopicStat> = topics.map { topic ->
        val group = cards.filter { it.topic == topic }
        val graded = group.mapNotNull { it.confidence }
        TopicStat(
            topic = topic,
            count = group.size,
            graded = graded.size,
            avgConfidence = if (graded.isEmpty()) null else graded.average(),
            difficult = group.count { it.confidence != null && it.confidence <= difficultThreshold },
        )
    }
}

object Workbook {
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Parse every topic sheet. Throws WorkbookException with a message to show as is. */
    fun load(bytes: ByteArray): WorkbookData {
        val sheets = try {
            XlsxReader.read(bytes)
        } catch (e: XlsxException) {
            throw WorkbookException(e.message ?: "Could not open the workbook.")
        } catch (e: Exception) {
            throw WorkbookException("Could not open the workbook: ${e.message}")
        }

        val cards = ArrayList<Card>()
        val topics = ArrayList<String>()
        val pairs = LinkedHashMap<String, LanguagePair>()
        val problems = ArrayList<String>()
        val missingHeaders = ArrayList<String>()

        for (sheet in sheets) {
            if (1 !in sheet.rows) continue
            val fullHeader = sheet.row(1, SRS_LAST_COL_INDEX)
            if (!isTopicSheet(sheet.name, fullHeader)) continue
            topics.add(sheet.name)
            val srsHeader = fullHeader.subList(SRS_FIRST_COL_INDEX - 1, SRS_LAST_COL_INDEX).map { clean(it) ?: "" }
            if (srsHeader != SRS_COLUMNS.map { SRS_HEADERS.getValue(it) }) missingHeaders.add(sheet.name)
            val header = fullHeader.subList(0, CONFIDENCE_COL_INDEX)
            val languages = languagePairFromHeader(header)
            pairs[sheet.name] = languages
            for (r in sheet.rows.keys.filter { it > 1 }.sorted()) {
                val values = sheet.row(r, SRS_LAST_COL_INDEX)
                val card = parseRow(sheet.name, header, values.subList(0, CONFIDENCE_COL_INDEX), r, languages) ?: continue
                val schedule = try {
                    Srs.scheduleFromCells(values.subList(SRS_FIRST_COL_INDEX - 1, SRS_LAST_COL_INDEX))
                } catch (e: IllegalArgumentException) {
                    problems.add("${sheet.name}!M$r: ${e.message}")
                    null
                }
                cards.add(card.copy(schedule = schedule))
            }
        }

        if (topics.isEmpty()) {
            throw WorkbookException(
                "No topic sheets found. A topic sheet needs the target language in A1, the native " +
                    "language in B1, and 'Confidence (1-5)' in L1, with the header on row 1.",
            )
        }
        val primary = pairs.getValue(topics[0])
        return WorkbookData(
            hash = sha256(bytes),
            cards = cards,
            languages = primary,
            topics = topics,
            languageConflicts = pairs.filterValues { it != primary }.keys.toList(),
            scheduleProblems = problems,
            sheetsMissingSrsHeaders = missingHeaders,
        )
    }
}
