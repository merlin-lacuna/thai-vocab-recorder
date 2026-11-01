package app.thaivocab.recorder.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records 16 kHz mono 16-bit PCM into a WAV file.
 *
 * This format is used because the speech recogniser can read it directly, and
 * because tools for later processing (for example Whisper) accept it without conversion.
 */
class WavRecorder {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var file: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = running

    /** Caller must hold the RECORD_AUDIO permission. */
    @SuppressLint("MissingPermission")
    fun start(target: File) {
        check(!running) { "Already recording" }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufSize = maxOf(minBuf, SAMPLE_RATE) // at least 0.5 s of audio
        val ar = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING, bufSize)
        check(ar.state == AudioRecord.STATE_INITIALIZED) { "Microphone is not available" }

        target.parentFile?.mkdirs()
        val raf = RandomAccessFile(target, "rw")
        raf.setLength(0)
        raf.write(ByteArray(WAV_HEADER_SIZE)) // header is filled in at the end

        record = ar
        file = target
        running = true
        startedAt = System.currentTimeMillis()
        ar.startRecording()

        thread = Thread {
            val buf = ByteArray(bufSize)
            var total = 0L
            try {
                while (running && total < MAX_BYTES) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n > 0) {
                        raf.write(buf, 0, n)
                        total += n
                    } else if (n < 0) break
                }
            } finally {
                writeHeader(raf, total)
                raf.close()
            }
        }.apply { name = "wav-recorder"; start() }
    }

    /** Stops recording and returns the duration in milliseconds. */
    fun stop(): Long {
        if (!running && thread == null) return 0
        running = false
        try {
            record?.stop()
        } catch (_: IllegalStateException) {
        }
        thread?.join(2000)
        record?.release()
        record = null
        thread = null
        return System.currentTimeMillis() - startedAt
    }

    private fun writeHeader(raf: RandomAccessFile, dataLen: Long) {
        val byteRate = SAMPLE_RATE * 2
        val h = ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt((36 + dataLen).toInt())
        h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16)
        h.putShort(1) // PCM
        h.putShort(1) // mono
        h.putInt(SAMPLE_RATE)
        h.putInt(byteRate)
        h.putShort(2) // block align
        h.putShort(16) // bits per sample
        h.put("data".toByteArray()); h.putInt(dataLen.toInt())
        raf.seek(0)
        raf.write(h.array())
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val WAV_HEADER_SIZE = 44
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        /** Hard limit of 60 seconds per clip. */
        private const val MAX_BYTES = SAMPLE_RATE * 2L * 60
    }
}
