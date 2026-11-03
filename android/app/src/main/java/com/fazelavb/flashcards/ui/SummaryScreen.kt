package com.fazelavb.flashcards.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fazelavb.flashcards.core.Summary

/** Every graded word, old -> new confidence and next review; nothing is written until Save. */
@Composable
fun SummaryScreen(summary: Summary, workbookName: String?, busy: Boolean, onSave: () -> Unit, onDiscard: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Section {
            Text("Session summary", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(6.dp))
            if (summary.rows.isEmpty()) {
                Text("No words were graded, so there is nothing to save.")
                Spacer(Modifier.height(14.dp))
                Button(onClick = onDiscard, modifier = Modifier.fillMaxWidth()) { Text("Back to start") }
                return@Section
            }
            val n = summary.rows.size
            Muted("$n word${if (n == 1) "" else "s"} graded; ${summary.cells} cell${if (summary.cells == 1) "" else "s"} will change in " +
                "${workbookName ?: "the workbook"}. A backup is made first.")
            Spacer(Modifier.height(10.dp))
            summary.rows.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.term, style = MaterialTheme.typography.titleMedium.copy(fontFamily = WordFont).dir(r.termRtl))
                        Text(r.meaning, style = MaterialTheme.typography.bodyMedium.dir(r.meaningRtl), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Muted("${r.topic} · grades ${r.grades.joinToString(", ")}")
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(if (r.old == r.new) r.new else "${r.old} → ${r.new}",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                        Muted(r.next)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Button(onClick = onSave, enabled = !busy, modifier = Modifier.fillMaxWidth().height(56.dp), shape = MaterialTheme.shapes.large) {
                if (busy) CircularProgressIndicator(Modifier.height(22.dp), strokeWidth = 2.dp)
                else Text("Save to workbook", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onDiscard, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Discard") }
        }
    }
}

@Composable
fun SavedScreen(saved: SavedInfo, onBack: () -> Unit) {
    Column(Modifier.padding(16.dp)) {
        Section {
            Text("Saved", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(8.dp))
            Text("Wrote ${saved.words} word${if (saved.words == 1) "" else "s"} (${saved.cells} cells) to ${saved.sheets.joinToString(", ")}.")
            saved.warning?.let { Spacer(Modifier.height(8.dp)); Message(MsgKind.WARN) { Text(it) } }
            Spacer(Modifier.height(6.dp))
            Muted("Backup kept in the app: ${saved.backup}")
            Spacer(Modifier.height(16.dp))
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Back to start") }
        }
    }
}
