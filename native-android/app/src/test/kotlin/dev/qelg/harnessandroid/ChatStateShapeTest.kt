package dev.qelg.harnessandroid

import dev.qelg.harnessandroid.data.SessionId
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStateShapeTest {
    @Test
    fun root_has_only_authoritative_branches() {
        val names =
            ChatUiState::class
                .java
                .declaredFields
                .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) || it.isSynthetic }
                .map { it.name }
                .toSet()
        assertEquals(setOf("ui", "harness"), names)
    }

    @Test
    fun typed_ids_and_synchronized_absence_are_explicit() {
        assertTrue(
            HarnessState::class
                .java
                .declaredFields
                .first { it.name == "sessionsById" }
                .genericType
                .typeName
                .contains(SessionId::class.java.name)
        )
        assertNull(SynchronizedData.Complete<SessionContent?>(null).value)
    }

    @Test
    fun session_data_is_normalized_not_a_harness_dto_wrapper() {
        val fields = SessionData::class.java.declaredFields.map { it.name }
        assertFalse("session" in fields)
        assertTrue("content" in fields)
        assertFalse(
            SessionData::class.java.declaredFields.any {
                it.type.name == dev.qelg.harnessandroid.data.HarnessSession::class.java.name
            }
        )
    }

    @Test
    fun normalized_shape_has_no_compatibility_state_or_secondary_session_constructor() {
        val sessionFields = SessionData::class.java.declaredFields.associateBy { it.name }
        assertEquals(
            setOf(
                "id",
                "resolved",
                "name",
                "parentSessionId",
                "tags",
                "summary",
                "modelSelection",
                "state",
                "content",
                "children",
            ),
            sessionFields.keys.filterNot { it.startsWith("$") }.toSet(),
        )
        assertTrue(
            sessionFields.getValue("state").genericType.typeName.contains("SynchronizedData")
        )
        assertTrue(
            sessionFields.getValue("content").genericType.typeName.contains("SynchronizedData")
        )
        assertTrue(
            HarnessState::class
                .java
                .declaredFields
                .first { it.name == "sessionsById" }
                .genericType
                .typeName
                .contains("SessionData")
        )
        assertEquals(1, SessionData::class.java.declaredConstructors.count { !it.isSynthetic })
        val source = java.io.File("src/main/kotlin/dev/qelg/harnessandroid/ChatState.kt").readText()
        assertFalse(source.contains("asPresentationSession"))
        assertFalse(source.contains("toPresentationSession"))
        assertFalse(Regex("fun mergeSession\\([^\n]*HarnessSession").containsMatchIn(source))
        assertFalse(Regex("fun mergeSessions\\([^\n]*HarnessSession").containsMatchIn(source))
    }

    @Test
    fun stale_history_completion_cannot_replace_new_selection_load() {
        var state =
            ChatReducer.mergeSessions(
                ChatUiState(),
                listOf(
                    sessionDataFromTransport(
                        dev.qelg.harnessandroid.data.HarnessSession("one", "One")
                    ),
                    sessionDataFromTransport(
                        dev.qelg.harnessandroid.data.HarnessSession("two", "Two")
                    ),
                ),
            )
        state = ChatReducer.beginHistory(state, "one", null, 1)
        state = ChatReducer.beginHistory(state, "one", null, 2)
        state =
            ChatReducer.completeHistory(
                state,
                "one",
                listOf(
                    kotlinx.serialization.json.buildJsonObject {
                        put("id", 1)
                        put("role", "user")
                        put("content", "stale")
                    }
                ),
                1,
            )
        assertTrue(sessionTimeline(state, SessionId("one")).isEmpty())
    }

    @Test
    fun assistant_deltas_are_keyed_per_run_and_only_matching_completion_removes_them() {
        var state =
            ChatReducer.mergeSession(
                ChatUiState(),
                sessionDataFromTransport(
                    dev.qelg.harnessandroid.data.HarnessSession("session", "Session")
                ),
                null,
            )
        fun delta(run: String, text: String) =
            dev.qelg.harnessandroid.data.GatewayEvent(
                type = "message.delta",
                sessionId = "session",
                payload =
                    buildJsonObject {
                        put("run_id", run)
                        put("text", text)
                    },
                durable = false,
            )
        state = ChatReducer.appendAssistantDelta(state, "session", delta("one", "Hel"))
        state = ChatReducer.appendAssistantDelta(state, "session", delta("one", "lo"))
        state = ChatReducer.appendAssistantDelta(state, "session", delta("two", "Other"))
        assertEquals(
            listOf("Hello", "Other"),
            sessionTimeline(state, SessionId("session")).map {
                (it as dev.qelg.harnessandroid.data.ChatItem.Message).text
            },
        )

        state =
            ChatReducer.mergeTransport(
                state,
                dev.qelg.harnessandroid.data.GatewayEvent(
                    type = "message.complete",
                    sessionId = "session",
                    payload = emptyMap(),
                    durable = true,
                    cursor = 10,
                    sourceEventId = 10,
                    messageProjection =
                        buildJsonObject {
                            put("run_id", "one")
                            put("id", "complete-one")
                            put("role", "assistant")
                            put("content", "Hello")
                        },
                ),
            )
        assertEquals(
            listOf("Hello", "Other"),
            sessionTimeline(state, SessionId("session")).map {
                (it as dev.qelg.harnessandroid.data.ChatItem.Message).text
            },
        )
        assertEquals(10L, state.harness.lastAppliedEventId?.value)
    }

    @Test
    fun transient_events_do_not_enter_durable_raw_history_or_advance_cursor() {
        var state =
            ChatReducer.mergeSession(
                ChatUiState(),
                sessionDataFromTransport(
                    dev.qelg.harnessandroid.data.HarnessSession("session", "Session")
                ),
                null,
            )
        state =
            ChatReducer.mergeTransport(
                state,
                dev.qelg.harnessandroid.data.GatewayEvent(
                    type = "message.delta",
                    sessionId = "session",
                    payload = emptyMap(),
                    rawEvent = buildJsonObject { put("id", 77) },
                    cursor = 77,
                    durable = false,
                ),
            )
        val content = sessionContent(state.harness.sessionsById[SessionId("session")])
        assertTrue(content?.rawEventsById.orEmpty().isEmpty())
        assertNull(state.harness.lastAppliedEventId)
    }

    @Test
    fun stale_two_part_session_load_is_a_total_no_op() {
        var state =
            ChatReducer.mergeSession(
                ChatUiState(),
                sessionDataFromTransport(
                    dev.qelg.harnessandroid.data.HarnessSession("session", "Session")
                ),
                null,
            )
        state = ChatReducer.beginSessionLoad(state, "session", null, 1)
        state = ChatReducer.beginSessionLoad(state, "session", null, 2)
        val before = state
        val staleRows =
            listOf(
                buildJsonObject {
                    put("id", 40)
                    put("role", "user")
                    put("content", "stale")
                }
            )
        val staleEvent =
            dev.qelg.harnessandroid.data.SessionEvent.fromJson(
                buildJsonObject {
                    put("id", 41)
                    put("name", "chat.message.user.created")
                    put("payload", buildJsonObject { put("content", "stale") })
                }
            )
        assertEquals(before, ChatReducer.completeHistory(state, "session", staleRows, 1))
        assertEquals(
            before,
            ChatReducer.completeEventDetails(state, "session", listOf(staleEvent), 1),
        )
    }

    @Test
    fun refresh_snapshot_cursor_does_not_acknowledge_queued_durable_events() {
        val id = SessionId("session")
        var state =
            ChatReducer.mergeSession(
                ChatUiState(harness = HarnessState(lastAppliedEventId = EventId(10))),
                sessionDataFromTransport(
                    dev.qelg.harnessandroid.data.HarnessSession(id.value, "Before refresh")
                ),
                10,
            )
        state =
            ChatReducer.mergeSnapshot(
                state,
                listOf(
                    sessionDataFromTransport(
                        dev.qelg.harnessandroid.data.HarnessSession(id.value, "Refreshed")
                    )
                ),
                snapshotCursor = 20,
            )
        assertEquals(10L, state.harness.lastAppliedEventId?.value)
        state =
            ChatReducer.mergeTransport(
                state,
                dev.qelg.harnessandroid.data.GatewayEvent(
                    type = "message.user",
                    sessionId = id.value,
                    payload = emptyMap(),
                    cursor = 15,
                    durable = true,
                    sourceEventId = 15,
                    messageProjection =
                        buildJsonObject {
                            put("id", 15)
                            put("role", "user")
                            put("content", "must reduce")
                        },
                ),
            )
        assertEquals(15L, state.harness.lastAppliedEventId?.value)
        assertEquals(
            listOf("must reduce"),
            sessionTimeline(state, id)
                .filterIsInstance<dev.qelg.harnessandroid.data.ChatItem.Message>()
                .map { it.text },
        )
    }

    @Test
    fun only_a_fresh_connection_can_establish_snapshot_subscription_baseline() {
        val fresh = ChatReducer.establishSubscriptionBaseline(ChatUiState(), 20)
        assertEquals(20L, fresh.harness.lastAppliedEventId?.value)
        val streaming =
            ChatReducer.establishSubscriptionBaseline(
                fresh.copy(harness = fresh.harness.copy(lastAppliedEventId = EventId(10))),
                20,
            )
        assertEquals(10L, streaming.harness.lastAppliedEventId?.value)
    }

    @Test
    fun failed_session_states_fallback_keeps_authority_pending_for_retry() {
        val id = SessionId("session")
        val loadId = LoadId(4)
        var state =
            ChatReducer.mergeSession(
                ChatUiState(harness = HarnessState(connection = ConnectionState.Connected)),
                sessionDataFromTransport(
                    dev.qelg.harnessandroid.data.HarnessSession(id.value, "Session"),
                    SynchronizedData.PendingHistory(null, null, loadId),
                ),
                null,
            )
        state =
            ChatReducer.failSessionStateSync(
                state,
                listOf(id),
                loadId,
                ErrorMessage("fallback unavailable"),
            )
        val pending =
            state.harness.sessionsById.getValue(id).state as SynchronizedData.PendingHistory
        assertEquals("fallback unavailable", pending.error?.text)
        assertTrue(state.harness.connection is ConnectionState.Connected)
        state =
            ChatReducer.completeSessionStateSync(
                state,
                listOf(id),
                listOf(
                    dev.qelg.harnessandroid.data.HarnessSessionState(
                        id.value,
                        "finished",
                        eventId = 5,
                    )
                ),
                loadId,
            )
        assertTrue(state.harness.sessionsById.getValue(id).state is SynchronizedData.Complete)
    }

    @Test
    fun selection_keeps_message_content_when_events_fail_and_retries_both_parts() {
        val id = SessionId("session")
        var state =
            ChatReducer.mergeSession(
                ChatUiState(),
                sessionDataFromTransport(
                    dev.qelg.harnessandroid.data.HarnessSession(id.value, "Session")
                ),
                null,
            )
        state = ChatReducer.beginSessionLoad(state, id.value, null, 1)
        state =
            ChatReducer.completeHistory(
                state,
                id.value,
                listOf(
                    buildJsonObject {
                        put("id", 1)
                        put("role", "user")
                        put("content", "available immediately")
                    }
                ),
                1,
            )
        assertTrue(
            state.harness.sessionsById.getValue(id).content is SynchronizedData.PendingHistory
        )
        assertEquals(
            listOf("available immediately"),
            sessionTimeline(state, id)
                .filterIsInstance<dev.qelg.harnessandroid.data.ChatItem.Message>()
                .map { it.text },
        )
        state = ChatReducer.failEventDetails(state, id.value, 1, ErrorMessage("events unavailable"))
        val failedBarrier = sessionContent(state.harness.sessionsById[id])!!.loadBarrier
        assertTrue(failedBarrier?.events is SessionLoadPart.Failed)
        assertEquals(
            "events unavailable",
            (sessionContent(state.harness.sessionsById[id])!!.eventDetails
                    as SynchronizedData.PendingHistory)
                .error
                ?.text,
        )

        state = ChatReducer.beginSessionLoad(state, id.value, null, 2)
        state = ChatReducer.completeHistory(state, id.value, emptyList(), 2)
        val event =
            dev.qelg.harnessandroid.data.SessionEvent.fromJson(
                buildJsonObject {
                    put("id", 2)
                    put("name", "chat.message.user.created")
                    put("payload", buildJsonObject { put("content", "event loaded") })
                }
            )
        state = ChatReducer.completeEventDetails(state, id.value, listOf(event), 2)
        assertTrue(state.harness.sessionsById.getValue(id).content is SynchronizedData.Complete)
    }

    @Test
    fun canonical_user_projection_delivers_queued_message_before_raw_event_arrives() {
        var state =
            ChatReducer.mergeSession(
                ChatUiState(),
                sessionDataFromTransport(
                    dev.qelg.harnessandroid.data.HarnessSession("session", "Session")
                ),
                null,
            )
        state = ChatReducer.beginHistory(state, "session", null, 1)
        state =
            ChatReducer.completeHistory(
                state,
                "session",
                listOf(
                    buildJsonObject {
                        put("id", 10)
                        put("event_name", "queued.message")
                        put("queue_mode", "after_response")
                        put("content", "queued")
                    },
                    buildJsonObject {
                        put("id", 11)
                        put("event_name", "chat.message.user.created")
                        put("role", "user")
                        put("content", "delivered")
                        put("queued_message_event_id", 10)
                    },
                ),
                1,
            )
        assertTrue(
            sessionContent(state.harness.sessionsById[SessionId("session")])!!
                .rawEventsById
                .isEmpty()
        )
        assertTrue(queuedMessagesFor(state, SessionId("session")).isEmpty())
    }

    @Test
    fun state_sync_moves_pending_history_to_complete_without_accepting_stale_state() {
        val id = SessionId("session")
        var state =
            ChatReducer.mergeSession(
                ChatUiState(),
                sessionDataFromTransport(
                    dev.qelg.harnessandroid.data.HarnessSession("session", "Session"),
                    SynchronizedData.PendingHistory(null, EventId(5), LoadId(1)),
                ),
                5,
            )
        assertTrue(state.harness.sessionsById[id]!!.state is SynchronizedData.PendingHistory)
        state =
            ChatReducer.completeSessionStateSync(
                state,
                listOf(id),
                listOf(
                    dev.qelg.harnessandroid.data.HarnessSessionState(
                        "session",
                        "running",
                        eventId = 8,
                    )
                ),
                LoadId(1),
            )
        assertTrue(state.harness.sessionsById[id]!!.state is SynchronizedData.Complete)
        state = ChatReducer.beginSessionStateSync(state, listOf(id), EventId(8), LoadId(2))
        state =
            ChatReducer.completeSessionStateSync(
                state,
                listOf(id),
                listOf(
                    dev.qelg.harnessandroid.data.HarnessSessionState(
                        "session",
                        "finished",
                        eventId = 7,
                    )
                ),
                LoadId(2),
            )
        assertEquals("running", state.harness.sessionsById[id]!!.state.value?.state)
    }

    @Test
    fun only_final_durable_notification_acknowledges_a_frame() {
        val id = SessionId("session")
        var state =
            ChatReducer.mergeSession(
                ChatUiState(),
                sessionDataFromTransport(
                    dev.qelg.harnessandroid.data.HarnessSession("session", "Session")
                ),
                null,
            )
        val raw = buildJsonObject { put("id", 9) }
        state =
            ChatReducer.mergeTransport(
                state,
                dev.qelg.harnessandroid.data.GatewayEvent(
                    "error",
                    "session",
                    emptyMap(),
                    rawEvent = null,
                    cursor = null,
                    durable = false,
                ),
            )
        assertNull(state.harness.lastAppliedEventId)
        assertTrue(
            sessionContent(state.harness.sessionsById[id])?.rawEventsById.orEmpty().isEmpty()
        )
        state =
            ChatReducer.mergeTransport(
                state,
                dev.qelg.harnessandroid.data.GatewayEvent(
                    "session.inactive",
                    "session",
                    emptyMap(),
                    rawEvent = raw,
                    cursor = 9,
                    durable = true,
                ),
            )
        assertEquals(9L, state.harness.lastAppliedEventId?.value)
        assertEquals(
            setOf(EventId(9)),
            sessionContent(state.harness.sessionsById[id])!!.rawEventsById.keys,
        )
    }

    @Test
    fun canonical_tool_projection_does_not_need_a_second_transient_tool_overlay() {
        val id = SessionId("session")
        var state =
            ChatReducer.mergeSession(
                ChatUiState(),
                sessionDataFromTransport(
                    dev.qelg.harnessandroid.data.HarnessSession("session", "Session")
                ),
                null,
            )
        state =
            ChatReducer.mergeTransport(
                state,
                dev.qelg.harnessandroid.data.GatewayEvent(
                    type = "tool.complete",
                    sessionId = id.value,
                    payload = buildJsonObject { put("tool_call_id", "call-1") },
                    cursor = 20,
                    durable = true,
                    sourceEventId = 20,
                    messageProjection =
                        buildJsonObject {
                            put("id", 20)
                            put("role", "tool")
                            put("tool", "terminal")
                            put("run_id", "call-1")
                            put("content", "ok")
                        },
                ),
            )
        // Replaying the durable row replaces its canonical source projection, never appending.
        state =
            ChatReducer.mergeTransport(
                state,
                dev.qelg.harnessandroid.data.GatewayEvent(
                    type = "tool.complete",
                    sessionId = id.value,
                    payload = buildJsonObject { put("tool_call_id", "call-1") },
                    cursor = 20,
                    durable = true,
                    sourceEventId = 20,
                    messageProjection =
                        buildJsonObject {
                            put("id", 20)
                            put("role", "tool")
                            put("tool", "terminal")
                            put("run_id", "call-1")
                            put("content", "ok")
                        },
                ),
            )
        assertEquals(1, sessionTimeline(state, id).size)
        assertTrue(
            sessionContent(state.harness.sessionsById[id])!!.transientOverlaysByKey.isEmpty()
        )
    }

    @Test
    fun history_completion_removes_only_overlays_covered_by_canonical_run_ids() {
        val id = SessionId("session")
        var state =
            ChatReducer.mergeSession(
                ChatUiState(),
                sessionDataFromTransport(
                    dev.qelg.harnessandroid.data.HarnessSession("session", "Session")
                ),
                null,
            )
        state = ChatReducer.beginHistory(state, id.value, null, 1)
        state =
            ChatReducer.addTransientOverlay(
                state,
                id.value,
                dev.qelg.harnessandroid.data.ChatItem.Tool("call-1", "terminal", "running"),
            )
        state =
            ChatReducer.appendAssistantDelta(
                state,
                id.value,
                dev.qelg.harnessandroid.data.GatewayEvent(
                    "message.delta",
                    id.value,
                    buildJsonObject {
                        put("run_id", "run-2")
                        put("text", "partial")
                    },
                    durable = false,
                ),
            )
        state =
            ChatReducer.completeHistory(
                state,
                id.value,
                listOf(
                    buildJsonObject {
                        put("id", 10)
                        put("role", "tool")
                        put("tool", "terminal")
                        put("run_id", "call-1")
                        put("content", "ok")
                    },
                    buildJsonObject {
                        put("id", 11)
                        put("role", "assistant")
                        put("run_id", "run-2")
                        put("content", "complete")
                    },
                ),
                1,
            )
        val content = sessionContent(state.harness.sessionsById[id])!!
        assertTrue(content.transientOverlaysByKey.isEmpty())
        assertEquals(2, sessionTimeline(state, id).size)
    }

    @Test
    fun unversioned_null_dto_cannot_close_pending_authoritative_state_sync() {
        val id = SessionId("session")
        var state =
            ChatReducer.mergeSession(
                ChatUiState(),
                sessionDataFromTransport(
                    dev.qelg.harnessandroid.data.HarnessSession("session", "Session", eventId = 5),
                    SynchronizedData.PendingHistory(null, EventId(5), LoadId(1)),
                ),
                5,
            )
        val unversionedState =
            sessionDataFromTransport(
                dev.qelg.harnessandroid.data.HarnessSession("session", "Session", eventId = 6)
            )
        state = ChatReducer.mergeSession(state, unversionedState, 6)
        assertTrue(state.harness.sessionsById[id]!!.state is SynchronizedData.PendingHistory)

        state =
            ChatReducer.mergeSessionState(
                state,
                dev.qelg.harnessandroid.data.HarnessSessionState(id.value, "finished", eventId = 7),
            )
        assertTrue(state.harness.sessionsById[id]!!.state is SynchronizedData.Complete)
        assertEquals("finished", state.harness.sessionsById[id]!!.state.value?.state)
    }

    @Test
    fun history_generation_fences_pending_secret_side_effects() {
        var state =
            ChatReducer.mergeSession(
                ChatUiState(),
                sessionDataFromTransport(
                    dev.qelg.harnessandroid.data.HarnessSession("session", "Session")
                ),
                null,
            )
        state = ChatReducer.beginHistory(state, "session", null, 1)
        state = ChatReducer.beginHistory(state, "session", null, 2)
        assertFalse(ChatReducer.hasMatchingHistoryLoad(state, "session", 1))
        assertTrue(ChatReducer.hasMatchingHistoryLoad(state, "session", 2))
    }

    @Test
    fun transport_normalization_keeps_runtime_event_under_stored_session_id() {
        val raw =
            dev.qelg.harnessandroid.data.GatewayEvent(
                "message.delta",
                "runtime-id",
                buildJsonObject { put("text", "hello") },
                durable = false,
            )
        assertEquals(
            "stored-id",
            ChatViewModel.normalizeTransportEvent(raw, SessionId("stored-id")).sessionId,
        )
        assertEquals(
            "runtime-id",
            ChatViewModel.normalizeTransportEvent(
                    raw.copy(type = "session.created"),
                    SessionId("stored-id"),
                )
                .sessionId,
        )
    }

    @Test
    fun unknown_child_state_is_retained_without_content_then_hydrates_without_losing_newer_state() {
        val child = SessionId("child")
        var state =
            ChatReducer.mergeTransport(
                ChatUiState(),
                dev.qelg.harnessandroid.data.GatewayEvent(
                    type = "session.state",
                    sessionId = child.value,
                    payload = emptyMap(),
                    cursor = 10,
                    durable = true,
                ),
            )
        state =
            ChatReducer.mergeSessionState(
                state,
                dev.qelg.harnessandroid.data.HarnessSessionState(
                    child.value,
                    "running",
                    eventId = 10,
                ),
            )
        assertFalse(state.harness.sessionsById.getValue(child).resolved)
        assertNull(state.harness.sessionsById.getValue(child).content)
        assertTrue(overviewSessions(state).isEmpty())

        state =
            ChatReducer.mergeSessions(
                state,
                listOf(
                    sessionDataFromTransport(
                        dev.qelg.harnessandroid.data.HarnessSession(
                            child.value,
                            "Hydrated child",
                            parentSessionId = "parent",
                            sessionState =
                                dev.qelg.harnessandroid.data.HarnessSessionState(
                                    child.value,
                                    "finished",
                                    eventId = 5,
                                ),
                        )
                    )
                ),
            )
        val hydrated = state.harness.sessionsById.getValue(child)
        assertTrue(hydrated.resolved)
        assertEquals("Hydrated child", hydrated.name.value)
        assertEquals(SessionId("parent"), hydrated.parentSessionId.value)
        assertEquals("running", hydrated.state.value?.state)
        assertEquals(listOf(child), overviewSessions(state).map { it.id })
    }

    @Test
    fun direct_acknowledgement_blocks_until_authoritative_run_state_arrives() {
        val id = SessionId("session")
        val submission = dev.qelg.harnessandroid.data.DraftSubmission("", 1, id.value, 0, "go", 1)
        var state =
            ChatReducer.mergeSession(
                ChatUiState(
                    ui =
                        LocalUiState(
                            sessionUi =
                                mapOf(id to SessionUiState(draft = "go", submission = submission))
                        )
                ),
                sessionDataFromTransport(
                    dev.qelg.harnessandroid.data.HarnessSession(id.value, "Session")
                ),
                null,
            )
        state = ChatReducer.acknowledgeSubmission(state, id.value, submission, null)
        assertTrue(sessionUi(state, id).awaitingRunStart)
        assertTrue(sessionUi(state, id).sending)
        state =
            ChatReducer.mergeSessionState(
                state,
                dev.qelg.harnessandroid.data.HarnessSessionState(id.value, "running", eventId = 2),
            )
        assertFalse(sessionUi(state, id).awaitingRunStart)
        state =
            ChatReducer.mergeSessionState(
                state,
                dev.qelg.harnessandroid.data.HarnessSessionState(id.value, "finished", eventId = 3),
            )
        assertFalse(sessionUi(state, id).sending)
    }

    @Test
    fun voice_ack_after_authoritative_terminal_state_does_not_restore_run_start_fence() {
        // Voice submission takes the same acknowledged direct-message path as the typed sender.
        val id = SessionId("voice-session")
        val submission =
            dev.qelg.harnessandroid.data.DraftSubmission("voice", 1, id.value, 0, "spoken", 1)
        var state =
            ChatReducer.mergeSession(
                ChatUiState(
                    ui =
                        LocalUiState(
                            sessionUi = mapOf(id to SessionUiState(submission = submission))
                        )
                ),
                sessionDataFromTransport(
                    dev.qelg.harnessandroid.data.HarnessSession(id.value, "Voice session")
                ),
                null,
            )
        state =
            ChatReducer.mergeSessionState(
                state,
                dev.qelg.harnessandroid.data.HarnessSessionState(id.value, "finished", eventId = 2),
            )
        state = ChatReducer.acknowledgeSubmission(state, id.value, submission, null)
        assertFalse(sessionUi(state, id).sending)
    }

    @Test
    fun direct_ack_after_authoritative_terminal_state_does_not_restore_run_start_fence() {
        val id = SessionId("session")
        val submission = dev.qelg.harnessandroid.data.DraftSubmission("", 1, id.value, 0, "go", 1)
        var state =
            ChatReducer.mergeSession(
                ChatUiState(
                    ui =
                        LocalUiState(
                            sessionUi =
                                mapOf(id to SessionUiState(draft = "go", submission = submission))
                        )
                ),
                sessionDataFromTransport(
                    dev.qelg.harnessandroid.data.HarnessSession(id.value, "Session")
                ),
                null,
            )
        state =
            ChatReducer.mergeSessionState(
                state,
                dev.qelg.harnessandroid.data.HarnessSessionState(id.value, "finished", eventId = 2),
            )
        state = ChatReducer.acknowledgeSubmission(state, id.value, submission, null)
        assertFalse(sessionUi(state, id).awaitingRunStart)
        assertFalse(sessionUi(state, id).sending)
    }
}
