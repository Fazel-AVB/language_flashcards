package com.fazelavb.flashcards.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fazelavb.flashcards.data.ThemeChoice

/*
 * The web app's three palettes (style.css): teal Light, Dark and warm Sepia
 * for long sessions, with "System" following the phone's dark mode. Calm
 * surfaces so nothing competes with the word; colour is kept for meaning:
 * the five grade buttons run red -> green, right/wrong answers are green/red.
 */

@Immutable
data class Extra(
    val grades: List<Color>,  // 1..5
    val ok: Color, val okBg: Color,
    val err: Color, val errBg: Color,
    val warn: Color, val warnBg: Color,
    val newChip: Color,
    // Memory stages New -> Learning -> Short-term -> Long-term: one teal, ordered from faint to
    // strong (validated as an ordinal ramp against each theme's card surface).
    val stages: List<Color>,
)

val LocalExtra = staticCompositionLocalOf { lightExtra }

private val light = lightColorScheme(
    primary = Color(0xFF0F766E), onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1), onPrimaryContainer = Color(0xFF134E4A),
    secondary = Color(0xFF47706B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2EF), onSecondaryContainer = Color(0xFF134E4A),
    tertiary = Color(0xFFB45309), tertiaryContainer = Color(0xFFFEF3C7), onTertiaryContainer = Color(0xFF92400E),
    background = Color(0xFFF0FDFA), onBackground = Color(0xFF042F2E),
    surface = Color(0xFFF0FDFA), onSurface = Color(0xFF042F2E),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7FEFC),
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFE6F6F3), surfaceContainerHighest = Color(0xFFDDF1ED),
    surfaceVariant = Color(0xFFE0F2EF), onSurfaceVariant = Color(0xFF47706B),
    outline = Color(0xFF99D5CC), outlineVariant = Color(0xFFCCE9E4),
    error = Color(0xFFB91C1C), errorContainer = Color(0xFFFEE2E2), onErrorContainer = Color(0xFF7F1D1D),
)

private val lightExtra = Extra(
    grades = listOf(Color(0xFFDC2626), Color(0xFFEA580C), Color(0xFFCA8A04), Color(0xFF16A34A), Color(0xFF0F766E)),
    ok = Color(0xFF047857), okBg = Color(0xFFD1FAE5), err = Color(0xFFB91C1C), errBg = Color(0xFFFEE2E2),
    warn = Color(0xFF92400E), warnBg = Color(0xFFFEF3C7), newChip = Color(0xFFFCD34D),
    stages = listOf(Color(0xFF6DBCAF), Color(0xFF3A9C8F), Color(0xFF16766A), Color(0xFF0B4E47)),
)

private val dark = darkColorScheme(
    // Bright teal with dark text on it: white on a mid teal is too low-contrast.
    primary = Color(0xFF2DD4BF), onPrimary = Color(0xFF0B1514),
    primaryContainer = Color(0xFF134E4A), onPrimaryContainer = Color(0xFF99F6E4),
    secondary = Color(0xFF8FB3AD), onSecondary = Color(0xFF0B1514),
    secondaryContainer = Color(0xFF1C3532), onSecondaryContainer = Color(0xFFCDE9E4),
    tertiary = Color(0xFFFBBF24), tertiaryContainer = Color(0xFF3A2A0B), onTertiaryContainer = Color(0xFFFBBF24),
    background = Color(0xFF0B1514), onBackground = Color(0xFFE3F2EF),
    surface = Color(0xFF0B1514), onSurface = Color(0xFFE3F2EF),
    surfaceContainerLowest = Color(0xFF081110), surfaceContainerLow = Color(0xFF101D1B),
    surfaceContainer = Color(0xFF142422), surfaceContainerHigh = Color(0xFF1A2E2B), surfaceContainerHighest = Color(0xFF213835),
    surfaceVariant = Color(0xFF1C3532), onSurfaceVariant = Color(0xFF8FB3AD),
    outline = Color(0xFF2B4B47), outlineVariant = Color(0xFF223D3A),
    error = Color(0xFFF87171), errorContainer = Color(0xFF3B1517), onErrorContainer = Color(0xFFFECACA),
)

