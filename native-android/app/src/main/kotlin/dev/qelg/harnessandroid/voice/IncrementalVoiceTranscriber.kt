package dev.qelg.harnessandroid.voice

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class VoiceTranscriptionSnapshot(
    val text: String,
    val completedSamples: Long,
    val recordedSamples: Long,
    val recording: Boolean,
)

/**
 * Transcribes each completed recording chunk exactly once. Work on the first 30-second chunk can
 * overlap the inexpensive AudioRecord capture; stopping only leaves the unprocessed tail. Calls are
 * deliberately serialized because a whisper context cannot be used concurrently.
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
    private var recording = true
    private var started = false
    private var stopped = false

    private val job =
        scope.launch {
            try {
                for (command in commands) {
                    when (command) {
                        is Command.Chunk -> process(command.pcm16)
                        is Command.Finish -> {
                            process(command.pcm16)
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
                commands.close()
                onStopped()
            }
        }

    fun addChunk(pcm16: ByteArray) {
        if (pcm16.isNotEmpty()) commands.trySend(Command.Chunk(pcm16))
    }

    fun noteRecordedSamples(count: Long) {
        recordedSamples.accumulateAndGet(count, ::maxOf)
        emitSnapshotIfStarted()
    }

    fun finish(pcm16: ByteArray, totalSamples: Long) {
        synchronized(snapshotLock) { recording = false }
        recordedSamples.accumulateAndGet(totalSamples, ::maxOf)
        emitSnapshotIfStarted()
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
                    true
                }
            }
        if (notify) onStarted()
    }

    private fun emitSnapshotIfStarted() {
        if (synchronized(snapshotLock) { started && !stopped }) emitSnapshot()
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
