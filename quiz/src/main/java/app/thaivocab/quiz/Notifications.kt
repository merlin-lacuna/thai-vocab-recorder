package app.thaivocab.quiz

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifications {
    const val CHANNEL = "quiz"
    const val EXTRA_QUIZ_ID = "quiz_id"

    /** Shows "Your Thai quiz is ready". A tap opens the app with this quiz. */
    fun showQuiz(context: Context, quizId: String?) {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_QUIZ_ID, quizId)
        val pending = PendingIntent.getActivity(
            context, quizId.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Your Thai quiz is ready")
            .setContentText("Tap to start. The timer starts when the question opens.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(quizId.hashCode(), notification) }
    }
}
