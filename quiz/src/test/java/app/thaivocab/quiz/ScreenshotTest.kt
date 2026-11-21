package app.thaivocab.quiz

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.thaivocab.core.update.Updater
import app.thaivocab.quiz.ui.QuizActions
import app.thaivocab.quiz.ui.QuizScreen
import app.thaivocab.quiz.ui.Result
import app.thaivocab.quiz.ui.Screen
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

/**
 * Draws the quiz screens with sample data (no phone, no server) and saves PNG files.
 * Run:  ./gradlew :quiz:recordPaparazziDebug
 * Output: quiz/src/test/snapshots/images/
 */
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, maxPercentDifference = 0.1)

    private class Sample(s: Screen, push: Boolean = true) : QuizActions {
        override val screen = MutableStateFlow(s)
        override val labels = MutableStateFlow(listOf(
            LabelOption("field", "Field work"), LabelOption("wondering", "Just wondering"),
            LabelOption("lingping_club", "Lingping"), LabelOption("Boom", "Kru Boom"), LabelOption("Ae", "Kru Ae"),
        ))
        override val selected = MutableStateFlow(setOf("Boom", "Ae"))
        override val pushReady = MutableStateFlow(push)
        override val update = MutableStateFlow<Updater.Available?>(null)
        override val updateProgress = MutableStateFlow<Float?>(null)
        override val email = "me@example.com"
        override fun elapsedMs() = 7_400L
        override fun signIn(email: String, password: String, onError: (String) -> Unit) {}
        override fun signOut() {}
        override fun toggleLabel(value: String) {}
        override fun clearLabels() {}
        override fun startRound(firstId: String?) {}
        override fun nextQuestion() {}
        override fun finish() {}
        override fun home() {}
        override fun choose(choice: Int) {}
        override fun playNative(url: String) {}
        override fun installUpdate() {}
    }

    private val question = Question(
        "q1", "Yesterday, I went to see my godson.", "godson",
        listOf(
            "เมื่อวานฉันไปหาหลานชาย", "เมื่อวานฉันไปหาลูกทูนหัว", "วันนี้ฉันไปหาลุง",
            "เมื่อวานฉันลืมไปหาพี่ชาย", "พรุ่งนี้ฉันจะไปหาลูกพี่ลูกน้อง", "เมื่อวานฉันไปหาพ่อทูนหัว",
        ),
    )
    private val options = listOf(
        Option("เมื่อวานฉันไปหาหลานชาย", "mʉ̂a-waan chǎn bpai hǎa lǎan-chaai", "Yesterday I went to see my nephew.", false),
        Option("เมื่อวานฉันไปหาลูกทูนหัว", "mʉ̂a-waan chǎn bpai hǎa lûuk-tuun-hǔa", "Yesterday I went to see my godson.", true),
        Option("วันนี้ฉันไปหาลุง", "wan-níi chǎn bpai hǎa lung", "Today I went to see my uncle.", false),
        Option("เมื่อวานฉันลืมไปหาพี่ชาย", "mʉ̂a-waan chǎn lʉʉm bpai hǎa pîi-chaai", "Yesterday I forgot to visit my older brother.", false),
        Option("พรุ่งนี้ฉันจะไปหาลูกพี่ลูกน้อง", "prûng-níi chǎn jà bpai hǎa lûuk-pîi-lûuk-nɔ́ɔng", "Tomorrow I will go and see my cousin.", false),
        Option("เมื่อวานฉันไปหาพ่อทูนหัว", "mʉ̂a-waan chǎn bpai hǎa pɔ̂ɔ-tuun-hǔa", "Yesterday I went to see my godfather.", false),
    )
    private val answer = Answer(true, 1, Word("ลูกทูนหัว", "lûuk-tuun-hǔa", "godson, goddaughter", "noun"), null, options)

    private fun shot(s: Screen, push: Boolean = true) = paparazzi.snapshot {
        MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF13315C))) { QuizScreen(Sample(s, push)) }
    }

    @Test fun login() = shot(Screen.Login)
    @Test fun home() = shot(Screen.Home)
    @Test fun homeWithoutNtfy() = shot(Screen.Home, push = false)
    @Test fun question() = shot(Screen.Asking(2, question, emptyMap(), checking = false))
    @Test fun questionWrongTry() = shot(Screen.Asking(2, question, mapOf(0 to options[0]), checking = false))
    @Test fun solved() = shot(Screen.Solved(2, question, answer, 7.4, 2))
    @Test fun summary() = shot(Screen.Summary(listOf(
        Result("ลูกทูนหัว", "godson, goddaughter", 7.4, 2),
        Result("โน้มน้าว", "to convince", 4.1, 1),
        Result("กระดานดำ", "blackboard", 3.2, 1),
        Result("คัดตัว", "to audition", 12.8, 3),
        Result("หิ้ว", "to carry by hand", 5.0, 1),
    )))
}
