package com.fazelavb.flashcards.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fazelavb.flashcards.core.DeckOptions
import com.fazelavb.flashcards.core.Direction
import com.fazelavb.flashcards.core.ExerciseChoice
import com.fazelavb.flashcards.core.MAX_SESSION_SIZE
import com.fazelavb.flashcards.core.Mode
import com.fazelavb.flashcards.core.Order
import com.fazelavb.flashcards.core.Overview
import com.fazelavb.flashcards.speech.Speaker

@Composable
fun SetupScreen(
    state: UiState,
    speaker: Speaker,
    speechRevision: Int,
    onPickWorkbook: () -> Unit,
    onDeck: (DeckOptions) -> Unit,
    onStart: () -> Unit,
    onAutoSpeak: (Boolean) -> Unit,
    onOnlineVoices: (Boolean) -> Unit,
    onReload: () -> Unit,
    onInstallVoices: () -> Unit,
    onSpeechSettings: () -> Unit,
) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val o = state.overview
        when {
            !state.hasWorkbook -> Welcome(onPickWorkbook)
            o == null -> {
                state.loadError?.let { Message(MsgKind.ERR) { Text(it) } }
                WorkbookPanel(state, open = true, onPickWorkbook, onReload)
            }
            else -> {
                if (o.scheduleProblems.isNotEmpty()) Message(MsgKind.WARN) {
                    Text("These rows have a schedule (columns M–R) that could not be read and are treated as new: " +
                        o.scheduleProblems.joinToString("; "))
                }
                if (o.languageConflicts.isNotEmpty()) Message(MsgKind.WARN) {
                    Text("These sheets declare a different language pair in A1/B1: ${o.languageConflicts.joinToString(", ")}")
                }
                Tiles(o)
                DeckForm(state, o, speaker, speechRevision, onDeck, onStart)
                TopicTable(o)
                SpeechPanel(state, o, speaker, speechRevision, onAutoSpeak, onOnlineVoices, onInstallVoices, onSpeechSettings)
                WorkbookPanel(state, open = false, onPickWorkbook, onReload)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Welcome(onPick: () -> Unit) {
    Section {
        Text("Welcome", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.height(8.dp))
        Text("Choose the Excel workbook with your vocabulary. It is remembered, so you only do this once.")
        Spacer(Modifier.height(6.dp))
        Muted("It can be on the phone (e.g. Downloads) or in Google Drive or OneDrive. Each topic sheet needs the two " +
            "language names in A1 and B1 and “Confidence (1-5)” in L1, the same layout as the desktop app.")
        Spacer(Modifier.height(16.dp))
        Button(onClick = onPick, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Choose workbook") }
    }
}

@Composable
private fun Tiles(o: Overview) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Tile("Due now", o.due.toString(), "to review", hero = true, modifier = Modifier.weight(1.25f))
        Tile("New", o.new.toString(), "never seen", modifier = Modifier.weight(1f))
        Tile("Later", o.later.toString(), o.nextDue?.let { "next $it" } ?: "none yet", modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Tile(title: String, value: String, detail: String, modifier: Modifier = Modifier, hero: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (hero) cs.primary else cs.surfaceContainer,
        contentColor = if (hero) cs.onPrimary else cs.onSurface,
        border = if (hero) null else BorderStroke(1.dp, cs.outlineVariant),
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = if (hero) cs.onPrimary else cs.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = if (hero) cs.onPrimary else cs.primary)
            Text(detail, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (hero) cs.onPrimary else cs.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun DeckForm(state: UiState, o: Overview, speaker: Speaker, speechRevision: Int, onDeck: (DeckOptions) -> Unit, onStart: () -> Unit) {
    val d = state.deck
    Section {
        Text("Build a deck", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.height(12.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Mode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = d.mode == m, onClick = { onDeck(d.copy(mode = m)) },
                    shape = SegmentedButtonDefaults.itemShape(i, Mode.entries.size),
                    icon = {},
                ) { Text(m.label, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
            }
        }
        Muted(d.mode.tip, Modifier.padding(top = 6.dp))

        if (d.mode != Mode.MIX) {
            Spacer(Modifier.height(12.dp))
            Muted(if (d.mode == Mode.TOPIC) "Topics" else "Limit to topics (none = all)")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                o.topics.forEach { t ->
                    val on = t.topic in d.topics
                    FilterChip(selected = on, label = { Text(t.topic) }, onClick = {
                        onDeck(d.copy(topics = if (on) d.topics - t.topic else d.topics + t.topic))
                    })
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        val voiceForTerm = speechRevision >= 0 && speaker.voiceFor(o.termCode) != null
        Dropdown("Exercise", ExerciseChoice.entries, d.exercise, onPick = { onDeck(d.copy(exercise = it)) },
            label = { e ->
                when (e) {
                    ExerciseChoice.ARTICLE -> if (o.articleWords > 0) "${e.label} · ${o.articleWords} nouns" else "${e.label} (no der/die/das nouns)"
                    ExerciseChoice.FORMS -> if (o.formsWords > 0) "${e.label} · ${o.formsWords} words" else "${e.label} (no forms in column E)"
                    ExerciseChoice.LISTEN -> if (voiceForTerm) e.label else "${e.label} (no ${o.languages.term} voice)"
                    else -> e.label
                }
            },
            enabled = { e ->
                when (e) {
                    ExerciseChoice.ARTICLE -> o.articleWords > 0
                    ExerciseChoice.FORMS -> o.formsWords > 0
                    ExerciseChoice.LISTEN -> voiceForTerm
                    else -> true
                }
            })
        Spacer(Modifier.height(8.dp))
        val pair = o.languages
        Dropdown("Direction", Direction.entries, d.direction, onPick = { onDeck(d.copy(direction = it)) }, label = {
            when (it) {
                Direction.MIXED -> "Mixed (random)"
                Direction.TERM_TO_NATIVE -> "${pair.term} → ${pair.native}"
                Direction.NATIVE_TO_TERM -> "${pair.native} → ${pair.term}"
            }
        })
        Spacer(Modifier.height(8.dp))
        Dropdown("Order", Order.entries, d.order, onPick = { onDeck(d.copy(order = it)) }, label = { it.label })
        if (d.mode == Mode.DUE) Muted("Due words always come most-forgotten first; the order applies to the new words after them.",
            Modifier.padding(top = 4.dp))

        Spacer(Modifier.height(8.dp))
        Stepper("Words per session", d.size, 1, MAX_SESSION_SIZE, step = 5) { onDeck(d.copy(size = it)) }
        if (d.mode == Mode.DUE) {
            Stepper("New words per session", d.newPerSession, 0, MAX_SESSION_SIZE, step = 1) { onDeck(d.copy(newPerSession = it)) }
        }
        if (d.mode == Mode.DIFFICULT) {
            Stepper("“Difficult” means confidence ≤", d.threshold, 1, 5, step = 1) { onDeck(d.copy(threshold = it)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = d.repeat, onCheckedChange = { onDeck(d.copy(repeat = it)) })
            Text("Keep repeating words below 5 until the deck is clear", style = MaterialTheme.typography.bodyMedium)
        }

        val s = state.scope
        val scopeText = when {
            d.mode == Mode.TOPIC && d.topics.isEmpty() -> "Pick at least one topic."
            s == null -> " "
            s.inScope == 0 -> "No words match these settings."
            else -> {
                val parts = if (d.mode == Mode.DUE) " (${s.due} due, ${s.new} new)" else ""
                if (s.inScope > s.drawn) "${s.inScope} words in scope; this session draws ${s.drawn}$parts."
                else "${s.drawn} word${if (s.drawn == 1) "" else "s"} in this session$parts."
            }
        }
        Muted(scopeText, Modifier.padding(vertical = 10.dp))
        Button(
            onClick = onStart,
            enabled = (s?.drawn ?: 0) > 0 && !(d.mode == Mode.TOPIC && d.topics.isEmpty()),
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = MaterialTheme.shapes.large,
        ) { Text("Start review", style = MaterialTheme.typography.titleMedium) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Dropdown(title: String, items: List<T>, selected: T, onPick: (T) -> Unit, label: (T) -> String, enabled: (T) -> Boolean = { true }) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = label(selected), onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text(title) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { item ->
                DropdownMenuItem(text = { Text(label(item)) }, enabled = enabled(item), onClick = { onPick(item); open = false })
            }
        }
    }
}

@Composable
private fun Stepper(title: String, value: Int, min: Int, max: Int, step: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { onChange((value - step).coerceAtLeast(min)) }, enabled = value > min) { Text("−") }
        Text(value.toString(), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.width(52.dp))
        OutlinedButton(onClick = { onChange(if (value < step && step > 1) step else (value + step).coerceAtMost(max)) }, enabled = value < max) { Text("+") }
    }
}

@Composable
private fun TopicTable(o: Overview) {
    Expandable("Topics (${o.words} words)") {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Muted("Topic", Modifier.weight(1f))
            listOf("Words", "Due", "New", "Avg").forEach { Muted(it, Modifier.width(48.dp)) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        o.topics.forEach { t ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t.topic, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                listOf(t.words.toString(), t.due.toString(), t.new.toString(), t.avgConfidence?.let { "%.1f".format(it) } ?: "—")
                    .forEach { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(48.dp)) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun SpeechPanel(
    state: UiState, o: Overview, speaker: Speaker, speechRevision: Int,
    onAutoSpeak: (Boolean) -> Unit, onOnlineVoices: (Boolean) -> Unit, onInstallVoices: () -> Unit, onSpeechSettings: () -> Unit,
) {
    Expandable("Pronunciation") {
        Muted("Words are spoken by the phone's text-to-speech engine with the voices installed on it: offline, nothing to sign up for.")
        Spacer(Modifier.height(8.dp))
        // speechRevision: re-read when the engine becomes ready or voices change.
        if (speechRevision >= 0) {
            for ((name, code) in listOf(o.languages.term to o.termCode, o.languages.native to o.nativeCode)) {
                val voice = speaker.voiceName(code)
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text("$name: ", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                    when {
                        voice != null -> Text(voice, style = MaterialTheme.typography.bodyMedium)
                        code == null -> Muted("unknown language name in A1/B1")
                        !speaker.ready -> Muted("speech engine starting…")
                        else -> Muted("no voice installed")
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text("Pronounce the word automatically during review", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = state.autoSpeak, onCheckedChange = onAutoSpeak)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Also use online voices", style = MaterialTheme.typography.bodyMedium)
                Muted("Better quality for some languages; the text is sent to the voice's provider (usually Google).")
            }
            Switch(checked = state.onlineVoices, onCheckedChange = onOnlineVoices)
        }
        Spacer(Modifier.height(10.dp))
        Muted("Missing a language? Install its voice in the speech engine, then come back.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            FilledTonalButton(onClick = onInstallVoices) { Text("Install voices") }
            TextButton(onClick = onSpeechSettings) { Text("Speech settings") }
        }
    }
}

@Composable
private fun WorkbookPanel(state: UiState, open: Boolean, onPick: () -> Unit, onReload: () -> Unit) {
    Expandable("Workbook", initiallyOpen = open) {
        state.workbookName?.let { Text(it, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)) }
        state.overview?.let { Muted("${it.languages.term} → ${it.languages.native} · ${it.words} words in ${it.topics.size} topics") }
        Spacer(Modifier.height(6.dp))
        Muted("Save writes your grades into this file (columns L–R only), after keeping a backup copy inside the app " +
            "(${state.backups} so far; the newest 50 are kept). Edited the file elsewhere? Reload it.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
            FilledTonalButton(onClick = onPick) { Text("Choose another…") }
            OutlinedButton(onClick = onReload) { Text("Reload") }
        }
    }
}
