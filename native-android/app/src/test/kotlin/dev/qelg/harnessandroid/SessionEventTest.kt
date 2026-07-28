package dev.qelg.harnessandroid

import dev.qelg.harnessandroid.data.SessionEvent
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEventTest {
    @Test
    fun parsesEventSummaryWithoutDroppingRawPayload() {
        val raw =
            Json.parseToJsonElement(
                    """{
                      "id":42,
                      "name":"tool.call.requested",
                      "created_at_ms":1752757200123,
                      "producer":"tool-call-requester",
                      "causation_id":17,
                      "tags":{"session":"sess_1","tool":"terminal"},
                      "payload":{"input":{"cmd":"pwd"},"unknown":true}
                    }"""
                )
                .jsonObject

        val event = SessionEvent.fromJson(raw)

        assertEquals(42L, event.id)
        assertEquals("tool.call.requested", event.displayName)
        assertEquals(Instant.ofEpochMilli(1752757200123), event.timestamp)
        assertEquals("tool-call-requester", event.originator)
        assertEquals(17L, event.causationId)
        assertEquals(raw, event.raw)
        assertTrue(event.prettyJson.contains("\"unknown\": true"))
    }

    @Test
    fun optionalSummaryFieldsHaveSafeFallbacks() {
        val raw =
            Json.parseToJsonElement(
                    """{"id":{},"name":[],"created_at_ms":{},"producer":[],"payload":{"future":"field"}}"""
                )
                .jsonObject

        val event = SessionEvent.fromJson(raw)

        assertEquals("Unknown event", event.displayName)
        assertEquals(null, event.timestamp)
        assertEquals(null, event.originator)
        assertEquals(null, event.causationId)
        assertEquals(raw, event.raw)
    }

    @Test
    fun resolvesCausationEventById() {
        val cause =
            SessionEvent.fromJson(
                Json.parseToJsonElement("""{"id":4,"name":"llm.run.requested"}""").jsonObject
            )
        val effect =
            SessionEvent.fromJson(
                Json.parseToJsonElement("""{"id":7,"name":"llm.run.started","causation_id":4}""")
                    .jsonObject
            )

        assertEquals(cause, causationEvent(effect, listOf(cause, effect)))
        assertEquals(null, causationEvent(effect, listOf(effect)))
    }
}
