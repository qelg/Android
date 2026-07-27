package dev.qelg.harnessandroid.data

import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class HermesClient(
    private val config: ConnectionConfig,
    @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
    private val client: OkHttpClient = OkHttpClient(),
) : Closeable {
    private val json = Json { ignoreUnknownKeys = true }
    private val eventChannel = Channel<GatewayEvent>(Channel.UNLIMITED)
    val events: Flow<GatewayEvent> = eventChannel.receiveAsFlow()

    @Volatile private var closed = false
    @Volatile private var connected = false
    @Volatile private var activeCall: Call? = null
    @Volatile private var activeRunId: String? = null
    @Volatile private var activeSessionId: String? = null
    @Volatile private var stopRequested = false
    private var toolSequence = 0L
    private val runningToolIds = mutableMapOf<String, java.util.ArrayDeque<String>>()
    private val dashboardTranscription =
        if (config.transcriptionBackend == TranscriptionBackend.DASHBOARD)
            DashboardTranscriptionClient(config)
        else null

    suspend fun connect() {
        check(!closed) { "Hermes client is closed" }
        if (connected) return
        val capabilities = http("GET", "/v1/capabilities")
        check(capabilities.string("platform") == "hermes-agent") {
            "Endpoint is not a Hermes API Server"
        }
        dashboardTranscription?.authenticate()
        connected = true
    }

    suspend fun modelOptions(sessionModel: String? = null): ModelCatalog {
        val response = http("GET", "/v1/models")
        val models =
            (response["data"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.mapNotNull { model ->
                    model.string("id")?.takeIf(String::isNotBlank)?.let { id ->
                        ModelOption(id = id, resolvedModel = model.string("root"))
                    }
                }
                .orEmpty()
        return ModelCatalog(
                providers =
                    if (models.isEmpty()) emptyList()
                    else listOf(ModelProvider("api_server", "Hermes API Server", models))
            )
            .selectedFor(sessionModel)
    }

    suspend fun history(sessionId: String): List<JsonObject> {
        val response = http("GET", "/api/sessions/${sessionId.urlEncode()}/messages")
        return ((response["data"] as? JsonArray) ?: (response["messages"] as? JsonArray))
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
    }

    suspend fun sessions(): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        var offset = 0
        var pages = 0
        do {
            val response =
                http("GET", "/api/sessions?limit=200&offset=$offset&include_children=true")
            result +=
                ((response["data"] as? JsonArray) ?: (response["sessions"] as? JsonArray))
                    ?.mapNotNull { it as? JsonObject }
                    .orEmpty()
            val hasMore = response["has_more"]?.jsonPrimitive?.contentOrNull == "true"
            pages++
            check(!hasMore || pages < 100) { "Hermes session pagination exceeded 100 pages" }
            offset += 200
        } while (hasMore)
        return result.distinctBy { it.string("id") }
    }

    suspend fun createSession(model: String? = null): JsonObject {
        val response =
            http(
                "POST",
                "/api/sessions",
                buildJsonObject { model?.takeIf(String::isNotBlank)?.let { put("model", it) } },
            )
        return response["session"] as? JsonObject ?: error("Hermes returned no session")
    }

    suspend fun submit(sessionId: String, text: String, model: String? = null) {
        check(activeRunId == null && activeCall == null) { "A Hermes turn is already active" }
        activeSessionId = sessionId
        val started =
            try {
                val conversationHistory = buildJsonArray {
                    history(sessionId).forEach { row ->
                        val role = row.string("role")
                        val content = row.string("content")
                        if (role != null && content != null) {
                            add(
                                buildJsonObject {
                                    put("role", role)
                                    put("content", content)
                                }
                            )
                        }
                    }
                }
                http(
                    "POST",
                    "/v1/runs",
                    buildJsonObject {
                        put("input", text)
                        put("session_id", sessionId)
                        model?.takeIf(String::isNotBlank)?.let { put("model", it) }
                        put("conversation_history", conversationHistory)
                    },
                )
            } catch (error: Throwable) {
                activeSessionId = null
                stopRequested = false
                throw error
            }
        val runId =
            started.string("run_id")
                ?: run {
                    activeSessionId = null
                    stopRequested = false
                    error("Hermes returned no run ID")
                }
        activeRunId = runId
        try {
            if (stopRequested) stopRun(runId)
            streamRunEvents(runId)
            val latest = latestSessionId(sessionId)
            if (latest != sessionId) {
                eventChannel.send(
                    GatewayEvent(
                        "session.rotated",
                        sessionId,
                        mapOf("new_session_id" to JsonPrimitive(latest)),
                    )
                )
            }
        } finally {
            clearActiveRun(runId)
        }
    }

    private suspend fun streamRunEvents(runId: String) =
        withContext(Dispatchers.IO) {
            require(config.token.isNotBlank()) { "API key is required" }
            val request =
                Request.Builder()
                    .url(config.normalizedBaseUrl + "/v1/runs/${runId.urlEncode()}/events")
                    .header("Accept", "text/event-stream")
                    .header("Authorization", "Bearer ${config.token}")
                    .get()
                    .build()
            val call = client.newCall(request)
            activeCall = call
            var streamError: String? = null
            var terminalEventReceived = false
            try {
                call.execute().use { response ->
                    val responseBody = response.body
                    check(response.isSuccessful) {
                        "Hermes HTTP ${response.code}: ${responseBody?.string().orEmpty()}"
                    }
                    val source = responseBody?.source() ?: error("Hermes returned no event stream")
                    var eventName: String? = null
                    val dataLines = mutableListOf<String>()

                    suspend fun dispatch() {
                        if (dataLines.isNotEmpty()) {
                            val payload =
                                runCatching {
                                        json
                                            .parseToJsonElement(dataLines.joinToString("\n"))
                                            .jsonObject
                                    }
                                    .getOrElse { error ->
                                        throw IllegalStateException(
                                            "Hermes returned an invalid SSE event",
                                            error,
                                        )
                                    }
                            val name =
                                eventName
                                    ?: payload.string("event")
                                    ?: error("Hermes SSE event has no type")
                            if (name in setOf("run.completed", "run.cancelled", "run.failed")) {
                                terminalEventReceived = true
                            }
                            streamError = streamError ?: translateEvent(name, payload)
                        }
                        eventName = null
                        dataLines.clear()
                    }

                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        when {
                            line.isEmpty() -> dispatch()
                            line.startsWith("event:") -> eventName = line.substringAfter(':').trim()
                            line.startsWith("data:") ->
                                dataLines += line.substringAfter(':').trimStart()
                        }
                    }
                    dispatch()
                }
            } catch (error: java.io.IOException) {
                if (!call.isCanceled()) throw error
            } finally {
                if (activeCall === call) activeCall = null
            }
            streamError?.let { error(it) }
            if (!terminalEventReceived) {
                error("Hermes run event stream ended before a terminal event")
            }
        }

    private suspend fun translateEvent(name: String, payload: JsonObject): String? {
        val sessionId = activeSessionId
        fun event(type: String, values: Map<String, JsonElement>) =
            GatewayEvent(type, sessionId, values)

        val translated =
            when (name) {
                "message.delta" ->
                    event(
                        "message.delta",
                        mapOf(
                            "text" to (payload["delta"] ?: JsonPrimitive("")),
                            "message_id" to (payload["run_id"] ?: JsonPrimitive("api-message")),
                        ),
                    )
                "tool.started",
                "tool.completed",
                "tool.failed" -> {
                    val toolName = payload.string("tool_name") ?: payload.string("tool") ?: "tool"
                    val correlationKey = "${payload.string("run_id").orEmpty()}:$toolName"
                    val toolId =
                        if (name == "tool.started") {
                            "api-tool:${++toolSequence}"
                                .also {
                                    runningToolIds
                                        .getOrPut(correlationKey) { java.util.ArrayDeque() }
                                        .addLast(it)
                                }
                        } else {
                            runningToolIds[correlationKey]?.pollFirst()
                                ?: "api-tool:${++toolSequence}"
                        }
                    if (runningToolIds[correlationKey]?.isEmpty() == true) {
                        runningToolIds.remove(correlationKey)
                    }
                    val failed = name == "tool.failed" || payload.string("error") == "true"
                    event(
                        when {
                            name == "tool.started" -> "tool.start"
                            failed -> "tool.failed"
                            else -> "tool.complete"
                        },
                        buildMap {
                            put("name", JsonPrimitive(toolName))
                            put("tool_call_id", JsonPrimitive(toolId))
                            payload["args"]?.let { put("arguments", it) }
                            payload["preview"]?.let { put("result", it) }
                            payload["duration"]
                                ?.jsonPrimitive
                                ?.contentOrNull
                                ?.toDoubleOrNull()
                                ?.let { put("duration_ms", JsonPrimitive((it * 1_000).toLong())) }
                        },
                    )
                }
                "approval.request" ->
                    event(
                        "approval.request",
                        buildMap {
                            payload["command"]?.let { put("command", it) }
                            payload["description"]?.let { put("description", it) }
                            put(
                                "allow_permanent",
                                JsonPrimitive(
                                    (payload["choices"] as? JsonArray)?.any {
                                        it.jsonPrimitive.contentOrNull == "always"
                                    } == true
                                ),
                            )
                        },
                    )
                "run.completed" -> {
                    runningToolIds.clear()
                    (payload["usage"] as? JsonObject)?.let { usage ->
                        eventChannel.send(event("session.info", mapOf("usage" to usage)))
                    }
                    eventChannel.send(
                        event(
                            "message.complete",
                            mapOf(
                                "text" to (payload["output"] ?: JsonPrimitive("")),
                                "message_id" to (payload["run_id"] ?: JsonPrimitive("api-message")),
                            ),
                        )
                    )
                    event("session.inactive", emptyMap())
                }
                "run.cancelled" -> {
                    runningToolIds.clear()
                    event("session.inactive", emptyMap())
                }
                "run.failed",
                "error" -> {
                    runningToolIds.clear()
                    return payload.string("error")
                        ?: payload.string("message")
                        ?: "Hermes API run failed"
                }
                else -> null
            }
        translated?.let { eventChannel.send(it) }
        return null
    }

    suspend fun interrupt() {
        stopRequested = true
        val runId = activeRunId ?: return
        stopRun(runId)
    }

    suspend fun approve(choice: String) {
        val runId = activeRunId ?: error("No active Hermes run")
        http(
            "POST",
            "/v1/runs/${runId.urlEncode()}/approval",
            buildJsonObject { put("choice", choice) },
        )
    }

    private suspend fun stopRun(runId: String) {
        http("POST", "/v1/runs/${runId.urlEncode()}/stop", JsonObject(emptyMap()))
    }

    private fun clearActiveRun(runId: String) {
        if (activeRunId == runId) activeRunId = null
        activeSessionId = null
        stopRequested = false
        runningToolIds.clear()
    }

    fun reconnectNow() {
        // HTTP requests reconnect independently; there is no persistent socket.
    }

    suspend fun contextBreakdown(runtimeSessionId: String): ContextBreakdown =
        throw UnsupportedOperationException(
            "The Hermes API Server does not expose a context breakdown endpoint"
        )

    suspend fun toolDefinitions(runtimeSessionId: String): ToolDefinitions {
        val response = http("GET", "/v1/toolsets")
        val sections =
            (response["data"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.filter { it["enabled"]?.jsonPrimitive?.contentOrNull == "true" }
                ?.mapNotNull { toolset ->
                    val tools =
                        (toolset["tools"] as? JsonArray)
                            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                            ?.map { ToolSummary(it, "") }
                            .orEmpty()
                    if (tools.isEmpty()) null
                    else
                        ToolSection(
                            toolset.string("label") ?: toolset.string("name").orEmpty(),
                            tools,
                        )
                }
                .orEmpty()
        return ToolDefinitions(sections, sections.sumOf { it.tools.size })
    }

    suspend fun latestSessionId(sessionId: String): String =
        latestSession(sessionId).string("id") ?: sessionId

    suspend fun latestSession(sessionId: String): JsonObject {
        val rows = sessions()
        val descendants = mutableSetOf(sessionId)
        var changed: Boolean
        do {
            changed = false
            rows.forEach { row ->
                val id = row.string("id")
                val parent = row.string("parent_session_id")
                if (id != null && parent in descendants && descendants.add(id)) changed = true
            }
        } while (changed)
        val leaves =
            rows.filter { row ->
                val id = row.string("id")
                id in descendants && rows.none { it.string("parent_session_id") == id }
            }
        return leaves.maxByOrNull {
            it["last_active"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
        } ?: buildJsonObject { put("id", sessionId) }
    }

    suspend fun sessionTokenUsage(storedSessionId: String): CumulativeTokenUsage =
        CumulativeTokenUsage.fromJson(sessionDetail(storedSessionId))

    suspend fun conversationTokenUsage(storedSessionId: String): CumulativeTokenUsage =
        conversationTokenDetails(storedSessionId).usage

    suspend fun conversationTokenDetails(storedSessionId: String): ConversationTokenDetails {
        var detail = sessionDetail(storedSessionId)
        var total = CumulativeTokenUsage.fromJson(detail)
        val visited = mutableSetOf(storedSessionId)
        while (visited.size < 100) {
            val parentId = detail.string("parent_session_id")?.takeIf(String::isNotBlank) ?: break
            if (!visited.add(parentId)) break
            val parent = sessionDetail(parentId)
            if (parent.string("end_reason") != "compression") break
            total += CumulativeTokenUsage.fromJson(parent)
            detail = parent
        }
        // The API intentionally exposes only has_system_prompt, never the prompt text.
        return ConversationTokenDetails(total, null)
    }

    private suspend fun sessionDetail(storedSessionId: String): JsonObject {
        val response = http("GET", "/api/sessions/${storedSessionId.urlEncode()}")
        return response["session"] as? JsonObject ?: error("Hermes returned no session details")
    }

    suspend fun transcribe(bytes: ByteArray, mimeType: String): String =
        dashboardTranscription?.transcribe(bytes, mimeType)
            ?: throw UnsupportedOperationException("Voice transcription is not configured")

    private suspend fun http(method: String, path: String, body: JsonObject? = null): JsonObject =
        withContext(Dispatchers.IO) {
            require(config.token.isNotBlank()) { "API key is required" }
            val request =
                Request.Builder()
                    .url(config.normalizedBaseUrl + path)
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer ${config.token}")
                    .apply {
                        when (method) {
                            "GET" -> get()
                            "POST" ->
                                post(
                                    (body ?: JsonObject(emptyMap()))
                                        .toString()
                                        .toRequestBody(JSON_MEDIA_TYPE)
                                )
                            else -> error("Unsupported HTTP method: $method")
                        }
                    }
                    .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                check(response.isSuccessful) { "Hermes HTTP ${response.code}: $text" }
                if (text.isBlank()) JsonObject(emptyMap())
                else json.parseToJsonElement(text).jsonObject
            }
        }

    override fun close() {
        if (closed) return
        closed = true
        activeCall?.cancel()
        activeCall = null
        eventChannel.close()
        dashboardTranscription?.close()
        client.connectionPool.evictAll()
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

private fun String.urlEncode(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
