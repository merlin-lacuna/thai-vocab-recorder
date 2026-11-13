package app.thaivocab.boox

import android.os.Bundle
import androidx.activity.ComponentActivity
import app.thaivocab.boox.ink.InkView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(InkView(this) {})
    }
}
