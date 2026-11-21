package app.thaivocab.quiz.ui

import android.app.Application
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.thaivocab.core.sync.SupabaseConfig
import app.thaivocab.core.update.Updater
import app.thaivocab.quiz.Answer
import app.thaivocab.quiz.LabelOption
import app.thaivocab.quiz.Option
import app.thaivocab.quiz.Question
import app.thaivocab.quiz.QuizApi
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One finished question of a round, for the summary. */
data class Result(val thai: String, val english: String, val seconds: Double, val tries: Int)

sealed interface Screen {
    data object Login : Screen
    data object Home : Screen
    /** Waiting for question [number] of the round. */
    data class Loading(val number: Int) : Screen
    /** [wrong]: choices tapped so far that were wrong, with their meaning. */
    data class Asking(
        val number: Int, val question: Question, val wrong: Map<Int, Option>, val checking: Boolean,
    ) : Screen
    data class Solved(val number: Int, val question: Question, val answer: Answer, val seconds: Double, val tries: Int) : Screen
    data class Summary(val results: List<Result>) : Screen
    data class Failed(val message: String) : Screen
}

class QuizViewModel(app: Application) : AndroidViewModel(app), QuizActions {
    private val api = QuizApi.get(app)
    private val config = SupabaseConfig.get(app)

    private val _screen = MutableStateFlow<Screen>(if (config.state.value.connected) Screen.Home else Screen.Login)
    override val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _labels = MutableStateFlow<List<LabelOption>>(emptyList())
    override val labels: StateFlow<List<LabelOption>> = _labels.asStateFlow()

    private val _selected = MutableStateFlow(api.selectedLabels.toSet())
    override val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    /** True when a push distributor (ntfy) is installed and registered. */
    private val _pushReady = MutableStateFlow(true)
    override val pushReady: StateFlow<Boolean> = _pushReady.asStateFlow()

    private val updater = Updater(app)
    private val _update = MutableStateFlow<Updater.Available?>(null)
    override val update: StateFlow<Updater.Available?> = _update.asStateFlow()
    private val _updateProgress = MutableStateFlow<Float?>(null)
    override val updateProgress: StateFlow<Float?> = _updateProgress.asStateFlow()

    override val email: String get() = config.state.value.email

    // Round state.
    private val roundIds = mutableListOf<String>()
    private val results = mutableListOf<Result>()
    private var next: Deferred<Question>? = null
    private var startedAt = 0L   // SystemClock.elapsedRealtime() when the choices appeared, moved forward by waits
    private var player: MediaPlayer? = null

    init {
        if (config.state.value.connected) onLoggedIn()
        viewModelScope.launch { _update.value = updater.check() }
    }

    private fun onLoggedIn() {
        viewModelScope.launch {
            runCatching { _labels.value = api.labels() }
                .onFailure { if (it is QuizApi.NotLoggedIn) _screen.value = Screen.Login }
            // Sends the push address again at each start, in case an earlier upload failed.
            api.endpoint?.let { e -> runCatching { api.registerPush(e) } }
        }
    }

    fun setPushReady(ready: Boolean) { _pushReady.value = ready }

    override fun signIn(email: String, password: String, onError: (String) -> Unit) {
        viewModelScope.launch {
            val error = api.signIn(email, password)
            if (error != null) onError(error) else { _screen.value = Screen.Home; onLoggedIn() }
        }
    }

    override fun signOut() { api.signOut(); _screen.value = Screen.Login }

    override fun toggleLabel(value: String) {
        val s = _selected.value.toMutableSet()
        if (!s.add(value)) s.remove(value)
        _selected.value = s
        api.selectedLabels = s.toList()
        next = null // a prepared question for the old filter is not used
    }

    override fun clearLabels() { _selected.value = emptySet(); api.selectedLabels = emptyList(); next = null }

    /** Starts a new series of questions. [firstId]: a quiz from a notification. */
    override fun startRound(firstId: String?) {
        roundIds.clear(); results.clear(); next = null
        showQuestion(1, firstId)
    }

    override fun nextQuestion() {
        val n = (screen.value as? Screen.Solved)?.number ?: return
        showQuestion(n + 1, null)
    }

    /** Ends the series and shows the summary of the answered questions. */
    override fun finish() {
        stopAudio()
        next = null
        _screen.value = if (results.isEmpty()) Screen.Home else Screen.Summary(results.toList())
    }

    override fun home() { stopAudio(); _screen.value = Screen.Home }

    private fun showQuestion(number: Int, id: String?) {
        stopAudio()
        _screen.value = Screen.Loading(number)
        viewModelScope.launch {
            try {
                val q = when {
                    id != null -> api.question(id)
                    else -> (next ?: fetchAsync()).await()
                }
                next = null
                roundIds += q.id
                startedAt = SystemClock.elapsedRealtime()
                _screen.value = Screen.Asking(number, q, emptyMap(), checking = false)
                // Fetch the next question while the user answers this one.
                next = fetchAsync()
            } catch (e: Exception) {
                next = null
                handle(e)
            }
        }
    }

    private fun fetchAsync(): Deferred<Question> {
        val exclude = roundIds.toList()
        val labels = _selected.value.toList()
        return viewModelScope.async { api.practice(labels, exclude) }
    }

    /** Milliseconds since the choices appeared, without the time spent waiting for the server. */
    override fun elapsedMs(): Long = SystemClock.elapsedRealtime() - startedAt

    override fun choose(choice: Int) {
        val s = screen.value as? Screen.Asking ?: return
        if (s.checking || choice in s.wrong) return
        val ms = elapsedMs()
        val waitStart = SystemClock.elapsedRealtime()
        _screen.value = s.copy(checking = true)
        viewModelScope.launch {
            try {
                val a = api.answer(s.question.id, choice, ms)
                startedAt += SystemClock.elapsedRealtime() - waitStart // the wait does not count
                if (a.correct) {
                    val tries = s.wrong.size + 1
                    results += Result(a.word.thai, a.word.english, ms / 1000.0, tries)
                    _screen.value = Screen.Solved(s.number, s.question, a, ms / 1000.0, tries)
                } else {
                    _screen.value = s.copy(wrong = s.wrong + (choice to a.options[choice]), checking = false)
                }
            } catch (e: Exception) {
                startedAt += SystemClock.elapsedRealtime() - waitStart
                _screen.value = s.copy(checking = false)
                handle(e)
            }
        }
    }

    override fun playNative(url: String) {
        stopAudio()
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            setDataSource(url)
            setOnPreparedListener { it.start() }
            setOnCompletionListener { stopAudio() }
            prepareAsync()
        }
    }

    private fun stopAudio() { player?.release(); player = null }

    override fun installUpdate() {
        if (_updateProgress.value != null) return
        viewModelScope.launch {
            _updateProgress.value = 0f
            try {
                val apk = updater.download { p -> _updateProgress.value = p }
                updater.install(apk)
            } catch (e: Exception) {
                handle(e)
            } finally {
                _updateProgress.value = null
            }
        }
    }

    private fun handle(e: Throwable) {
        when (e) {
            is QuizApi.NotLoggedIn -> _screen.value = Screen.Login
            is java.io.IOException -> _screen.value = Screen.Failed("No internet connection. Try again.")
            else -> _screen.value = Screen.Failed(e.message ?: e.toString())
        }
    }

    override fun onCleared() { stopAudio() }

}
