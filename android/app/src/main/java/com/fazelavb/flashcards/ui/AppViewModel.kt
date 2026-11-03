package com.fazelavb.flashcards.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fazelavb.flashcards.core.Answer
import com.fazelavb.flashcards.core.CardView
import com.fazelavb.flashcards.core.DeckOptions
import com.fazelavb.flashcards.core.Engine
import com.fazelavb.flashcards.core.Overview
import com.fazelavb.flashcards.core.ReviewSession
import com.fazelavb.flashcards.core.Scope
import com.fazelavb.flashcards.core.Summary
import com.fazelavb.flashcards.core.UserException
import com.fazelavb.flashcards.core.WorkbookException
import com.fazelavb.flashcards.core.WritebackException
import com.fazelavb.flashcards.data.LoadedWorkbook
import com.fazelavb.flashcards.data.Prefs
import com.fazelavb.flashcards.data.SessionStore
import com.fazelavb.flashcards.data.ThemeChoice
import com.fazelavb.flashcards.data.WorkbookRepository
import com.fazelavb.flashcards.speech.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/*
 * The app's state and what each button does: the phone's counterpart of the
 * web app's server (service.py). The rules are in core/; this wires them to
 * the workbook file, the settings and the speech engine.
 *
 * Stages, as in the web app: setup -> review -> summary -> saved.
 */

enum class Stage { LOADING, SETUP, REVIEW, SUMMARY, SAVED }

data class SavedInfo(val words: Int, val cells: Int, val sheets: List<String>, val backup: String, val warning: String?)

