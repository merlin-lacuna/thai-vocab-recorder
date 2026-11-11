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
import app.thaivocab.recorder.data.CaptureContext
import app.thaivocab.recorder.data.Transcription
import app.thaivocab.recorder.data.VocabEntry
import app.thaivocab.recorder.data.VocabStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
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

        val pending = store.pendingSync()
        val audioDir = File(applicationContext.filesDir, "audio")

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
                // A native speaker's recording made on this phone.
                var toSend = entry
                if (!entry.deleted && entry.nativeAudioFile != null && !entry.nativeAudioUploaded) {
                    val path = entry.nativeAudioPath ?: "${session.userId}/native/${entry.id}.wav"
                    val file = File(entry.nativeAudioFile)
                    if (file.exists()) api.uploadFile(session.accessToken, AUDIO_BUCKET, path, file, "audio/wav")
                    store.markNativeUploaded(entry.id, path)
                    toSend = entry.copy(nativeAudioPath = path, nativeAudioUploaded = true)
                }
                api.upsert(session.accessToken, TABLE, toSend.toRow(audioPath))

                // Step 1 on the server: Thai text from ElevenLabs. The result is saved
                // locally at once, so the list shows the Thai text before step 2 starts.
                // Network problems are thrown, so the entry stays "not uploaded" and the
                // job retries later.
                var transcribed: VocabEntry? = null
                if (!entry.deleted && entry.transcription == Transcription.PENDING) {
                    try {
                        transcribed = api.process(session.accessToken, entry.id, "transcribe").toEntry(audioDir)
                    } catch (e: HttpException) {
                        Log.w(TAG, "transcribe failed for ${entry.id}", e)
                        config.setError(e.message)
                        store.update(entry.copy(transcription = Transcription.FAILED, transcriptionError = e.message))
                        continue
                    }
                }
                store.markSynced(entry.id, entry.updatedAt, audioUploaded)
                transcribed?.let { store.applyRemote(it) }

                // Step 2 on the server: spelling check, romanization and English (Claude).
                // A failure here leaves the row as it is; the catch-up in pull() retries it.
                val current = transcribed ?: entry
                if (!entry.deleted && current.thai != null && current.english == null) {
                    try {
                        store.applyRemote(api.process(session.accessToken, entry.id, "enrich").toEntry(audioDir))
                    } catch (e: Exception) {
                        Log.w(TAG, "enrich failed for ${entry.id}", e)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Sync failed for ${entry.id}", e)
                config.setError(e.message)
                failed = true
            }
        }
        // Then download the newest rows, so the phone also shows words from other sources,
        // changes made in the dashboard, and the history after a reinstall.
        try {
            pull(api, session.accessToken, session.userId, store)
        } catch (e: Exception) {
            Log.w(TAG, "Download failed", e)
            config.setError(e.message)
            failed = true
        }

        if (!failed) config.setError(null)
        store.refresh()
        if (failed) Result.retry() else Result.success()
    }

    private fun pull(api: SupabaseApi, token: String, userId: String, store: VocabStore) {
        val rows = api.select(
            token, TABLE,
            "select=id,thai,thai_alternatives,notes,audio_path,context,teacher,transcription_status," +
                "transcription_error,english,romanization,captured_at,updated_at,deleted_at," +
                "enrichment_status,enrichment_error,source,native_audio_path,native_audio_source" +
                "&order=captured_at.desc&limit=$PULL_LIMIT",
        )
        val audioDir = File(applicationContext.filesDir, "audio")
        val remote = (0 until rows.length()).map { i -> rows.getJSONObject(i).toEntry(audioDir) }
        store.mergeFromServer(remote, complete = remote.size < PULL_LIMIT)

        // Older words (or words from other sources) that have Thai text but were never
        // enriched: let the server add spelling check, romanization and English.
        // Rows where enrichment failed before are skipped, so a broken key costs nothing.
        (0 until rows.length()).map { rows.getJSONObject(it) }
            .filter {
                it.isNull("deleted_at") && !it.isNull("thai") && it.isNull("enrichment_error") &&
                    it.optString("enrichment_status") == "new"
            }
            .take(ENRICH_BATCH)
            .forEach { row ->
                try {
                    store.applyRemote(api.process(token, row.getString("id"), "enrich").toEntry(audioDir))
                } catch (e: HttpException) {
                    Log.w(TAG, "process failed for ${row.getString("id")}", e)
                }
            }

        // Fetch native pronunciation clips that are missing on the phone, newest first.
        remote.asSequence()
            .filter { !it.deleted && it.nativeAudioPath != null && it.nativeAudioFile != null && !File(it.nativeAudioFile).exists() }
            .take(AUDIO_DOWNLOAD_LIMIT)
            .forEach { e ->
                val target = File(e.nativeAudioFile!!)
                target.parentFile?.mkdirs()
                val tmp = File(target.path + ".part")
                tmp.writeBytes(api.downloadFile(token, AUDIO_BUCKET, e.nativeAudioPath!!))
                tmp.renameTo(target)
            }

        // Fetch audio that is missing on the phone, newest first.
        remote.asSequence()
            .filter { !it.deleted && it.audioFile != null && !File(it.audioFile).exists() }
            .take(AUDIO_DOWNLOAD_LIMIT)
            .forEach { e ->
                val path = rows.getJSONObject(remote.indexOf(e)).optString("audio_path")
                val bytes = api.downloadFile(token, AUDIO_BUCKET, path)
                val target = File(e.audioFile!!)
                target.parentFile?.mkdirs()
                val tmp = File(target.path + ".part")
                tmp.writeBytes(bytes)
                tmp.renameTo(target)
            }
    }

    private fun JSONObject.toEntry(audioDir: File): VocabEntry {
        fun str(key: String): String? = if (isNull(key)) null else optString(key)
        fun time(key: String): Long? = str(key)?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
        val id = getString("id")
        val alts = optJSONArray("thai_alternatives") ?: JSONArray()
        val context = when (str("context")) {
            "lingping_club" -> CaptureContext.LINGPING
            "wondering" -> CaptureContext.WONDERING
            "lesson" -> when (str("teacher")?.lowercase()) {
                "ae" -> CaptureContext.KRU_AE
                else -> CaptureContext.KRU_BOOM
            }
            else -> CaptureContext.FIELD
        }
        val thai = str("thai")
        return VocabEntry(
            id = id,
            thai = thai,
            alternatives = List(alts.length()) { alts.getString(it) },
            notes = str("notes"),
            audioFile = str("audio_path")?.let { File(audioDir, "$id.wav").absolutePath },
            capturedAt = time("captured_at") ?: 0L,
            updatedAt = time("updated_at") ?: 0L,
            transcription = when (str("transcription_status")) {
                "done" -> Transcription.DONE
                "failed" -> Transcription.FAILED
                "pending" -> Transcription.PENDING
                else -> if (thai != null) Transcription.MANUAL else Transcription.FAILED
            },
            dirty = false,
            audioUploaded = true,
            deleted = str("deleted_at") != null,
            context = context,
            transcriptionError = str("transcription_error"),
            english = str("english"),
            romanization = str("romanization"),
            source = str("source") ?: SOURCE,
            nativeAudioPath = str("native_audio_path"),
            nativeAudioSource = str("native_audio_source"),
            nativeAudioFile = str("native_audio_path")?.let { path ->
                File(audioDir, "native_$id.${path.substringAfterLast('.', "wav")}").absolutePath
            },
            nativeAudioUploaded = true,
        )
    }

    private fun VocabEntry.toRow(audioPath: String?) = JSONObject().apply {
        put("id", id)
        put("thai", thai ?: JSONObject.NULL)
        put("thai_alternatives", JSONArray(alternatives))
        put("notes", notes ?: JSONObject.NULL)
        put("audio_path", audioPath ?: JSONObject.NULL)
        put("source", source)
        // Native clip fields only when known, so a stale phone never erases a clip that a
        // batch job added in the meantime.
        nativeAudioPath?.let { put("native_audio_path", it) }
        nativeAudioSource?.let { put("native_audio_source", it) }
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
        private const val PULL_LIMIT = 500
        private const val AUDIO_DOWNLOAD_LIMIT = 100
        private const val ENRICH_BATCH = 20

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
