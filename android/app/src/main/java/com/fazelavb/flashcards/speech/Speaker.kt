package com.fazelavb.flashcards.speech

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/*
 * Pronunciation with Android's own text-to-speech engine (usually "Speech
 * Services by Google"): the phone's counterpart of the browser speech engine
 * the web app uses. No permission, and offline with the voices installed on
 * the phone. More voices: Settings > Text-to-speech output > engine settings >
 * Install voice data (installVoices() opens it).
 *
 * Local voices only unless you allow online ones, as in the web app: a network
 * voice sends the text to the engine's provider.
 */
class Speaker(context: Context) {
    private val app = context.applicationContext
    private var tts: TextToSpeech? = null

    /** Changes when the engine is ready or its voices change, so the screens redraw. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    var ready = false
        private set
    var online = false
        set(v) {
            field = v
            _revision.value++
        }

    private var voices: List<Voice> = emptyList()

    init {
        connect()
    }

    private fun connect() {
        tts = TextToSpeech(app) { status ->
            ready = status == TextToSpeech.SUCCESS
            refresh()
        }
    }

    /** Re-read the installed voices (after installing one and coming back). */
    fun refresh() {
        voices = if (ready) runCatching { tts?.voices?.toList() }.getOrNull().orEmpty() else emptyList()
        _revision.value++
    }

    /** The voice for a language code ("de"), preferring installed, high-quality ones; null if none. */
    fun voiceFor(lang: String?): Voice? {
        if (lang == null || !ready) return null
        val matches = voices.filter { normalize(it.locale.language) == lang && usable(it) }
        return matches.sortedWith(
            compareBy<Voice>({ it.isNetworkConnectionRequired }, { -it.quality }, { it.locale.country != defaultCountry(lang) }),
        ).firstOrNull()
    }

    /** Language codes this phone can speak, as allowed by the online-voices setting. */
    fun languages(): Set<String> = voices.filter(::usable).map { normalize(it.locale.language) }.toSet()

    fun voiceName(lang: String?): String? = voiceFor(lang)?.let { v ->
        val where = v.locale.displayCountry.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        v.locale.displayLanguage + where + if (v.isNetworkConnectionRequired) " · online" else ""
    }

    /** Speak; a new word interrupts the previous one. Rate 0.9: a little slower than conversation. */
    fun speak(text: String, lang: String?, rate: Float = 0.9f) {
        val engine = tts ?: return
        val voice = voiceFor(lang) ?: return
        if (text.isBlank()) return
        engine.voice = voice
        engine.setSpeechRate(rate)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "fc")
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }

    /** The engine's "install voice data" screen. */
    fun installVoicesIntent() = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Android's text-to-speech settings (engine, default language, rate). */
    fun settingsIntent() = Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun usable(v: Voice): Boolean {
        val features = v.features.orEmpty()
        if (TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in features) return false
        return online || !v.isNetworkConnectionRequired
    }

    // Java's old ISO codes: Hebrew "iw", Indonesian "in", Yiddish "ji"; Norwegian as "no".
    private fun normalize(code: String) = when (code) {
        "iw" -> "he"
        "in" -> "id"
        "ji" -> "yi"
        "no" -> "nb"
        else -> code
    }

    private fun defaultCountry(lang: String) = when (lang) {
        "de" -> "DE"; "en" -> "US"; "fr" -> "FR"; "es" -> "ES"; "pt" -> "BR"; "fa" -> "IR"
        else -> Locale(lang).country
    }
}
