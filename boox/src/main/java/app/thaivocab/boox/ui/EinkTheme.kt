package app.thaivocab.boox.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF000000)
val Paper = Color(0xFFFFFFFF)
val Grey = Color(0xFF555555)

/** Black and white only, and no ripple animation: the screen is e-ink. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EinkTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = Ink, onPrimary = Paper, primaryContainer = Paper, onPrimaryContainer = Ink,
        secondary = Ink, onSecondary = Paper, secondaryContainer = Ink, onSecondaryContainer = Paper,
        background = Paper, onBackground = Ink, surface = Paper, onSurface = Ink,
        surfaceVariant = Paper, onSurfaceVariant = Grey, outline = Ink, outlineVariant = Grey,
        error = Ink, onError = Paper,
        surfaceContainer = Paper, surfaceContainerLow = Paper, surfaceContainerHigh = Paper,
        surfaceContainerHighest = Paper, surfaceContainerLowest = Paper, surfaceTint = Paper,
    )
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalRippleConfiguration provides null, content = content)
    }
}
