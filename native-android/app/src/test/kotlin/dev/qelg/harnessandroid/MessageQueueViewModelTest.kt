package dev.qelg.harnessandroid

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import dev.qelg.harnessandroid.data.ChatItem
import dev.qelg.harnessandroid.data.HarnessSession
import dev.qelg.harnessandroid.data.MessageQueueMode
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MessageQueueViewModelTest {
    @Test
    fun activeSessionQueuesWithoutInsertingMessageIntoRunningTimeline() {
        val submissions = mutableListOf<Submission>()
        val submitted = CompletableDeferred<Unit>()
        val viewModel =
            viewModel(active = true) { sessionId, text, model, queueMode ->
                submissions += Submission(sessionId, text, model, queueMode)
                submitted.await()
            }
        viewModel.setDraft("  follow up  ")

        viewModel.send("  follow up  ", MessageQueueMode.AfterResponse)

        val submitting = viewModel.state.value
        assertTrue(submitting.active)
        assertTrue(submitting.items.isEmpty())
        assertEquals("follow up", submitting.selectedQueuedMessages.single().text)
        assertTrue(submitting.selectedQueuedMessages.single().submitting)

        submitted.complete(Unit)
        idleMainLooper()

        assertEquals(
            listOf(Submission("session-1", "follow up", null, MessageQueueMode.AfterResponse)),
            submissions,
        )
        assertFalse(viewModel.state.value.selectedQueuedMessages.single().submitting)
        assertNull(viewModel.state.value.drafts["session-1"])
    }

    @Test
    fun inactiveSessionSendsImmediatelyWithoutQueueMode() {
        val submissions = mutableListOf<Submission>()
        val viewModel =
            viewModel(active = false) { sessionId, text, model, queueMode ->
                submissions += Submission(sessionId, text, model, queueMode)
            }
        viewModel.setDraft("start now")

        viewModel.send("start now")

        val sending = viewModel.state.value
        assertTrue(sending.active)
        assertTrue(sending.selectedQueuedMessages.isEmpty())
        assertEquals("start now", (sending.items.single() as ChatItem.Message).text)
        idleMainLooper()
        assertEquals(listOf(Submission("session-1", "start now", null, null)), submissions)
    }

    @Test
    fun failedQueueSubmissionKeepsDraftAndRemovesQueueIndicator() {
        val viewModel = viewModel(active = true) { _, _, _, _ -> error("queue rejected") }
        viewModel.setDraft("try later")

        viewModel.send("try later", MessageQueueMode.AfterNextToolResponse)
        idleMainLooper()

        val failed = viewModel.state.value
        assertTrue(failed.active)
        assertTrue(failed.selectedQueuedMessages.isEmpty())
        assertEquals("try later", failed.drafts["session-1"])
        assertEquals("queue rejected", failed.error?.text)
    }

    @Test
    fun historyReconciliationRemovesOnlyQueueEntriesThatWereReleased() {
        val queued =
            ChatUiState(
                queuedMessages =
                    mapOf(
                        "session-1" to
                            listOf(
                                QueuedMessage(
                                    1,
                                    "repeat",
                                    MessageQueueMode.AfterResponse,
                                    expectedUserMessageOccurrence = 2,
                                    submitting = false,
                                ),
                                QueuedMessage(
                                    2,
                                    "repeat",
                                    MessageQueueMode.AfterResponse,
                                    expectedUserMessageOccurrence = 3,
                                    submitting = false,
                                ),
                            )
                    )
            )
        val history =
            listOf(
                ChatItem.Message("user", "repeat"),
                ChatItem.Message("assistant", "done"),
                ChatItem.Message("user", "repeat"),
            )

        val reconciled = queued.reconcileQueuedMessages("session-1", history)

        assertEquals(listOf(2L), reconciled.queuedMessages["session-1"]?.map { it.id })
    }

    @Test
    fun activeSessionRequiresAnExplicitQueueChoice() {
        var submitted = false
        val viewModel = viewModel(active = true) { _, _, _, _ -> submitted = true }
        viewModel.setDraft("do not guess")

        viewModel.send("do not guess")
        idleMainLooper()

        assertFalse(submitted)
        assertEquals("do not guess", viewModel.state.value.drafts["session-1"])
        assertTrue(viewModel.state.value.selectedQueuedMessages.isEmpty())
    }

    private fun viewModel(active: Boolean, submitter: MessageSubmitter): ChatViewModel {
        val application = RuntimeEnvironment.getApplication()
        application.getSharedPreferences("chat_drafts", 0).edit().clear().commit()
        val session = HarnessSession("session-1", "Test", active = active)
        return ChatViewModel(
            application,
            SavedStateHandle(),
            ChatUiState(
                configured = true,
                selectedId = session.id,
                sessions = listOf(session),
                activeSessionIds = if (active) setOf(session.id) else emptySet(),
                title = session.title,
            ),
            submitter,
        )
    }

    private fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private data class Submission(
        val sessionId: String,
        val text: String,
        val model: String?,
        val queueMode: MessageQueueMode?,
    )
}
