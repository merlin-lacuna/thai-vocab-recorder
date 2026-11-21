package app.thaivocab.quiz

import kotlinx.coroutines.launch
import org.json.JSONObject
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Called by the UnifiedPush library when the distributor app (ntfy) passes on an event.
 * The server sends {"type": "quiz", "id": "<quiz id>"} when a quiz email is sent.
 */
class QuizPushService : PushService() {

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val api = QuizApi.get(this)
        api.saveEndpoint(endpoint.url)
        QuizApp.scope.launch { runCatching { api.registerPush(endpoint.url) } }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val json = runCatching { JSONObject(String(message.content)) }.getOrNull()
        if (json != null && json.optString("type") != "quiz") return
        Notifications.showQuiz(this, json?.optString("id")?.ifBlank { null })
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        QuizApi.get(this).saveEndpoint(null)
    }

    override fun onUnregistered(instance: String) {
        QuizApi.get(this).saveEndpoint(null)
    }
}
