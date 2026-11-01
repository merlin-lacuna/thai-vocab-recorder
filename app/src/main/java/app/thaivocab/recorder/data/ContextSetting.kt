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
    private val prefs = context.applicationContext.getSharedPreferences("capture_context", Context.MODE_PRIVATE)
    private val _current = MutableStateFlow(load())
    val current: StateFlow<CaptureContext> = _current.asStateFlow()

    private fun load(): CaptureContext {
        val lastUsed = prefs.getLong("last_used", 0)
        if (System.currentTimeMillis() - lastUsed > RESET_AFTER_MS) return CaptureContext.FIELD
        return runCatching { CaptureContext.valueOf(prefs.getString("value", null)!!) }
            .getOrDefault(CaptureContext.FIELD)
    }

    fun set(value: CaptureContext) {
        _current.value = value
        touch()
    }

    /** Call when the context is used (selected, or a word is recorded). */
    fun touch() {
        prefs.edit().putString("value", _current.value.name).putLong("last_used", System.currentTimeMillis()).apply()
    }

    /** Re-checks the 4-hour reset, for example when the app returns to the screen. */
    fun recheck() {
        _current.value = load()
    }

    private companion object {
        const val RESET_AFTER_MS = 4 * 60 * 60 * 1000L
    }
}
