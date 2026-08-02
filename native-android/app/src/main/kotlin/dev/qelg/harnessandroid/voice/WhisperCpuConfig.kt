package dev.qelg.harnessandroid.voice

internal object WhisperCpuConfig {
    const val AUTOMATIC = 0
    const val MAX_CONFIGURABLE_THREADS = 8

    val availableProcessors: Int
        get() = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    val automaticThreadCount: Int
        get() = automaticThreadCountFor(availableProcessors)

    fun isValid(configured: Int): Boolean =
        configured == AUTOMATIC || configured in 1..MAX_CONFIGURABLE_THREADS

    fun resolve(configured: Int): Int {
        require(isValid(configured))
        return if (configured == AUTOMATIC) automaticThreadCount else configured
    }

    internal fun automaticThreadCountFor(availableProcessors: Int): Int =
        minOf(4, availableProcessors.coerceAtLeast(1))
}
