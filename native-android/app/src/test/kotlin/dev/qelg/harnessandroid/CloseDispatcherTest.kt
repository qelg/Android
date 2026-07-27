package dev.qelg.harnessandroid

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CloseDispatcherTest {
    @Test
    fun closeIsDispatchedOffTheCallingThread() {
        val executor =
            Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "client-close") }
        val dispatcher = executor.asCoroutineDispatcher()
        val callingThread = Thread.currentThread()
        var closeThread: Thread? = null
        val closed = CountDownLatch(1)
        val closeable = Closeable {
            closeThread = Thread.currentThread()
            closed.countDown()
        }

        try {
            dispatchClose(closeable, dispatcher)

            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertNotSame(callingThread, closeThread)
        } finally {
            dispatcher.close()
        }
    }
}
