package app.thaivocab.boox.ui

import app.thaivocab.core.data.CaptureContext
import app.thaivocab.core.data.VocabEntry

/**
 * Text for comparing: lower case, and Latin letters without their accents, so the search
 * text "plom" finds the romanization "bplɔɔm" only by its plain letters and "mai" finds "mâi".
 * Thai vowel and tone marks are outside the removed range and stay.
 */
internal fun plain(text: String): String =
    java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        .filterNot { it in '̀'..'ͯ' }
        .lowercase()

/** The texts of an entry that the search box looks at. */
private fun searchTexts(entry: VocabEntry): List<String> = listOfNotNull(
    entry.thai, entry.romanization, entry.english, entry.notes, entry.writtenText, entry.writtenEnglish,
) + entry.alternatives

/**
 * The entries that the list shows, newest first.
 *
 * [query]: the text of the search box. An entry passes when one of its texts contains it.
 * An empty text passes every entry. [labelId]: the id of the selected label
 * ([CaptureContext.id]), or null for all labels.
 */
fun filterEntries(entries: List<VocabEntry>, query: String, labelId: String?): List<VocabEntry> {
    val wanted = plain(query.trim())
    return entries
        .filter { labelId == null || it.context.id == labelId }
        .filter { entry -> wanted.isEmpty() || searchTexts(entry).any { wanted in plain(it) } }
        .sortedByDescending { it.capturedAt }
}

/** The labels that at least one entry has. The label of the newest entry comes first. */
fun usedLabels(entries: List<VocabEntry>): List<CaptureContext> =
    entries.sortedByDescending { it.capturedAt }.map { it.context }.distinctBy { it.id }
