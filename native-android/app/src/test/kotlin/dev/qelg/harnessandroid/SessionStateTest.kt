package dev.qelg.harnessandroid

import dev.qelg.harnessandroid.data.HarnessSession
import dev.qelg.harnessandroid.data.HarnessSessionState
import dev.qelg.harnessandroid.data.applySessionStates
import dev.qelg.harnessandroid.data.filterArchivedSessions
import dev.qelg.harnessandroid.data.formatSessionState
import dev.qelg.harnessandroid.data.isSessionRead
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateTest {
    @Test
    fun decodesHarnessSessionStateContract() {
        val state =
            HarnessSessionState.fromJson(
                Json.parseToJsonElement(
                        """{"session_id":"sess_1","state":"finished","read":"unread","archive":"true","source_event_id":11,"outcome":"stop","event_id":12,"created_at_ms":1752757200123}"""
                    )
                    .jsonObject
            )

        assertEquals("sess_1", state.sessionId)
        assertTrue(state.finished)
        assertTrue(state.unread)
        assertTrue(state.archived)
        assertEquals("stop", state.outcome)
        assertEquals(11L, state.sourceEventId)
        assertEquals(12L, state.eventId)
        assertEquals(Instant.ofEpochMilli(1752757200123), Instant.parse(state.updatedAt!!))
    }

    @Test
    fun runningStateClearsLocalUnreadCount() {
        val initial =
            ChatUiState(
                selectedId = "session",
                sessions = listOf(HarnessSession("session", "Session")),
                unreadCounts = mapOf("session" to 1),
            )
        val running = initial.withSessionState(HarnessSessionState("session", "running"))
        assertEquals(emptyMap<String, Int>(), running.unreadCounts)
    }

    @Test
    fun serverStatesDriveActivityOrderingAndReadStatus() {
        val sessions =
            listOf(
                HarnessSession("first", "First", updatedAt = "2026-07-18T08:00:00Z"),
                HarnessSession("second", "Second", updatedAt = "2026-07-18T09:00:00Z"),
            )
        val states =
            listOf(
                HarnessSessionState(
                    "first",
                    "finished",
                    read = "unread",
                    outcome = "stop",
                    createdAtMs = Instant.parse("2026-07-18T10:00:00Z").toEpochMilli(),
                ),
                HarnessSessionState(
                    "second",
                    "running",
                    createdAtMs = Instant.parse("2026-07-18T11:00:00Z").toEpochMilli(),
                ),
            )

        val updated = applySessionStates(sessions, states)

        assertFalse(updated[0].active)
        assertFalse(isSessionRead(updated[0], "2026-07-19T00:00:00Z"))
        assertTrue(updated[1].active)
        assertEquals("Finished · stop", formatSessionState(updated[0].sessionState!!))
        assertEquals("Running", formatSessionState(updated[1].sessionState!!))
    }

    @Test
    fun archivedSessionsAreHiddenByDefault() {
        val visible = HarnessSession("visible", "Visible")
        val archived =
            HarnessSession(
                "archived",
                "Archived",
                sessionState =
                    HarnessSessionState("archived", "finished", read = "read", archive = "true"),
            )

        assertEquals(listOf(visible), filterArchivedSessions(listOf(visible, archived), false))
        assertEquals(
            listOf(visible, archived),
            filterArchivedSessions(listOf(visible, archived), true),
        )
    }

    @Test
    fun missingServerStatePreservesLegacySessionFields() {
        val session =
            HarnessSession("legacy", "Legacy", updatedAt = "2026-07-18T08:00:00Z", active = true)

        assertEquals(session, applySessionStates(listOf(session), emptyList()).single())
        assertTrue(isSessionRead(session, "2026-07-18T09:00:00Z"))
    }

    @Test
    fun liveSessionStateIsPerSessionAndSelectedActivityIsDerived() {
        val initial =
            ChatUiState(
                selectedId = "first",
                sessions =
                    listOf(HarnessSession("first", "First"), HarnessSession("second", "Second")),
            )

        val secondRunning =
            initial.withSessionState(HarnessSessionState("second", "running", eventId = 10))
        assertFalse(secondRunning.active)
        assertEquals(setOf("second"), secondRunning.activeSessionIds)

        val selectedRunning = secondRunning.copy(selectedId = "second")
        assertTrue(selectedRunning.active)

        val secondFinished =
            selectedRunning.withSessionState(
                HarnessSessionState("second", "finished", read = "unread", eventId = 11)
            )
        assertFalse(secondFinished.active)
        assertTrue(secondFinished.sessions.single { it.id == "second" }.sessionState!!.finished)
    }

    @Test
    fun olderLiveSessionStateCannotReplaceNewerState() {
        val finished =
            ChatUiState(
                    selectedId = "session",
                    sessions = listOf(HarnessSession("session", "Session")),
                )
                .withSessionState(
                    HarnessSessionState("session", "finished", read = "unread", eventId = 12)
                )

        val staleRunning =
            finished.withSessionState(HarnessSessionState("session", "running", eventId = 11))

        assertEquals(finished, staleRunning)
        assertFalse(staleRunning.active)
    }

    @Test
    fun staleStateRefreshCannotReplaceNewerLiveState() {
        val current = HarnessSessionState("session", "finished", read = "unread", eventId = 12)
        val staleFetched = HarnessSessionState("session", "running", eventId = 11)

        assertEquals(listOf(current), newestSessionStates(listOf(staleFetched), listOf(current)))
    }
}
