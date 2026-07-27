package dev.qelg.harnessandroid.data

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

class ReconnectPolicy {
    private var attempt = 0

    fun nextDelayMillis(): Long {
        val delay = (1_000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L)
        attempt++
        return delay
    }

    fun reset() {
        attempt = 0
    }
}

class ReconnectWait {
    private val manualTrigger = Channel<Unit>(Channel.CONFLATED)

    suspend fun await(delayMillis: Long, onCountdown: suspend (Int) -> Unit) {
        while (manualTrigger.tryReceive().isSuccess) {
            // Ignore triggers left over from a connection attempt that has already started.
        }
        var remainingMillis = delayMillis.coerceAtLeast(0L)
        while (remainingMillis > 0L) {
            onCountdown(((remainingMillis + 999L) / 1_000L).toInt())
            val stepMillis = minOf(1_000L, remainingMillis)
            val triggered = withTimeoutOrNull(stepMillis) { manualTrigger.receive() } != null
            if (triggered) return
            remainingMillis -= stepMillis
        }
    }

    fun connectNow() {
        manualTrigger.trySend(Unit)
    }
}
