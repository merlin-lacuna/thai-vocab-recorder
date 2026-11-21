package app.thaivocab.quiz

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import app.thaivocab.core.sync.SupabaseConfig
import app.thaivocab.quiz.ui.QuizScreen
import app.thaivocab.quiz.ui.QuizViewModel
import org.unifiedpush.android.connector.UnifiedPush

class MainActivity : ComponentActivity() {
    private val vm: QuizViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (savedInstanceState == null) openFromNotification(intent)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (dark) darkColorScheme(primary = Color(0xFF8AB4F8))
                else lightColorScheme(primary = Color(0xFF13315C)),
            ) { QuizScreen(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFromNotification(intent)
    }

    override fun onResume() {
        super.onResume()
        // Registers with the push distributor (ntfy). Android keeps the registration; this
        // call only renews it. Without a distributor, the home screen shows a hint.
        if (SupabaseConfig.get(this).state.value.connected) {
            UnifiedPush.tryUseCurrentOrDefaultDistributor(this) { ok ->
                if (ok) UnifiedPush.register(this)
                vm.setPushReady(ok)
            }
        }
    }

    private fun openFromNotification(intent: Intent?) {
        val id = intent?.getStringExtra(Notifications.EXTRA_QUIZ_ID) ?: return
        intent.removeExtra(Notifications.EXTRA_QUIZ_ID)
        if (SupabaseConfig.get(this).state.value.connected) vm.startRound(firstId = id)
    }
}
