package app.thaivocab.recorder.ui

import android.app.Application
import android.media.MediaPlayer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.thaivocab.recorder.audio.WavRecorder
import app.thaivocab.recorder.data.CaptureContext
import app.thaivocab.recorder.data.ContextSetting
import app.thaivocab.recorder.data.Transcription
import app.thaivocab.recorder.data.VocabEntry
import app.thaivocab.recorder.data.VocabStore
import app.thaivocab.recorder.speech.ThaiTranscriber
import app.thaivocab.recorder.sync.SupabaseApi
import app.thaivocab.recorder.sync.SupabaseConfig
import app.thaivocab.recorder.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = VocabStore.get(app)
    private val config = SupabaseConfig.get(app)
    private val recorder = WavRecorder()
    private val transcriber = ThaiTranscriber(app)
    private val contextSetting = ContextSetting(app)
    val captureContext: StateFlow<CaptureContext> = contextSetting.current
    private var player: MediaPlayer? = null

    val entries: StateFlow<List<VocabEntry>> = store.entries
    val supabase: StateFlow<SupabaseConfig.State> = config.state

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var currentId: String? = null
    private var currentFile: File? = null

    init {
        // Entries left in PENDING (for example the app was closed during recognition).
        val stale = System.currentTimeMillis() - 60_000
        store.entries.value
            .filter { it.transcription == Transcription.PENDING && it.capturedAt < stale }
            .forEach { store.update(it.copy(transcription = Transcription.FAILED)) }
        SyncWorker.schedule(app)
    }

    fun setContext(value: CaptureContext) = contextSetting.set(value)

    fun recheckContext() = contextSetting.recheck()

    fun startRecording() {
        if (recorder.isRecording) return
        val id = UUID.randomUUID().toString()
        val file = File(getApplication<Application>().filesDir, "audio/$id.wav")
        try {
            recorder.start(file)
        } catch (e: Exception) {
            showMessage("Microphone error: ${e.message}")
            return
        }
        currentId = id
        currentFile = file
        _recording.value = true
    }

    fun stopRecording() {
        if (!recorder.isRecording) return
        val duration = recorder.stop()
        _recording.value = false
        val id = currentId ?: return
        val file = currentFile ?: return
        currentId = null
        currentFile = null

        if (duration < MIN_DURATION_MS) {
            file.delete()
            showMessage("Hold the button while you speak")
            return
        }

        val now = System.currentTimeMillis()
        val entry = VocabEntry(
            id = id, thai = null, alternatives = emptyList(), notes = null,
            audioFile = file.absolutePath, capturedAt = now, updatedAt = now,
            transcription = Transcription.PENDING, dirty = true, audioUploaded = false, deleted = false,
            context = contextSetting.current.value,
        )
        store.insert(entry)
        contextSetting.touch()

        viewModelScope.launch {
            val results = transcriber.transcribe(file)
            val latest = store.get(id) ?: return@launch
            if (latest.deleted) return@launch
            // If the user typed a text while recognition ran, keep their text.
            val updated = if (latest.transcription == Transcription.MANUAL) {
                latest.copy(alternatives = results)
            } else if (results.isEmpty()) {
                latest.copy(transcription = Transcription.FAILED)
            } else {
                latest.copy(thai = results.first(), alternatives = results.drop(1), transcription = Transcription.DONE)
            }
            store.update(updated)
            SyncWorker.schedule(getApplication())
        }
    }

    /** Adds an entry without audio (for example a word read on a sign). */
    fun addTyped(thai: String, notes: String?) {
        val now = System.currentTimeMillis()
        store.insert(
            VocabEntry(
                id = UUID.randomUUID().toString(), thai = thai.trim(), alternatives = emptyList(),
                notes = notes?.trim()?.ifEmpty { null }, audioFile = null, capturedAt = now, updatedAt = now,
                transcription = Transcription.MANUAL, dirty = true, audioUploaded = false, deleted = false,
                context = contextSetting.current.value,
            )
        )
        contextSetting.touch()
        SyncWorker.schedule(getApplication())
    }

    fun saveEdit(entry: VocabEntry, thai: String, notes: String) {
        val newThai = thai.trim().ifEmpty { null }
        // Keep the recogniser's other candidates, including the old main one.
        val alts = (listOfNotNull(entry.thai) + entry.alternatives).filter { it != newThai }.distinct()
        store.update(
            entry.copy(
                thai = newThai,
                alternatives = alts,
                notes = notes.trim().ifEmpty { null },
                transcription = if (newThai != entry.thai) Transcription.MANUAL else entry.transcription,
            )
        )
        SyncWorker.schedule(getApplication())
    }

    fun delete(entry: VocabEntry) {
        store.markDeleted(entry.id)
        SyncWorker.schedule(getApplication())
    }

    fun play(entry: VocabEntry) {
        val path = entry.audioFile ?: return
        if (!File(path).exists()) return showMessage("Audio file is missing")
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(path)
            setOnCompletionListener { it.release(); if (player === it) player = null }
            prepare()
            start()
        }
    }

    fun syncNow() = SyncWorker.schedule(getApplication())

    /** Signs in to Supabase. Returns an error message, or null on success. */
    suspend fun connect(url: String, apiKey: String, email: String, password: String): String? =
        withContext(Dispatchers.IO) {
            val cleanUrl = url.trim().trimEnd('/')
            try {
                val session = SupabaseApi(cleanUrl, apiKey.trim()).signIn(email.trim(), password)
                config.save {
                    putString("url", cleanUrl)
                    putString("api_key", apiKey.trim())
                    putString("email", email.trim())
                    putString("user_id", session.userId)
                    putString("refresh_token", session.refreshToken)
                    remove("last_error")
                }
                SyncWorker.schedule(getApplication())
                null
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
        }

    fun disconnect() = config.save { remove("refresh_token"); remove("user_id") }

    fun showMessage(text: String) {
        _message.value = text
        viewModelScope.launch {
            delay(2500)
            if (_message.value == text) _message.value = null
        }
    }

    override fun onCleared() {
        recorder.stop()
        player?.release()
    }

    private companion object {
        const val MIN_DURATION_MS = 400L
    }
}
