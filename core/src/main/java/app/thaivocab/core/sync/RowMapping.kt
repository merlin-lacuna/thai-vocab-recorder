package app.thaivocab.core.sync

import app.thaivocab.core.data.Transcription
import app.thaivocab.core.data.VocabEntry
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

const val AUDIO_BUCKET = "vocab-audio"
const val HANDWRITING_BUCKET = "vocab-handwriting"

fun audioPath(userId: String, id: String) = "$userId/$id.wav"
fun imagePath(userId: String, id: String) = "$userId/$id.png"
fun englishImagePath(userId: String, id: String) = "$userId/$id-en.png"

/** Maps vocab_items.transcription_status to the local enum. */
fun transcriptionOf(status: String?, hasThai: Boolean): Transcription = when (status) {
    "done" -> Transcription.DONE
    "guessed" -> Transcription.GUESSED
    "failed" -> Transcription.FAILED
    "pending" -> Transcription.PENDING
    else -> if (hasThai) Transcription.MANUAL else Transcription.FAILED
}

/** The JSON row that the sync job upserts into vocab_items. */
fun VocabEntry.toRow(userId: String) = JSONObject().apply {
    put("id", id)
    put("thai", thai ?: JSONObject.NULL)
    put("thai_alternatives", JSONArray(alternatives))
    put("notes", notes ?: JSONObject.NULL)
    put("audio_path", audioFile?.let { audioPath(userId, id) } ?: JSONObject.NULL)
    put("source", source)
    // Native clip fields only when known, so a stale device never erases a clip that a
    // batch job added in the meantime.
    if (nativeCleared) {
        // The user removed the native clip on the device.
        put("native_audio_path", JSONObject.NULL)
        put("native_audio_source", JSONObject.NULL)
    } else {
        nativeAudioPath?.let { put("native_audio_path", it) }
        nativeAudioSource?.let { put("native_audio_source", it) }
    }
    // Handwriting columns, by the same rule.
    if (imageCleared) {
        put("image_path", JSONObject.NULL)
        put("english_image_path", JSONObject.NULL)
    } else {
        imageFile?.let { put("image_path", imagePath(userId, id)) }
        englishImageFile?.let { put("english_image_path", englishImagePath(userId, id)) }
    }
    writtenScript?.let { put("written_script", it) }
    put("context", context.dbValue)
    put("teacher", context.teacher ?: JSONObject.NULL)
    put("transcription_error", transcriptionError ?: JSONObject.NULL)
    put("transcription_status", transcription.name.lowercase())
    put("captured_at", Instant.ofEpochMilli(capturedAt).toString())
    put("updated_at", Instant.ofEpochMilli(updatedAt).toString())
    put("deleted_at", if (deleted) Instant.ofEpochMilli(updatedAt).toString() else JSONObject.NULL)
}
