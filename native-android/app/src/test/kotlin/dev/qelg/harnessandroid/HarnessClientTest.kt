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

    @Test
    fun chatGptCodexOffersSupportedModels() = runBlocking {
        server(
            MockResponse().setBody("""{"providers":["chatgpt-codex"]}"""),
            MockResponse()
                .setBody("""{"provider":"chatgpt-codex","model":"terra","scope":"session"}"""),
        ) { client, _ ->
            val catalog = client.modelOptions("sess_1")
            assertEquals(
                listOf("gpt-5.6-sol", "terra", "luna"),
                catalog.providers.single().models.map { it.id },
            )
            assertEquals(ModelSelection("chatgpt-codex", "terra"), catalog.selected)
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
            val collected = async { client.events.take(4).toList() }
            client.submit("sess_1", "where am I?")
            val events = collected.await()
            assertEquals(
                listOf("tool.start", "tool.complete", "message.complete", "session.inactive"),
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
