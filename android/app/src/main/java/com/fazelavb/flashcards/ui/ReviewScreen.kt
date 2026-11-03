package com.fazelavb.flashcards.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fazelavb.flashcards.core.Answer
import com.fazelavb.flashcards.core.Back
import com.fazelavb.flashcards.core.CardView
import com.fazelavb.flashcards.core.CheckResult
import com.fazelavb.flashcards.core.ExerciseView
import com.fazelavb.flashcards.core.Kind
import com.fazelavb.flashcards.core.Verdict
import com.fazelavb.flashcards.speech.Speaker

/*
 * One card at a time: the prompt, the exercise, then the answer and the five
 * grade buttons, each showing what it will do (new confidence, next review).
 * A flashcard flips to its answer; an exercise checks your answer and
 * proposes a grade, which you take or override.
 */

@Composable
fun ReviewScreen(
    state: UiState,
    speaker: Speaker,
    speechRevision: Int,
    onReveal: () -> Unit,
    onCheck: (Answer) -> Unit,
    onGrade: (Int) -> Unit,
    onSkip: () -> Unit,
    onEnd: () -> Unit,
    onAutoSpeak: (Boolean) -> Unit,
) {
    val c = state.card ?: return
    val ex = c.exercise
    val res = c.result
    val answered = state.revealed || res != null
    val canSpeak = { lang: String? -> speechRevision >= 0 && speaker.voiceFor(lang) != null }
    val frontIsTerm = ex.kind != Kind.BLANK && c.front.text.isNotEmpty() && c.front.text == c.back.term

    // Pronounce once per card side: the word when it shows, or on the answer side if the card didn't show it.
    LaunchedEffect(c.id, c.turn, answered) {
        if (!state.autoSpeak || ex.kind == Kind.LISTEN && !answered) return@LaunchedEffect
        if (!answered && frontIsTerm) speaker.speak(c.front.text, c.front.lang)
        if (answered && !frontIsTerm) speaker.speak(c.back.term, c.back.termLang)
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp)) {
        val progress by animateFloatAsState(if (c.total == 0) 0f else c.done.toFloat() / c.total, label = "progress")
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
            drawStopIndicator = {})
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp)) {
            Muted("${c.done} / ${c.total} graded" + if (c.repeats > 0) " · ${c.repeats} repeat${if (c.repeats == 1) "" else "s"} queued" else "",
                Modifier.weight(1f))
            val showDir = ex.kind !in setOf(Kind.BLANK, Kind.LISTEN, Kind.SCRAMBLE, Kind.FORMS)
            Muted("${c.topic} · ${ex.kind.label}" + if (showDir) " · ${c.directionLabel}" else "", Modifier.weight(1f).padding(start = 8.dp))
        }

        FlipCard(flipped = ex.kind == Kind.FLASHCARD && state.revealed, key = "${c.id}:${c.turn}") { backSide ->
            Column(Modifier.padding(20.dp).animateContentSize()) {
                if (!backSide) {
                    if (c.front.text.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                            Text(c.front.text, style = MaterialTheme.typography.displaySmall.dir(c.front.rtl), textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f, fill = false))
                            SpeakButton(canSpeak(c.front.lang), { speaker.speak(c.front.text, c.front.lang) })
                        }
                        if (c.front.hint.isNotEmpty()) Text(c.front.hint, style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                    if (ex.kind == Kind.BLANK) BlankSentence(ex, res)
                    MetaChips(c)
                    if (!answered) {
                        Spacer(Modifier.height(16.dp))
                        when (ex.kind) {
                            Kind.CHOICE, Kind.ARTICLE -> Options(ex, null, onPick = { onCheck(Answer.Picked(it)) })
                            Kind.BLANK -> TypedAnswer(c, ex, "The missing word", onCheck)
                            Kind.RECALL -> { Hint(ex); TypedAnswer(c, ex, "The word", onCheck) }
                            Kind.LISTEN -> Listen(c, ex, speaker, onCheck)
                            Kind.FORMS -> Forms(c, ex, onCheck)
                            Kind.SCRAMBLE -> ScrambleBuilder(c, ex, onCheck)
                            Kind.FLASHCARD -> Button(onClick = onReveal, modifier = Modifier.fillMaxWidth().height(56.dp), shape = MaterialTheme.shapes.large) {
                                Text("Show answer", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    } else {
                        if (ex.kind == Kind.CHOICE || ex.kind == Kind.ARTICLE) { Spacer(Modifier.height(12.dp)); Options(ex, res, onPick = {}) }
                        if (res != null && ex.kind == Kind.SCRAMBLE) ScrambleResult(ex, res)
                        if (res != null) { Spacer(Modifier.height(12.dp)); VerdictBox(res, ex.kind) }
                        AnswerDetails(c.back, speaker, canSpeak)
                    }
                } else {
                    // The flashcard's back: the answer, then the details.
                    AnswerDetails(c.back, speaker, canSpeak, top = true)
                    MetaChips(c)
                }
            }
        }

        AnimatedVisibility(answered, enter = fadeIn() + expandVertically()) {
            Column(Modifier.padding(top = 16.dp)) {
                if (res != null) Muted("Suggested grade highlighted: take it, or pick another.", Modifier.padding(bottom = 8.dp))
                GradeButtons(c, res?.suggested, onGrade)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
            TextButton(onClick = onSkip) { Text("Skip") }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                Muted("Auto-speak")
                Spacer(Modifier.width(6.dp))
                Switch(checked = state.autoSpeak, onCheckedChange = onAutoSpeak, modifier = Modifier.graphicsLayer(scaleX = 0.8f, scaleY = 0.8f))
            }
            TextButton(onClick = onEnd) { Text("End session") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** The card, with a quiet stack behind it; turns over (rotationY) to show a flashcard's answer. */
@Composable
private fun FlipCard(flipped: Boolean, key: String, content: @Composable (backSide: Boolean) -> Unit) {
    // Per card: the next card starts face up instead of turning back (which would show its answer).
    val turn = remember(key) { Animatable(if (flipped) 180f else 0f) }
    LaunchedEffect(key, flipped) { turn.animateTo(if (flipped) 180f else 0f, tween(420)) }
    val rotation = turn.value
    val density = LocalDensity.current.density
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        // Two ghost cards peeking out at the top: depth without decoration.
        Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainerHighest, modifier = Modifier
            .matchParentSize().padding(horizontal = 28.dp).offset(y = (-10).dp).alpha(0.6f)) {}
        Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainerHigh, modifier = Modifier
            .matchParentSize().padding(horizontal = 14.dp).offset(y = (-5).dp)) {}
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = cs.surfaceContainerLowest,
            border = BorderStroke(1.dp, cs.outlineVariant),
            shadowElevation = 3.dp,
            modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp).graphicsLayer {
                rotationY = rotation
                cameraDistance = 14f * density
            },
        ) {
            val back = rotation > 90f
            Box(Modifier.graphicsLayer { if (back) rotationY = 180f }) { content(back) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetaChips(c: CardView) {
    val x = LocalExtra.current
    val cs = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        when (c.schedule) {
            "new word" -> Pill(c.schedule, container = x.warnBg, content = x.warn, border = x.newChip)
            "due now" -> Pill(c.schedule, container = cs.primary, content = cs.onPrimary, border = null)
            else -> Pill(c.schedule)
        }
        Pill("confidence ${c.confidence}")
    }
}

/** Multiple choice and der/die/das: numbered options; after checking, the right one green and a wrong pick red. */
@Composable
private fun Options(ex: ExerciseView, res: CheckResult?, onPick: (Int) -> Unit) {
    val x = LocalExtra.current
    val cs = MaterialTheme.colorScheme
    @Composable
    fun option(i: Int, text: String, modifier: Modifier, centered: Boolean) {
        val (bg, border) = when {
            res == null -> cs.surfaceContainerLowest to cs.outlineVariant
            text == res.expected -> x.okBg to x.ok
            i == res.picked -> x.errBg to x.err
            else -> cs.surfaceContainerLowest to cs.outlineVariant
        }
        val dim = res != null && text != res.expected && i != res.picked
        Surface(
            onClick = { onPick(i) }, enabled = res == null, shape = MaterialTheme.shapes.medium, color = bg,
            border = BorderStroke(if (res != null && border != cs.outlineVariant) 2.dp else 1.dp, border),
            modifier = modifier.alpha(if (dim) 0.55f else 1f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
                if (!centered) {
                    Box(Modifier.size(26.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                        Surface(color = cs.primaryContainer, shape = CircleShape, modifier = Modifier.size(26.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = cs.onPrimaryContainer)
                            }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                }
                Text(text, style = (if (centered) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge).dir(ex.rtl),
                    modifier = if (centered) Modifier else Modifier.weight(1f))
            }
        }
    }
    if (ex.kind == Kind.ARTICLE) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ex.options.forEachIndexed { i, t -> option(i, t, Modifier.weight(1f), centered = true) }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ex.options.forEachIndexed { i, t -> option(i, t, Modifier.fillMaxWidth(), centered = false) }
        }
    }
}

/** Fill in the blank: the sentence with a gap (or, once checked, with the answer marked). */
@Composable
private fun BlankSentence(ex: ExerciseView, res: CheckResult?) {
    val x = LocalExtra.current
    val gap = "_".repeat(ex.answerLen.coerceIn(4, 14))
    val text = buildAnnotatedString {
        append(ex.before)
        if (res == null) withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)) { append(gap) }
        else withStyle(SpanStyle(color = x.ok, background = x.okBg, fontWeight = FontWeight.Bold)) { append(res.expected) }
        append(ex.after)
    }
    Text(text, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = WordFont, lineHeight = 36.sp).dir(ex.rtl),
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
    if (res == null) Hint(ex)
}

@Composable
private fun Hint(ex: ExerciseView) {
    if (ex.hint.isNotEmpty()) Text(ex.hint, style = MaterialTheme.typography.bodyMedium.dir(ex.hintRtl),
        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    if (ex.hintExample.isNotEmpty()) Text(ex.hintExample, style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic).dir(ex.hintRtl),
        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
}

/** A typed answer: Enter (Done on the keyboard) checks it. No autocorrect: it would "fix" the very mistakes being tested. */
@Composable
private fun TypedAnswer(c: CardView, ex: ExerciseView, label: String, onCheck: (Answer) -> Unit) {
    var text by rememberSaveable(c.id, c.turn) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(c.id, c.turn) { runCatching { focus.requestFocus() } }
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = text, onValueChange = { text = it }, singleLine = true, label = { Text(label) },
        textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = WordFont, textAlign = TextAlign.Center).dir(ex.rtl),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onCheck(Answer.Typed(text)) }),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    Spacer(Modifier.height(12.dp))
    Button(onClick = { onCheck(Answer.Typed(text)) }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.large) {
        Text("Check", style = MaterialTheme.typography.titleMedium)
    }
}

