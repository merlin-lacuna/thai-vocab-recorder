package app.thaivocab.boox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.thaivocab.core.data.CaptureContext
import app.thaivocab.core.data.Transcription
import app.thaivocab.core.data.VocabEntry

private fun sample(
    id: String, thai: String?, transcription: Transcription, romanization: String? = null, english: String? = null,
    script: String = "thai", writtenText: String? = null, writtenEnglish: String? = null, minutesAgo: Int = 0,
) = VocabEntry(
    id = id, thai = thai, alternatives = emptyList(), notes = null, audioFile = null,
    capturedAt = 1_794_556_000_000 - minutesAgo * 60_000L, updatedAt = 0, context = CaptureContext.KRU_BOOM,
    source = "boox_handwriting", romanization = romanization, english = english,
    imageFile = "/sample/$id.png", writtenScript = script, writtenText = writtenText, writtenEnglish = writtenEnglish,
    transcription = transcription, dirty = transcription == Transcription.PENDING, audioUploaded = false, deleted = false,
)

val sampleEntries = listOf(
    sample("1", null, Transcription.PENDING),
    sample("2", "ไม่เป็นไร", Transcription.GUESSED, "mâi bpen rai", "never mind, no problem",
        script = "phonetic", writtenText = "mai pen rai", writtenEnglish = "no problem", minutesAgo = 4),
    sample("3", "ตลาดน้ำ", Transcription.DONE, "dtà-làat-náam", "floating market", minutesAgo = 9),
    sample("4", null, Transcription.FAILED, minutesAgo = 15),
)

/** The tablet screen at the size of the Boox Note Air2 Plus (936 x 1248 dp). */
@Preview(widthDp = 936, heightDp = 1248)
@Composable
fun PreviewBoox() {
    EinkTheme {
        BooxContent(
            entries = sampleEntries, connected = true, syncError = null,
            captureContext = CaptureContext.KRU_BOOM, contextOptions = CaptureContext.BUILT_IN,
            onContextChange = {}, onEditLabels = {}, onLabelMenu = {},
            updateBuild = null, updateProgress = null, onUpdateClick = {},
            onSyncBarClick = {}, onEntryClick = {}, thaiInk = true,
            onClear = {}, onType = {}, onNext = {},
            pad = { Box(Modifier.fillMaxSize().background(Paper)) },
        )
    }
}
