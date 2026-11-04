package com.fazelavb.flashcards.data

import android.content.Context
import com.fazelavb.flashcards.core.SessionRecord
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/*
 * The app's own history, for the Progress screen, in its private storage on
 * the phone (no other app can read it, and the app has no internet access):
 *
 *   history.json  one entry per finished session (saved or discarded)
 *   usage.json    seconds the app was open, per day
 *
 * Kept apart from the workbook: these describe how you practise, not your
 * vocabulary, and uninstalling the app deletes them.
 */
class ProgressStore(context: Context) {
    private val dir = context.filesDir
    private val historyFile = File(dir, "history.json")
    private val usageFile = File(dir, "usage.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val historySerializer = ListSerializer(SessionRecord.serializer())
    private val usageSerializer = MapSerializer(String.serializer(), Long.serializer())

    fun history(): List<SessionRecord> = read(historyFile) { json.decodeFromString(historySerializer, it) } ?: emptyList()

    fun add(record: SessionRecord) {
        // A few years of daily sessions is a few hundred kilobytes; cap it anyway.
        write(historyFile, json.encodeToString(historySerializer, (history() + record).takeLast(5000)))
    }

    fun usage(): Map<String, Long> = read(usageFile) { json.decodeFromString(usageSerializer, it) } ?: emptyMap()

    /** Add [seconds] of the app being open to [day]. */
    fun addUsage(day: LocalDate, seconds: Long) {
        if (seconds <= 0) return
        val map = usage().toMutableMap()
        map.merge(day.toString(), seconds, Long::plus)
        val kept = map.toSortedMap().entries.toList().takeLast(800).associate { it.key to it.value }
        write(usageFile, json.encodeToString(usageSerializer, kept))
    }

    private fun <T> read(file: File, parse: (String) -> T): T? =
        if (!file.exists()) null else runCatching { parse(file.readText()) }.getOrNull()

    private fun write(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(file)
    }
}
