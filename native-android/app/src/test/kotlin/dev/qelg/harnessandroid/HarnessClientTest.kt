package dev.qelg.harnessandroid

import dev.qelg.harnessandroid.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class HarnessClientTest {
    @Test
    fun healthAndOptionalAuth() = runBlocking {
        server(MockResponse().setBody("""{"status":"ok"}"""), token = "secret") { c, s ->
            c.connect()
            assertEquals("Bearer secret", s.takeRequest().getHeader("Authorization"))
        }
    }

    @Test
    fun listsSessionsAndHistory() = runBlocking {
        server(
            MockResponse().setBody("""[{"id":"sess_1"}]"""),
            MockResponse().setBody("""[{"id":1,"role":"user","content":"hi"}]"""),
        ) { c, s ->
            assertEquals(1, c.sessions().size)
            assertEquals(1, c.history("sess_1").size)
            assertEquals("/sessions", s.takeRequest().path)
            assertEquals("/sessions/sess_1/messages", s.takeRequest().path)
        }
    }

    @Test
    fun createsSessionAndSelectsModel() = runBlocking {
        server(MockResponse().setBody("""{"id":"sess_1"}"""), MockResponse().setBody("{}")) { c, s
            ->
            c.createSession("test-model")
            s.takeRequest()
            val r = s.takeRequest()
            assertEquals("/model-selection", r.path)
            assertTrue(r.body.readUtf8().contains("mock-llm"))
        }
    }

    @Test
    fun streamsDeltasAndCompletion() = runBlocking {
        val body =
            "event: llm.delta\ndata: {\"payload\":{\"delta\":\"Hi\"}}\n\nevent: chat.message.assistant.created\ndata: {\"id\":3,\"payload\":{\"content\":\"Hi\"}}\n\n"
        server(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)) { c, _
            ->
            val got = async { c.events.take(3).toList() }
            c.submit("sess_1", "hello")
            assertEquals(
                listOf("message.delta", "message.complete", "session.inactive"),
                got.await().map { it.type },
            )
        }
    }

    private suspend fun server(
        vararg responses: MockResponse,
        token: String = "",
        block: suspend (HarnessClient, MockWebServer) -> Unit,
    ) {
        val s = MockWebServer()
        responses.forEach(s::enqueue)
        s.start()
        val c =
            HarnessClient(
                ConnectionConfig(s.url("/").toString(), token),
                CoroutineScope(Dispatchers.Unconfined),
            )
        try {
            block(c, s)
        } finally {
            c.close()
            s.shutdown()
        }
    }
}
