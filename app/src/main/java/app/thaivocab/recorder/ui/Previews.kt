package app.thaivocab.recorder.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.thaivocab.recorder.data.CaptureContext
import app.thaivocab.recorder.data.Transcription
import app.thaivocab.recorder.data.VocabEntry

/**
 * Sample data and preview functions. Android Studio shows these screens in the
 * editor (split view, "Preview" tab); the screenshot tests in app/src/test use them too.
 */
object SampleData {
    private const val NOW = 1_762_000_000_000L
    private const val MIN = 60_000L

    private fun entry(
        id: String,
        thai: String?,
        alternatives: List<String> = emptyList(),
        notes: String? = null,
        minutesAgo: Long,
        transcription: Transcription = Transcription.DONE,
        dirty: Boolean = false,
        audio: Boolean = true,
        context: CaptureContext = CaptureContext.FIELD,
    ) = VocabEntry(
        id = id, thai = thai, alternatives = alternatives, notes = notes,
        audioFile = if (audio) "/sample/$id.wav" else null,
        capturedAt = NOW - minutesAgo * MIN, updatedAt = NOW - minutesAgo * MIN,
        transcription = transcription, dirty = dirty, audioUploaded = !dirty, deleted = false,
        context = context,
    )

    val entries = listOf(
        entry("1", null, minutesAgo = 0, transcription = Transcription.PENDING, dirty = true),
        entry("2", "ตลาดน้ำ", listOf("ตลาด น้ำ", "ตลาดนัด"), notes = "floating market – boat driver", minutesAgo = 4, dirty = true),
        entry("3", "ขอบคุณครับ", minutesAgo = 35, context = CaptureContext.LINGPING),
        entry("4", null, minutesAgo = 80, transcription = Transcription.FAILED)
            .copy(transcriptionError = "online: no match; on-device: Thai not downloaded"),
        entry("5", "ห้ามจอดรถ", notes = "sign on the gate", minutesAgo = 120, transcription = Transcription.MANUAL, audio = false),
        entry("6", "อร่อยมาก", listOf("อร่อย มาก"), minutesAgo = 300, context = CaptureContext.KRU_BOOM),
    )
}

@Preview(name = "List", showBackground = true, heightDp = 780, widthDp = 412)
@Composable
fun PreviewList() = ThaiVocabTheme(darkTheme = false) {
    MainContent(
        entries = SampleData.entries, recording = false, message = null, connected = true, syncError = null,
        playingId = "2", playProgress = { 0.6f },
    )
}

@Preview(name = "List – dark", showBackground = true, heightDp = 780, widthDp = 412)
@Composable
fun PreviewListDark() = ThaiVocabTheme(darkTheme = true) {
    MainContent(entries = SampleData.entries, recording = false, message = null, connected = true, syncError = null)
}

@Preview(name = "Recording", showBackground = true, heightDp = 780, widthDp = 412)
@Composable
fun PreviewRecording() = ThaiVocabTheme(darkTheme = false) {
    MainContent(
        entries = SampleData.entries.drop(1), recording = true, message = null, connected = true, syncError = null,
        captureContext = CaptureContext.KRU_AE,
        levels = List(60) { i -> if (i < 15) 0f else (0.05f + 0.6f * kotlin.math.abs(kotlin.math.sin(i / 3.0)).toFloat()) },
        playingId = null,
    )
}

@Preview(name = "First start", showBackground = true, heightDp = 780, widthDp = 412)
@Composable
fun PreviewEmpty() = ThaiVocabTheme(darkTheme = false) {
    MainContent(
        entries = emptyList(), recording = false, message = "Hold the button while you speak",
        connected = false, syncError = null,
    )
}

@Preview(name = "Edit dialog", showBackground = true, widthDp = 412)
@Composable
fun PreviewEditDialog() = ThaiVocabTheme(darkTheme = false) {
    EditDialog(entry = SampleData.entries[1], onDismiss = {}, onSave = { _, _ -> }, onDelete = {}, onPlay = {})
}
