package dev.qelg.harnessandroid.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@SuppressLint("MissingPermission")
class LocalAudioRecorder {
    private val minimumBuffer =
        AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
    private val recorder =
        AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minimumBuffer.coerceAtLeast(SAMPLE_RATE),
        )
    private val audio = ByteArrayOutputStream()
    @Volatile private var recording = false
    private var thread: Thread? = null

    fun start() {
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone is unavailable" }
            recorder.startRecording()
        } catch (error: Throwable) {
            recorder.release()
            throw error
        }
        recording = true
        thread =
            Thread(
                    {
                        val buffer = ByteArray(minimumBuffer.coerceAtLeast(2048))
                        while (recording) {
                            val count = recorder.read(buffer, 0, buffer.size)
                            if (count > 0) synchronized(audio) { audio.write(buffer, 0, count) }
                        }
                    },
                    "local-whisper-recorder",
                )
                .apply { start() }
    }

    suspend fun stop(): FloatArray =
        withContext(Dispatchers.IO) {
            recording = false
            runCatching { recorder.stop() }
            thread?.join(1_000)
            recorder.release()
            pcm16ToFloat(synchronized(audio) { audio.toByteArray() })
        }

    fun discard() {
        recording = false
        runCatching { recorder.stop() }
        thread?.join(1_000)
        recorder.release()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}

internal fun pcm16ToFloat(bytes: ByteArray): FloatArray =
    FloatArray(bytes.size / 2) { index ->
        val low = bytes[index * 2].toInt() and 0xff
        val high = bytes[index * 2 + 1].toInt()
        ((high shl 8) or low).toShort() / 32768f
    }
