package dev.qelg.harnessandroid

import dev.qelg.harnessandroid.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun listsChildSessions() = runBlocking {
        server(
            MockResponse()
                .setBody("""[{"id":"child_1","title":"Child","parent_session_id":"parent"}]""")
        ) { client, server ->
            val child = client.childSessions("parent with space").single()

            assertEquals("child_1", child["id"]?.jsonPrimitive?.content)
            assertEquals("/sessions/parent+with+space/children", server.takeRequest().path)
        }
    }

    @Test
    fun listsCompleteLowLevelSessionEvents() = runBlocking {
        server(
            MockResponse()
                .setBody(
                    """[{"id":7,"name":"session.created","created_at_ms":1752757200123,"producer":"harness-api","tags":{"session":"sess_1"},"payload":{"title":"Demo","unknown":true}}]"""
                )
        ) { client, server ->
            val event = client.sessionEvents("session with space").single()

            assertEquals("session.created", event.displayName)
            assertEquals("harness-api", event.originator)
            assertTrue(event.prettyJson.contains("unknown"))
            assertEquals("/sessions/session+with+space/events", server.takeRequest().path)
        }
    }

    @Test
    fun listsAndMarksServerSessionStates() = runBlocking {
        server(
            MockResponse()
                .setBody(
                    """[{"session_id":"sess_1","state":"finished","read":"unread","outcome":"stop","event_id":12,"created_at_ms":1752757200123}]"""
                ),
            MockResponse()
                .setBody(
                    """{"session_id":"sess_1","state":"finished","read":"read","outcome":"stop","event_id":13,"created_at_ms":1752757201123}"""
                ),
            MockResponse()
                .setBody(
                    """{"session_id":"sess_1","state":"finished","read":"read","archive":"true","outcome":"stop","event_id":14,"created_at_ms":1752757202123}"""
                ),
        ) { client, server ->
            val unread = client.sessionStates().single()
            val read = client.markSessionRead("session with space")
            val archived = client.archiveSession("session with space")

            assertTrue(unread.unread)
            assertEquals("stop", unread.outcome)
            assertEquals("read", read.read)
            assertTrue(archived.archived)
            assertEquals("/session-states", server.takeRequest().path)
            assertEquals("/sessions/session+with+space/state/read", server.takeRequest().path)
            assertEquals("/sessions/session+with+space/state/archive", server.takeRequest().path)
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
    fun submitsMessageWithoutWaitingForTheLlmRun() = runBlocking {
        server(MockResponse().setBody("""{"id":1,"role":"user","content":"hello"}""")) {
            client,
            server ->
            client.submit("sess_1", "hello")
            val request = server.takeRequest()
            assertEquals("/sessions/sess_1/messages", request.path)
            assertTrue(request.body.readUtf8().contains("hello"))
        }
    }

    @Test
    fun watchesSessionDeltasAndCompletionFromLastHistoryEvent() = runBlocking {
        val body =
            """id: 3
                |event: llm.delta
                |data: {"event":{"id":3,"name":"llm.delta","payload":{"delta":"Hi"},"created_at_ms":1752757200123}}
                |
                |id: 4
                |event: chat.message.assistant.created
                |data: {"event":{"id":4,"name":"chat.message.assistant.created","payload":{"content":"Hi"},"created_at_ms":1752757201123}}
                |
                |"""
                .trimMargin()
        server(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)) {
            client,
            server ->
            val got = async { client.events.take(2).toList() }
            client.watchSession("sess_1", 2)
            assertEquals(
                listOf("message.delta", "message.complete"),
                withTimeout(5_000) { got.await() }.map { it.type },
            )
            assertEquals("/sessions/sess_1/messages/updates?since_id=2", server.takeRequest().path)
        }
    }

    @Test
    fun chatGptCodexOffersSupportedModels() = runBlocking {
        server(
            MockResponse().setBody("""{"providers":["chatgpt-codex"]}"""),
            MockResponse()
                .setBody(
                    """{"provider":"chatgpt-codex","model":"gpt-5.6-terra","scope":"session"}"""
                ),
        ) { client, _ ->
            val catalog = client.modelOptions("sess_1")
            assertEquals(
                listOf("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna"),
                catalog.providers.single().models.map { it.id },
            )
            assertEquals(ModelSelection("chatgpt-codex", "gpt-5.6-terra"), catalog.selected)
        }
    }

    @Test
    fun openRouterOffersConfiguredModels() = runBlocking {
        server(MockResponse().setBody("""{"providers":["openrouter"]}""")) { client, _ ->
            val catalog = client.modelOptions()
            assertEquals(
                listOf(
                    "openai/gpt-4o-mini",
                    "deepseek/deepseek-v4-flash",
                    "qwen/qwen3.6-35b-a3b",
                    "moonshotai/kimi-k3",
                ),
                catalog.providers.single().models.map { it.id },
            )
        }
    }

    @Test
    fun streamsStructuredCodexAnswerAndToolDetails() = runBlocking {
        val body =
            listOf(
                    "event: chat.message.assistant.created",
                    "data: {\"id\":2,\"payload\":{\"content\":[{\"type\":\"function_call\",\"name\":\"podman-shell\",\"arguments\":\"{\\\"cmd\\\":\\\"pwd\\\"}\"}]}}",
                    "",
                    "event: tool.call.requested",
                    "data: {\"id\":3,\"payload\":{\"tool\":\"podman-shell\",\"run_id\":\"call_1\",\"input\":{\"cmd\":\"pwd\"}}}",
                    "",
                    "event: chat.message.tool.created",
                    "data: {\"id\":4,\"payload\":{\"tool\":\"podman-shell\",\"run_id\":\"call_1\",\"content\":\"/work\"}}",
                    "",
                    "event: chat.message.assistant.created",
                    "data: {\"id\":5,\"payload\":{\"content\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"The directory is /work.\"}]}]}}",
                    "",
                    "",
                )
                .joinToString("\n")
        server(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)) {
            client,
            _ ->
            val collected = async { client.events.take(3).toList() }
            client.watchSession("sess_1")
            val events = withTimeout(5_000) { collected.await() }
            assertEquals(
                listOf("tool.start", "tool.complete", "message.complete"),
                events.map { it.type },
            )
            assertEquals("podman-shell", events[0].payload["name"]?.jsonPrimitive?.content)
            assertEquals(
                "pwd",
                events[0].payload["arguments"]?.jsonObject?.get("cmd")?.jsonPrimitive?.content,
            )
            assertEquals(
                "The directory is /work.",
                events[2].payload["text"]?.jsonPrimitive?.content,
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
