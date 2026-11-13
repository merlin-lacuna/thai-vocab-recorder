package app.thaivocab.core.sync

import app.thaivocab.core.data.CaptureContext
import app.thaivocab.core.data.Transcription
import app.thaivocab.core.data.VocabEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RowMappingTest {
    private fun entry(
        imageFile: String? = null, englishImageFile: String? = null, imageCleared: Boolean = false,
        script: String? = null, transcription: Transcription = Transcription.PENDING,
    ) = VocabEntry(
        id = "id-1", thai = null, alternatives = emptyList(), notes = null, audioFile = null,
        capturedAt = 0, updatedAt = 0, context = CaptureContext.KRU_BOOM, source = "boox_handwriting",
        imageFile = imageFile, englishImageFile = englishImageFile, imageCleared = imageCleared,
        writtenScript = script, transcription = transcription,
        dirty = true, audioUploaded = false, deleted = false,
    )

    @Test fun `status text from the server becomes the enum`() {
        assertEquals(Transcription.GUESSED, transcriptionOf("guessed", hasThai = true))
        assertEquals(Transcription.DONE, transcriptionOf("done", hasThai = true))
        assertEquals(Transcription.PENDING, transcriptionOf("pending", hasThai = false))
        assertEquals(Transcription.MANUAL, transcriptionOf(null, hasThai = true))
        assertEquals(Transcription.FAILED, transcriptionOf(null, hasThai = false))
    }

    @Test fun `row of a handwriting entry carries the image paths and the script`() {
        val row = entry(imageFile = "/x/id-1.png", englishImageFile = "/x/id-1-en.png", script = "phonetic")
            .toRow(userId = "u1")
        assertEquals("u1/id-1.png", row.getString("image_path"))
        assertEquals("u1/id-1-en.png", row.getString("english_image_path"))
        assertEquals("phonetic", row.getString("written_script"))
        assertEquals("boox_handwriting", row.getString("source"))
        assertEquals("pending", row.getString("transcription_status"))
        assertEquals("Boom", row.getString("teacher"))
    }

    @Test fun `row of an entry without handwriting leaves the image columns out`() {
        val row = entry().toRow(userId = "u1")
        assertFalse(row.has("image_path"))
        assertFalse(row.has("english_image_path"))
        assertFalse(row.has("written_script"))
    }

    @Test fun `removed handwriting image sends null paths`() {
        val row = entry(imageCleared = true).toRow(userId = "u1")
        assertTrue(row.has("image_path") && row.isNull("image_path"))
        assertTrue(row.has("english_image_path") && row.isNull("english_image_path"))
    }

    @Test fun `guessed status is written in lower case`() {
        val row = entry(transcription = Transcription.GUESSED).toRow(userId = "u1")
        assertEquals("guessed", row.getString("transcription_status"))
    }

    @Test fun `audio path is derived from the user id`() {
        val row = entry().copy(audioFile = "/x/id-1.wav").toRow(userId = "u1")
        assertEquals("u1/id-1.wav", row.getString("audio_path"))
        assertTrue(entry().toRow("u1").isNull("audio_path"))
    }
}