data class UiState(
    val stage: Stage = Stage.LOADING,
    val workbookName: String? = null,
    val hasWorkbook: Boolean = false,
    val loadError: String? = null,
    val overview: Overview? = null,
    val deck: DeckOptions = DeckOptions(),
    val scope: Scope? = null,
    val card: CardView? = null,
    val revealed: Boolean = false,
    val summary: Summary? = null,
    val saved: SavedInfo? = null,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val busy: Boolean = false,
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val autoSpeak: Boolean = true,
    val onlineVoices: Boolean = false,
    val backups: Int = 0,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val repo = WorkbookRepository(app)
    private val store = SessionStore(app)
    val speaker = Speaker(app).also { it.online = prefs.onlineVoices }
    private val engine = Engine()

    private var loaded: LoadedWorkbook? = null
    private var session: ReviewSession? = null

    private val _state = MutableStateFlow(
        UiState(deck = prefs.deck, theme = prefs.theme, autoSpeak = prefs.autoSpeak, onlineVoices = prefs.onlineVoices),
    )
    val state: StateFlow<UiState> = _state

    init {
        reload(resume = true)
    }

    override fun onCleared() {
        speaker.shutdown()
    }

    // ------------------------------------------------------------------ workbook

    /** Read the workbook again (on start, after Save, when coming back to the app). */
    fun reload(resume: Boolean = false) {
        if (session != null && !resume) return  // never swap the file under a running session
        val uri = prefs.workbookUri
        if (uri == null) {
            _state.update { it.copy(stage = Stage.SETUP, hasWorkbook = false, overview = null, loadError = null) }
            return
        }
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repo.load(uri) } }
            // Building the start screen is inside the catch too: a bug there must show a message, not crash the app.
            result.mapCatching { lw ->
                loaded = lw
                if (resume) restoreSession(lw)
                refreshSetup(lw)
            }.onFailure { e ->
                loaded = null
                _state.update {
                    it.copy(stage = if (session == null) Stage.SETUP else it.stage, hasWorkbook = true, overview = null,
                        loadError = describe(e), busy = false, workbookName = repo.displayName(uri))
                }
            }
        }
    }

    fun chooseWorkbook(uri: Uri) {
        if (session != null) return flash("Finish or discard the current session before changing the workbook.", true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repo.load(uri) } }
            result.mapCatching { lw ->
                prefs.workbookUri?.takeIf { it != uri }?.let(repo::forget)
                repo.remember(uri)
                prefs.workbookUri = uri
                loaded = lw
                // Topics of the previous workbook mean nothing in this one.
                updateDeck(prefs.deck.copy(topics = emptyList()))
                refreshSetup(lw)
                flash("Workbook set: ${lw.name}")
            }.onFailure { e -> flash(describe(e), true) }
        }
    }

    private fun refreshSetup(lw: LoadedWorkbook) {
        val known = lw.data.topics.toSet()
        val deck = _state.value.deck.let { d -> d.copy(topics = d.topics.filter { it in known }) }
        _state.update {
            it.copy(
                hasWorkbook = true, workbookName = lw.name, loadError = null, busy = false,
                overview = engine.overview(lw.data, deck.threshold), deck = deck,
                scope = runCatching { engine.scope(lw.data, deck) }.getOrNull(),
                backups = repo.backupCount(),
                stage = if (session != null) it.stage else if (it.stage == Stage.SAVED) Stage.SAVED else Stage.SETUP,
            )
        }
    }

    private fun restoreSession(lw: LoadedWorkbook) {
        val saved = store.load() ?: return
        val s = engine.resume(lw.data, saved)
        if (s == null) {
            store.save(null)
            flash("The workbook changed since your last session, so its unsaved grades could not be resumed.", true)
            return
        }
        session = s
        showSession()
    }

    // ------------------------------------------------------------------ deck settings

    fun updateDeck(deck: DeckOptions) {
        prefs.deck = deck
        val data = loaded?.data
        _state.update {
            it.copy(
                deck = deck,
                scope = data?.let { d -> runCatching { engine.scope(d, deck) }.getOrNull() },
                overview = if (data != null && it.overview != null && deck.threshold != it.deck.threshold) engine.overview(data, deck.threshold) else it.overview,
            )
        }
    }

    fun start() {
        val lw = loaded ?: return flash("Choose your vocabulary workbook first.", true)
        try {
            session = engine.start(lw.data, _state.value.deck, speaker.languages(), Random.nextLong(1L shl 31))
            showSession()
        } catch (e: UserException) {
            flash(e.message!!, true)
        }
    }

    // ------------------------------------------------------------------ reviewing

    fun reveal() = _state.update { it.copy(revealed = true) }

    fun check(answer: Answer) = act(keepReveal = true) {
        try {
            it.check(answer)
        } catch (e: IllegalArgumentException) {
            flash(e.message ?: "That answer doesn't fit this exercise.", true)
        }
    }

    fun grade(grade: Int) {
        if (_state.value.busy) return
        act { it.grade(grade, java.time.Instant.now()) }
    }

    fun skip() = act { it.skip() }

    fun end() = act { it.end() }

    fun discard() {
        session = null
        store.save(null)
        _state.update { it.copy(card = null, summary = null, saved = null, stage = Stage.SETUP) }
        reload()
    }

    fun backToStart() {
        _state.update { it.copy(saved = null, stage = Stage.SETUP) }
        reload()
    }

    fun save() {
        val s = session ?: return
        val lw = loaded ?: return flash("The workbook is not loaded.", true)
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repo.save(lw, engine.updatesToSave(s, lw.data), s) }
            }
            result.onSuccess { r ->
                val info = SavedInfo(s.gradedIds.size, r.write.cellsWritten, r.write.sheetsTouched, r.backup.name, r.logWarning)
                session = null
                store.save(null)
                _state.update { it.copy(busy = false, stage = Stage.SAVED, saved = info, summary = null, card = null) }
                reload()
            }.onFailure { e ->
                _state.update { it.copy(busy = false) }
                flash(describe(e), true)
            }
        }
    }

    private fun act(keepReveal: Boolean = false, block: (ReviewSession) -> Unit) {
        val s = session ?: return
        if (s.finished) return
        block(s)
        store.save(s)
        showSession(keepReveal)
    }

    private fun showSession(keepReveal: Boolean = false) {
        val s = session ?: return
        val lw = loaded ?: return
        if (s.finished) {
            _state.update { it.copy(stage = Stage.SUMMARY, summary = engine.summary(s, lw.data.languages), card = null, revealed = false) }
        } else {
            _state.update {
                it.copy(stage = Stage.REVIEW, card = engine.cardView(s), revealed = keepReveal && it.revealed, summary = null)
            }
        }
    }

    // ------------------------------------------------------------------ settings

    fun setTheme(t: ThemeChoice) {
        prefs.theme = t
        _state.update { it.copy(theme = t) }
    }

    fun setAutoSpeak(on: Boolean) {
        prefs.autoSpeak = on
        _state.update { it.copy(autoSpeak = on) }
    }

    fun setOnlineVoices(on: Boolean) {
        prefs.onlineVoices = on
        speaker.online = on
        _state.update { it.copy(onlineVoices = on) }
    }

    fun flash(text: String, error: Boolean = false) = _state.update { it.copy(message = text, messageIsError = error) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun describe(e: Throwable): String = when (e) {
        is UserException, is WorkbookException, is WritebackException -> e.message ?: "Something went wrong."
        else -> "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
    }
}
