package app.thaivocab.recorder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import app.thaivocab.recorder.ui.MainScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val red = Color(0xFFD32F2F)
            val colors = if (isSystemInDarkTheme()) {
                darkColorScheme(primary = Color(0xFFFF6F60), error = Color(0xFFFF8A80))
            } else {
                lightColorScheme(primary = red)
            }
            MaterialTheme(colorScheme = colors) { MainScreen() }
        }
    }
}
