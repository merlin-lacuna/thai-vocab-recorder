package app.thaivocab.recorder.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.thaivocab.recorder.data.Transcription
import app.thaivocab.recorder.data.VocabEntry
import app.thaivocab.recorder.data.VocabStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Uploads new and changed entries to Supabase. Runs in the background when the
 * phone has a network connection, and retries with backoff when it fails.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val config = SupabaseConfig.get(applicationContext)
        val state = config.state.value
        if (!state.connected) return@withContext Result.success() // nothing to do until set up

        val store = VocabStore.get(applicationContext)
        val api = SupabaseApi(state.url, state.apiKey)

        val session = try {
            api.refresh(state.refreshToken!!).also { s ->
                config.save { putString("refresh_token", s.refreshToken); putString("user_id", s.userId) }
            }
        } catch (e: HttpException) {
            if (e.code in 400..499) {
                // Refresh token is no longer valid: the user must sign in again.
                config.save { remove("refresh_token"); putString("last_error", "Signed out. Please sign in again.") }
                return@withContext Result.success()
            }
            config.setError(e.message)
            return@withContext Result.retry()
        } catch (e: Exception) {
            config.setError(e.message)
            return@withContext Result.retry()
        }

        val now = System.currentTimeMillis()
        val pending = store.pendingSync().filter {
            // Give speech recognition time to finish before the first upload.
            it.transcription != Transcription.PENDING || now - it.capturedAt > 2 * 60_000
        }

        var failed = false
        for (entry in pending) {
            try {
                var audioUploaded = entry.audioUploaded
                val audioPath = entry.audioFile?.let { "${session.userId}/${entry.id}.wav" }
                if (!entry.deleted && entry.audioFile != null && !audioUploaded) {
                    val file = File(entry.audioFile)
                    if (file.exists()) {
                        api.uploadFile(session.accessToken, AUDIO_BUCKET, audioPath!!, file, "audio/wav")
                        store.markAudioUploaded(entry.id)
                    }
                    audioUploaded = true
                }
                api.upsert(session.accessToken, TABLE, entry.toRow(audioPath))
                store.markSynced(entry.id, entry.updatedAt, audioUploaded)
            } catch (e: Exception) {
                Log.w(TAG, "Sync failed for ${entry.id}", e)
                config.setError(e.message)
                failed = true
            }
        }
        if (!failed) config.setError(null)
        store.refresh()
        if (failed) Result.retry() else Result.success()
    }

    private fun VocabEntry.toRow(audioPath: String?) = JSONObject().apply {
        put("id", id)
        put("thai", thai ?: JSONObject.NULL)
        put("thai_alternatives", JSONArray(alternatives))
        put("notes", notes ?: JSONObject.NULL)
        put("audio_path", audioPath ?: JSONObject.NULL)
        put("source", SOURCE)
        put("context", context.dbValue)
        put("teacher", context.teacher ?: JSONObject.NULL)
        put("transcription_error", transcriptionError ?: JSONObject.NULL)
        put("transcription_status", transcription.name.lowercase())
        put("captured_at", Instant.ofEpochMilli(capturedAt).toString())
        put("updated_at", Instant.ofEpochMilli(updatedAt).toString())
        put("deleted_at", if (deleted) Instant.ofEpochMilli(updatedAt).toString() else JSONObject.NULL)
    }

    companion object {
        private const val TAG = "SyncWorker"
        const val TABLE = "vocab_items"
        const val AUDIO_BUCKET = "vocab-audio"
        const val SOURCE = "field_recorder"

        /** Asks WorkManager to run a sync as soon as there is a network connection. */
        fun schedule(context: Context, delaySeconds: Long = 0) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("sync", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
