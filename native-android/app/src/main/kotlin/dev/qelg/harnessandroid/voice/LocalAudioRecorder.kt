package dev.qelg.harnessandroid.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RecordedAudioTail(val totalSamples: Long, val audioFile: File)

@SuppressLint("MissingPermission")
class LocalAudioRecorder(
    private val audioFile: File,
    private val onSamplesRecorded: (Long) -> Unit = {},
    private val onMaximumDuration: () -> Unit = {},
) {
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
    private var totalBytes = 0L
    private val shutdownLock = Any()
    @Volatile private var recording = false
    private var released = false
    private var lastReportedSecond = 0L
    private var maximumDurationReported = false
    private var thread: Thread? = null
    private var audioOutput: FileOutputStream? = null

    fun start() {
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone is unavailable" }
            audioFile.parentFile?.mkdirs()
            audioOutput = FileOutputStream(audioFile, false)
            recorder.startRecording()
        } catch (error: Throwable) {
            synchronized(shutdownLock) {
                runCatching { audioOutput?.close() }
                audioOutput = null
                recorder.release()
                audioFile.delete()
                released = true
            }
            throw error
        }
        recording = true
        thread =
            Thread(
                    {
                        val buffer = ByteArray(minimumBuffer.coerceAtLeast(2048))
                        while (recording) {
                            val count = recorder.read(buffer, 0, buffer.size)
                            if (count > 0) acceptAudio(buffer, count)
                        }
                    },
                    "local-whisper-recorder",
                )
                .apply { start() }
    }

    suspend fun stop(): RecordedAudioTail =
        withContext(Dispatchers.IO) {
            shutdown()
            RecordedAudioTail(totalBytes / PCM_BYTES, audioFile)
        }

    fun discard() = shutdown(deleteFile = true)

    private fun shutdown(deleteFile: Boolean = false) =
        synchronized(shutdownLock) {
            if (released) return@synchronized
            recording = false
            runCatching { recorder.stop() }
            thread?.join()
            runCatching { audioOutput?.close() }
            audioOutput = null
            recorder.release()
            if (deleteFile) audioFile.delete()
            released = true
        }

    private fun acceptAudio(buffer: ByteArray, count: Int) {
        runCatching { audioOutput?.write(buffer, 0, count) }
        totalBytes += count
        val samples = totalBytes / PCM_BYTES
        val second = samples / SAMPLE_RATE
        if (second > lastReportedSecond) {
            lastReportedSecond = second
            runCatching { onSamplesRecorded(samples) }
        }
        if (!maximumDurationReported && samples >= MAX_RECORDING_SAMPLES) {
            maximumDurationReported = true
            recording = false
            runCatching(onMaximumDuration)
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        internal const val MAX_RECORDING_MINUTES = 10
        private const val MAX_RECORDING_SAMPLES = SAMPLE_RATE * MAX_RECORDING_MINUTES * 60L
        private const val PCM_BYTES = 2
    }
}

internal fun pcm16ToFloat(bytes: ByteArray): FloatArray =
    FloatArray(bytes.size / 2) { index ->
        val low = bytes[index * 2].toInt() and 0xff
        val high = bytes[index * 2 + 1].toInt()
        ((high shl 8) or low).toShort() / 32768f
    }