private val darkExtra = Extra(
    grades = listOf(Color(0xFFF87171), Color(0xFFFB923C), Color(0xFFFACC15), Color(0xFF4ADE80), Color(0xFF2DD4BF)),
    ok = Color(0xFF34D399), okBg = Color(0xFF0F3A2C), err = Color(0xFFF87171), errBg = Color(0xFF3B1517),
    warn = Color(0xFFFBBF24), warnBg = Color(0xFF3A2A0B), newChip = Color(0xFFB45309),
    stages = listOf(Color(0xFF1E6F66), Color(0xFF2A9688), Color(0xFF4DC4B3), Color(0xFF99F0E2)),
)

// Dark brown on warm paper: less brightness, contrast and blue than Light.
private val sepia = lightColorScheme(
    primary = Color(0xFF2F6B63), onPrimary = Color(0xFFFBF6EA),
    primaryContainer = Color(0xFFE9DFC6), onPrimaryContainer = Color(0xFF2A4A45),
    secondary = Color(0xFF7A6650), onSecondary = Color(0xFFFBF6EA),
    secondaryContainer = Color(0xFFEDE3CC), onSecondaryContainer = Color(0xFF3B2F22),
    tertiary = Color(0xFF7A4E0E), tertiaryContainer = Color(0xFFF3E2B8), onTertiaryContainer = Color(0xFF7A4E0E),
    background = Color(0xFFF4ECD8), onBackground = Color(0xFF3B2F22),
    surface = Color(0xFFF4ECD8), onSurface = Color(0xFF3B2F22),
    surfaceContainerLowest = Color(0xFFFFFBF2), surfaceContainerLow = Color(0xFFF8F1E1),
    surfaceContainer = Color(0xFFFBF6EA), surfaceContainerHigh = Color(0xFFEFE5CF), surfaceContainerHighest = Color(0xFFE9DFC6),
    surfaceVariant = Color(0xFFEDE3CC), onSurfaceVariant = Color(0xFF7A6650),
    outline = Color(0xFFD9C9A8), outlineVariant = Color(0xFFE6D9BE),
    error = Color(0xFF9B2C2C), errorContainer = Color(0xFFF2D8C8), onErrorContainer = Color(0xFF6B1F1F),
)

private val sepiaExtra = Extra(
    grades = listOf(Color(0xFFB83A2E), Color(0xFFC2611F), Color(0xFFA07510), Color(0xFF4E7F2E), Color(0xFF2F6B63)),
    ok = Color(0xFF3F6B2E), okBg = Color(0xFFE3E6C6), err = Color(0xFF9B2C2C), errBg = Color(0xFFF2D8C8),
    warn = Color(0xFF7A4E0E), warnBg = Color(0xFFF3E2B8), newChip = Color(0xFFD9B45A),
    stages = listOf(Color(0xFF6DBCAF), Color(0xFF3A9C8F), Color(0xFF16766A), Color(0xFF0B4E47)),
)

/** The headword and examples in a serif face: reads like a printed dictionary, calm next to the sans UI. */
val WordFont: FontFamily = FontFamily.Serif

private val typography = Typography().let { t ->
    t.copy(
        displaySmall = TextStyle(fontFamily = WordFont, fontWeight = FontWeight.SemiBold, fontSize = 38.sp, lineHeight = 46.sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

fun isDark(choice: ThemeChoice, system: Boolean) = choice == ThemeChoice.DARK || (choice == ThemeChoice.SYSTEM && system)

@Composable
fun FlashcardsTheme(choice: ThemeChoice, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val (scheme: ColorScheme, extra: Extra) = when {
        choice == ThemeChoice.SEPIA -> sepia to sepiaExtra
        isDark(choice, systemDark) -> dark to darkExtra
        else -> light to lightExtra
    }
    CompositionLocalProvider(LocalExtra provides extra) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
    }
}
