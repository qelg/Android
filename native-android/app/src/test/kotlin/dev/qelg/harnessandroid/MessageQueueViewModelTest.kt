package dev.qelg.harnessandroid

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import dev.qelg.harnessandroid.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
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
        val submitted = CompletableDeferred<kotlinx.serialization.json.JsonObject>()
        val viewModel = viewModel(true) { _, _, _, _ -> submitted.await() }
        viewModel.setDraft(" follow up ")
        viewModel.send(" follow up ", MessageQueueMode.AfterResponse)
        assertTrue(selectedTimeline(viewModel.state.value).isEmpty())
        assertTrue(sessionUi(viewModel.state.value, SessionId("session-1")).sending)
        submitted.complete(
            buildJsonObject {
                put("id", 1)
                put("event_name", "queued.message")
                put("queue_mode", "after_response")
                put("content", "follow up")
            }
        )
        idle()
        assertEquals(
            listOf("follow up"),
            queuedMessagesFor(viewModel.state.value, SessionId("session-1")).map { it.text },
        )
        assertFalse(sessionUi(viewModel.state.value, SessionId("session-1")).sending)
    }

    @Test
    fun inactiveSessionSendsImmediatelyWithoutQueueMode() {
        val viewModel =
            viewModel(false) { _, text, _, _ ->
                buildJsonObject {
                    put("id", 2)
                    put("event_name", "chat.message.user.created")
                    put("role", "user")
                    put("content", text)
                }
            }
        viewModel.setDraft("start now")
        viewModel.send("start now")
        idle()
        assertEquals("", sessionUi(viewModel.state.value, SessionId("session-1")).draft)
        assertTrue(selectedTimeline(viewModel.state.value).isNotEmpty())
    }

    @Test
    fun direct_acknowledgement_fence_rejects_a_second_send_before_running_state() {
        var calls = 0
        val viewModel =
            viewModel(false) { _, text, _, _ ->
                calls += 1
                buildJsonObject {
                    put("id", calls)
                    put("event_name", "chat.message.user.created")
                    put("role", "user")
                    put("content", text)
                }
            }
        viewModel.setDraft("first")
        viewModel.send("first")
        idle()
        assertTrue(sessionUi(viewModel.state.value, SessionId("session-1")).awaitingRunStart)
        viewModel.setDraft("second")
        viewModel.send("second")
        idle()
        assertEquals(1, calls)
    }

    @Test
    fun websocket_user_ack_clears_persisted_draft_before_in_memory_submission_is_reduced() {
        val post = CompletableDeferred<kotlinx.serialization.json.JsonObject>()
        val application = RuntimeEnvironment.getApplication()
        val viewModel = viewModel(false) { _, _, _, _ -> post.await() }
        viewModel.setDraft("ack me")
        viewModel.send("ack me")
        assertTrue(sessionUi(viewModel.state.value, SessionId("session-1")).sending)
        assertEquals("ack me", DraftStore(application).load("")["session-1"])

        val method =
            ChatViewModel::class.java.getDeclaredMethod("handleTransport", GatewayEvent::class.java)
        method.isAccessible = true
        method.invoke(
            viewModel,
            GatewayEvent(
                type = "message.user",
                sessionId = "session-1",
                payload = buildJsonObject { put("text", "ack me") },
                cursor = 10,
                durable = true,
                sourceEventId = 10,
                messageProjection =
                    buildJsonObject {
                        put("id", 10)
                        put("role", "user")
                        put("content", "ack me")
                    },
            ),
        )

        assertEquals("", sessionUi(viewModel.state.value, SessionId("session-1")).draft)
        assertTrue(sessionUi(viewModel.state.value, SessionId("session-1")).sending)
        assertTrue(sessionUi(viewModel.state.value, SessionId("session-1")).awaitingRunStart)
        assertFalse(DraftStore(application).load("").containsKey("session-1"))

        // The later HTTP success sees an already-acknowledged submission and is idempotent.
        post.complete(buildJsonObject { put("id", 10) })
        idle()
        assertTrue(sessionUi(viewModel.state.value, SessionId("session-1")).sending)
        assertFalse(DraftStore(application).load("").containsKey("session-1"))
    }

    @Test
    fun replayed_durable_completion_does_not_increment_background_unread_twice() {
        val application = RuntimeEnvironment.getApplication()
        val background = SessionId("background")
        val selected = SessionId("selected")
        val state =
            ChatReducer.mergeSessions(
                ChatUiState(ui = LocalUiState(configured = true, selectedSessionId = selected)),
                listOf(
                    sessionDataFromTransport(HarnessSession(background.value, "Background")),
                    sessionDataFromTransport(HarnessSession(selected.value, "Selected")),
                ),
            )
        val viewModel =
            ChatViewModel(application, SavedStateHandle(), state) { _, _, _, _ ->
                buildJsonObject {}
            }
        val method =
            ChatViewModel::class.java.getDeclaredMethod("handleTransport", GatewayEvent::class.java)
        method.isAccessible = true
        val completion =
            GatewayEvent(
                type = "message.complete",
                sessionId = background.value,
                payload = emptyMap(),
                rawEvent = buildJsonObject { put("id", 10) },
                cursor = 10,
                durable = true,
                sourceEventId = 10,
                messageProjection =
                    buildJsonObject {
                        put("id", 10)
                        put("role", "assistant")
                        put("content", "done")
                    },
            )
        method.invoke(viewModel, completion)
        method.invoke(viewModel, completion)
        assertEquals(1, viewModel.state.value.ui.unreadCounts[background])
    }

    @Test
    fun failedQueueSubmissionKeepsDraftAndRemovesQueueIndicator() {
        val viewModel = viewModel(true) { _, _, _, _ -> error("queue rejected") }
        viewModel.setDraft("try later")
        viewModel.send("try later", MessageQueueMode.AfterNextToolResponse)
        idle()
        assertTrue(queuedMessagesFor(viewModel.state.value, SessionId("session-1")).isEmpty())
        assertEquals("try later", sessionUi(viewModel.state.value, SessionId("session-1")).draft)
        assertEquals("queue rejected", viewModel.state.value.ui.error?.text)
    }

    @Test
    fun historyReconciliationUsesCanonicalQueueProjection() {
        var state =
            ChatReducer.mergeSession(
                ChatUiState(),
                sessionDataFromTransport(HarnessSession("session-1", "Test")),
                null,
            )
        state = ChatReducer.beginHistory(state, "session-1", null, 1)
        state =
            ChatReducer.completeHistory(
                state,
                "session-1",
                listOf(
                    buildJsonObject {
                        put("id", 2)
                        put("event_name", "queued.message")
                        put("queue_mode", "after_response")
                        put("content", "repeat")
                    }
                ),
                1,
            )
        assertEquals(listOf(2L), queuedMessagesFor(state, SessionId("session-1")).map { it.id })
    }

    @Test
    fun voice_target_uses_session_runtime_id_when_available() {
        val application = RuntimeEnvironment.getApplication()
        val id = SessionId("stored")
        val state =
            ChatReducer.mergeSession(
                ChatUiState(ui = LocalUiState(configured = true, selectedSessionId = id)),
                sessionDataFromTransport(HarnessSession("stored", "Test", runtimeId = "runtime")),
                null,
            )
        val viewModel =
            ChatViewModel(application, SavedStateHandle(), state) { _, _, _, _ ->
                buildJsonObject {}
            }
        assertEquals("stored", viewModel.currentVoiceMessageTarget()?.storedSessionId)
        assertEquals("runtime", viewModel.currentVoiceMessageTarget()?.runtimeSessionId)
    }

    @Test
    fun activeSessionRequiresAnExplicitQueueChoice() {
        var called = false
        val viewModel =
            viewModel(true) { _, _, _, _ ->
                called = true
                buildJsonObject {}
            }
        viewModel.setDraft("do not guess")
        viewModel.send("do not guess")
        idle()
        assertFalse(called)
        assertEquals("do not guess", sessionUi(viewModel.state.value, SessionId("session-1")).draft)
    }

    private fun viewModel(active: Boolean, submitter: MessageSubmitter): ChatViewModel {
        val application = RuntimeEnvironment.getApplication()
        val session = HarnessSession("session-1", "Test", active = active)
        val id = SessionId(session.id)
        val state =
            ChatReducer.mergeSession(
                ChatUiState(ui = LocalUiState(configured = true, selectedSessionId = id)),
                sessionDataFromTransport(session),
                null,
            )
        return ChatViewModel(application, SavedStateHandle(), state, submitter)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
