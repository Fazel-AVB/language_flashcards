package com.fazelavb.flashcards.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fazelavb.flashcards.data.ThemeChoice

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            val dark = isDark(state.theme, isSystemInDarkTheme())
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            FlashcardsTheme(state.theme) { App(vm, state) }
        }
    }
}

private val XLSX_TYPES = arrayOf(
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-excel.sheet.macroEnabled.12",
    // Some storage apps label an .xlsx generically.
    "application/octet-stream",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(vm: AppViewModel, state: UiState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val speechRevision by vm.speaker.revision.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::chooseWorkbook) }
    var confirmEnd by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    // Back from another app (voices installed, workbook edited): re-read voices and the file.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.appVisible()
        vm.speaker.refresh()
        if (state.stage == Stage.SETUP) vm.reload()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.appHidden() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.speaker.stop() }

    LaunchedEffect(state.message) {
        val text = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(text, withDismissAction = true,
            duration = if (state.messageIsError) SnackbarDuration.Long else SnackbarDuration.Short)
        vm.clearMessage()
    }

    BackHandler(enabled = state.stage == Stage.REVIEW) { confirmEnd = true }
    BackHandler(enabled = state.stage == Stage.SUMMARY) { confirmDiscard = true }
    BackHandler(enabled = state.stage == Stage.SAVED) { vm.backToStart() }
    BackHandler(enabled = state.stage == Stage.PROGRESS) { vm.closeProgress() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val pair = state.overview?.languages
                    Text(if (state.stage == Stage.PROGRESS) "Your progress" else if (pair != null) "${pair.term} Flashcards" else "Flashcards", maxLines = 1)
                },
                navigationIcon = {
                    if (state.stage == Stage.PROGRESS) IconButton(onClick = vm::closeProgress) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.stage == Stage.SETUP && state.hasWorkbook) IconButton(onClick = vm::openProgress) {
                        Icon(ChartIcon, contentDescription = "Your progress")
                    }
                    ThemeMenu(state.theme, vm::setTheme)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
            // Phones fill the width; tablets and landscape get a readable column, as in the web page.
            Box(Modifier.widthIn(max = 720.dp)) {
                AnimatedContent(targetState = state.stage, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "stage") { stage ->
                    when (stage) {
                        Stage.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        Stage.SETUP -> SetupScreen(
                            state, vm.speaker, speechRevision,
                            onPickWorkbook = { picker.launch(XLSX_TYPES) },
                            onDeck = vm::updateDeck, onStart = vm::start,
                            onAutoSpeak = vm::setAutoSpeak, onOnlineVoices = vm::setOnlineVoices,
                            onReload = { vm.reload() },
                            onInstallVoices = { runCatching { context.startActivity(vm.speaker.installVoicesIntent()) }
                                .onFailure { vm.flash("No speech engine settings found on this phone.", true) } },
                            onSpeechSettings = { runCatching { context.startActivity(vm.speaker.settingsIntent()) }
                                .onFailure { vm.flash("Open Settings › Accessibility › Text-to-speech output.", true) } },
                        )
                        Stage.REVIEW -> ReviewScreen(
                            state, vm.speaker, speechRevision,
                            onReveal = vm::reveal, onCheck = vm::check, onGrade = vm::grade,
                            onSkip = vm::skip, onEnd = { confirmEnd = true }, onAutoSpeak = vm::setAutoSpeak,
                        )
                        Stage.SUMMARY -> state.summary?.let {
                            SummaryScreen(it, state.workbookName, state.busy, onSave = vm::save, onDiscard = {
                                if (it.rows.isEmpty()) vm.discard() else { confirmDiscard = true }
                            })
                        }
                        Stage.SAVED -> state.saved?.let { SavedScreen(it, onBack = vm::backToStart) }
                        Stage.PROGRESS -> state.progress?.let { ProgressScreen(it) }
                    }
                }
            }
        }
    }

    if (confirmEnd) AlertDialog(
        onDismissRequest = { confirmEnd = false },
        title = { Text("End this session?") },
        text = { Text("You'll see the summary of the words graded so far. Nothing is written until you press Save there.") },
        confirmButton = { TextButton(onClick = { confirmEnd = false; vm.end() }) { Text("End session") } },
        dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep going") } },
    )
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("Throw away this session's grades?") },
        text = { Text("The workbook stays as it was.") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; vm.discard() }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Cancel") } },
    )
}

@Composable
private fun ThemeMenu(current: ThemeChoice, onPick: (ThemeChoice) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(PaletteIcon, contentDescription = "Colour theme") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ThemeChoice.entries.forEach { t ->
                DropdownMenuItem(
                    text = { Text(t.label) },
                    leadingIcon = { RadioButton(selected = t == current, onClick = null) },
                    onClick = { onPick(t); open = false },
                )
            }
        }
    }
}
