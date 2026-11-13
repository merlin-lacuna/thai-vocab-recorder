package app.thaivocab.core.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Where a word came from. [key] is stored in vocab_items.context; lessons use the key
 * "lesson" plus a [teacher]. Built-in contexts are fixed; the user adds others in the app
 * ("Labels" window), stored in the table capture_contexts.
 */
data class CaptureContext(
    val key: String,
    val label: String,
    val teacher: String? = null,
    val builtIn: Boolean = false,
) {
    val isLesson: Boolean get() = teacher != null
    val dbValue: String get() = key

    /** Unique id of the option, also saved on the phone: "field", "lesson:Boom", "market". */
    val id: String get() = if (teacher != null) "lesson:$teacher" else key

    companion object {
        val FIELD = CaptureContext("field", "Field work", builtIn = true)
        val WONDERING = CaptureContext("wondering", "Just wondering", builtIn = true)
        val LINGPING = CaptureContext("lingping_club", "Lingping", builtIn = true)
        val KRU_BOOM = CaptureContext("lesson", "Kru Boom", "Boom", builtIn = true)
        val KRU_AE = CaptureContext("lesson", "Kru Ae", "Ae", builtIn = true)
        val BUILT_IN = listOf(FIELD, WONDERING, LINGPING, KRU_BOOM, KRU_AE)

        /** Names used by app versions before custom labels existed. */
        val LEGACY_NAMES = mapOf(
            "FIELD" to "field", "WONDERING" to "wondering", "LINGPING" to "lingping_club",
            "KRU_BOOM" to "lesson:Boom", "KRU_AE" to "lesson:Ae",
        )
    }
}

/**
 * A label that the user added. [dirty]: changed on the phone, not uploaded yet.
 * [removed]: the user deleted it; the sync job deletes the row on the server, or hides it
 * when words still use it.
 */
data class CustomContext(
    val key: String,
    val label: String,
    val sortOrder: Int,
    val hidden: Boolean,
    val dirty: Boolean,
    val removed: Boolean = false,
)

/**
 * All capture contexts: the built-in ones and the user's own, with a copy on the phone so
 * that the app works offline. The sync job uploads changes and downloads the server list.
 */
class ContextCatalog private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("contexts", Context.MODE_PRIVATE)

    private val _customs = MutableStateFlow(load())
    val customs: StateFlow<List<CustomContext>> = _customs.asStateFlow()

    private val _selectable = MutableStateFlow(selectableFrom(_customs.value))
    /** The options shown in the app: built-in first, then the user's visible labels. */
    val selectable: StateFlow<List<CaptureContext>> = _selectable.asStateFlow()

    private fun selectableFrom(list: List<CustomContext>) =
        CaptureContext.BUILT_IN + list.filter { !it.hidden }.sortedWith(compareBy({ it.sortOrder }, { it.label }))
            .map { CaptureContext(it.key, it.label) }

    /** Finds the option for a saved id (also the old names); unknown ids become Field work. */
    fun resolveId(id: String?): CaptureContext {
        val clean = CaptureContext.LEGACY_NAMES[id] ?: id ?: return CaptureContext.FIELD
        CaptureContext.BUILT_IN.firstOrNull { it.id == clean }?.let { return it }
        if (clean.startsWith("lesson:")) {
            val teacher = clean.removePrefix("lesson:")
            return CaptureContext("lesson", "Kru $teacher", teacher)
        }
        // Hidden labels keep their name on old words.
        _customs.value.firstOrNull { it.key == clean }?.let { return CaptureContext(it.key, it.label) }
        return CaptureContext(clean, clean)
    }

    /** Maps the database columns (context, teacher) to an option. */
    fun fromDb(key: String?, teacher: String?): CaptureContext = when {
        key == null -> CaptureContext.FIELD
        key == "lesson" && teacher != null -> resolveId("lesson:${teacher.replaceFirstChar { it.uppercase() }}")
        key == "lesson" -> CaptureContext.KRU_BOOM
        else -> resolveId(key)
    }

    /** Adds a label and returns it. The key is made from the label (letters a-z, digits, "_"). */
    @Synchronized
    fun add(label: String): CaptureContext {
        val clean = label.trim().take(40)
        val used = (CaptureContext.BUILT_IN.map { it.key } + _customs.value.map { it.key }).toSet() + "lesson"
        val base = clean.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(30)
        var key = base.ifEmpty { "ctx_" + UUID.randomUUID().toString().take(6) }
        var n = 2
        while (key in used) key = "${base.ifEmpty { "ctx" }}_${n++}"
        // A hidden label with the same text comes back instead of a new one.
        val existing = _customs.value.firstOrNull { it.hidden && it.label.equals(clean, ignoreCase = true) }
        if (existing != null) {
            save(_customs.value.map { if (it.key == existing.key) it.copy(hidden = false, dirty = true) else it })
            return CaptureContext(existing.key, existing.label)
        }
        val sort = (_customs.value.maxOfOrNull { it.sortOrder } ?: 100) + 1
        save(_customs.value + CustomContext(key, clean, sort, hidden = false, dirty = true))
        return CaptureContext(key, clean)
    }

    @Synchronized
    fun rename(key: String, label: String) =
        save(_customs.value.map { if (it.key == key) it.copy(label = label.trim().take(40), dirty = true) else it })

    /** Deletes the label: it leaves the selection at once; the sync job finishes the job. */
    @Synchronized
    fun remove(key: String) =
        save(_customs.value.map { if (it.key == key) it.copy(hidden = true, removed = true, dirty = true) else it })

    /** After the server row was deleted (no word used the label). */
    @Synchronized
    fun drop(key: String) = save(_customs.value.filter { it.key != key })

    /** After the server row was hidden instead (words still use the label). */
    @Synchronized
    fun keepHidden(key: String) =
        save(_customs.value.map { if (it.key == key) it.copy(removed = false, dirty = false, hidden = true) else it })

    fun dirty(): List<CustomContext> = _customs.value.filter { it.dirty }

    /** After an upload: clears the flag of the rows that did not change meanwhile. */
    @Synchronized
    fun markUploaded(uploaded: List<CustomContext>) =
        save(_customs.value.map { c -> if (uploaded.any { it == c }) c.copy(dirty = false) else c })

    /** Replaces the list with the server's list; rows with changes not uploaded yet stay. */
    @Synchronized
    fun replaceFromServer(server: List<CustomContext>) {
        val local = _customs.value
        val keep = local.filter { it.dirty }
        val merged = server.filter { s -> keep.none { it.key == s.key } } + keep
        if (merged.toSet() != local.toSet()) save(merged)
    }

    private fun save(list: List<CustomContext>) {
        val json = JSONArray(list.map {
            JSONObject().put("key", it.key).put("label", it.label).put("sort", it.sortOrder)
                .put("hidden", it.hidden).put("dirty", it.dirty).put("removed", it.removed)
        })
        prefs.edit().putString("custom", json.toString()).apply()
        _customs.value = list
        _selectable.value = selectableFrom(list)
    }

    private fun load(): List<CustomContext> = runCatching {
        val arr = JSONArray(prefs.getString("custom", "[]"))
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            CustomContext(o.getString("key"), o.getString("label"), o.optInt("sort", 100),
                o.optBoolean("hidden"), o.optBoolean("dirty"), o.optBoolean("removed"))
        }
    }.getOrDefault(emptyList())

    companion object {
        @Volatile private var instance: ContextCatalog? = null
        fun get(context: Context): ContextCatalog =
            instance ?: synchronized(this) { instance ?: ContextCatalog(context).also { instance = it } }
    }
}
