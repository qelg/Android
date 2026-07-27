package dev.qelg.harnessandroid

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTranscriptionTest {
    @Test
    fun transcriptionStateStaysActiveUntilWorkCompletes() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val states = mutableListOf<Boolean>()

        val result =
            async(start = CoroutineStart.UNDISPATCHED) {
                runVoiceTranscription(
                    setTranscribing = states::add,
                    operation = {
                        release.await()
                        "Transcript"
                    },
                )
            }

        assertEquals(listOf(true), states)
        assertFalse(result.isCompleted)
        release.complete(Unit)
        assertEquals(Result.success("Transcript"), result.await())
        assertEquals(listOf(true, false), states)
    }

    @Test
    fun transcriptionStateClearsAfterFailure() = runBlocking {
        val states = mutableListOf<Boolean>()

        val result =
            runVoiceTranscription(
                setTranscribing = states::add,
                operation = { error("server timeout") },
            )

        assertTrue(result.isFailure)
        assertEquals(listOf(true, false), states)
    }
}
