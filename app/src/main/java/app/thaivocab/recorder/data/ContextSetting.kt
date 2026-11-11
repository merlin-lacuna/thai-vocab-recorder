package app.thaivocab.recorder.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The selected capture context (Field work, Lingping, lesson with a teacher).
 * It stays selected between app starts, but returns to Field work after
 * [RESET_AFTER_MS] without use, so a forgotten lesson flag does not label later words.
 */
class ContextSetting(context: Context) {
    private val catalog = ContextCatalog.get(context)
    private val prefs = context.applicationContext.getSharedPreferences("capture_context", Context.MODE_PRIVATE)
    private val _current = MutableStateFlow(load())
    val current: StateFlow<CaptureContext> = _current.asStateFlow()

    /** "Native speaker" switch: new recordings are a native speaker's pronunciation,
     *  and playback prefers native clips. Resets together with the context. */
    private val _native = MutableStateFlow(loadNative())
    val native: StateFlow<Boolean> = _native.asStateFlow()

    private fun loadNative(): Boolean {
        val lastUsed = prefs.getLong("last_used", 0)
        if (System.currentTimeMillis() - lastUsed > RESET_AFTER_MS) return false
        return prefs.getBoolean("native", false)
    }

    fun setNative(value: Boolean) {
        _native.value = value
        touch()
    }

    private fun load(): CaptureContext {
        val lastUsed = prefs.getLong("last_used", 0)
        if (System.currentTimeMillis() - lastUsed > RESET_AFTER_MS) return CaptureContext.FIELD
        val saved = catalog.resolveId(prefs.getString("value", null))
        // A label that was removed (hidden) is no longer selected.
        return if (catalog.selectable.value.any { it.id == saved.id }) saved else CaptureContext.FIELD
    }

    fun set(value: CaptureContext) {
        _current.value = value
        touch()
    }

    /** Call when the context is used (selected, or a word is recorded). */
    fun touch() {
        prefs.edit()
            .putString("value", _current.value.id)
            .putBoolean("native", _native.value)
            .putLong("last_used", System.currentTimeMillis())
            .apply()
    }

    /** Re-checks the 4-hour reset, for example when the app returns to the screen. */
    fun recheck() {
        _current.value = load()
        _native.value = loadNative()
    }

    private companion object {
        const val RESET_AFTER_MS = 4 * 60 * 60 * 1000L
    }
}
