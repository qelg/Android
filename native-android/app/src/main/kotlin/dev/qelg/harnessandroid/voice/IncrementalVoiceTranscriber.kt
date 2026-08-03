package dev.qelg.harnessandroid.voice

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class VoiceTranscriptionSnapshot(
    val text: String,
    val completedSamples: Long,
    val recordedSamples: Long,
    val recording: Boolean,
    val transcriptionElapsedMs: Long?,
)

/**
 * Buffers completed recording chunks while capture is active, then transcribes them in order after
 * recording stops. Calls are deliberately serialized because a whisper context cannot be used
 * concurrently.
 */
internal class IncrementalVoiceTranscriber(
    private val scope: CoroutineScope,
    private val transcribe:
        suspend (
            samples: FloatArray,
            initialPrompt: String?,
            onProgress: (Int) -> Unit,
            onPartial: (String) -> Unit,
            shouldAbort: () -> Boolean,
        ) -> String,
    private val onStarted: () -> Unit,
    private val onUpdate: (VoiceTranscriptionSnapshot) -> Unit,
    private val onComplete: (String) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val onStopped: () -> Unit,
    private val conversionDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private sealed interface Command {
        data class Chunk(val pcm16: ByteArray) : Command

        data class Finish(val pcm16: ByteArray) : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val recordedSamples = AtomicLong(0)
    private val abortRequested = AtomicBoolean(false)
    private val snapshotLock = Any()
    private var completedSamples = 0L
    private var visibleCompletedSamples = 0L
    private var transcript = ""
    private var visibleTranscript = ""
    private var pendingChunks = mutableListOf<ByteArray>()
    private var recording = true
    private var started = false
    private var transcriptionStartedAtMs: Long? = null
    private var elapsedTicker: Job? = null
    private var stopped = false

    private val job =
        scope.launch {
            try {
                for (command in commands) {
                    when (command) {
                        is Command.Chunk -> pendingChunks += command.pcm16
                        is Command.Finish -> {
                            if (command.pcm16.isNotEmpty()) pendingChunks += command.pcm16
                            val chunks = pendingChunks
                            pendingChunks = mutableListOf()
                            for (chunk in chunks) process(chunk)
                            val finalText = synchronized(snapshotLock) { transcript.trim() }
                            require(finalText.isNotEmpty()) { "Whisper did not detect any speech" }
                            onComplete(finalText)
                            break
                        }
                    }
                }
            } catch (error: Throwable) {
                onFailure(error)
            } finally {
                synchronized(snapshotLock) { stopped = true }
                elapsedTicker?.cancel()
                commands.close()
                onStopped()
            }
        }

    fun addChunk(pcm16: ByteArray) {
        if (pcm16.isNotEmpty()) commands.trySend(Command.Chunk(pcm16))
    }

    fun noteRecordedSamples(count: Long) {
        recordedSamples.accumulateAndGet(count, ::maxOf)
        emitSnapshot()
    }

    fun finish(pcm16: ByteArray, totalSamples: Long) {
        synchronized(snapshotLock) { recording = false }
        recordedSamples.accumulateAndGet(totalSamples, ::maxOf)
        emitSnapshot()
        commands.trySend(Command.Finish(pcm16))
    }

    fun cancel() {
        abortRequested.set(true)
        job.cancel()
        commands.close()
    }

    private suspend fun process(pcm16: ByteArray) {
        if (pcm16.isEmpty()) return
        markStarted()
        val samples = withContext(conversionDispatcher) { pcm16ToFloat(pcm16) }
        val prefix: String
        val chunkStart: Long
        synchronized(snapshotLock) {
            prefix = transcript
            chunkStart = completedSamples
            visibleCompletedSamples = completedSamples
            visibleTranscript = transcript
        }
        val result =
            transcribe(
                samples,
                prefix.takeLast(MAX_PROMPT_CHARACTERS).ifBlank { null },
                { percent ->
                    synchronized(snapshotLock) {
                        val inChunk = samples.size.toLong() * percent.coerceIn(0, 100) / 100
                        visibleCompletedSamples =
                            maxOf(visibleCompletedSamples, chunkStart + inChunk)
                    }
                    emitSnapshot()
                },
                { partial ->
                    synchronized(snapshotLock) {
                        visibleTranscript = joinTranscript(prefix, partial)
                    }
                    emitSnapshot()
                },
                abortRequested::get,
            )
        synchronized(snapshotLock) {
            transcript = joinTranscript(prefix, result)
            completedSamples = chunkStart + samples.size
            visibleCompletedSamples = completedSamples
            visibleTranscript = transcript
        }
        emitSnapshot()
    }

    private fun markStarted() {
        val notify =
            synchronized(snapshotLock) {
                if (started) false
                else {
                    started = true
                    transcriptionStartedAtMs = elapsedRealtime()
                    true
                }
            }
        if (notify) {
            onStarted()
            elapsedTicker =
                scope.launch {
                    while (isActive) {
                        delay(1_000)
                        emitSnapshot()
                    }
                }
        }
    }

    private fun emitSnapshot() {
        val snapshot =
            synchronized(snapshotLock) {
                if (stopped) return
                VoiceTranscriptionSnapshot(
                    text = visibleTranscript,
                    completedSamples = visibleCompletedSamples.coerceAtLeast(0),
                    recordedSamples = recordedSamples.get().coerceAtLeast(visibleCompletedSamples),
                    recording = recording,
                    transcriptionElapsedMs =
                        transcriptionStartedAtMs?.let { (elapsedRealtime() - it).coerceAtLeast(0) },
                )
            }
        scope.launch { onUpdate(snapshot) }
    }

    companion object {
        private const val MAX_PROMPT_CHARACTERS = 1_000

        internal fun joinTranscript(first: String, second: String): String =
            listOf(first.trim(), second.trim()).filter(String::isNotEmpty).joinToString(" ")
    }
}
