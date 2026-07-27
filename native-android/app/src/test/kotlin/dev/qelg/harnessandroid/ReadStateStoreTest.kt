package dev.qelg.harnessandroid

import android.content.Context
import dev.qelg.harnessandroid.data.ReadStateStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ReadStateStoreTest {
    @Test
    fun readTimestampsPersistAndRemainIsolatedByServer() {
        val context = RuntimeEnvironment.getApplication()
        context
            .getSharedPreferences("session_read_state", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val store = ReadStateStore(context)

        store.save("https://one.example", "session", "2026-07-18T10:00:00Z")
        store.save("https://two.example", "session", "2026-07-18T11:00:00Z")

        assertEquals(mapOf("session" to "2026-07-18T10:00:00Z"), store.load("https://one.example"))
        assertEquals(
            mapOf("session" to "2026-07-18T11:00:00Z"),
            ReadStateStore(context).load("https://two.example"),
        )
    }
}
