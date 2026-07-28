package dev.qelg.harnessandroid.data

import java.io.Closeable
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** HTTP/SSE client for the public API exposed by qelg/harness. */
class HarnessClient(
    private val config: ConnectionConfig,
    private val scope: CoroutineScope,
    private val client: OkHttpClient =
        OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build(),
) : Closeable {
    private val json = Json { ignoreUnknownKeys = true }
    private val eventChannel = Channel<GatewayEvent>(Channel.UNLIMITED)
    val events: Flow<GatewayEvent> = eventChannel.receiveAsFlow()
    @Volatile private var closed = false
    @Volatile private var watcherCall: Call? = null
    private var watcherJob: Job? = null

    suspend fun connect() {
        check(!closed) { "Harness client is closed" }
        check(request("GET", "/health").jsonObject.string("status") == "ok") {
            "Endpoint is not a qelg/harness server"
        }
    }

    suspend fun modelOptions(sessionId: String? = null): ModelCatalog {
        val providers =
            request("GET", "/providers")
                .jsonObject["providers"]
                ?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty()
        val selected =
            sessionId?.let {
                request("GET", "/sessions/${it.urlEncode()}/model-selection").jsonObject
            }
        val selectedProvider = selected?.string("provider")
        val selectedModel = selected?.string("model")
        val catalog =
            ModelCatalog(
                selected =
                    if (!selectedProvider.isNullOrBlank() && !selectedModel.isNullOrBlank())
                        ModelSelection(selectedProvider, selectedModel)
                    else null,
                providers =
                    providers.map { provider ->
                        val models =
                            buildList {
                                    addAll(MODEL_OPTIONS[provider].orEmpty())
                                    if (
                                        provider == selectedProvider &&
                                            !selectedModel.isNullOrBlank()
                                    )
                                        add(selectedModel)
                                    if (isEmpty()) add(provider)
                                }
                                .distinct()
                                .map(::ModelOption)
                        ModelProvider(provider, provider, models)
                    },
            )
        return if (catalog.selected != null) catalog else catalog.selectedFor(null)
    }

    suspend fun selectModel(sessionId: String, selection: ModelSelection) {
        request(
            "POST",
            "/model-selection",
            buildJsonObject {
                put("provider", selection.provider)
                put("model", selection.model)
                put("session_id", sessionId)
            },
        )
    }

    suspend fun history(sessionId: String): List<JsonObject> =
        request("GET", "/sessions/${sessionId.urlEncode()}/messages").jsonArray.mapNotNull {
            it as? JsonObject
        }

    suspend fun sessions(): List<JsonObject> =
        request("GET", "/sessions").jsonArray.mapNotNull { it as? JsonObject }

    suspend fun createSession(model: String? = null): JsonObject {
        val session =
            request(
                    "POST",
                    "/sessions",
                    buildJsonObject {
                        put("title", JsonNull)
                        put("tags", JsonArray(emptyList()))
                    },
                )
                .jsonObject
        val id = session.string("id") ?: error("Harness returned no session ID")
        model?.takeIf(String::isNotBlank)?.let {
            val provider =
                MODEL_OPTIONS.entries.firstOrNull { entry -> it in entry.value }?.key ?: "mock-llm"
            selectModel(id, ModelSelection(provider, it))
        }
        return session
    }

    suspend fun submit(sessionId: String, text: String, model: String? = null) {
        request(
            "POST",
            "/sessions/${sessionId.urlEncode()}/messages",
            buildJsonObject { put("content", text) },
        )
    }

    fun watchSession(sessionId: String, sinceId: Long? = null) {
        stopWatching()
        watcherJob =
            scope.launch(Dispatchers.IO) {
                var cursor = sinceId
                while (isActive && !closed) {
                    try {
                        cursor = streamUpdates(sessionId, cursor)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: java.io.IOException) {
                        if (isActive && !closed) delay(RECONNECT_DELAY_MS)
                    } catch (error: Throwable) {
                        if (isActive && !closed) {
                            eventChannel.send(
                                GatewayEvent(
                                    "error",
                                    sessionId,
                                    mapOf(
                                        "message" to
                                            JsonPrimitive(
                                                error.message ?: "Session update stream failed"
                                            )
                                    ),
                                )
                            )
                            delay(RECONNECT_DELAY_MS)
                        }
                    }
                }
            }
    }

    fun stopWatching() {
        watcherCall?.cancel()
        watcherCall = null
        watcherJob?.cancel()
        watcherJob = null
    }

    private suspend fun streamUpdates(sessionId: String, sinceId: Long?): Long? =
        withContext(Dispatchers.IO) {
            val suffix = sinceId?.let { "?since_id=$it" }.orEmpty()
            val request =
                requestBuilder("/sessions/${sessionId.urlEncode()}/messages/updates$suffix")
                    .header("Accept", "text/event-stream")
                    .get()
                    .build()
            val call = client.newCall(request)
            watcherCall = call
            var cursor = sinceId
            try {
                call.execute().use { response ->
                    val body = response.body
                    check(response.isSuccessful) {
                        "Harness HTTP ${response.code}: ${body?.string().orEmpty()}"
                    }
                    val source = body?.source() ?: error("Harness returned no update stream")
                    var eventName: String? = null
                    var eventId: Long? = null
                    val data = mutableListOf<String>()
                    suspend fun dispatch() {
                        if (data.isNotEmpty()) {
                            val event = json.parseToJsonElement(data.joinToString("\n")).jsonObject
                            translateEvent(eventName.orEmpty(), event, sessionId)
                            eventId?.let { cursor = maxOf(cursor ?: 0L, it) }
                        }
                        eventName = null
                        eventId = null
                        data.clear()
                    }
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        when {
                            line.isEmpty() -> dispatch()
                            line.startsWith("id:") ->
                                eventId = line.substringAfter(':').trim().toLongOrNull()
                            line.startsWith("event:") -> eventName = line.substringAfter(':').trim()
                            line.startsWith("data:") -> data += line.substringAfter(':').trimStart()
                        }
                    }
                    dispatch()
                }
            } finally {
                if (watcherCall === call) watcherCall = null
            }
            cursor
        }

    private suspend fun translateEvent(
        name: String,
        event: JsonObject,
        sessionId: String,
    ): String? {
        val eventRecord = event["event"] as? JsonObject ?: event
        val payload = eventRecord["payload"] as? JsonObject ?: eventRecord
        val eventName = name.ifBlank { eventRecord.string("name").orEmpty() }
        val timestamp = eventRecord["created_at_ms"]
        fun values(vararg entries: Pair<String, JsonElement>): Map<String, JsonElement> = buildMap {
            entries.forEach { (key, value) -> put(key, value) }
            timestamp?.let { put("created_at_ms", it) }
        }
        when (eventName) {
            "llm.delta" ->
                eventChannel.send(
                    GatewayEvent(
                        "message.delta",
                        sessionId,
                        values("text" to (payload["delta"] ?: JsonPrimitive(""))),
                    )
                )
            "tool.call.requested" ->
                eventChannel.send(
                    GatewayEvent(
                        "tool.start",
                        sessionId,
                        values(
                            "name" to (payload["tool"] ?: JsonPrimitive("tool")),
                            "tool_call_id" to (payload["run_id"] ?: JsonPrimitive("tool")),
                            "arguments" to (payload["input"] ?: JsonObject(emptyMap())),
                        ),
                    )
                )
            "chat.message.tool.created" ->
                eventChannel.send(
                    GatewayEvent(
                        "tool.complete",
                        sessionId,
                        values(
                            "name" to (payload["tool"] ?: JsonPrimitive("tool")),
                            "tool_call_id" to (payload["run_id"] ?: JsonPrimitive("tool")),
                            "result" to (payload["content"] ?: JsonPrimitive("")),
                        ),
                    )
                )
            "chat.message.assistant.created" -> {
                val content = payload["content"]
                if (!content.hasFunctionCall()) {
                    eventChannel.send(
                        GatewayEvent(
                            "message.complete",
                            sessionId,
                            values(
                                "text" to JsonPrimitive(content.assistantText()),
                                "message_id" to (eventRecord["id"] ?: JsonPrimitive("assistant")),
                            ),
                        )
                    )
                }
            }
            "chat.message.user.created" ->
                eventChannel.send(
                    GatewayEvent(
                        "message.user",
                        sessionId,
                        values(
                            "text" to (payload["content"] ?: JsonPrimitive("")),
                            "message_id" to (eventRecord["id"] ?: JsonPrimitive("user")),
                        ),
                    )
                )
            "llm.run.failed" -> {
                eventChannel.send(
                    GatewayEvent(
                        "error",
                        sessionId,
                        values(
                            "message" to
                                JsonPrimitive(payload.string("error") ?: "Harness LLM run failed")
                        ),
                    )
                )
                eventChannel.send(GatewayEvent("session.inactive", sessionId, emptyMap()))
            }
        }
        return null
    }

    suspend fun interrupt() = Unit

    suspend fun approve(choice: String): Unit =
        throw UnsupportedOperationException("Harness does not expose approval responses")

    fun reconnectNow() = Unit

    suspend fun usageSnapshot(sessionId: String): HarnessUsageSnapshot =
        harnessUsageSnapshot(history(sessionId))

    suspend fun contextBreakdown(runtimeSessionId: String): ContextBreakdown =
        usageSnapshot(runtimeSessionId).context
            ?: throw UnsupportedOperationException("No provider usage has been recorded yet")

    suspend fun toolDefinitions(runtimeSessionId: String): ToolDefinitions {
        val tools =
            request("GET", "/tools")
                .jsonObject["tools"]
                ?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.map { ToolSummary(it, "") }
                .orEmpty()
        return ToolDefinitions(
            if (tools.isEmpty()) emptyList() else listOf(ToolSection("Harness tools", tools)),
            tools.size,
        )
    }

    suspend fun latestSessionId(sessionId: String) = sessionId

    suspend fun latestSession(sessionId: String) = buildJsonObject { put("id", sessionId) }

    suspend fun sessionTokenUsage(storedSessionId: String): CumulativeTokenUsage =
        usageSnapshot(storedSessionId).cumulative
            ?: CumulativeTokenUsage.fromJson(JsonObject(emptyMap()))

    suspend fun conversationTokenUsage(storedSessionId: String): CumulativeTokenUsage =
        sessionTokenUsage(storedSessionId)

    suspend fun conversationTokenDetails(storedSessionId: String): ConversationTokenDetails =
        ConversationTokenDetails(sessionTokenUsage(storedSessionId), null)

    suspend fun transcribe(bytes: ByteArray, mimeType: String): String =
        throw UnsupportedOperationException("Harness does not expose voice transcription")

    private suspend fun request(
        method: String,
        path: String,
        body: JsonObject? = null,
    ): JsonElement =
        withContext(Dispatchers.IO) {
            val builder = requestBuilder(path).header("Accept", "application/json")
            when (method) {
                "GET" -> builder.get()
                "POST" ->
                    builder.post(
                        (body ?: JsonObject(emptyMap())).toString().toRequestBody(JSON_MEDIA_TYPE)
                    )
                else -> error("Unsupported method")
            }
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                check(response.isSuccessful) { "Harness HTTP ${response.code}: $text" }
                if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text)
            }
        }

    private fun requestBuilder(path: String) =
        Request.Builder().url(config.normalizedBaseUrl + path).apply {
            config.token.takeIf(String::isNotBlank)?.let { header("Authorization", "Bearer $it") }
        }

    override fun close() {
        if (!closed) {
            closed = true
            stopWatching()
            eventChannel.close()
            client.connectionPool.evictAll()
        }
    }

    private companion object {
        const val RECONNECT_DELAY_MS = 1_000L
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val MODEL_OPTIONS =
            mapOf(
                "mock-llm" to listOf("test-model"),
                "openai-codex" to listOf("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna"),
                "chatgpt-codex" to listOf("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna"),
                "openrouter" to
                    listOf(
                        "openai/gpt-4o-mini",
                        "deepseek/deepseek-v4-flash",
                        "qwen/qwen3.6-35b-a3b",
                        "moonshotai/kimi-k3",
                    ),
            )
    }
}

internal fun JsonElement?.assistantText(): String =
    when (this) {
        null,
        JsonNull -> ""
        is JsonPrimitive -> contentOrNull.orEmpty()
        is JsonArray -> map { it.assistantText() }.filter(String::isNotBlank).joinToString("\n")
        is JsonObject -> {
            when (string("type")) {
                "function_call" -> ""
                "message" -> this["content"].assistantText()
                "output_text",
                "text" -> this["text"].assistantText()
                else ->
                    listOf("text", "content", "output")
                        .firstNotNullOfOrNull { key ->
                            this[key]?.assistantText()?.takeIf(String::isNotBlank)
                        }
                        .orEmpty()
            }
        }
    }

internal fun JsonElement?.hasFunctionCall(): Boolean =
    when (this) {
        is JsonArray -> any { it.hasFunctionCall() }
        is JsonObject ->
            string("type") == "function_call" ||
                (this["tool_calls"] as? JsonArray)?.isNotEmpty() == true ||
                this["content"].hasFunctionCall() ||
                this["output"].hasFunctionCall()
        else -> false
    }

private fun String.urlEncode() = java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
