package dev.qelg.harnessandroid.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RecordedAudioTail(val pcm16: ByteArray, val totalSamples: Long, val audioFile: File)

internal class PcmChunkAccumulator(private val chunkBytes: Int) {
    private val pending = ByteArrayOutputStream()
    var totalBytes: Long = 0
        private set

    @Synchronized
    fun add(buffer: ByteArray, count: Int): List<ByteArray> {
        pending.write(buffer, 0, count)
        totalBytes += count
        val chunks = mutableListOf<ByteArray>()
        while (pending.size() >= chunkBytes) {
            val available = pending.toByteArray()
            chunks += available.copyOfRange(0, chunkBytes)
            pending.reset()
            if (available.size > chunkBytes)
                pending.write(available, chunkBytes, available.size - chunkBytes)
        }
        return chunks
    }

    @Synchronized fun remaining(): ByteArray = pending.toByteArray()
}

@SuppressLint("MissingPermission")
class LocalAudioRecorder(
    private val audioFile: File,
    private val onChunk: (ByteArray) -> Unit = {},
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
    private val audio = PcmChunkAccumulator(CHUNK_BYTES)
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
            RecordedAudioTail(audio.remaining(), audio.totalBytes / PCM_BYTES, audioFile)
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
        val chunks = audio.add(buffer, count)
        val samples = audio.totalBytes / PCM_BYTES
        chunks.forEach { chunk -> runCatching { onChunk(chunk) } }
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
        internal const val CHUNK_SECONDS = 30
        internal const val MAX_RECORDING_MINUTES = 10
        private const val MAX_RECORDING_SAMPLES = SAMPLE_RATE * MAX_RECORDING_MINUTES * 60L
        private const val PCM_BYTES = 2
        private const val CHUNK_BYTES = SAMPLE_RATE * CHUNK_SECONDS * PCM_BYTES
    }
}

internal fun pcm16ToFloat(bytes: ByteArray): FloatArray =
    FloatArray(bytes.size / 2) { index ->
        val low = bytes[index * 2].toInt() and 0xff
        val high = bytes[index * 2 + 1].toInt()
        ((high shl 8) or low).toShort() / 32768f
    }
