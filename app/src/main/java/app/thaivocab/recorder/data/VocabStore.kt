package app.thaivocab.recorder.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.File

/** How far the Thai speech-to-text step has come for one entry. */
enum class Transcription { PENDING, DONE, FAILED, MANUAL }

/**
 * One captured word or phrase, as stored on the phone.
 *
 * The phone keeps a local copy so that capture works offline. The cloud database
 * (Supabase) is the shared source of truth for other apps.
 */
data class VocabEntry(
    val id: String,
    val thai: String?,
    val alternatives: List<String>,
    val notes: String?,
    /** Absolute path of the local WAV file, or null for typed entries. */
    val audioFile: String?,
    val capturedAt: Long,
    val updatedAt: Long,
    val transcription: Transcription,
    /** True when the local version has changes that are not in the cloud yet. */
    val dirty: Boolean,
    val audioUploaded: Boolean,
    val deleted: Boolean,
)

/** Small SQLite store on the phone. One instance per process. */
class VocabStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "vocab.db", null, 1) {

    private val _entries = MutableStateFlow<List<VocabEntry>>(emptyList())

    /** Visible (not deleted) entries, newest first. */
    val entries: StateFlow<List<VocabEntry>> = _entries.asStateFlow()

    init {
        refresh()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE entries (
                id TEXT PRIMARY KEY,
                thai TEXT,
                alternatives TEXT NOT NULL DEFAULT '[]',
                notes TEXT,
                audio_file TEXT,
                captured_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                transcription TEXT NOT NULL,
                dirty INTEGER NOT NULL DEFAULT 1,
                audio_uploaded INTEGER NOT NULL DEFAULT 0,
                deleted INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun insert(entry: VocabEntry) {
        writableDatabase.insertOrThrow("entries", null, entry.toValues())
        refresh()
    }

    @Synchronized
    fun get(id: String): VocabEntry? =
        readableDatabase.query("entries", null, "id = ?", arrayOf(id), null, null, null)
            .use { c -> if (c.moveToFirst()) c.toEntry() else null }

    /** Saves a change made on the phone and marks it for upload. */
    @Synchronized
    fun update(entry: VocabEntry) {
        val changed = entry.copy(updatedAt = System.currentTimeMillis(), dirty = true)
        writableDatabase.update("entries", changed.toValues(), "id = ?", arrayOf(entry.id))
        refresh()
    }

    @Synchronized
    fun markDeleted(id: String) {
        val e = get(id) ?: return
        update(e.copy(deleted = true))
    }

    /** Entries the sync worker must send to the cloud. */
    @Synchronized
    fun pendingSync(): List<VocabEntry> =
        readableDatabase.query("entries", null, "dirty = 1", null, null, null, "captured_at ASC")
            .use { c -> buildList { while (c.moveToNext()) add(c.toEntry()) } }

    /**
     * Called by the sync worker after an upload. The flag is only cleared when the
     * entry did not change during the upload (same updatedAt).
     */
    @Synchronized
    fun markSynced(id: String, uploadedUpdatedAt: Long, audioUploaded: Boolean) {
        val e = get(id) ?: return
        val unchanged = e.updatedAt == uploadedUpdatedAt
        if (unchanged && e.deleted) {
            e.audioFile?.let { File(it).delete() }
            writableDatabase.delete("entries", "id = ?", arrayOf(id))
        } else {
            val values = ContentValues().apply {
                put("audio_uploaded", if (audioUploaded) 1 else 0)
                if (unchanged) put("dirty", 0)
            }
            writableDatabase.update("entries", values, "id = ?", arrayOf(id))
        }
        refresh()
    }

    @Synchronized
    fun markAudioUploaded(id: String) {
        val values = ContentValues().apply { put("audio_uploaded", 1) }
        writableDatabase.update("entries", values, "id = ?", arrayOf(id))
    }

    @Synchronized
    fun refresh() {
        _entries.value = readableDatabase.query(
            "entries", null, "deleted = 0", null, null, null, "captured_at DESC", "500"
        ).use { c -> buildList { while (c.moveToNext()) add(c.toEntry()) } }
    }

    private fun VocabEntry.toValues() = ContentValues().apply {
        put("id", id)
        put("thai", thai)
        put("alternatives", JSONArray(alternatives).toString())
        put("notes", notes)
        put("audio_file", audioFile)
        put("captured_at", capturedAt)
        put("updated_at", updatedAt)
        put("transcription", transcription.name)
        put("dirty", if (dirty) 1 else 0)
        put("audio_uploaded", if (audioUploaded) 1 else 0)
        put("deleted", if (deleted) 1 else 0)
    }

    private fun Cursor.toEntry(): VocabEntry {
        fun str(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
        fun long(name: String): Long = getLong(getColumnIndexOrThrow(name))
        val alts = JSONArray(str("alternatives") ?: "[]")
        return VocabEntry(
            id = str("id")!!,
            thai = str("thai"),
            alternatives = List(alts.length()) { alts.getString(it) },
            notes = str("notes"),
            audioFile = str("audio_file"),
            capturedAt = long("captured_at"),
            updatedAt = long("updated_at"),
            transcription = Transcription.valueOf(str("transcription")!!),
            dirty = long("dirty") == 1L,
            audioUploaded = long("audio_uploaded") == 1L,
            deleted = long("deleted") == 1L,
        )
    }

    companion object {
        @Volatile private var instance: VocabStore? = null

        fun get(context: Context): VocabStore =
            instance ?: synchronized(this) {
                instance ?: VocabStore(context).also { instance = it }
            }
    }
}
