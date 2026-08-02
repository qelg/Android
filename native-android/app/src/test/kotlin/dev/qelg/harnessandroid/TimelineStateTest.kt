package dev.qelg.harnessandroid

import dev.qelg.harnessandroid.data.ChatItem
import dev.qelg.harnessandroid.data.HarnessSession
import dev.qelg.harnessandroid.data.SessionEvent
import dev.qelg.harnessandroid.data.formatClockTime
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TimelineStateTest {
    @Test
    fun historyRowTreatsNullToolCallsAsNoTools() {
        val row =
            Json.parseToJsonElement(
                    """{"id":"m1","role":"assistant","content":"Hello","tool_calls":null}"""
                )
                .jsonObject
        assertEquals(
            listOf<ChatItem>(ChatItem.Message("assistant", "Hello", "m1")),
            messagesFromHistoryRow(row),
        )
    }

    @Test
    fun durableSecretAskIsRestoredUntilItsToolReply() {
        val ask =
            SessionEvent.fromJson(
                Json.parseToJsonElement(
                        """{"id":10,"name":"secret.ask","payload":{"description":"GitHub token","identifier":"secret-id","container":"container"}}"""
                    )
                    .jsonObject
            )
        val result =
            SessionEvent.fromJson(
                Json.parseToJsonElement(
                        """{"id":12,"name":"chat.message.tool.created","payload":{"tool":"retrieve-secret","metadata":{"secret_ask_event_id":10}}}"""
                    )
                    .jsonObject
            )

        val pending = pendingSecretFromEvents(listOf(ask))
        assertNotNull(pending)
        assertEquals(10L, pending!!.eventId)
        assertEquals("GitHub token", pending.description)
        assertNull(pendingSecretFromEvents(listOf(ask, result)))
    }

    @Test
    fun completionReconcilesDroppedDeltasWithCanonicalFinalText() {
        val partial = listOf<ChatItem>(ChatItem.Message("assistant", "Hel"))
        assertEquals(
            listOf<ChatItem>(ChatItem.Message("assistant", "Hello", pendingCanonical = true)),
            reconcileAssistantCompletion(partial, "Hello"),
        )
    }

    @Test
    fun historyCompletionPreservesEventsReceivedWhileLoading() {
        val history = listOf<ChatItem>(ChatItem.Message("user", "Question", "u1"))
        val live = listOf<ChatItem>(ChatItem.Message("assistant", "Streaming"))
        assertEquals(history + live, mergeHistoryAndLive(history, live))
    }

    @Test
    fun historyMergeDoesNotDuplicateItemsWithStableIds() {
        val message = ChatItem.Message("assistant", "Done", "m1")
        val tool = ChatItem.Tool("t1", "terminal", "completed", result = "ok")
        assertEquals(
            listOf(message, tool),
            mergeHistoryAndLive(listOf(message, tool), listOf(message, tool)),
        )
    }

    @Test
    fun historyMergeDoesNotDuplicateCanonicalCompletionReceivedWhileLoading() {
        val history = listOf<ChatItem>(ChatItem.Message("assistant", "Done", "m1"))
        val liveCompletion = listOf<ChatItem>(ChatItem.Message("assistant", "Done"))
        assertEquals(history, mergeHistoryAndLive(history, liveCompletion))
    }

    @Test
    fun canonicalReplacementKeepsLiveComposeKey() {
        val live = ChatItem.Message("assistant", "Streaming text", id = "m7", uiKey = "live:7")
        val canonical = ChatItem.Message("assistant", "Canonical text", id = "m7")
        assertEquals(
            listOf(canonical.copy(uiKey = "live:7")),
            mergeHistoryAndLive(listOf(canonical), listOf(live)),
        )
    }

    @Test
    fun staleHistoryKeepsTheEntireUnpersistedLiveTurn() {
        val oldUser = ChatItem.Message("user", "Old question", id = "u1")
        val oldAssistant = ChatItem.Message("assistant", "Old answer", id = "a1")
        val newUser = ChatItem.Message("user", "New question", uiKey = "live:1")
        val newAssistant = ChatItem.Message("assistant", "Streaming", uiKey = "live:2")
        val staleHistory = listOf<ChatItem>(oldUser, oldAssistant)
        assertEquals(
            staleHistory + listOf(newUser, newAssistant),
            mergeHistoryAndLive(staleHistory, staleHistory + listOf(newUser, newAssistant)),
        )
    }

    @Test
    fun authoritativeHistoryRemovesRowsThatPredateTheRequest() {
        val kept = ChatItem.Message("user", "Keep", id = "u1")
        val rewound = ChatItem.Message("assistant", "Remove", id = "a1")
        val baseline = listOf<ChatItem>(kept, rewound)
        assertEquals(listOf(kept), reconcileHistoryItems(listOf(kept), baseline, baseline))
    }

    @Test
    fun eventsReceivedAfterHistoryRequestRemainVisible() {
        val persisted = ChatItem.Message("user", "Question", id = "u1")
        val baseline = listOf<ChatItem>(persisted)
        val arrivedLater = ChatItem.Message("assistant", "Streaming", uiKey = "live:2")
        assertEquals(
            baseline + arrivedLater,
            reconcileHistoryItems(baseline, baseline + arrivedLater, baseline),
        )
    }

    @Test
    fun pendingLiveMessageSurvivesHistoryPersistenceDelay() {
        val pending =
            ChatItem.Message(
                "assistant",
                "Completed live",
                uiKey = "live:3",
                pendingCanonical = true,
            )
        assertEquals(
            listOf(pending),
            reconcileHistoryItems(emptyList(), listOf(pending), listOf(pending)),
        )
    }

    @Test
    fun unchangedHistoryReconciliationReturnsOriginalListInstance() {
        val current = listOf<ChatItem>(ChatItem.Message("assistant", "Done", id = "m1"))
        assertSame(current, reconcileHistoryItems(current.toList(), current))
    }

    @Test
    fun unreadCountersIncrementAndClearWithoutChangingUnrelatedChats() {
        val unread = addUnread(addUnread(mapOf("chat-a" to 1), "chat-b"), "chat-b")
        assertEquals(mapOf("chat-a" to 1, "chat-b" to 2), unread)
        assertEquals(mapOf("chat-b" to 2), clearUnread(unread, "chat-a"))
        assertSame(unread, clearUnread(unread, "missing"))
    }

    @Test
    fun runtimeUnreadCountIsRemappedWhenSessionListRefreshes() {
        val sessions = listOf(HarnessSession("stored-1", "Chat", runtimeId = "runtime-1"))
        assertEquals(mapOf("stored-1" to 2), remapUnread(mapOf("runtime-1" to 2), sessions))
    }

    @Test
    fun rememberedRuntimeSessionRoutesUnreadToStoredChat() {
        assertEquals(
            "stored-1",
            resolveStoredSessionId("runtime-1", mapOf("runtime-1" to "stored-1"), emptyList()),
        )
    }

    @Test
    fun timelineKeySurvivesStreamingToCanonicalReplacement() {
        val live = ChatItem.Message("assistant", "Done", uiKey = "live:9")
        val canonical = ChatItem.Message("assistant", "Done", id = "m9")
        val reconciled = mergeHistoryAndLive(listOf(canonical), listOf(live)).single()
        assertEquals(timelineKey(0, live), timelineKey(0, reconciled))
    }

    @Test
    fun liveAndServerMessageKeyNamespacesCannotCollide() {
        val live = ChatItem.Message("assistant", "Live", uiKey = "same")
        val persisted = ChatItem.Message("assistant", "Stored", id = "same")
        assertNotEquals(timelineKey(0, live), timelineKey(0, persisted))
    }

    @Test
    fun timelineKeysAreBundleSaveableStrings() {
        val items =
            listOf<ChatItem>(
                ChatItem.Message("assistant", "Stored", id = "22377"),
                ChatItem.Message("assistant", "Live", uiKey = "live:1"),
                ChatItem.Tool("tool:1", "terminal", "completed"),
                ChatItem.ParallelToolGroup(
                    "batch:1",
                    listOf(ChatItem.Tool("tool:2", "read_file", "completed")),
                ),
                ChatItem.ToolGroup(listOf(ChatItem.Tool("tool:3", "search_files", "completed"))),
                ChatItem.Status("working"),
            )
        items.forEachIndexed { index, item ->
            assertEquals(String::class.java, (timelineKey(index, item) as Any).javaClass)
        }
    }

    @Test
    fun historyRowsPairParallelStartsWithResultsAndEstimateMeaningfulDurations() {
        val rows =
            Json.parseToJsonElement(
                    """[
                    {"id":"m1","role":"assistant","content":"","created_at":"2026-07-17T10:00:00Z","tool_calls":[
                      {"id":"a","function":{"name":"terminal","arguments":"{\"command\":\"date\"}"}},
                      {"id":"b","function":{"name":"read_file","arguments":"{\"path\":\"x\"}"}}
                    ]},
                    {"id":"m2","role":"tool","tool_call_id":"b","tool_name":"read_file","content":"file","created_at":"2026-07-17T10:00:01Z"},
                    {"id":"m3","role":"tool","tool_call_id":"a","tool_name":"terminal","content":"Fri","created_at":"2026-07-17T10:00:02Z"}
                    ]"""
                )
                .jsonArray
                .map { it.jsonObject }
        val group = messagesFromHistoryRows(rows).single() as ChatItem.ParallelToolGroup
        assertEquals(listOf("a", "b"), group.tools.map { it.id })
        assertEquals(listOf("Fri", "file"), group.tools.map { it.result })
        assertEquals(listOf(2000L, 1000L), group.tools.map { it.durationMs })
        assertEquals(listOf(true, true), group.tools.map { it.durationEstimated })
    }

    @Test
    fun liveCompletionUpdatesMatchingPersistedStartDuringHistoryReconciliation() {
        val started =
            ChatItem.Tool(
                "t1",
                "terminal",
                "running",
                arguments = "input",
                startedAt = Instant.parse("2026-07-17T10:00:00Z"),
            )
        val completed =
            started.copy(
                state = "completed",
                result = "ok",
                completedAt = Instant.parse("2026-07-17T10:00:01Z"),
            )
        assertEquals(
            listOf(completed.copy(durationMs = 1000)),
            mergeHistoryAndLive(listOf(started), listOf(completed)),
        )
    }

    @Test
    fun partialHistoryKeepsMissingLiveParallelChildInOriginalGroup() {
        val persisted = ChatItem.Tool("a", "terminal", "running", arguments = "a")
        val live =
            ChatItem.ParallelToolGroup(
                "batch",
                listOf(persisted, ChatItem.Tool("b", "read_file", "running", arguments = "b")),
            )
        val merged = mergeHistoryAndLive(listOf(persisted), listOf(live))
        assertEquals(
            listOf("a", "b"),
            (merged.single() as ChatItem.ParallelToolGroup).tools.map { it.id },
        )
    }

    @Test
    fun databaseFlushTimestampsDoNotProduceFalsePrecision() {
        val rows =
            Json.parseToJsonElement(
                    """[
                    {"id":"m1","role":"assistant","content":"","created_at":"2026-07-17T10:00:00Z","tool_calls":[{"id":"a","function":{"name":"terminal","arguments":"{}"}}]},
                    {"id":"m2","role":"tool","tool_call_id":"a","tool_name":"terminal","content":"ok","created_at":"2026-07-17T10:00:00.001Z"}
                    ]"""
                )
                .jsonArray
                .map { it.jsonObject }
        val tool = messagesFromHistoryRows(rows).single() as ChatItem.Tool
        assertEquals(null, tool.durationMs)
        assertEquals(Instant.parse("2026-07-17T10:00:00.001Z"), tool.completedAt)
    }

    @Test
    fun historyTimestampIsPreservedAndFormattedInDeviceLocaleAndZone() {
        val row =
            Json.parseToJsonElement(
                    """{"id":"m1","role":"assistant","content":"Hello","created_at":"2026-07-17T12:37:00Z"}"""
                )
                .jsonObject
        val message = messagesFromHistoryRow(row).single() as ChatItem.Message
        assertEquals(Instant.parse("2026-07-17T12:37:00Z"), message.timestamp)
        assertEquals(
            "14:37",
            formatClockTime(message.timestamp!!, ZoneId.of("Europe/Berlin"), Locale.GERMANY),
        )
    }

    @Test
    fun historyRowParsesNumericTimestampAsInstant() {
        // Server stores timestamps as Unix floats (e.g. 1752757200.123)
        val row =
            Json.parseToJsonElement(
                    """{"id":"m1","role":"assistant","content":"Hi","timestamp":1752757200.0}"""
                )
                .jsonObject
        val message = messagesFromHistoryRow(row).single() as ChatItem.Message
        assertEquals(Instant.ofEpochSecond(1752757200), message.timestamp)
    }

    @Test
    fun historyRowTreatsCreatedAtMsAsMilliseconds() {
        val row =
            Json.parseToJsonElement(
                    """{"id":12,"role":"assistant","content":"Hi","created_at_ms":1752757200123}"""
                )
                .jsonObject
        val message = messagesFromHistoryRow(row).single() as ChatItem.Message
        assertEquals(Instant.ofEpochMilli(1752757200123), message.timestamp)
    }

    @Test
    fun genericNumericTimestampAcceptsNanoseconds() {
        val timestamp =
            mapOf<String, kotlinx.serialization.json.JsonElement>(
                    "timestamp" to JsonPrimitive(1752757200123000000L)
                )
                .instant()
        assertEquals(Instant.ofEpochMilli(1752757200123), timestamp)
    }

    @Test
    fun latestEventIdUsesPersistedHistoryIds() {
        val rows =
            listOf(
                Json.parseToJsonElement("""{"id":4}""").jsonObject,
                Json.parseToJsonElement("""{"id":9}""").jsonObject,
            )
        assertEquals(9L, rows.latestEventId())
    }

    @Test
    fun clarifyRequestParseQuestionAndChoices() {
        val payload =
            mapOf(
                "request_id" to JsonPrimitive("abc123"),
                "question" to JsonPrimitive("Which target?"),
                "choices" to JsonArray(listOf(JsonPrimitive("staging"), JsonPrimitive("prod"))),
            )
        val request = parseClarifyRequest(payload)
        assertNotNull(request)
        assertEquals("abc123", request!!.requestId)
        assertEquals("Which target?", request.question)
        assertEquals(listOf("staging", "prod"), request.choices)
    }

    @Test
    fun clarifyRequestWithoutChoicesIsOpenEnded() {
        val payload =
            mapOf(
                "request_id" to JsonPrimitive("r1"),
                "question" to JsonPrimitive("What do you think?"),
            )
        val request = parseClarifyRequest(payload)
        assertNotNull(request)
        assertEquals("What do you think?", request!!.question)
        assertEquals(emptyList<String>(), request.choices)
    }

    @Test
    fun clarifyRequestWithoutRequestIdReturnsNull() {
        val payload = mapOf("question" to JsonPrimitive("Q?"))
        assertNull(parseClarifyRequest(payload))
    }

    @Test
    fun clarifyRequestWithoutQuestionReturnsNull() {
        val payload = mapOf("request_id" to JsonPrimitive("r1"))
        assertNull(parseClarifyRequest(payload))
    }

    @Test
    fun clarifyRequestTruncatesExcessChoices() {
        val payload =
            mapOf(
                "request_id" to JsonPrimitive("r1"),
                "question" to JsonPrimitive("Pick"),
                "choices" to
                    JsonArray(listOf("a", "b", "c", "d", "e", "f").map { JsonPrimitive(it) }),
            )
        val request = parseClarifyRequest(payload)
        assertNotNull(request)
        assertEquals(listOf("a", "b", "c", "d"), request!!.choices)
    }

    @Test
    fun timelinesRemainScopedWhenSwitchingChats() {
        val firstMessage = ChatItem.Message("user", "first chat")
        val secondMessage = ChatItem.Message("assistant", "second chat")
        val first =
            ChatUiState(selectedId = "one")
                .withCurrentItems(listOf(firstMessage))
                .withTimeline("two", listOf(secondMessage))

        assertEquals(listOf(firstMessage), first.items)
        assertEquals(listOf(firstMessage), first.timelineFor("one"))
        assertEquals(listOf(secondMessage), first.timelineFor("two"))

        val switched = first.copy(selectedId = "two", items = first.timelineFor("two"))
        assertEquals(listOf(secondMessage), switched.items)
        assertEquals(listOf(firstMessage), switched.timelineFor("one"))
    }

    @Test
    fun backgroundTimelineUpdatesDoNotReplaceTheOpenChat() {
        val openMessage = ChatItem.Message("user", "open")
        val backgroundMessage = ChatItem.Message("user", "background")
        val state =
            ChatUiState(selectedId = "open")
                .withCurrentItems(listOf(openMessage))
                .withTimeline("background", listOf(backgroundMessage))

        assertEquals(listOf(openMessage), state.items)
        assertEquals(listOf(backgroundMessage), state.timelineFor("background"))
    }
}
