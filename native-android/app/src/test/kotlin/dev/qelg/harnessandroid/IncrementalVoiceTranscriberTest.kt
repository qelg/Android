package dev.qelg.harnessandroid

import dev.qelg.harnessandroid.voice.IncrementalVoiceTranscriber
import dev.qelg.harnessandroid.voice.VoiceTranscriptionSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IncrementalVoiceTranscriberTest {
    @Test
    fun chunksAreTranscribedOnceAndTailUsesEarlierTextAsPrompt() = runTest {
        val inputs = mutableListOf<Int>()
        val prompts = mutableListOf<String?>()
        val snapshots = mutableListOf<VoiceTranscriptionSnapshot>()
        val completed = mutableListOf<String>()
        var starts = 0
        var stops = 0
        val transcriber =
            IncrementalVoiceTranscriber(
                scope = this,
                transcribe = { samples, prompt, onProgress, onPartial, _ ->
                    inputs += samples.size
                    prompts += prompt
                    onProgress(50)
                    onPartial(if (inputs.size == 1) "hello" else "world")
                    if (inputs.size == 1) "hello" else "world"
                },
                onStarted = { starts++ },
                onUpdate = snapshots::add,
                onComplete = completed::add,
                onFailure = { throw it },
                onStopped = { stops++ },
                conversionDispatcher = StandardTestDispatcher(testScheduler),
            )

        transcriber.noteRecordedSamples(30)
        transcriber.addChunk(ByteArray(60))
        runCurrent()

        assertEquals(listOf(30), inputs)
        assertEquals(listOf(null), prompts)
        assertEquals("hello", snapshots.last().text)
        assertTrue(snapshots.last().recording)

        transcriber.noteRecordedSamples(40)
        transcriber.finish(ByteArray(20), totalSamples = 40)
        runCurrent()

        assertEquals(listOf(30, 10), inputs)
        assertEquals(listOf(null, "hello"), prompts)
        assertEquals(listOf("hello world"), completed)
        assertEquals(1, starts)
        assertEquals(1, stops)
        assertEquals(40, snapshots.last().completedSamples)
        assertEquals(40, snapshots.last().recordedSamples)
        assertFalse(snapshots.last().recording)
    }

    @Test
    fun partialTextAndNativeProgressAreExposedBeforeCompletion() = runTest {
        val updates = mutableListOf<VoiceTranscriptionSnapshot>()
        val completed = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        val transcriber =
            IncrementalVoiceTranscriber(
                scope = this,
                transcribe = { _, _, onProgress, onPartial, _ ->
                    onProgress(25)
                    onPartial("visible now")
                    release.await()
                    "visible now"
                },
                onStarted = {},
                onUpdate = updates::add,
                onComplete = completed::add,
                onFailure = { throw it },
                onStopped = {},
                conversionDispatcher = StandardTestDispatcher(testScheduler),
            )

        transcriber.noteRecordedSamples(100)
        transcriber.finish(ByteArray(200), totalSamples = 100)
        runCurrent()

        assertEquals(25, updates.last().completedSamples)
        assertEquals("visible now", updates.last().text)
        assertTrue(completed.isEmpty())

        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("visible now"), completed)
    }
}
