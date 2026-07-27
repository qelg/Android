package dev.qelg.harnessandroid

import dev.qelg.harnessandroid.data.GatewayEvent
import dev.qelg.harnessandroid.data.ProtocolCodec
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolCodecTest {
    @Test
    fun requestUsesHermesDesktopJsonRpcEnvelope() {
        val frame =
            ProtocolCodec.request("mobile-7", "session.list", mapOf("limit" to JsonPrimitive(200)))
        assertEquals("2.0", frame["jsonrpc"]?.jsonPrimitive?.content)
        assertEquals("mobile-7", frame["id"]?.jsonPrimitive?.content)
        assertEquals("session.list", frame["method"]?.jsonPrimitive?.content)
        assertEquals(200, frame["params"]?.jsonObject?.get("limit")?.jsonPrimitive?.int)
    }

    @Test
    fun eventFramePreservesSessionAndPayload() {
        val event =
            ProtocolCodec.event(
                """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"runtime-1","payload":{"text":"Hi"}}}"""
            )
        assertEquals(
            GatewayEvent("message.delta", "runtime-1", mapOf("text" to JsonPrimitive("Hi"))),
            event,
        )
    }

    @Test
    fun responseFrameIsNotMisclassifiedAsEvent() {
        assertTrue(ProtocolCodec.event("""{"jsonrpc":"2.0","id":"mobile-1","result":{}}""") == null)
    }
}
