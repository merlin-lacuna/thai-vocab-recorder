package app.thaivocab.recorder.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun ThaiVocabTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) {
        darkColorScheme(primary = Color(0xFFFF6F60), error = Color(0xFFFF8A80))
    } else {
        lightColorScheme(primary = Color(0xFFD32F2F))
    }
    MaterialTheme(colorScheme = colors, content = content)
}
