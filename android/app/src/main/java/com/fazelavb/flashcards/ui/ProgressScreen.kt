package com.fazelavb.flashcards.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fazelavb.flashcards.core.MemoryBreakdown
import com.fazelavb.flashcards.core.MemoryStage
import com.fazelavb.flashcards.core.SessionRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Progress, all computed on the phone: how firmly the words are held (from the
 * workbook's FSRS schedules), how the last sessions went, and time spent.
 * Charts are drawn here with Canvas: no chart library, nothing fetched.
 * Tap a bar or point for its exact value; "Show numbers" lists them all.
 */

private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

@Composable
fun ProgressScreen(p: ProgressView) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HeadlineTiles(p)
        p.breakdown?.let { MemorySection(it) }
        SessionsSection(p.history)
        TimeSection(p)
        Muted("Everything here is worked out on this phone from your workbook and the app's own history. " +
            "The app has no internet permission, so none of it can leave the phone.",
            Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(16.dp))
    }
}

// ------------------------------------------------------------------ headline

@Composable
private fun HeadlineTiles(p: ProgressView) {
    val b = p.breakdown
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        StatTile("Long-term", b?.counts?.get(MemoryStage.LONG_TERM)?.toString() ?: "—",
            b?.let { "of ${it.total} words" } ?: "", Modifier.weight(1f), hero = true)
        StatTile("Recall now", b?.recallNow?.let { "${(it * 100).roundToInt()}%" } ?: "—", "estimated", Modifier.weight(1f))
        StatTile("Streak", p.summary.streakDays.toString(), if (p.summary.streakDays == 1) "day" else "days", Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(title: String, value: String, detail: String, modifier: Modifier, hero: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, color = if (hero) cs.primary else cs.surfaceContainer,
        contentColor = if (hero) cs.onPrimary else cs.onSurface, modifier = modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = if (hero) cs.onPrimary else cs.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = if (hero) cs.onPrimary else cs.primary)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = if (hero) cs.onPrimary else cs.onSurfaceVariant, maxLines = 1)
        }
    }
}

// ------------------------------------------------------------------ memory

@Composable
private fun MemorySection(b: MemoryBreakdown) {
    val stageColors = LocalExtra.current.stages
    Section {
        SectionTitle("Where your words are")
        Muted("From each word's spaced-repetition schedule. A word moves right as you keep recalling it.")
        Spacer(Modifier.height(12.dp))
        StageBar(MemoryStage.entries.map { b.counts[it] ?: 0 }, stageColors, Modifier.fillMaxWidth().height(28.dp))
        Spacer(Modifier.height(10.dp))
        MemoryStage.entries.forEachIndexed { i, st ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(stageColors[i]))
                Spacer(Modifier.width(8.dp))
                Text(st.label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.width(92.dp))
                Muted(st.help, Modifier.weight(1f))
                Text("${b.counts[st] ?: 0}", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            }
        }
        if (b.byTopic.size > 1) {
            Spacer(Modifier.height(14.dp))
            Muted("By topic")
            b.byTopic.forEach { (topic, counts) ->
                val total = counts.values.sum()
                val long = counts[MemoryStage.LONG_TERM] ?: 0
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text(topic, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(1f))
                    Muted("$long / $total long-term")
                }
                StageBar(MemoryStage.entries.map { counts[it] ?: 0 }, stageColors, Modifier.fillMaxWidth().height(10.dp).padding(top = 3.dp))
            }
        }
    }
}

/** One whole split into its stages, left to right, with a 2dp surface gap between parts. */
@Composable
private fun StageBar(values: List<Int>, colors: List<Color>, modifier: Modifier) {
    val total = values.sum()
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier) {
        val r = CornerRadius(4.dp.toPx())
        if (total == 0) {
            drawRoundRect(track, cornerRadius = r)
            return@Canvas
        }
        val gap = 2.dp.toPx()
        val parts = values.count { it > 0 }
        val usable = size.width - gap * (parts - 1)
        var x = 0f
        values.forEachIndexed { i, v ->
            if (v == 0) return@forEachIndexed
            val w = usable * v / total
            drawRoundRect(colors[i], topLeft = Offset(x, 0f), size = Size(w, size.height), cornerRadius = r)
            x += w + gap
        }
    }
}

