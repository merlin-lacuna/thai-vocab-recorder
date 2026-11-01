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
import app.thaivocab.recorder.sync.SupabaseApi
import app.thaivocab.recorder.sync.SupabaseConfig
import app.thaivocab.recorder.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    private val contextSetting = ContextSetting(app)
    val captureContext: StateFlow<CaptureContext> = contextSetting.current
    private var player: MediaPlayer? = null

    val entries: StateFlow<List<VocabEntry>> = store.entries
    val supabase: StateFlow<SupabaseConfig.State> = config.state

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    /** Recent loudness values (0..1), newest last, for the waveform while recording. */
    private val _levels = MutableStateFlow<List<Float>>(emptyList())
    val levels: StateFlow<List<Float>> = _levels.asStateFlow()

    /** The entry whose audio is playing, and how far it has played (0..1). */
    private val _playingId = MutableStateFlow<String?>(null)
    val playingId: StateFlow<String?> = _playingId.asStateFlow()
    private val _playProgress = MutableStateFlow(0f)
    val playProgress: StateFlow<Float> = _playProgress.asStateFlow()
    private var progressJob: Job? = null

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var currentId: String? = null
    private var currentFile: File? = null

    init {
        // Uploads and transcribes anything that is still waiting.
        SyncWorker.schedule(app)
    }

    fun setContext(value: CaptureContext) = contextSetting.set(value)

    fun recheckContext() = contextSetting.recheck()

    private var pressedAt = 0L
    private var tailJob: Job? = null

    fun startRecording() {
        // A new press during the 0.5 s tail of the previous clip: finish that clip first.
        tailJob?.let { it.cancel(); tailJob = null; finishRecording() }
        if (recorder.isRecording) return
        val id = UUID.randomUUID().toString()
        val file = File(getApplication<Application>().filesDir, "audio/$id.wav")
        try {
            stopPlayback()
            _levels.value = emptyList()
            recorder.start(file) { level ->
                _levels.value = (_levels.value + level).takeLast(MAX_LEVELS)
            }
        } catch (e: Exception) {
            showMessage("Microphone error: ${e.message}")
            return
        }
        currentId = id
        currentFile = file
        pressedAt = System.currentTimeMillis()
        _recording.value = true
    }

    /**
     * Called when the button is released. A very short press is discarded. Otherwise the
     * recorder continues for [TAIL_MS], because people often release the button while they
     * say the last sound (for example an unreleased final -t, -p or -k in Thai).
     */
    fun stopRecording() {
        if (!recorder.isRecording || tailJob != null) return
        if (System.currentTimeMillis() - pressedAt < MIN_DURATION_MS) {
            recorder.stop()
            _recording.value = false
            currentFile?.delete()
            currentId = null
            currentFile = null
            showMessage("Hold the button while you speak")
            return
        }
        tailJob = viewModelScope.launch {
            delay(TAIL_MS)
            tailJob = null
            finishRecording()
        }
    }

    private fun finishRecording() {
        if (!recorder.isRecording) return
        recorder.stop()
        _recording.value = false
        val id = currentId ?: return
        val file = currentFile ?: return
        currentId = null
        currentFile = null

        val now = System.currentTimeMillis()
        val entry = VocabEntry(
            id = id, thai = null, alternatives = emptyList(), notes = null,
            audioFile = file.absolutePath, capturedAt = now, updatedAt = now,
            transcription = Transcription.PENDING, dirty = true, audioUploaded = false, deleted = false,
            context = contextSetting.current.value,
        )
        store.insert(entry)
        contextSetting.touch()
        // The sync job uploads the clip and gets the Thai text from ElevenLabs.
        SyncWorker.schedule(getApplication())
    }

    /** Runs speech recognition again, for an entry where it failed. */
    fun retryTranscription(entry: VocabEntry) {
        if (entry.audioFile == null) return
        store.update(entry.copy(transcription = Transcription.PENDING, transcriptionError = null))
        SyncWorker.schedule(getApplication())
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

    /** Plays the clip, or stops it when it is already playing. */
    fun play(entry: VocabEntry) {
        if (_playingId.value == entry.id) return stopPlayback()
        val path = entry.audioFile ?: return
        if (!File(path).exists()) return showMessage("Audio file is missing")
        stopPlayback()
        val mp = try {
            MediaPlayer().apply {
                setDataSource(path)
                prepare()
                start()
            }
        } catch (e: Exception) {
            return showMessage("Cannot play: ${e.message}")
        }
        mp.setOnCompletionListener { if (player === it) stopPlayback() }
        player = mp
        _playingId.value = entry.id
        _playProgress.value = 0f
        progressJob = viewModelScope.launch {
            while (isActive) {
                val p = player ?: break
                val duration = p.duration.coerceAtLeast(1)
                _playProgress.value = (p.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
                delay(40)
            }
        }
    }

    private fun stopPlayback() {
        progressJob?.cancel()
        progressJob = null
        player?.release()
        player = null
        _playingId.value = null
        _playProgress.value = 0f
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
                draftPassword = ""
                SyncWorker.schedule(getApplication())
                null
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
        }

    /** Password typed in the set-up window. Kept in memory only, never written to storage. */
    var draftPassword: String = ""

    fun saveSetupDraft(url: String, apiKey: String, email: String) = config.saveDraft(url, apiKey, email)

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
        stopPlayback()
    }

    private companion object {
        const val MIN_DURATION_MS = 400L
        const val TAIL_MS = 500L
        const val MAX_LEVELS = 60
    }
}
