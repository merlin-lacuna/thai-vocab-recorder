package app.thaivocab.recorder.speech

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import app.thaivocab.recorder.audio.WavRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * Converts a recorded WAV clip into Thai text with the Android speech recognisers.
 *
 * The clip is fed to the recogniser through EXTRA_AUDIO_SOURCE (Android 13+), so the
 * microphone is used only once, by our own recorder, and the audio file is kept.
 * First the default (Google online) recogniser is tried, then the on-device recogniser.
 */
class ThaiTranscriber(private val context: Context) {

    /** [texts] holds the candidates, best first. When it is empty, [error] says why. */
    data class Outcome(val texts: List<String>, val error: String?)

    suspend fun transcribe(wav: File): Outcome {
        val errors = mutableListOf<String>()

        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            val r = attempt(wav, onDevice = false)
            if (r.texts.isNotEmpty()) return r
            errors += "online: ${r.error}"
        } else {
            errors += "online: not available"
        }

        if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            val r = attempt(wav, onDevice = true)
            if (r.texts.isNotEmpty()) return r
            errors += "on-device: ${r.error}"
        } else {
            errors += "on-device: not available"
        }

        return Outcome(emptyList(), errors.joinToString("; "))
    }

    private suspend fun attempt(wav: File, onDevice: Boolean): Outcome =
        withTimeoutOrNull(TIMEOUT_MS) {
            withContext(Dispatchers.Main) { recognise(wav, onDevice) }
        } ?: Outcome(emptyList(), "timeout")

    private suspend fun recognise(wav: File, onDevice: Boolean): Outcome = suspendCancellableCoroutine { cont ->
        val recognizer = if (onDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        val (readSide, writeSide) = ParcelFileDescriptor.createPipe()

        fun finish(result: Outcome) {
            if (cont.isActive) cont.resume(result)
            recognizer.destroy()
            runCatching { readSide.close() }
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                finish(Outcome(list, if (list.isEmpty()) "no words found" else null))
            }

            override fun onError(error: Int) {
                Log.w(TAG, "Recognition error $error (onDevice=$onDevice)")
                finish(Outcome(emptyList(), errorName(error)))
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "th-TH")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "th-TH")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, WavRecorder.SAMPLE_RATE)
        }

        // Write the PCM data (without the WAV header) into the pipe, then add one second
        // of silence so the recogniser detects the end of speech, then close the pipe.
        Thread {
            ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
                runCatching {
                    wav.inputStream().use { input ->
                        input.skip(WavRecorder.WAV_HEADER_SIZE.toLong())
                        input.copyTo(out)
                    }
                    out.write(ByteArray(WavRecorder.SAMPLE_RATE * 2))
                }.onFailure { Log.w(TAG, "Could not feed audio", it) }
            }
        }.apply { name = "transcriber-feed"; start() }

        cont.invokeOnCancellation {
            Handler(Looper.getMainLooper()).post {
                recognizer.cancel()
                recognizer.destroy()
                runCatching { readSide.close() }
            }
        }
        recognizer.startListening(intent)
    }

    private companion object {
        const val TAG = "ThaiTranscriber"
        const val TIMEOUT_MS = 20_000L

        fun errorName(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
            SpeechRecognizer.ERROR_NETWORK -> "network error"
            SpeechRecognizer.ERROR_AUDIO -> "audio error"
            SpeechRecognizer.ERROR_SERVER -> "server error"
            SpeechRecognizer.ERROR_CLIENT -> "client error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech heard"
            SpeechRecognizer.ERROR_NO_MATCH -> "no match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recogniser busy"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "no permission"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "too many requests"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "server disconnected"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Thai not supported"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Thai not downloaded"
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "cannot check support"
            SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> "cannot listen to download events"
            else -> "error $code"
        }
    }
}