// ------------------------------------------------------------------ sessions

@Composable
private fun SessionsSection(history: List<SessionRecord>) {
    var range by rememberSaveable { mutableStateOf(10) }  // 0 = all
    Section {
        SectionTitle("Practice sessions")
        if (history.isEmpty()) {
            Muted("No sessions yet. Finish a session (save or discard it) and it shows up here.")
            return@Section
        }
        val ranges = listOf(10, 30, 0)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            ranges.forEachIndexed { i, n ->
                SegmentedButton(selected = range == n, onClick = { range = n }, icon = {},
                    shape = SegmentedButtonDefaults.itemShape(i, ranges.size)) { Text(if (n == 0) "All" else "Last $n") }
            }
        }
        val shown = if (range == 0) history else history.takeLast(range)
        val zone = ZoneId.systemDefault()
        fun day(r: SessionRecord) = DAY.format(Instant.ofEpochMilli(r.endedAt).atZone(zone))

        Spacer(Modifier.height(6.dp))
        Text("Average grade per session", style = MaterialTheme.typography.titleSmall)
        Muted("1 needs work … 5 mastered. Rising means the words are sticking.")
        TappableChart(
            labels = shown.map { r -> "${day(r)} · avg ${"%.1f".format(r.avgGrade)} · ${r.words} words · ${(r.recalledShare * 100).roundToInt()}% recalled" +
                if (r.saved) "" else " · not saved" },
        ) { selected, mod -> LineChart(shown.map { it.avgGrade }, 1.0, 5.0, selected, mod) }

        Spacer(Modifier.height(14.dp))
        Text("Minutes per session", style = MaterialTheme.typography.titleSmall)
        TappableChart(labels = shown.map { r -> "${day(r)} · ${minutes(r.activeSeconds / 60.0)} · ${r.exercise}" }) { selected, mod ->
            BarChart(shown.map { it.activeSeconds / 60.0 }, selected, mod)
        }

        NumbersTable(listOf("Date", "Avg", "Words", "Min")) {
            shown.reversed().map { r -> listOf(day(r), "%.1f".format(r.avgGrade), "${r.words}", "%.0f".format(r.activeSeconds / 60.0)) }
        }
    }
}

// ------------------------------------------------------------------ time

@Composable
private fun TimeSection(p: ProgressView) {
    Section {
        SectionTitle("Time in the app")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            Column {
                Muted("Per session")
                Text(p.summary.avgSessionMinutes?.let(::minutes) ?: "—", style = MaterialTheme.typography.titleLarge)
            }
            Column {
                Muted("Per day you used it")
                Text(p.summary.avgDailyMinutes?.let(::minutes) ?: "—", style = MaterialTheme.typography.titleLarge)
            }
            Column {
                Muted("Sessions")
                Text("${p.summary.sessions}", style = MaterialTheme.typography.titleLarge)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Last 14 days", style = MaterialTheme.typography.titleSmall)
        TappableChart(labels = p.daily.map { (d, m) -> "${DAY.format(d)} · ${minutes(m)}" }) { selected, mod ->
            BarChart(p.daily.map { it.second }, selected, mod)
        }
        Muted("Counted while the app is on screen, at most an hour at a time.")
        NumbersTable(listOf("Day", "Minutes")) { p.daily.reversed().map { (d, m) -> listOf(DAY.format(d), "%.0f".format(m)) } }
    }
}

// ------------------------------------------------------------------ chart pieces

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.padding(bottom = 4.dp))
}

private fun minutes(m: Double): String = when {
    m < 1 -> "${(m * 60).roundToInt()} s"
    m < 60 -> "${m.roundToInt()} min"
    else -> "${(m / 60).toInt()} h ${(m % 60).roundToInt()} min"
}

