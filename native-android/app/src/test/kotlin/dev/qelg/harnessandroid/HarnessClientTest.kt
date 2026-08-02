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
    fun listsAndDeletesContainers() = runBlocking {
        server(
            MockResponse()
                .setBody(
                    """[{"container_id":"container_1","name":"workspace","session_id":"session_1","session_title":"Container session","size_bytes":1536}]"""
                ),
            MockResponse().setResponseCode(204),
        ) { client, server ->
            val container = client.containers().single()
            client.deleteContainer("container with space")

            assertEquals("container_1", container.containerId)
            assertEquals("workspace", container.name)
            assertEquals("session_1", container.sessionId)
            assertEquals("Container session", container.sessionTitle)
            assertEquals(1536L, container.sizeBytes)
            assertEquals("/containers", server.takeRequest().path)
            val delete = server.takeRequest()
            assertEquals("DELETE", delete.method)
            assertEquals("/containers/container+with+space", delete.path)
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
            c.createSession(
                ModelSelection(
                    "mock-llm",
                    "test-model",
                    ThinkingLevel.High,
                    reasoningSummary = true,
                )
            )
            s.takeRequest()
            val r = s.takeRequest()
            assertEquals("/model-selection", r.path)
            val body = r.body.readUtf8()
            assertTrue(body.contains("mock-llm"))
            assertTrue(body.contains("\"thinking_level\":\"high\""))
            assertTrue(body.contains("\"reasoning_summary\":true"))
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
            """
            |id: 3
            |event: llm.delta
            |data: {"event":{"id":3,"name":"llm.delta","payload":{"delta":"Hi"},"created_at_ms":1752757200123}}
            |
            |id: 4
            |event: chat.message.assistant.created
            |data: {"event":{"id":4,"name":"chat.message.assistant.created","payload":{"content":"Hi"},"created_at_ms":1752757201123}}
            |
            |id: 5
            |event: session.state
            |data: {"event":{"id":5,"name":"session.state","tags":{"session":"sess_1","state":"finished","read":"unread"},"payload":{"source_event_id":4,"outcome":"stop"},"created_at_ms":1752757202123}}
            |
            |"""
                .trimMargin()
        server(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)) {
            client,
            server ->
            val got = async { client.events.take(3).toList() }
            client.watchSession("sess_1", 2)
            val events = withTimeout(5_000) { got.await() }
            assertEquals(
                listOf("message.delta", "message.complete", "session.state"),
                events.map { it.type },
            )
            assertEquals("finished", events[2].payload["state"]?.jsonPrimitive?.content)
            assertEquals("unread", events[2].payload["read"]?.jsonPrimitive?.content)
            assertEquals("stop", events[2].payload["outcome"]?.jsonPrimitive?.content)
            assertEquals(5L, events[2].payload["event_id"]?.jsonPrimitive?.content?.toLong())
            assertEquals("/sessions/sess_1/messages/updates?since_id=2", server.takeRequest().path)
        }
    }

    @Test
    fun chatGptCodexOffersSupportedModels() = runBlocking {
        server(
            MockResponse().setBody("""{"providers":["chatgpt-codex"]}"""),
            MockResponse()
                .setBody(
                    """{"provider":"chatgpt-codex","model":"gpt-5.6-terra","thinking_level":"medium","reasoning_summary":true,"scope":"session"}"""
                ),
        ) { client, _ ->
            val catalog = client.modelOptions("sess_1")
            assertEquals(
                listOf("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna"),
                catalog.providers.single().models.map { it.id },
            )
            assertEquals(
                ModelSelection(
                    "chatgpt-codex",
                    "gpt-5.6-terra",
                    ThinkingLevel.Medium,
                    reasoningSummary = true,
                ),
                catalog.selected,
            )
        }
    }

    @Test
    fun openRouterOffersConfiguredModels() = runBlocking {
        server(MockResponse().setBody("""{"providers":["openrouter"]}""")) { client, _ ->
            val catalog = client.modelOptions()
            assertEquals(
                listOf(
                    "openai/gpt-4o-mini",
                    "deepseek/deepseek-v4-flash-latest",
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
                    "data: {\"id\":2,\"payload\":{\"content\":[{\"type\":\"reasoning\",\"content\":[],\"summary\":[{\"type\":\"summary_text\",\"text\":\"Inspecting files\"}]},{\"type\":\"function_call\",\"name\":\"podman-shell\",\"arguments\":\"{\\\"cmd\\\":\\\"pwd\\\"}\"}]}}",
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
            val collected = async { client.events.take(4).toList() }
            client.watchSession("sess_1")
            val events = withTimeout(5_000) { collected.await() }
            assertEquals(
                listOf("message.reasoning", "tool.start", "tool.complete", "message.complete"),
                events.map { it.type },
            )
            assertEquals("podman-shell", events[1].payload["name"]?.jsonPrimitive?.content)
            assertEquals(
                "pwd",
                events[1].payload["arguments"]?.jsonObject?.get("cmd")?.jsonPrimitive?.content,
            )
            assertEquals(
                "The directory is /work.",
                events[3].payload["text"]?.jsonPrimitive?.content,
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
