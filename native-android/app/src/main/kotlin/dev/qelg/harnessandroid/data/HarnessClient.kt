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
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

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
    @Volatile private var watcherSocket: WebSocket? = null
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
                        ModelSelection(
                            selectedProvider,
                            selectedModel,
                            ThinkingLevel.fromApiValue(selected.string("thinking_level")),
                            selected["reasoning_summary"]?.jsonPrimitive?.booleanOrNull ?: false,
                        )
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
                selection.thinkingLevel?.let { put("thinking_level", it.apiValue) }
                put("reasoning_summary", selection.reasoningSummary)
            },
        )
    }

    suspend fun history(sessionId: String): List<JsonObject> =
        request("GET", "/sessions/${sessionId.urlEncode()}/messages").jsonArray.mapNotNull {
            it as? JsonObject
        }

    suspend fun sessionEvents(sessionId: String): List<SessionEvent> =
        request("GET", "/sessions/${sessionId.urlEncode()}/events").jsonArray.mapNotNull {
            (it as? JsonObject)?.let(SessionEvent::fromJson)
        }

    suspend fun sessions(): List<JsonObject> = sessionsSnapshot().sessions

    suspend fun sessionsSnapshot(): SessionSnapshot =
        withContext(Dispatchers.IO) {
            val request =
                requestBuilder("/sessions").header("Accept", "application/json").get().build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                check(response.isSuccessful) { "Harness HTTP ${response.code}: $text" }
                SessionSnapshot(
                    sessions =
                        if (text.isBlank()) emptyList()
                        else
                            json.parseToJsonElement(text).jsonArray.mapNotNull {
                                it as? JsonObject
                            },
                    cursor = response.header("X-Harness-Event-Cursor")?.toLongOrNull(),
                )
            }
        }

    suspend fun containers(): List<HarnessContainer> =
        request("GET", "/containers").jsonArray.mapNotNull { value ->
            (value as? JsonObject)?.let(HarnessContainer::fromJson)
        }

    suspend fun deleteContainer(containerId: String) {
        request("DELETE", "/containers/${containerId.urlEncode()}")
    }

    suspend fun childSessions(sessionId: String): List<JsonObject> =
        request("GET", "/sessions/${sessionId.urlEncode()}/children").jsonArray.mapNotNull {
            it as? JsonObject
        }

    suspend fun chatGptUsage(): ChatGptUsage =
        ChatGptUsage.fromJson(request("GET", "/chatgpt/usage").jsonObject)

    suspend fun sessionStates(): List<HarnessSessionState> =
        request("GET", "/session-states").jsonArray.mapNotNull {
            (it as? JsonObject)?.let(HarnessSessionState::fromJson)
        }

    suspend fun sessionOverviewUpdates(sinceId: Long): SessionOverviewUpdates {
        val response =
            request("GET", "/sessions/updates?since_id=${sinceId.coerceAtLeast(0)}").jsonObject
        return SessionOverviewUpdates(
            updates =
                (response["updates"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonObject)?.let(SessionOverviewUpdate::fromJson) }
                    .orEmpty(),
            nextSinceId = response["next_since_id"]?.jsonPrimitive?.longOrNull ?: sinceId,
            hasMore = response["has_more"]?.jsonPrimitive?.booleanOrNull == true,
        )
    }

    suspend fun markSessionRead(sessionId: String): HarnessSessionState =
        HarnessSessionState.fromJson(
            request("POST", "/sessions/${sessionId.urlEncode()}/state/read").jsonObject
        )

    suspend fun archiveSession(sessionId: String): HarnessSessionState =
        HarnessSessionState.fromJson(
            request("POST", "/sessions/${sessionId.urlEncode()}/state/archive").jsonObject
        )

    suspend fun createSession(selection: ModelSelection? = null): JsonObject {
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
        selection?.let { selectModel(id, it) }
        return session
    }

    suspend fun submit(
        sessionId: String,
        text: String,
        model: String? = null,
        queueMode: MessageQueueMode? = null,
    ) {
        request(
            "POST",
            "/sessions/${sessionId.urlEncode()}/messages",
            buildJsonObject {
                put("content", text)
                queueMode?.let { put("queue_mode", it.apiValue) }
            },
        )
    }

    suspend fun submitSecret(eventId: Long, identifier: String, secret: String) {
        withContext(Dispatchers.IO) {
            val request =
                requestBuilder("/secrets/$eventId/${identifier.urlEncode()}")
                    .header("Accept", "application/json")
                    .post(secret.toRequestBody(SECRET_MEDIA_TYPE))
                    .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                check(response.isSuccessful) { "Harness HTTP ${response.code}: $text" }
            }
        }
    }

    fun watchEvents(sinceId: Long? = null, eventTypes: Set<String> = setOf("*")) {
        stopWatching()
        watcherJob =
            scope.launch(Dispatchers.IO) {
                var cursor = sinceId
                var hadConnection = false
                while (isActive && !closed) {
                    try {
                        cursor = streamWebSocket(cursor, eventTypes)
                        if (!hadConnection) {
                            eventChannel.send(GatewayEvent("connection.restored", null, emptyMap()))
                        }
                        hadConnection = true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        if (isActive && !closed) {
                            eventChannel.send(
                                GatewayEvent(
                                    "connection.lost",
                                    null,
                                    mapOf("message" to JsonPrimitive(error.message.orEmpty())),
                                )
                            )
                            delay(RECONNECT_DELAY_MS)
                        }
                    }
                }
            }
    }

    fun subscribeEventTypes(eventTypes: Set<String>, sinceId: Long? = null) {
        watcherSocket?.send(
            buildJsonObject {
                    put("type", "subscribe")
                    put("event_types", JsonArray(eventTypes.map(::JsonPrimitive)))
                    sinceId?.let { put("since_id", it) }
                }
                .toString()
        )
    }

    fun unsubscribeEventTypes(eventTypes: Set<String>) {
        watcherSocket?.send(
            buildJsonObject {
                    put("type", "unsubscribe")
                    put("event_types", JsonArray(eventTypes.map(::JsonPrimitive)))
                }
                .toString()
        )
    }

    /** Legacy per-session SSE watcher, retained while older servers are deployed. */
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
        watcherSocket?.cancel()
        watcherSocket = null
        watcherJob?.cancel()
        watcherJob = null
    }

    private suspend fun streamWebSocket(sinceId: Long?, eventTypes: Set<String>): Long? =
        withContext(Dispatchers.IO) {
            val frames = Channel<String>(Channel.UNLIMITED)
            var failure: Throwable? = null
            val request =
                Request.Builder()
                    .url(config.normalizedBaseUrl.websocketUrl("/events"))
                    .header("Authorization", "Bearer ${config.token}")
                    .build()
            val socket =
                client.newWebSocket(
                    request,
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            watcherSocket = webSocket
                            webSocket.send(
                                buildJsonObject {
                                        put("type", "subscribe")
                                        put(
                                            "event_types",
                                            JsonArray(eventTypes.map(::JsonPrimitive)),
                                        )
                                        sinceId?.let { put("since_id", it) }
                                    }
                                    .toString()
                            )
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            frames.trySend(text)
                        }

                        override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: Response?,
                        ) {
                            failure = t
                            frames.close()
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            frames.close()
                        }
                    },
                )
            watcherSocket = socket
            var cursor = sinceId
            try {
                for (text in frames) {
                    val frame = json.parseToJsonElement(text).jsonObject
                    if (frame.string("type") != "event") continue
                    val event = frame["event"] as? JsonObject ?: continue
                    val eventCursor = frame["cursor"]?.jsonPrimitive?.longOrNull
                    eventCursor?.let { cursor = maxOf(cursor ?: 0L, it) }
                    translateEvent(
                        event.string("name").orEmpty(),
                        event,
                        event.sessionId().orEmpty(),
                        eventCursor,
                        frame["message"] as? JsonObject,
                    )
                }
                failure?.let { throw it }
                cursor
            } finally {
                if (watcherSocket === socket) watcherSocket = null
            }
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
                            val eventRecord = event["event"] as? JsonObject ?: event
                            translateEvent(eventName.orEmpty(), eventRecord, sessionId, eventId)
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
        eventRecord: JsonObject,
        sessionId: String,
        cursor: Long? = null,
        message: JsonObject? = null,
    ) {
        val payload = eventRecord["payload"] as? JsonObject ?: eventRecord
        val messagePayload = message ?: payload
        val eventSessionId =
            eventRecord.sessionId()?.takeIf(String::isNotBlank)
                ?: sessionId.takeIf(String::isNotBlank)
        fun values(vararg entries: Pair<String, JsonElement>): Map<String, JsonElement> = buildMap {
            entries.forEach { (key, value) -> put(key, value) }
            eventRecord["created_at_ms"]?.let { put("created_at_ms", it) }
        }
        suspend fun emit(type: String, payload: Map<String, JsonElement>) {
            eventChannel.send(GatewayEvent(type, eventSessionId, payload, eventRecord, cursor))
        }
        when (name) {
            "llm.delta" ->
                emit("message.delta", values("text" to (payload["delta"] ?: JsonPrimitive(""))))
            "secret.ask" ->
                emit(
                    "secret.ask",
                    values(
                        "event_id" to (eventRecord["id"] ?: JsonPrimitive("")),
                        "identifier" to (payload["identifier"] ?: JsonPrimitive("")),
                        "description" to (payload["description"] ?: JsonPrimitive("")),
                        "container" to (payload["container"] ?: JsonPrimitive("")),
                    ),
                )
            "tool.call.requested" ->
                emit(
                    "tool.start",
                    values(
                        "name" to (payload["tool"] ?: JsonPrimitive("tool")),
                        "tool_call_id" to (payload["run_id"] ?: JsonPrimitive("tool")),
                        "arguments" to (payload["input"] ?: JsonObject(emptyMap())),
                    ),
                )
            "chat.message.tool.created" ->
                emit(
                    "tool.complete",
                    values(
                        "name" to (messagePayload["tool"] ?: JsonPrimitive("tool")),
                        "tool_call_id" to (messagePayload["run_id"] ?: JsonPrimitive("tool")),
                        "result" to (messagePayload["content"] ?: JsonPrimitive("")),
                    ),
                )
            "chat.message.assistant.created" -> {
                val content = messagePayload["content"]
                if (content.hasFunctionCall()) {
                    if (content.reasoningContent() != null)
                        emit(
                            "message.reasoning",
                            values(
                                "message_id" to (eventRecord["id"] ?: JsonPrimitive("assistant"))
                            ),
                        )
                } else {
                    emit(
                        "message.complete",
                        values(
                            "text" to JsonPrimitive(content.assistantText()),
                            "message_id" to (eventRecord["id"] ?: JsonPrimitive("assistant")),
                        ),
                    )
                }
            }
            "session.state" -> {
                val tags = eventRecord["tags"] as? JsonObject ?: JsonObject(emptyMap())
                val statePayload =
                    buildMap<String, JsonElement> {
                        put("session_id", JsonPrimitive(eventSessionId.orEmpty()))
                        listOf("state", "read", "archive").forEach { key ->
                            tags[key]?.let { put(key, it) }
                        }
                        listOf(
                                "source_event_id",
                                "outcome",
                                "tasks",
                                "total",
                                "finished",
                                "in_progress",
                            )
                            .forEach { key -> payload[key]?.let { put(key, it) } }
                        eventRecord["id"]?.let { put("event_id", it) }
                        eventRecord["created_at_ms"]?.let { put("created_at_ms", it) }
                    }
                emit("session.state", statePayload)
            }
            "chat.message.user.created" ->
                emit(
                    "message.user",
                    values(
                        "text" to (messagePayload["content"] ?: JsonPrimitive("")),
                        "message_id" to
                            (messagePayload["id"] ?: eventRecord["id"] ?: JsonPrimitive("user")),
                    ),
                )
            "llm.run.failed" -> {
                emit(
                    "error",
                    values(
                        "message" to
                            JsonPrimitive(payload.string("error") ?: "Harness LLM run failed")
                    ),
                )
                emit("session.inactive", emptyMap())
            }
            "session.created" -> emit("session.created", payload)
            "session.renamed" -> emit("session.renamed", payload)
            else -> emit("raw.event", payload)
        }
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
                "DELETE" -> builder.delete()
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
        val SECRET_MEDIA_TYPE = "application/octet-stream".toMediaType()
        val MODEL_OPTIONS =
            mapOf(
                "chatgpt-codex" to listOf("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna"),
                "openrouter" to
                    listOf(
                        "openai/gpt-oss-120b",
                        "deepseek/deepseek-v4-flash-0731",
                        "qwen/qwen3.6-35b-a3b",
                        "moonshotai/kimi-k3",
                        "xiaomi/mimo-v2.5",
                        "z-ai/glm-5.2",
                        "minimax/minimax-m3",
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

private fun JsonObject.sessionId(): String? =
    string("session_id") ?: (this["tags"] as? JsonObject)?.string("session")

private fun String.websocketUrl(path: String): String =
    when {
        startsWith("https://") -> "wss://" + substring("https://".length) + path
        startsWith("http://") -> "ws://" + substring("http://".length) + path
        else -> this + path
    }

private fun String.urlEncode() = java.net.URLEncoder.encode(this, Charsets.UTF_8.name())

data class ReasoningContent(val text: String, val isSummary: Boolean)

internal fun JsonElement?.reasoningContent(): ReasoningContent? =
    when (this) {
        null,
        JsonNull,
        is JsonPrimitive -> null
        is JsonArray -> {
            val parts = mapNotNull { it.reasoningContent() }
            parts
                .takeIf { it.isNotEmpty() }
                ?.let {
                    ReasoningContent(
                        text = it.joinToString("\n") { part -> part.text },
                        isSummary = it.all(ReasoningContent::isSummary),
                    )
                }
        }
        is JsonObject -> {
            when (string("type")) {
                "summary_text" ->
                    string("text")?.takeIf(String::isNotBlank)?.let { ReasoningContent(it, true) }
                "reasoning" ->
                    this["summary"].reasoningContent()
                        ?: listOf("reasoning", "reasoning_content")
                            .firstNotNullOfOrNull { key -> string(key)?.takeIf(String::isNotBlank) }
                            ?.let { ReasoningContent(it, false) }
                else -> {
                    listOf("reasoning", "reasoning_content")
                        .firstNotNullOfOrNull { key -> string(key)?.takeIf(String::isNotBlank) }
                        ?.let { ReasoningContent(it, false) }
                        ?: this["reasoning_details"].reasoningDetailsContent()
                        ?: this["output"].reasoningContent()
                }
            }
        }
    }

private fun JsonElement?.reasoningDetailsContent(): ReasoningContent? {
    val text =
        (this as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.string("text") }
            ?.filter(String::isNotBlank)
            ?.joinToString("")
            .orEmpty()
    return text.takeIf(String::isNotBlank)?.let { ReasoningContent(it, false) }
}