/** A chart that shows the tapped item's details above it (the touch version of a tooltip). */
@Composable
private fun TappableChart(labels: List<String>, chart: @Composable (selected: Int?, Modifier) -> Unit) {
    var selected by remember(labels.size) { mutableStateOf<Int?>(null) }
    Text(selected?.let { labels.getOrNull(it) } ?: "Tap the chart for details",
        style = MaterialTheme.typography.labelMedium,
        color = if (selected != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
    // The modifier goes on the plot area only, so a tap's x maps straight onto the items.
    chart(selected, Modifier.pointerInput(labels.size) {
        detectTapGestures { pos ->
            if (labels.isEmpty()) return@detectTapGestures
            val i = ((pos.x / size.width) * labels.size).toInt().coerceIn(0, labels.size - 1)
            selected = if (selected == i) null else i
        }
    })
}

/** Recessive horizontal guides at fixed fractions of the height. */
private fun DrawScope.grid(color: Color, lines: Int = 4) {
    for (k in 0..lines) {
        val y = size.height * k / lines
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
    }
}

/** One series over sessions: 2dp line, 8dp points, the tapped point ringed. */
@Composable
private fun LineChart(values: List<Double>, min: Double, max: Double, selected: Int?, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surfaceContainer
    Row(Modifier.fillMaxWidth().height(140.dp)) {
        Column(Modifier.width(24.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Muted("5"); Muted("3"); Muted("1")
        }
        Canvas(Modifier.weight(1f).fillMaxHeight().then(modifier)) {
            grid(gridColor)
            if (values.isEmpty()) return@Canvas
            val step = size.width / values.size
            fun pt(i: Int) = Offset(step * (i + 0.5f), (size.height * (1 - (values[i] - min) / (max - min))).toFloat())
            if (values.size > 1) {
                val path = Path().apply { values.indices.forEach { i -> if (i == 0) moveTo(pt(i).x, pt(i).y) else lineTo(pt(i).x, pt(i).y) } }
                drawPath(path, line, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            values.indices.forEach { i ->
                val r = if (i == selected) 6.dp.toPx() else 4.dp.toPx()
                drawCircle(surface, radius = r + 2.dp.toPx(), center = pt(i))  // 2dp surface ring
                drawCircle(line, radius = r, center = pt(i))
            }
        }
    }
}

/** Bars from a zero baseline with rounded tops; the tapped bar full colour, the rest a little lighter. */
@Composable
private fun BarChart(values: List<Double>, selected: Int?, modifier: Modifier) {
    val bar = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val top = (values.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    Row(Modifier.fillMaxWidth().height(140.dp)) {
        Column(Modifier.width(24.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Muted("%.0f".format(top)); Muted("0")
        }
        Canvas(Modifier.weight(1f).fillMaxHeight().then(modifier)) {
            grid(gridColor)
            if (values.isEmpty()) return@Canvas
            val slot = size.width / values.size
            val w = (slot - 2.dp.toPx()).coerceAtLeast(1f).coerceAtMost(28.dp.toPx())
            values.forEachIndexed { i, v ->
                val h = (size.height * v / top).toFloat()
                if (h <= 0f) return@forEachIndexed
                val x = slot * i + (slot - w) / 2
                val color = if (selected == null || selected == i) bar else bar.copy(alpha = 0.55f)
                val r = minOf(4.dp.toPx(), w / 2, h / 2)
                // Rounded top, square base: a round rect plus a square patch over its bottom corners.
                drawRoundRect(color, topLeft = Offset(x, size.height - h), size = Size(w, h), cornerRadius = CornerRadius(r))
                drawRect(color, topLeft = Offset(x, size.height - minOf(h, r)), size = Size(w, minOf(h, r)))
            }
        }
    }
}

/** The values as a table, folded away by default (for exact numbers, and when colours are hard to tell apart). */
@Composable
private fun NumbersTable(header: List<String>, rows: () -> List<List<String>>) {
    var open by rememberSaveable(header.joinToString()) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(if (open) "Hide numbers" else "Show numbers") }
    if (!open) return
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { header.forEach { Muted(it, Modifier.weight(1f)) } }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    rows().forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            row.forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)) }
        }
    }
}
