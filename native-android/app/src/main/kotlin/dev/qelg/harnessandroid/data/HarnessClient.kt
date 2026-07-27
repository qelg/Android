package dev.qelg.harnessandroid.data

import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
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
    @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
    private val client: OkHttpClient = OkHttpClient(),
) : Closeable {
    private val json = Json { ignoreUnknownKeys = true }
    private val eventChannel = Channel<GatewayEvent>(Channel.UNLIMITED)
    val events: Flow<GatewayEvent> = eventChannel.receiveAsFlow()
    @Volatile private var closed = false
    @Volatile private var activeCall: Call? = null

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
                        val model =
                            if (provider == selectedProvider && !selectedModel.isNullOrBlank())
                                selectedModel
                            else DEFAULT_MODELS[provider] ?: provider
                        ModelProvider(provider, provider, listOf(ModelOption(model)))
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
                DEFAULT_MODELS.entries.firstOrNull { entry -> entry.value == it }?.key ?: "mock-llm"
            selectModel(id, ModelSelection(provider, it))
        }
        return session
    }

    suspend fun submit(sessionId: String, text: String, model: String? = null) {
        check(activeCall == null) { "A Harness turn is already active" }
        try {
            stream(sessionId, text)
        } finally {
            eventChannel.send(GatewayEvent("session.inactive", sessionId, emptyMap()))
        }
    }

    private suspend fun stream(sessionId: String, text: String) =
        withContext(Dispatchers.IO) {
            val request =
                requestBuilder("/sessions/${sessionId.urlEncode()}/messages/stream")
                    .header("Accept", "text/event-stream")
                    .post(
                        buildJsonObject { put("content", text) }
                            .toString()
                            .toRequestBody(JSON_MEDIA_TYPE)
                    )
                    .build()
            val call = client.newCall(request)
            activeCall = call
            var failure: String? = null
            try {
                call.execute().use { response ->
                    val body = response.body
                    check(response.isSuccessful) {
                        "Harness HTTP ${response.code}: ${body?.string().orEmpty()}"
                    }
                    val source = body?.source() ?: error("Harness returned no event stream")
                    var eventName: String? = null
                    val data = mutableListOf<String>()
                    suspend fun dispatch() {
                        if (data.isNotEmpty())
                            failure =
                                failure
                                    ?: translateEvent(
                                        eventName.orEmpty(),
                                        json.parseToJsonElement(data.joinToString("\n")).jsonObject,
                                        sessionId,
                                    )
                        eventName = null
                        data.clear()
                    }
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        when {
                            line.isEmpty() -> dispatch()
                            line.startsWith("event:") -> eventName = line.substringAfter(':').trim()
                            line.startsWith("data:") -> data += line.substringAfter(':').trimStart()
                        }
                    }
                    dispatch()
                }
            } catch (error: java.io.IOException) {
                if (!call.isCanceled()) throw error
            } finally {
                if (activeCall === call) activeCall = null
            }
            failure?.let { error(it) }
        }

    private suspend fun translateEvent(
        name: String,
        event: JsonObject,
        sessionId: String,
    ): String? {
        val payload = event["payload"] as? JsonObject ?: event
        when (name.ifBlank { event.string("name").orEmpty() }) {
            "llm.delta" ->
                eventChannel.send(
                    GatewayEvent(
                        "message.delta",
                        sessionId,
                        mapOf("text" to (payload["delta"] ?: JsonPrimitive(""))),
                    )
                )
            "tool.call.requested" ->
                eventChannel.send(
                    GatewayEvent(
                        "tool.start",
                        sessionId,
                        mapOf(
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
                        mapOf(
                            "name" to (payload["tool"] ?: JsonPrimitive("tool")),
                            "tool_call_id" to (payload["run_id"] ?: JsonPrimitive("tool")),
                            "result" to (payload["content"] ?: JsonPrimitive("")),
                        ),
                    )
                )
            "chat.message.assistant.created" ->
                eventChannel.send(
                    GatewayEvent(
                        "message.complete",
                        sessionId,
                        mapOf(
                            "text" to (payload["content"] ?: JsonPrimitive("")),
                            "message_id" to (event["id"] ?: JsonPrimitive("assistant")),
                        ),
                    )
                )
            "llm.run.failed" -> return payload.string("error") ?: "Harness LLM run failed"
        }
        return null
    }

    suspend fun interrupt() {
        activeCall?.cancel()
    }

    suspend fun approve(choice: String): Unit =
        throw UnsupportedOperationException("Harness does not expose approval responses")

    fun reconnectNow() = Unit

    suspend fun contextBreakdown(runtimeSessionId: String): ContextBreakdown =
        throw UnsupportedOperationException("Harness does not expose context breakdowns")

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

    private fun zeroUsage() = CumulativeTokenUsage.fromJson(JsonObject(emptyMap()))

    suspend fun sessionTokenUsage(storedSessionId: String) = zeroUsage()

    suspend fun conversationTokenUsage(storedSessionId: String) = zeroUsage()

    suspend fun conversationTokenDetails(storedSessionId: String) =
        ConversationTokenDetails(zeroUsage(), null)

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
            activeCall?.cancel()
            eventChannel.close()
            client.connectionPool.evictAll()
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val DEFAULT_MODELS =
            mapOf(
                "mock-llm" to "test-model",
                "openai-codex" to "gpt-5.3-codex",
                "chatgpt-codex" to "gpt-5.3-codex",
                "openrouter" to "openai/gpt-4o-mini",
            )
    }
}

private fun String.urlEncode() = java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
