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
    val context: CaptureContext = CaptureContext.FIELD,
    /** Why speech recognition failed, if it did. */
    val transcriptionError: String? = null,
    /** Filled in the database (by hand or by an enrichment step); read-only on the phone. */
    val english: String? = null,
    val romanization: String? = null,
    /** Input that created the row; kept when the phone edits rows from other sources. */
    val source: String = "field_recorder",
    /** Native Thai pronunciation: local file, path in the cloud, and who made it. */
    val nativeAudioFile: String? = null,
    val nativeAudioPath: String? = null,
    val nativeAudioSource: String? = null,
    val nativeAudioUploaded: Boolean = true,
    val transcription: Transcription,
    /** True when the local version has changes that are not in the cloud yet. */
    val dirty: Boolean,
    val audioUploaded: Boolean,
    val deleted: Boolean,
)

/** Small SQLite store on the phone. One instance per process. */
class VocabStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "vocab.db", null, 5) {

    private val catalog = ContextCatalog.get(context)

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
                deleted INTEGER NOT NULL DEFAULT 0,
                context TEXT NOT NULL DEFAULT 'FIELD',
                transcription_error TEXT,
                english TEXT,
                romanization TEXT,
                source TEXT NOT NULL DEFAULT 'field_recorder',
                native_audio_file TEXT,
                native_audio_path TEXT,
                native_audio_source TEXT,
                native_audio_uploaded INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE entries ADD COLUMN context TEXT NOT NULL DEFAULT 'FIELD'")
        if (oldVersion < 3) db.execSQL("ALTER TABLE entries ADD COLUMN transcription_error TEXT")
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE entries ADD COLUMN english TEXT")
            db.execSQL("ALTER TABLE entries ADD COLUMN romanization TEXT")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE entries ADD COLUMN source TEXT NOT NULL DEFAULT 'field_recorder'")
            db.execSQL("ALTER TABLE entries ADD COLUMN native_audio_file TEXT")
            db.execSQL("ALTER TABLE entries ADD COLUMN native_audio_path TEXT")
            db.execSQL("ALTER TABLE entries ADD COLUMN native_audio_source TEXT")
            db.execSQL("ALTER TABLE entries ADD COLUMN native_audio_uploaded INTEGER NOT NULL DEFAULT 1")
        }
    }

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
            e.nativeAudioFile?.let { File(it).delete() }
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
    fun markNativeUploaded(id: String, path: String) {
        val values = ContentValues().apply {
            put("native_audio_uploaded", 1)
            put("native_audio_path", path)
        }
        writableDatabase.update("entries", values, "id = ?", arrayOf(id))
    }

    @Synchronized
    fun markAudioUploaded(id: String) {
        val values = ContentValues().apply { put("audio_uploaded", 1) }
        writableDatabase.update("entries", values, "id = ?", arrayOf(id))
    }

    /**
     * Applies the newest rows from the cloud database. [server] holds the newest rows
     * (deleted ones included), newest first; [complete] is true when it holds all rows.
     * Local entries with changes that are not uploaded yet keep the local version.
     * Clean local entries that are missing on the server (deleted in the dashboard) are removed.
     */
    @Synchronized
    fun mergeFromServer(server: List<VocabEntry>, complete: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val serverIds = HashSet<String>()
            for (remote in server) {
                serverIds += remote.id
                val local = get(remote.id)
                if (local != null && local.dirty) continue
                if (remote.deleted) {
                    local?.audioFile?.let { File(it).delete() }
                    db.delete("entries", "id = ?", arrayOf(remote.id))
                } else {
                    db.insertWithOnConflict("entries", null, remote.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            // Clean local entries in the covered time range that the server no longer has.
            val oldest = if (complete || server.isEmpty()) 0L else server.minOf { it.capturedAt }
            db.query("entries", arrayOf("id", "audio_file"), "dirty = 0 AND captured_at >= ?",
                arrayOf(oldest.toString()), null, null, null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    if (id !in serverIds) {
                        if (!c.isNull(1)) File(c.getString(1)).delete()
                        db.delete("entries", "id = ?", arrayOf(id))
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        refresh()
    }

    /** Saves a row returned by the server, unless the local entry has newer unsent changes. */
    @Synchronized
    fun applyRemote(remote: VocabEntry) {
        val local = get(remote.id)
        if (local != null && local.dirty) return
        writableDatabase.insertWithOnConflict("entries", null, remote.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
        refresh()
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
        put("context", context.id)
        put("transcription_error", transcriptionError)
        put("english", english)
        put("romanization", romanization)
        put("source", source)
        put("native_audio_file", nativeAudioFile)
        put("native_audio_path", nativeAudioPath)
        put("native_audio_source", nativeAudioSource)
        put("native_audio_uploaded", if (nativeAudioUploaded) 1 else 0)
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
            context = catalog.resolveId(str("context")),
            transcriptionError = str("transcription_error"),
            english = str("english"),
            romanization = str("romanization"),
            source = str("source") ?: "field_recorder",
            nativeAudioFile = str("native_audio_file"),
            nativeAudioPath = str("native_audio_path"),
            nativeAudioSource = str("native_audio_source"),
            nativeAudioUploaded = long("native_audio_uploaded") == 1L,
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