/** Listening: the word plays once when the card appears (the sound IS the question), then on demand. */
@Composable
private fun Listen(c: CardView, ex: ExerciseView, speaker: Speaker, onCheck: (Answer) -> Unit) {
    LaunchedEffect(c.id, c.turn) {
        kotlinx.coroutines.delay(250)
        speaker.speak(ex.say, ex.lang)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { speaker.speak(ex.say, ex.lang) }) { Text("🔊  Play again") }
        OutlinedButton(onClick = { speaker.speak(ex.say, ex.lang, rate = 0.55f) }) { Text("🐢  Slow") }
    }
    Spacer(Modifier.height(6.dp))
    Hint(ex)
    TypedAnswer(c, ex, "What you heard", onCheck)
}

/** Forms drill: "der Stift → Plural", type the form. */
@Composable
private fun Forms(c: CardView, ex: ExerciseView, onCheck: (Answer) -> Unit) {
    val text = buildAnnotatedString {
        append(ex.prompt)
        append("  →  ")
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)) { append(ex.label) }
    }
    Text(text, style = MaterialTheme.typography.titleMedium.dir(ex.rtl), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    TypedAnswer(c, ex, ex.label, onCheck)
}

/** Sentence scramble: tap the tiles in order; tap a placed tile to put it back. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScrambleBuilder(c: CardView, ex: ExerciseView, onCheck: (Answer) -> Unit) {
    val placed = remember(c.id, c.turn) { mutableStateListOf<Int>() }
    val cs = MaterialTheme.colorScheme
    Hint(ex.copy(hintExample = ""))
    Surface(shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp), color = cs.primaryContainer.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp).heightIn(min = 56.dp)) {
        if (placed.isEmpty()) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(16.dp)) { Muted("Tap the words in order") }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(8.dp)) {
                placed.forEachIndexed { k, i -> Tile(ex.tiles[i], ex.rtl) { placed.removeAt(k) } }
                if (placed.size == ex.tiles.size && ex.end.isNotEmpty()) {
                    Text(ex.end, style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()) {
        ex.tiles.forEachIndexed { i, word ->
            if (i in placed) Box(Modifier.alpha(0f)) { Tile(word, ex.rtl) {} } else Tile(word, ex.rtl) { placed.add(i) }
        }
    }
    Spacer(Modifier.height(14.dp))
    val left = ex.tiles.size - placed.size
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { if (placed.isNotEmpty()) placed.removeAt(placed.lastIndex) }, enabled = placed.isNotEmpty(),
            modifier = Modifier.height(52.dp)) { Text("Undo") }
        Button(onClick = { onCheck(Answer.Ordered(placed.toList())) }, enabled = left == 0,
            modifier = Modifier.weight(1f).height(52.dp), shape = MaterialTheme.shapes.large) {
            Text(if (left == 0) "Check" else "$left word${if (left == 1) "" else "s"} left", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun Tile(word: String, rtl: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = MaterialTheme.shapes.small, color = cs.surfaceContainerLowest,
        border = BorderStroke(1.dp, cs.outline), shadowElevation = 1.dp) {
        Text(word, style = MaterialTheme.typography.titleMedium.copy(fontFamily = WordFont, fontWeight = FontWeight.Normal).dir(rtl),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@Composable
private fun ScrambleResult(ex: ExerciseView, res: CheckResult) {
    val x = LocalExtra.current
    Spacer(Modifier.height(10.dp))
    Text(res.expected, style = MaterialTheme.typography.titleLarge.copy(fontFamily = WordFont, color = x.ok).dir(ex.rtl),
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    if (res.verdict != Verdict.CORRECT && res.given != null) {
        Muted("Yours: ${res.given}", Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

@Composable
private fun VerdictBox(res: CheckResult, kind: Kind) {
    val bold = SpanStyle(fontWeight = FontWeight.Bold)
    when {
        res.verdict == Verdict.CORRECT -> Message(MsgKind.OK) { Text("✓ Correct") }
        kind == Kind.SCRAMBLE -> if (res.verdict == Verdict.CLOSE) Message(MsgKind.WARN) { Text("≈ Almost: two neighbouring words are swapped.") }
            else Message(MsgKind.ERR) { Text("✗ Not quite: compare your order with the sentence above.") }
        res.verdict == Verdict.CLOSE && kind == Kind.FORMS -> Message(MsgKind.WARN) {
            Text(buildAnnotatedString {
                append("≈ Almost. You wrote "); withStyle(bold) { append(res.given ?: "") }
                append(", the form is "); withStyle(bold) { append(res.expected) }; append(".")
            })
        }
        res.verdict == Verdict.CLOSE -> Message(MsgKind.WARN) {
            Text(buildAnnotatedString {
                append("≈ Almost: check the spelling. You wrote "); withStyle(bold) { append(res.given ?: "") }
                append(if (kind == Kind.BLANK) ", the sentence has " else ", the word is "); withStyle(bold) { append(res.expected) }; append(".")
            })
        }
        else -> Message(MsgKind.ERR) {
            Text(buildAnnotatedString {
                append("✗ Not quite. The answer is "); withStyle(bold) { append(res.expected) }
                if (!res.given.isNullOrBlank()) { append(" (you wrote "); withStyle(bold) { append(res.given) }; append(")") }
                append(".")
            })
        }
    }
}

/** The answer side: the word, its meaning, forms, example, related words, notes. */
@Composable
private fun AnswerDetails(b: Back, speaker: Speaker, canSpeak: (String?) -> Boolean, top: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = if (top) 0.dp else 16.dp)) {
        if (!top) HorizontalDivider(color = cs.outlineVariant)
        Spacer(Modifier.height(if (top) 0.dp else 12.dp))
        if (!b.article.isNullOrEmpty()) Muted("${b.articleLabel}: ${b.article}")
        WordLine(b.term, b.termRtl, MaterialTheme.typography.headlineMedium.copy(fontFamily = WordFont, fontWeight = FontWeight.SemiBold),
            canSpeak(b.termLang)) { speaker.speak(b.term, b.termLang) }
        if (!b.pronunciation.isNullOrEmpty()) Text(b.pronunciation, style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
            color = cs.onSurfaceVariant)
        Label(b.nativeLanguage)
        WordLine(b.meaning, b.nativeRtl, MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal),
            canSpeak(b.nativeLang)) { speaker.speak(b.meaning, b.nativeLang) }
        val meta = listOfNotNull(b.wordType, b.forms?.let { "${b.formsLabel}: $it" })
        if (meta.isNotEmpty()) Muted(meta.joinToString(" · "), Modifier.padding(top = 4.dp))
        if (!b.exampleTerm.isNullOrEmpty()) {
            Label("Example")
            Quote(b.exampleTerm, b.termRtl, italic = false, canSpeak(b.termLang)) { speaker.speak(b.exampleTerm, b.termLang) }
            if (!b.exampleMeaning.isNullOrEmpty()) {
                Quote(b.exampleMeaning, b.nativeRtl, italic = true, canSpeak(b.nativeLang)) { speaker.speak(b.exampleMeaning, b.nativeLang) }
            }
        }
        if (!b.synonyms.isNullOrEmpty()) {
            Label("Related")
            Text(b.synonyms, style = MaterialTheme.typography.bodyLarge.dir(b.termRtl))
        }
        if (!b.notes.isNullOrEmpty()) {
            Label("Notes")
            Text(b.notes, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun WordLine(text: String, rtl: Boolean, style: androidx.compose.ui.text.TextStyle, speakable: Boolean, onSpeak: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = style.dir(rtl), modifier = Modifier.weight(1f))
        SpeakButton(speakable, onSpeak)
    }
}

