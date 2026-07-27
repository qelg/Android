package dev.qelg.harnessandroid

import dev.qelg.harnessandroid.data.ReconnectPolicy
import dev.qelg.harnessandroid.data.ReconnectWait
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {
    @Test
    fun backoffIsBoundedAndResetsAfterConnection() {
        val policy = ReconnectPolicy()
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (1..7).map { policy.nextDelayMillis() },
        )
        policy.reset()
        assertEquals(1_000L, policy.nextDelayMillis())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun reconnectWaitCountsDownAndConnectNowSkipsRemainingDelay() = runTest {
        val wait = ReconnectWait()
        val countdown = mutableListOf<Int>()
        val waiting = launch { wait.await(2_500L) { countdown += it } }

        runCurrent()
        assertEquals(listOf(3), countdown)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(listOf(3, 2), countdown)

        wait.connectNow()
        runCurrent()

        assertTrue(waiting.isCompleted)
        assertEquals(listOf(3, 2), countdown)
    }
}
