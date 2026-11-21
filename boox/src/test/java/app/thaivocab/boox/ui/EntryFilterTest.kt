package app.thaivocab.boox.ui

import app.thaivocab.core.data.CaptureContext
import app.thaivocab.core.data.Transcription
import app.thaivocab.core.data.VocabEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class EntryFilterTest {
    private fun entry(
        id: String, thai: String, at: Long, context: CaptureContext = CaptureContext.FIELD,
        romanization: String? = null, english: String? = null, notes: String? = null,
    ) = VocabEntry(
        id = id, thai = thai, alternatives = emptyList(), notes = notes, audioFile = null,
        capturedAt = at, updatedAt = at, context = context, romanization = romanization, english = english,
        transcription = Transcription.DONE, dirty = false, audioUploaded = true, deleted = false,
    )

    private val market = entry("1", "ตลาดน้ำ", 100, CaptureContext.KRU_BOOM, "dtà-làat-náam", "floating market")
    private val never = entry("2", "ไม่เป็นไร", 300, CaptureContext.FIELD, "mâi bpen rai", "never mind")
    private val ant = entry("3", "มด", 200, CaptureContext.KRU_BOOM, "mót", "ant", notes = "from the picture book")
    private val all = listOf(market, never, ant)

    private fun ids(entries: List<VocabEntry>) = entries.map { it.id }

    @Test fun `without search text and label the list has every entry, newest first`() {
        assertEquals(listOf("2", "3", "1"), ids(filterEntries(all, "", null)))
    }

    @Test fun `a label keeps the entries of that label only`() {
        assertEquals(listOf("3", "1"), ids(filterEntries(all, "", CaptureContext.KRU_BOOM.id)))
    }

    @Test fun `the search finds Thai text, English text and notes`() {
        assertEquals(listOf("1"), ids(filterEntries(all, "ตลาด", null)))
        assertEquals(listOf("1"), ids(filterEntries(all, "Market", null)))
        assertEquals(listOf("3"), ids(filterEntries(all, "picture", null)))
    }

    @Test fun `the search finds a romanization typed without accents`() {
        assertEquals(listOf("2"), ids(filterEntries(all, "mai bpen", null)))
        assertEquals(listOf("3"), ids(filterEntries(all, " mot ", null)))
    }

    @Test fun `search text and label work together`() {
        assertEquals(emptyList<String>(), ids(filterEntries(all, "never", CaptureContext.KRU_BOOM.id)))
        assertEquals(listOf("2"), ids(filterEntries(all, "never", CaptureContext.FIELD.id)))
    }

    @Test fun `used labels are the labels of the entries, newest use first`() {
        assertEquals(listOf(CaptureContext.FIELD, CaptureContext.KRU_BOOM), usedLabels(all))
    }
}
