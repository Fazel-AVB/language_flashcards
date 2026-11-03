package com.fazelavb.flashcards.data

import android.content.Context
import android.net.Uri
import com.fazelavb.flashcards.core.DeckOptions
import com.fazelavb.flashcards.core.Direction
import com.fazelavb.flashcards.core.ExerciseChoice
import com.fazelavb.flashcards.core.Mode
import com.fazelavb.flashcards.core.Order
import com.fazelavb.flashcards.core.ReviewSession
import com.fazelavb.flashcards.core.SessionState
import kotlinx.serialization.json.Json
import java.io.File

enum class ThemeChoice(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark"), SEPIA("Sepia") }

/** Settings kept on the phone: the workbook, the deck settings, theme and pronunciation. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("flashcards", Context.MODE_PRIVATE)

    var workbookUri: Uri?
        get() = sp.getString("workbook", null)?.let(Uri::parse)
        set(v) = sp.edit().putString("workbook", v?.toString()).apply()

    var theme: ThemeChoice
        get() = enumOr(sp.getString("theme", null), ThemeChoice.SYSTEM)
        set(v) = sp.edit().putString("theme", v.name).apply()

    /** Say the word when it appears (on the answer side if the card doesn't show it). */
    var autoSpeak: Boolean
        get() = sp.getBoolean("autoSpeak", true)
        set(v) = sp.edit().putBoolean("autoSpeak", v).apply()

    /** Also use voices that need the internet (better quality; the text goes to the voice's provider). */
    var onlineVoices: Boolean
        get() = sp.getBoolean("onlineVoices", false)
        set(v) = sp.edit().putBoolean("onlineVoices", v).apply()

    var deck: DeckOptions
        get() = DeckOptions(
            mode = enumOr(sp.getString("mode", null), Mode.DUE),
            exercise = enumOr(sp.getString("exercise", null), ExerciseChoice.FLASHCARD),
            topics = sp.getStringSet("topics", emptySet())!!.sorted(),
            direction = enumOr(sp.getString("direction", null), Direction.MIXED),
            threshold = sp.getInt("threshold", 2),
            order = enumOr(sp.getString("order", null), Order.WEIGHTED),
            repeat = sp.getBoolean("repeat", false),
            size = sp.getInt("size", 20),
            newPerSession = sp.getInt("newPerSession", 5),
        )
        set(d) = sp.edit()
            .putString("mode", d.mode.name).putString("exercise", d.exercise.name)
            .putStringSet("topics", d.topics.toSet()).putString("direction", d.direction.name)
            .putInt("threshold", d.threshold).putString("order", d.order.name)
            .putBoolean("repeat", d.repeat).putInt("size", d.size).putInt("newPerSession", d.newPerSession)
            .apply()

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}

/**
 * The session in progress, saved after every action: Android may stop the app
 * in the background, and an unsaved session must not be lost with it.
 */
class SessionStore(context: Context) {
    private val file = File(context.filesDir, "session.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun save(session: ReviewSession?) {
        if (session == null) {
            file.delete()
            return
        }
        val tmp = File(file.parentFile, "session.json.tmp")
        tmp.writeText(json.encodeToString(SessionState.serializer(), session.state))
        tmp.renameTo(file)
    }

    fun load(): SessionState? = runCatching {
        if (file.exists()) json.decodeFromString(SessionState.serializer(), file.readText()) else null
    }.getOrNull()
}
