package app.thaivocab.boox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.thaivocab.boox.ui.BooxScreen
import app.thaivocab.boox.ui.EinkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EinkTheme { BooxScreen() } }
    }
}
