package app.thaivocab.recorder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.thaivocab.recorder.ui.MainScreen
import app.thaivocab.recorder.ui.ThaiVocabTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ThaiVocabTheme { MainScreen() }
        }
    }
}
