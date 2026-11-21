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
import app.thaivocab.quiz.ui.Screen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
        watchLogin()
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

    /**
     * Registers with the push distributor (ntfy) each time the screen opens while logged in,
     * and right after a login. ntfy keeps the registration; this call only renews it.
     * Without a distributor, the home screen shows a hint.
     */
    private fun watchLogin() = lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.screen.map { it != Screen.Login }.distinctUntilChanged().collect { loggedIn ->
                if (loggedIn) {
                    UnifiedPush.tryUseCurrentOrDefaultDistributor(this@MainActivity) { ok ->
                        if (ok) UnifiedPush.register(this@MainActivity)
                        vm.setPushReady(ok)
                    }
                }
            }
        }
    }

    private fun openFromNotification(intent: Intent?) {
        val id = intent?.getStringExtra(Notifications.EXTRA_QUIZ_ID) ?: return
        intent.removeExtra(Notifications.EXTRA_QUIZ_ID)
        if (SupabaseConfig.get(this).state.value.connected) vm.startRound(firstId = id)
    }
}
