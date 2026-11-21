package app.thaivocab.quiz.ui

import app.thaivocab.core.update.Updater
import app.thaivocab.quiz.LabelOption
import kotlinx.coroutines.flow.StateFlow

/** What the screen reads and calls. QuizViewModel implements it; the screenshot tests use sample data. */
interface QuizActions {
    val screen: StateFlow<Screen>
    val labels: StateFlow<List<LabelOption>>
    val selected: StateFlow<Set<String>>
    val pushReady: StateFlow<Boolean>
    val update: StateFlow<Updater.Available?>
    val updateProgress: StateFlow<Float?>
    val email: String
    fun elapsedMs(): Long
    fun signIn(email: String, password: String, onError: (String) -> Unit)
    fun signOut()
    fun toggleLabel(value: String)
    fun clearLabels()
    fun startRound(firstId: String? = null)
    fun nextQuestion()
    fun home()
    fun choose(choice: Int)
    fun playNative(url: String)
    fun installUpdate()
}
