package com.fazelavb.flashcards.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp

/** Material's "volume up", drawn here so the large extended-icons library isn't needed. */
val SpeakerIcon: ImageVector by lazy {
    ImageVector.Builder("speaker", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(3f, 9f); verticalLineToRelative(6f); horizontalLineToRelative(4f); lineToRelative(5f, 5f)
            verticalLineTo(4f); lineTo(7f, 9f); horizontalLineTo(3f); close()
            moveTo(16.5f, 12f)
            curveToRelative(0f, -1.77f, -1.02f, -3.29f, -2.5f, -4.03f); verticalLineToRelative(8.05f)
            curveToRelative(1.48f, -0.73f, 2.5f, -2.25f, 2.5f, -4.02f); close()
            moveTo(14f, 3.23f); verticalLineToRelative(2.06f)
            curveToRelative(2.89f, 0.86f, 5f, 3.54f, 5f, 6.71f); reflectiveCurveToRelative(-2.11f, 5.85f, -5f, 6.71f)
            verticalLineToRelative(2.06f)
            curveToRelative(4.01f, -0.91f, 7f, -4.49f, 7f, -8.77f); reflectiveCurveToRelative(-2.99f, -7.86f, -7f, -8.77f); close()
        }
    }.build()
}

/** Material's "palette", for the theme menu. */
val PaletteIcon: ImageVector by lazy {
    ImageVector.Builder("palette", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 3f)
            curveToRelative(-4.97f, 0f, -9f, 4.03f, -9f, 9f); reflectiveCurveToRelative(4.03f, 9f, 9f, 9f)
            curveToRelative(0.83f, 0f, 1.5f, -0.67f, 1.5f, -1.5f)
            curveToRelative(0f, -0.39f, -0.15f, -0.74f, -0.39f, -1.01f)
            curveToRelative(-0.23f, -0.26f, -0.38f, -0.61f, -0.38f, -0.99f)
            curveToRelative(0f, -0.83f, 0.67f, -1.5f, 1.5f, -1.5f); horizontalLineTo(16f)
            curveToRelative(2.76f, 0f, 5f, -2.24f, 5f, -5f)
            curveToRelative(0f, -4.42f, -4.03f, -8f, -9f, -8f); close()
            moveTo(6.5f, 12f)
            curveToRelative(-0.83f, 0f, -1.5f, -0.67f, -1.5f, -1.5f); reflectiveCurveTo(5.67f, 9f, 6.5f, 9f)
            reflectiveCurveTo(8f, 9.67f, 8f, 10.5f); reflectiveCurveTo(7.33f, 12f, 6.5f, 12f); close()
            moveTo(9.5f, 8f)
            curveTo(8.67f, 8f, 8f, 7.33f, 8f, 6.5f); reflectiveCurveTo(8.67f, 5f, 9.5f, 5f)
            reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f); reflectiveCurveTo(10.33f, 8f, 9.5f, 8f); close()
            moveTo(14.5f, 8f)
            curveToRelative(-0.83f, 0f, -1.5f, -0.67f, -1.5f, -1.5f); reflectiveCurveTo(13.67f, 5f, 14.5f, 5f)
            reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f); reflectiveCurveTo(15.33f, 8f, 14.5f, 8f); close()
            moveTo(17.5f, 12f)
            curveToRelative(-0.83f, 0f, -1.5f, -0.67f, -1.5f, -1.5f); reflectiveCurveTo(16.67f, 9f, 17.5f, 9f)
            reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f); reflectiveCurveToRelative(-0.67f, 1.5f, -1.5f, 1.5f); close()
        }
    }.build()
}

/** Material's "bar chart", for the Progress button. */
val ChartIcon: ImageVector by lazy {
    ImageVector.Builder("chart", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(5f, 9.2f); horizontalLineToRelative(3f); verticalLineTo(19f); horizontalLineTo(5f); close()
            moveTo(10.6f, 5f); horizontalLineToRelative(2.8f); verticalLineToRelative(14f); horizontalLineToRelative(-2.8f); close()
            moveTo(16.2f, 13f); horizontalLineTo(19f); verticalLineToRelative(6f); horizontalLineToRelative(-2.8f); close()
        }
    }.build()
}

/** Right-to-left languages need an explicit direction, or punctuation lands at the wrong end. */
fun TextStyle.dir(rtl: Boolean) = copy(textDirection = if (rtl) TextDirection.Rtl else TextDirection.Content)

/** 🔊 next to a word; disabled (with the reason in the description) when no voice speaks its language. */
@Composable
fun SpeakButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(SpeakerIcon, contentDescription = if (enabled) "Pronounce" else "No voice for this language",
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, container: Color = MaterialTheme.colorScheme.surfaceContainer,
         content: Color = MaterialTheme.colorScheme.onPrimaryContainer, border: Color? = MaterialTheme.colorScheme.outlineVariant) {
    Surface(shape = CircleShape, color = container, contentColor = content,
        border = border?.let { BorderStroke(1.dp, it) }, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

/** A rounded surface section of the start screen. */
@Composable
fun Section(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

/** A section that opens on tap: topics, pronunciation, workbook. */
@Composable
fun Expandable(title: String, initiallyOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f))
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = if (open) "Collapse" else "Expand", modifier = Modifier.rotate(turn))
            }
            AnimatedVisibility(open) {
                Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp), content = content)
            }
        }
    }
}

enum class MsgKind { OK, WARN, ERR, INFO }

/** A message box: verdicts, warnings. */
@Composable
fun Message(kind: MsgKind, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val x = LocalExtra.current
    val (bg, fg) = when (kind) {
        MsgKind.OK -> x.okBg to x.ok
        MsgKind.WARN -> x.warnBg to x.warn
        MsgKind.ERR -> x.errBg to x.err
        MsgKind.INFO -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
    }
    Surface(shape = RoundedCornerShape(14.dp), color = bg, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(4.dp, 20.dp).background(fg, CircleShape))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(top = 12.dp, bottom = 2.dp))
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

val Gap = Arrangement.spacedBy(10.dp)
