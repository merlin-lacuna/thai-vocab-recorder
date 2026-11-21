package app.thaivocab.quiz

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import app.thaivocab.core.AppProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class QuizApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppProfile.source = "quiz_app"
        AppProfile.releaseApk = "thai-vocab-quiz.apk"
        AppProfile.releaseVersionFile = "version-quiz.json"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(Notifications.CHANNEL, "Quiz ready", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "A notification when a new quiz is sent" }
        )
    }

    companion object {
        /** For work that must finish after a screen or the push service closes. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
