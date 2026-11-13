package app.thaivocab.boox.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.thaivocab.boox.ink.InkRenderer
import app.thaivocab.boox.ink.Stroke
import app.thaivocab.boox.ink.splitZones
import app.thaivocab.core.data.CaptureContext
import app.thaivocab.core.data.ContextCatalog
import app.thaivocab.core.data.ContextSetting
import app.thaivocab.core.data.CustomContext
import app.thaivocab.core.data.Transcription
import app.thaivocab.core.data.VocabEntry
import app.thaivocab.core.data.VocabStore
import app.thaivocab.core.sync.Connection
import app.thaivocab.core.sync.SupabaseConfig
import app.thaivocab.core.sync.SyncWorker
import app.thaivocab.core.update.Updater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class BooxViewModel(app: Application) : AndroidViewModel(app) {
    private val store = VocabStore.get(app)
    private val contextSetting = ContextSetting(app)
    private val catalog = ContextCatalog.get(app)
    val connection = Connection(app)

    val entries: StateFlow<List<VocabEntry>> = store.entries
    val supabase: StateFlow<SupabaseConfig.State> = SupabaseConfig.get(app).state
    val captureContext: StateFlow<CaptureContext> = contextSetting.current
    val contextOptions: StateFlow<List<CaptureContext>> = catalog.selectable
    val customContexts: StateFlow<List<CustomContext>> = catalog.customs

    init {
        // Uploads and reads anything that is still waiting.
        SyncWorker.schedule(app)
    }

    fun syncNow() = connection.syncNow()

    /**
     * Saves what the user wrote as a new entry. [phonetic] is true for the button
     * "Next: Phonetic". Returns false when the upper zone has no ink; the function then
     * saves no entry.
     */
    fun saveInk(strokes: List<Stroke>, boxWidth: Float, boxHeight: Float, strokeWidth: Float, phonetic: Boolean): Boolean {
        val (thai, english) = splitZones(strokes, boxHeight)
        val id = UUID.randomUUID().toString()
        val dir = File(getApplication<Application>().filesDir, "handwriting")
        val image = File(dir, "$id.png")
        if (!InkRenderer.writePng(thai, boxWidth, boxHeight, strokeWidth, image)) return false
        val englishImage = File(dir, "$id-en.png")
        val hasEnglish = InkRenderer.writePng(english, boxWidth, boxHeight, strokeWidth, englishImage)
        val now = System.currentTimeMillis()
        store.insert(
            VocabEntry(
                id = id, thai = null, alternatives = emptyList(), notes = null, audioFile = null,
                capturedAt = now, updatedAt = now, context = contextSetting.current.value,
                imageFile = image.absolutePath,
                englishImageFile = if (hasEnglish) englishImage.absolutePath else null,
                imagesUploaded = false,
                writtenScript = if (phonetic) "phonetic" else "thai",
                transcription = Transcription.PENDING, dirty = true, audioUploaded = false, deleted = false,
            )
        )
        contextSetting.touch()
        syncNow()
        return true
    }

    /** Adds an entry without handwriting, typed with the keyboard. */
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
        syncNow()
    }

    fun saveEdit(entry: VocabEntry, thai: String, notes: String, context: CaptureContext) {
        val newThai = thai.trim().ifEmpty { null }
        // Keep the other readings, including the old main one.
        val alts = (listOfNotNull(entry.thai) + entry.alternatives).filter { it != newThai }.distinct()
        store.update(
            entry.copy(
                thai = newThai, alternatives = alts, notes = notes.trim().ifEmpty { null }, context = context,
                transcription = if (newThai != entry.thai) Transcription.MANUAL else entry.transcription,
                // New Thai text: the server adds a new meaning and romanization.
                english = if (newThai != entry.thai) null else entry.english,
                romanization = if (newThai != entry.thai) null else entry.romanization,
            )
        )
        syncNow()
    }

    /**
     * The user pressed the wrong Next button: changes the script type and lets the server
     * read the image again. The row is uploaded with the status "pending" and without Thai
     * text; the "read" step accepts a row in that state.
     */
    fun readAgainAsOtherType(entry: VocabEntry) {
        if (entry.imageFile == null) return
        store.update(
            entry.copy(
                writtenScript = if (entry.writtenScript == "phonetic") "thai" else "phonetic",
                thai = null, alternatives = emptyList(), english = null, romanization = null,
                transcription = Transcription.PENDING, transcriptionError = null,
            )
        )
        syncNow()
    }

    /** Runs the reading step again, for an entry where it failed. */
    fun retryReading(entry: VocabEntry) {
        if (entry.imageFile == null) return
        store.update(entry.copy(transcription = Transcription.PENDING, transcriptionError = null))
        syncNow()
    }

    /** Removes the handwriting images from the word; the word itself stays. */
    fun removeImage(entry: VocabEntry) {
        entry.imageFile?.let { File(it).delete() }
        entry.englishImageFile?.let { File(it).delete() }
        store.update(entry.copy(imageFile = null, englishImageFile = null, imagesUploaded = true, imageCleared = true))
        syncNow()
    }

    fun delete(entry: VocabEntry) {
        store.markDeleted(entry.id)
        syncNow()
    }

    // Context labels: built-in plus the user's own (synced with the table capture_contexts).

    fun setContext(value: CaptureContext) = contextSetting.set(value)
    fun recheckContext() = contextSetting.recheck()

    fun addContext(label: String) {
        if (label.isBlank()) return
        catalog.add(label)
        syncNow()
    }

    fun renameContext(key: String, label: String) {
        if (label.isBlank()) return
        catalog.rename(key, label)
        afterContextChange()
    }

    fun removeContext(key: String) {
        catalog.remove(key)
        afterContextChange()
    }

    private fun afterContextChange() {
        store.refresh()
        contextSetting.recheck()
        syncNow()
    }

    // In-app update from the "latest" GitHub release.

    private val updater = Updater(app)
    private val _update = MutableStateFlow<Updater.Available?>(null)
    val update: StateFlow<Updater.Available?> = _update.asStateFlow()

    /** Download progress 0..1 while an update downloads, else null. */
    private val _updateProgress = MutableStateFlow<Float?>(null)
    val updateProgress: StateFlow<Float?> = _updateProgress.asStateFlow()
    private var lastUpdateCheck = 0L

    /** Checks GitHub for a newer build, at most every 10 minutes. */
    fun checkForUpdate() {
        val now = System.currentTimeMillis()
        if (now - lastUpdateCheck < 10 * 60_000) return
        lastUpdateCheck = now
        viewModelScope.launch { _update.value = updater.check() }
    }

    fun installUpdate() {
        if (_updateProgress.value != null) return
        _updateProgress.value = 0f
        viewModelScope.launch {
            try {
                updater.install(updater.download { p -> _updateProgress.value = p })
            } catch (e: Exception) {
                // The banner stays on the screen, so the user can tap it again.
            } finally {
                _updateProgress.value = null
            }
        }
    }
}
