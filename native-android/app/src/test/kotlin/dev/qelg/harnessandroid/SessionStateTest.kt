package dev.qelg.harnessandroid

import dev.qelg.harnessandroid.data.HarnessSession
import dev.qelg.harnessandroid.data.HarnessSessionState
import dev.qelg.harnessandroid.data.applySessionStates
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
                        """{"session_id":"sess_1","state":"finished","read":"unread","source_event_id":11,"outcome":"stop","event_id":12,"created_at_ms":1752757200123}"""
                    )
                    .jsonObject
            )

        assertEquals("sess_1", state.sessionId)
        assertTrue(state.finished)
        assertTrue(state.unread)
        assertEquals("stop", state.outcome)
        assertEquals(11L, state.sourceEventId)
        assertEquals(12L, state.eventId)
        assertEquals(Instant.ofEpochMilli(1752757200123), Instant.parse(state.updatedAt!!))
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
    fun missingServerStatePreservesLegacySessionFields() {
        val session =
            HarnessSession("legacy", "Legacy", updatedAt = "2026-07-18T08:00:00Z", active = true)

        assertEquals(session, applySessionStates(listOf(session), emptyList()).single())
        assertTrue(isSessionRead(session, "2026-07-18T09:00:00Z"))
    }
}