@Composable
private fun Quote(text: String, rtl: Boolean, italic: Boolean, speakable: Boolean, onSpeak: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Surface(color = cs.primaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = WordFont,
                fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal).dir(rtl),
                color = if (italic) cs.onSurfaceVariant else cs.onSurface, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }
        SpeakButton(speakable, onSpeak)
    }
}

/** 1-5, red to green; each shows the confidence and next review it would set. 3 + 2 on a phone, as in the web app. */
@Composable
private fun GradeButtons(c: CardView, suggested: Int?, onGrade: (Int) -> Unit) {
    val x = LocalExtra.current
    @Composable
    fun grade(i: Int, modifier: Modifier) {
        val g = c.grades[i]
        val color = x.grades[i]
        val on = suggested == g.grade
        Surface(
            onClick = { onGrade(g.grade) }, shape = MaterialTheme.shapes.medium,
            color = if (on) color else MaterialTheme.colorScheme.surfaceContainerLowest,
            contentColor = if (on) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(2.dp, color), shadowElevation = if (on) 4.dp else 0.dp, modifier = modifier,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp)) {
                Text("${g.grade}", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = if (on) Color.Unspecified else color)
                Text(g.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                Text("→ ${g.confidence} · ${g.next.removePrefix("next review ")}", style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center, modifier = Modifier.alpha(0.8f), maxLines = 2)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { for (i in 0..2) grade(i, Modifier.weight(1f)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(0.5f))
            for (i in 3..4) grade(i, Modifier.weight(1f))
            Spacer(Modifier.weight(0.5f))
        }
    }
}
