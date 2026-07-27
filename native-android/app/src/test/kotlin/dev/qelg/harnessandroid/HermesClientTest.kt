package dev.qelg.harnessandroid

import dev.qelg.harnessandroid.data.ConnectionConfig
import dev.qelg.harnessandroid.data.DashboardTranscriptionClient
import dev.qelg.harnessandroid.data.HermesClient
import dev.qelg.harnessandroid.data.TranscriptionBackend
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesClientTest {
    @Test
    fun connectUsesApiCapabilitiesWithBearerAuthentication() = runBlocking {
        withClient(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"object":"hermes.api_server.capabilities","platform":"hermes-agent","features":{"session_resources":true,"session_chat_streaming":true}}"""
                )
        ) { client, server ->
            client.connect()

            val request = server.takeRequest()
            assertEquals("/v1/capabilities", request.path)
            assertEquals("Bearer api-key", request.getHeader("Authorization"))
            assertEquals(null, request.getHeader("X-Hermes-Session-Token"))
        }
    }

    @Test
    fun sessionsIncludeChildrenAndFollowAllApiPages() = runBlocking {
        withClient(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"object":"list","data":[{"id":"root","title":"Root"},{"id":"delegate","source":"delegate_task","parent_session_id":"root"}],"limit":200,"offset":0,"has_more":true}"""
                ),
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"object":"list","data":[{"id":"compression","parent_session_id":"root","end_reason":"compression"}],"limit":200,"offset":200,"has_more":false}"""
                ),
        ) { client, server ->
            val sessions = client.sessions()

            assertEquals(
                listOf("root", "delegate", "compression"),
                sessions.map { it["id"]?.jsonPrimitive?.contentOrNull },
            )
            assertEquals(
                "/api/sessions?limit=200&offset=0&include_children=true",
                server.takeRequest().path,
            )
            assertEquals(
                "/api/sessions?limit=200&offset=200&include_children=true",
                server.takeRequest().path,
            )
        }
    }

    @Test
    fun transcriptionCanUseDashboardWithoutChangingSessionRuntimeBackend() = runBlocking {
        val dashboard = MockWebServer()
        dashboard.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"transcript":"dashboard text"}""")
        )
        dashboard.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client =
            HermesClient(
                ConnectionConfig(
                    baseUrl = "https://api.example.test",
                    token = "api-key",
                    dashboardBaseUrl = dashboard.url("/").toString(),
                    dashboardToken = "dashboard-token",
                    transcriptionBackend = TranscriptionBackend.DASHBOARD,
                ),
                scope,
            )
        try {
            assertEquals(
                "dashboard text",
                client.transcribe("audio".encodeToByteArray(), "audio/mp4"),
            )
            val request = dashboard.takeRequest()
            assertEquals("/api/audio/transcribe", request.path)
            assertEquals("dashboard-token", request.getHeader("X-Hermes-Session-Token"))
            assertEquals(null, request.getHeader("Authorization"))
        } finally {
            client.close()
            scope.cancel()
            dashboard.shutdown()
        }
    }

    @Test
    fun dashboardTranscriptionSupportsPasswordLoginCookies() = runBlocking {
        val dashboard = MockWebServer()
        dashboard.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setHeader("Set-Cookie", "session=authenticated; Path=/")
                .setBody("{}")
        )
        dashboard.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"transcript":"cookie text"}""")
        )
        dashboard.start()
        val client =
            DashboardTranscriptionClient(
                ConnectionConfig(
                    baseUrl = "https://api.example.test",
                    dashboardBaseUrl = dashboard.url("/").toString(),
                    username = "mobile",
                    password = "secret",
                    transcriptionBackend = TranscriptionBackend.DASHBOARD,
                )
            )
        try {
            client.authenticate()
            assertEquals("cookie text", client.transcribe(byteArrayOf(1), "audio/mp4"))
            assertEquals("/auth/password-login", dashboard.takeRequest().path)
            val transcribe = dashboard.takeRequest()
            assertEquals("/api/audio/transcribe", transcribe.path)
            assertTrue(transcribe.getHeader("Cookie")?.contains("session=authenticated") == true)
        } finally {
            client.close()
            dashboard.shutdown()
        }
    }

    @Test
    fun createSessionUsesRestResourceAndUnwrapsSession() = runBlocking {
        withClient(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"object":"hermes.session","session":{"id":"api-1","title":"Untitled session","source":"api_server"}}"""
                )
        ) { client, server ->
            val session = client.createSession()

            assertEquals("api-1", session["id"]?.jsonPrimitive?.contentOrNull)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/sessions", request.path)
            assertEquals("{}", request.body.readUtf8())
        }
    }

    @Test
    fun submitUsesControllableRunsAndMapsSseEventsIntoTimelineContract() = runBlocking {
        withClient(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"object":"list","data":[{"role":"assistant","content":"Earlier"}]}"""),
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"run_id":"run-1","status":"started"}"""),
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"event":"message.delta","run_id":"run-1","delta":"Hel"}

                    data: {"event":"tool.started","run_id":"run-1","tool":"read_file","preview":"README.md"}

                    data: {"event":"tool.completed","run_id":"run-1","tool":"read_file","duration":0.1,"error":false}

                    data: {"event":"run.completed","run_id":"run-1","output":"Hello","usage":{"input_tokens":10,"output_tokens":2,"total_tokens":12,"context_used":20000,"context_max":100000,"context_percent":20,"total":500,"calls":3}}

                    """
                        .trimIndent()
                ),
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"object":"list","data":[{"id":"api-1","last_active":10}]}"""),
        ) { client, server ->
            val events = mutableListOf<dev.qelg.harnessandroid.data.GatewayEvent>()
            val collector = launch { client.events.take(6).toList(events) }

            client.submit("api-1", "Hello", "deep")
            withTimeout(5_000) { collector.join() }

            assertEquals(
                listOf(
                    "message.delta",
                    "tool.start",
                    "tool.complete",
                    "session.info",
                    "message.complete",
                    "session.inactive",
                ),
                events.map { it.type },
            )
            assertEquals("Hel", events[0].payload["text"]?.jsonPrimitive?.contentOrNull)
            assertEquals("read_file", events[1].payload["name"]?.jsonPrimitive?.contentOrNull)
            assertTrue(
                events[1].payload["tool_call_id"]?.jsonPrimitive?.contentOrNull?.isNotBlank() ==
                    true
            )
            assertEquals(events[1].payload["tool_call_id"], events[2].payload["tool_call_id"])
            assertEquals(
                20_000L,
                events[3].payload["usage"]?.jsonObject?.get("context_used")?.jsonPrimitive?.long,
            )
            assertEquals("Hello", events[4].payload["text"]?.jsonPrimitive?.contentOrNull)

            val historyRequest = server.takeRequest()
            assertEquals("/api/sessions/api-1/messages", historyRequest.path)
            val runRequest = server.takeRequest()
            assertEquals("/v1/runs", runRequest.path)
            assertEquals("Bearer api-key", runRequest.getHeader("Authorization"))
            val runBody = runRequest.body.readUtf8()
            assertTrue(runBody.contains("\"input\":\"Hello\""))
            assertTrue(runBody.contains("\"session_id\":\"api-1\""))
            assertTrue(runBody.contains("\"model\":\"deep\""))
            assertTrue(runBody.contains("\"conversation_history\""))
            assertEquals("/v1/runs/run-1/events", server.takeRequest().path)
            assertEquals(
                "/api/sessions?limit=200&offset=0&include_children=true",
                server.takeRequest().path,
            )
        }
    }

    @Test
    fun submitRejectsEventStreamEofWithoutTerminalRunEvent() = runBlocking {
        withClient(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"object":"list","data":[]}"""),
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"run_id":"run-cut","status":"started"}"""),
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"event":"message.delta","run_id":"run-cut","delta":"partial"}

                    """
                        .trimIndent()
                ),
        ) { client, _ ->
            val error = runCatching { client.submit("api-1", "Hello") }.exceptionOrNull()

            assertTrue(error?.message.orEmpty().contains("before a terminal event"))
        }
    }

    @Test
    fun interruptStopsTheServerRunBeforeClosingItsEventStream() = runBlocking {
        val server = MockWebServer()
        val eventsStarted = CountDownLatch(1)
        val releaseEvents = CountDownLatch(1)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (request.path) {
                        "/api/sessions/api-1/messages" ->
                            MockResponse()
                                .setHeader("Content-Type", "application/json")
                                .setBody("""{"object":"list","data":[]}""")
                        "/v1/runs" ->
                            MockResponse()
                                .setResponseCode(202)
                                .setHeader("Content-Type", "application/json")
                                .setBody("""{"run_id":"run-stop","status":"started"}""")
                        "/v1/runs/run-stop/events" -> {
                            eventsStarted.countDown()
                            releaseEvents.await(5, TimeUnit.SECONDS)
                            MockResponse()
                                .setHeader("Content-Type", "text/event-stream")
                                .setBody(
                                    """
                                    data: {"event":"run.cancelled","run_id":"run-stop"}

                                    """
                                        .trimIndent()
                                )
                        }
                        "/v1/runs/run-stop/stop" -> {
                            releaseEvents.countDown()
                            MockResponse()
                                .setHeader("Content-Type", "application/json")
                                .setBody("""{"run_id":"run-stop","status":"stopping"}""")
                        }
                        "/api/sessions?limit=200&offset=0&include_children=true" ->
                            MockResponse()
                                .setHeader("Content-Type", "application/json")
                                .setBody("""{"object":"list","data":[{"id":"api-1"}]}""")
                        else -> MockResponse().setResponseCode(404)
                    }
            }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client =
            HermesClient(ConnectionConfig(server.url("/").toString(), token = "api-key"), scope)
        try {
            var failure: Throwable? = null
            val turn = launch {
                runCatching { client.submit("api-1", "Long task") }.onFailure { failure = it }
            }

            assertTrue(withContext(Dispatchers.IO) { eventsStarted.await(5, TimeUnit.SECONDS) })
            client.interrupt()
            withTimeout(5_000) { turn.join() }

            assertEquals(null, failure)
            val paths = (1..5).map { server.takeRequest().path }
            assertTrue("/v1/runs/run-stop/stop" in paths)
            assertTrue(
                paths.indexOf("/v1/runs/run-stop/stop") <
                    paths.indexOf("/api/sessions?limit=200&offset=0&include_children=true")
            )
        } finally {
            releaseEvents.countDown()
            client.close()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun delayedStopFailureStillClearsRunForTheNextSubmission() = runBlocking {
        val server = MockWebServer()
        val firstRunRequested = CountDownLatch(1)
        val releaseFirstRun = CountDownLatch(1)
        val runCount = AtomicInteger()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when {
                        request.path == "/api/sessions/api-1/messages" ->
                            MockResponse()
                                .setHeader("Content-Type", "application/json")
                                .setBody("""{"object":"list","data":[]}""")
                        request.path == "/v1/runs" && runCount.incrementAndGet() == 1 -> {
                            firstRunRequested.countDown()
                            releaseFirstRun.await(5, TimeUnit.SECONDS)
                            MockResponse()
                                .setResponseCode(202)
                                .setHeader("Content-Type", "application/json")
                                .setBody("""{"run_id":"run-stop-fails","status":"started"}""")
                        }
                        request.path == "/v1/runs" ->
                            MockResponse()
                                .setResponseCode(202)
                                .setHeader("Content-Type", "application/json")
                                .setBody("""{"run_id":"run-next","status":"started"}""")
                        request.path == "/v1/runs/run-stop-fails/stop" ->
                            MockResponse()
                                .setResponseCode(500)
                                .setHeader("Content-Type", "application/json")
                                .setBody("""{"error":"stop failed"}""")
                        request.path == "/v1/runs/run-next/events" ->
                            MockResponse()
                                .setHeader("Content-Type", "text/event-stream")
                                .setBody(
                                    """
                                    data: {"event":"run.completed","run_id":"run-next","output":"done"}

                                    """
                                        .trimIndent()
                                )
                        request.path == "/api/sessions?limit=200&offset=0&include_children=true" ->
                            MockResponse()
                                .setHeader("Content-Type", "application/json")
                                .setBody("""{"object":"list","data":[{"id":"api-1"}]}""")
                        else -> MockResponse().setResponseCode(404)
                    }
            }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client =
            HermesClient(ConnectionConfig(server.url("/").toString(), token = "api-key"), scope)
        try {
            var firstFailure: Throwable? = null
            val first = launch {
                runCatching { client.submit("api-1", "first") }.onFailure { firstFailure = it }
            }
            assertTrue(withContext(Dispatchers.IO) { firstRunRequested.await(5, TimeUnit.SECONDS) })
            client.interrupt()
            releaseFirstRun.countDown()
            withTimeout(5_000) { first.join() }
            assertTrue(firstFailure?.message.orEmpty().contains("Hermes HTTP 500"))

            client.submit("api-1", "second")

            val paths = (1..7).map { server.takeRequest().path }
            assertTrue("/v1/runs/run-stop-fails/stop" in paths)
            assertTrue("/v1/runs/run-next/events" in paths)
        } finally {
            releaseFirstRun.countDown()
            client.close()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun latestSessionIdFollowsCompressionChildrenAcrossAllPages() = runBlocking {
        withClient(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"object":"list","data":[{"id":"root","last_active":1}],"has_more":true}"""
                ),
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"object":"list","data":[{"id":"child","parent_session_id":"root","last_active":2},{"id":"tip","parent_session_id":"child","last_active":3,"model":"deep"}],"has_more":false}"""
                ),
        ) { client, server ->
            val latest = client.latestSession("root")

            assertEquals("tip", latest["id"]?.jsonPrimitive?.contentOrNull)
            assertEquals("deep", latest["model"]?.jsonPrimitive?.contentOrNull)
            assertEquals(
                "/api/sessions?limit=200&offset=0&include_children=true",
                server.takeRequest().path,
            )
            assertEquals(
                "/api/sessions?limit=200&offset=200&include_children=true",
                server.takeRequest().path,
            )
        }
    }

    @Test
    fun modelCatalogExposesApiServerRoutesAndSelectsTheSessionsCurrentModel() = runBlocking {
        withClient(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"object":"list","data":[{"id":"hermes-agent","root":"hermes-agent"},{"id":"fast","root":"openai/gpt-fast"},{"id":"deep","root":"anthropic/claude-deep"}]}"""
                )
        ) { client, server ->
            val catalog = client.modelOptions("anthropic/claude-deep")

            assertEquals("deep", catalog.selected?.model)
            assertEquals("api_server", catalog.selected?.provider)
            assertEquals(
                listOf("hermes-agent", "fast", "deep"),
                catalog.providers.single().models.map { it.id },
            )
            assertEquals(
                "anthropic/claude-deep",
                catalog.providers.single().models.last().resolvedModel,
            )
            assertEquals("/v1/models", server.takeRequest().path)
        }
    }

    @Test
    fun historyReadsApiServerDataField() = runBlocking {
        withClient(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"object":"list","session_id":"s1","data":[{"role":"assistant","content":"Hello","timestamp":"2026-07-17T12:00:00Z"}]}"""
                )
        ) { client, server ->
            val history = client.history("existing-session")

            assertEquals(1, history.size)
            assertEquals("assistant", history[0]["role"]?.jsonPrimitive?.contentOrNull)
            assertEquals("Hello", history[0]["content"]?.jsonPrimitive?.contentOrNull)
            assertEquals("/api/sessions/existing-session/messages", server.takeRequest().path)
        }
    }

    @Test
    fun historyTreatsNullDataAsEmpty() = runBlocking {
        withClient(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"object":"list","data":null}""")
        ) { client, _ ->
            assertEquals(emptyList<JsonObject>(), client.history("existing-session"))
        }
    }

    @Test
    fun conversationTokenUsageUnwrapsApiSessionsAndTraversesCompressionParents() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"object":"hermes.session","session":{"id":"tip","parent_session_id":"root","input_tokens":50,"output_tokens":10,"cache_read_tokens":150,"cache_write_tokens":5,"api_call_count":1,"has_system_prompt":true}}"""
                )
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"object":"hermes.session","session":{"id":"root","end_reason":"compression","input_tokens":100,"output_tokens":20,"cache_read_tokens":300,"cache_write_tokens":10,"api_call_count":2}}"""
                )
        )
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client =
            HermesClient(ConnectionConfig(server.url("/").toString(), token = "api-key"), scope)
        try {
            val details = client.conversationTokenDetails("tip")

            assertEquals(645L, details.usage.totalTokens)
            assertEquals(null, details.systemPrompt)
            assertEquals("/api/sessions/tip", server.takeRequest().path)
            assertEquals("/api/sessions/root", server.takeRequest().path)
        } finally {
            client.close()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun toolDefinitionsUseApiServerToolsets() = runBlocking {
        withClient(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"object":"list","platform":"api_server","data":[{"name":"file","label":"Files","description":"File operations","enabled":true,"configured":true,"tools":["read_file","patch"]},{"name":"browser","label":"Browser","enabled":false,"configured":true,"tools":["browser"]}]}"""
                )
        ) { client, server ->
            val definitions = client.toolDefinitions("api-1")

            assertEquals(2, definitions.total)
            assertEquals("Files", definitions.sections.single().name)
            assertEquals(
                listOf("read_file", "patch"),
                definitions.sections.single().tools.map { it.name },
            )
            assertEquals("/v1/toolsets", server.takeRequest().path)
        }
    }

    @Test
    fun transcriptionExplainsWhenNoBackendIsConfigured() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = HermesClient(ConnectionConfig("https://example.com", token = "api-key"), scope)
        try {
            val error =
                runCatching { client.transcribe(byteArrayOf(1), "audio/mp4") }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("not configured"))
        } finally {
            client.close()
            scope.cancel()
        }
    }

    private suspend fun withClient(
        vararg responses: MockResponse,
        block: suspend (HermesClient, MockWebServer) -> Unit,
    ) {
        val server = MockWebServer()
        responses.forEach(server::enqueue)
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client =
            HermesClient(ConnectionConfig(server.url("/").toString(), token = "api-key"), scope)
        try {
            block(client, server)
        } finally {
            client.close()
            scope.cancel()
            server.shutdown()
        }
    }
}
