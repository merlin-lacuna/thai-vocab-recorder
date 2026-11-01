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
 * Converts a recorded WAV clip into Thai text with the Android speech recogniser
 * (Google speech services on a Pixel).
 *
 * The clip is fed to the recogniser through EXTRA_AUDIO_SOURCE (Android 13+), so the
 * microphone is used only once, by our own recorder, and the audio file is kept.
 */
class ThaiTranscriber(private val context: Context) {

    /** Returns the candidate transcripts, best first. Empty list when recognition fails. */
    suspend fun transcribe(wav: File): List<String> {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "No speech recognition service on this device")
            return emptyList()
        }
        return withTimeoutOrNull(TIMEOUT_MS) {
            withContext(Dispatchers.Main) { recognise(wav) }
        } ?: emptyList()
    }

    private suspend fun recognise(wav: File): List<String> = suspendCancellableCoroutine { cont ->
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val (readSide, writeSide) = ParcelFileDescriptor.createPipe()

        fun finish(result: List<String>) {
            if (cont.isActive) cont.resume(result)
            recognizer.destroy()
            runCatching { readSide.close() }
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                finish(list.map { it.trim() }.filter { it.isNotEmpty() }.distinct())
            }

            override fun onError(error: Int) {
                Log.w(TAG, "Recognition error $error")
                finish(emptyList())
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
        const val TIMEOUT_MS = 25_000L
    }
}
