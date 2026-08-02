package dev.qelg.harnessandroid

import android.content.Context
import dev.qelg.harnessandroid.voice.WhisperCpuConfig
import dev.qelg.harnessandroid.voice.WhisperModel
import dev.qelg.harnessandroid.voice.WhisperModelStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WhisperSettingsStoreTest {
    private val context
        get() = RuntimeEnvironment.getApplication()

    @Before
    @After
    fun clearSettings() {
        context.getSharedPreferences("local_whisper", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun threadCountDefaultsToAutomaticAndRoundTrips() {
        val store = WhisperModelStore(context)
        assertEquals(WhisperCpuConfig.AUTOMATIC, store.loadThreadCount())

        store.saveThreadCount(4)

        assertEquals(4, WhisperModelStore(context).loadThreadCount())
    }

    @Test
    fun modelAndThreadSettingsAreIndependent() {
        val store = WhisperModelStore(context)
        store.saveThreadCount(8)
        store.save(WhisperModel.LargeTurbo)

        val reloaded = WhisperModelStore(context)
        assertEquals(8, reloaded.loadThreadCount())
        assertEquals(WhisperModel.LargeTurbo, reloaded.load())
    }

    @Test
    fun invalidPersistedThreadCountFallsBackToAutomatic() {
        context
            .getSharedPreferences("local_whisper", Context.MODE_PRIVATE)
            .edit()
            .putInt("thread_count", 99)
            .commit()

        assertEquals(WhisperCpuConfig.AUTOMATIC, WhisperModelStore(context).loadThreadCount())
    }
}
