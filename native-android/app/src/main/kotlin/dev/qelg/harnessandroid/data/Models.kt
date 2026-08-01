package dev.qelg.harnessandroid.data

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import kotlinx.serialization.json.*

@JvmInline value class SessionId(val value: String)

data class HarnessSession(
    val id: String,
    val title: String,
    val updatedAt: String? = null,
    val source: String? = null,
    val preview: String? = null,
    val active: Boolean = false,
    val runtimeId: String? = null,
    val parentSessionId: String? = null,
    val endReason: String? = null,
    val model: String? = null,
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val cacheReadTokens: Long = 0L,
    val cacheWriteTokens: Long = 0L,
    val reasoningTokens: Long = 0L,
    val estimatedCostUsd: Double? = null,
    val actualCostUsd: Double? = null,
    val apiCallCount: Int = 0,
    val cumulativeTokenUsage: CumulativeTokenUsage? = null,
    val sessionState: HarnessSessionState? = null,
) {
    companion object {
        fun fromJson(value: JsonObject): HarnessSession {
            val id = value.string("id") ?: value.string("session_id") ?: ""
            return HarnessSession(
                id = id,
                title = value.string("title")?.takeIf(String::isNotBlank) ?: "Untitled session",
                updatedAt =
                    value.string("updated_at")
                        ?: value.string("last_active")
                        ?: value.string("last_active_at")
                        ?: value.string("started_at")
                        ?: value.string("created_at")
                        ?: value["created_at_ms"]?.jsonPrimitive?.longOrNull?.let {
                            java.time.Instant.ofEpochMilli(it).toString()
                        },
                source = value.string("source"),
                preview = value.string("preview"),
                active =
                    value["active"]?.jsonPrimitive?.booleanOrNull == true ||
                        value.string("status") in setOf("active", "running", "streaming"),
                runtimeId =
                    value.string("runtime_session_id")
                        ?: value.string("runtime_id")
                        ?: value.string("session_id")?.takeIf {
                            value.string("id") != null && it != id
                        },
                parentSessionId = value.string("parent_session_id"),
                endReason = value.string("end_reason"),
                model = value.string("model"),
                inputTokens = value["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
                outputTokens = value["output_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
                cacheReadTokens = value["cache_read_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
                cacheWriteTokens = value["cache_write_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
                reasoningTokens = value["reasoning_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
                estimatedCostUsd = value["estimated_cost_usd"]?.jsonPrimitive?.doubleOrNull,
                actualCostUsd = value["actual_cost_usd"]?.jsonPrimitive?.doubleOrNull,
                apiCallCount = value["api_call_count"]?.jsonPrimitive?.intOrNull ?: 0,
                cumulativeTokenUsage = CumulativeTokenUsage.fromJsonOrNull(value),
            )
        }
    }
}

fun modelCatalogForSession(catalog: ModelCatalog, session: HarnessSession): ModelCatalog =
    catalog.selectedFor(session.model)

fun sessionModelForLineage(
    storedSessionId: String,
    runtimeSessionId: String?,
    sessions: List<HarnessSession>,
    overrides: Map<String, String>,
): String? =
    overrides[storedSessionId]
        ?: runtimeSessionId?.let { runtime -> sessions.firstOrNull { it.id == runtime }?.model }
        ?: sessions.firstOrNull { it.id == storedSessionId }?.model

fun applySessionModelOverrides(
    sessions: List<HarnessSession>,
    overrides: Map<String, String>,
): List<HarnessSession> =
    sessions.map { session -> overrides[session.id]?.let { session.copy(model = it) } ?: session }

fun sessionsWithModelSelection(
    sessions: List<HarnessSession>,
    selectedId: String,
    selection: ModelSelection,
): List<HarnessSession> =
    sessions.map { session ->
        if (session.id == selectedId) session.copy(model = selection.model) else session
    }

fun filterSessions(sessions: List<HarnessSession>, query: String): List<HarnessSession> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return sessions
    return sessions.filter {
        it.title.lowercase().contains(needle) ||
            it.preview?.lowercase()?.contains(needle) == true ||
            it.source?.lowercase()?.contains(needle) == true ||
            it.id.lowercase().contains(needle)
    }
}

fun sortSessionsForOverview(sessions: List<HarnessSession>): List<HarnessSession> =
    sessions.sortedWith(
        compareByDescending<HarnessSession> { it.active }
            .thenByDescending { it.updatedAt?.let(::parseSessionUpdateInstant) }
    )

private val earliestSessionUpdate = java.time.Instant.parse("2000-01-01T00:00:00Z")
private val latestSessionUpdateExclusive = java.time.Instant.parse("2100-01-01T00:00:00Z")

private fun parseSessionUpdateInstant(value: String): java.time.Instant? {
    val parsed = runCatching { java.time.Instant.parse(value) }.getOrNull()
    if (parsed != null)
        return parsed.takeIf {
            !it.isBefore(earliestSessionUpdate) && it.isBefore(latestSessionUpdateExclusive)
        }
    val epochSeconds =
        value.toDoubleOrNull()?.takeIf {
            it.isFinite() &&
                it >= earliestSessionUpdate.epochSecond &&
                it < latestSessionUpdateExclusive.epochSecond
        } ?: return null
    return java.time.Instant.ofEpochMilli((epochSeconds * 1000).toLong())
}

fun isSessionUpdateRead(session: HarnessSession, readAt: String?): Boolean {
    val updated = session.updatedAt?.let(::parseSessionUpdateInstant) ?: return false
    val read = readAt?.let(::parseSessionUpdateInstant) ?: return false
    return read >= updated
}

fun canMarkSessionRead(historyLoadedFor: String?, selectedId: String?, sessionId: String): Boolean =
    historyLoadedFor == sessionId && selectedId == sessionId

fun confirmedReadAt(
    session: HarnessSession,
    now: java.time.Instant = java.time.Instant.now(),
): String {
    val updated = session.updatedAt?.let(::parseSessionUpdateInstant)
    return maxOf(now, updated ?: now).toString()
}

fun formatSessionUpdate(
    value: String,
    zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    locale: java.util.Locale = java.util.Locale.getDefault(),
): String? =
    parseSessionUpdateInstant(value)?.let {
        java.time.format.DateTimeFormatter.ofLocalizedDateTime(
                java.time.format.FormatStyle.MEDIUM,
                java.time.format.FormatStyle.SHORT,
            )
            .withLocale(locale)
            .withZone(zoneId)
            .format(it)
    }

fun prioritizeSessionsWithDrafts(
    sessions: List<HarnessSession>,
    drafts: Map<String, String>,
): List<HarnessSession> {
    val sorted = sortSessionsForOverview(sessions)
    val (live, inactive) = sorted.partition(HarnessSession::active)
    val (liveWithDraft, liveWithoutDraft) = live.partition { drafts[it.id]?.isNotBlank() == true }
    val (inactiveWithDraft, inactiveWithoutDraft) =
        inactive.partition { drafts[it.id]?.isNotBlank() == true }
    return liveWithDraft + liveWithoutDraft + inactiveWithDraft + inactiveWithoutDraft
}

fun updateDrafts(
    drafts: Map<String, String>,
    sessionId: String,
    text: String,
): Map<String, String> =
    drafts.toMutableMap().apply {
        if (text.isBlank()) remove(sessionId) else this[sessionId] = text
    }

data class DraftSubmission(
    val namespace: String,
    val connectionVersion: Long,
    val sessionId: String,
    val revision: Long,
    val text: String,
)

fun canClearDraft(
    submitted: DraftSubmission,
    currentNamespace: String,
    currentConnectionVersion: Long,
    currentRevision: Long,
    currentText: String?,
): Boolean =
    submitted.namespace == currentNamespace &&
        submitted.connectionVersion == currentConnectionVersion &&
        submitted.revision == currentRevision &&
        submitted.text == currentText

enum class ThinkingLevel(val apiValue: String, val displayName: String) {
    None("none", "None"),
    Low("low", "Low"),
    Medium("medium", "Medium"),
    High("high", "High");

    companion object {
        fun fromApiValue(value: String?): ThinkingLevel? =
            entries.firstOrNull { it.apiValue == value }
    }
}

data class ModelSelection(
    val provider: String,
    val model: String,
    val thinkingLevel: ThinkingLevel? = null,
    val reasoningSummary: Boolean = false,
)

fun modelSelectionFromSessionInfo(
    provider: String?,
    model: String?,
    current: ModelSelection?,
): ModelSelection? =
    if (!provider.isNullOrBlank() && !model.isNullOrBlank())
        ModelSelection(
            provider,
            model,
            thinkingLevel = current?.thinkingLevel,
            reasoningSummary = current?.reasoningSummary ?: false,
        )
    else current

data class ModelOption(
    val id: String,
    val unavailable: Boolean = false,
    val resolvedModel: String? = null,
)

data class ModelProvider(val slug: String, val name: String, val models: List<ModelOption>)

data class ModelCatalog(
    val selected: ModelSelection? = null,
    val providers: List<ModelProvider> = emptyList(),
) {
    fun selectedFor(sessionModel: String?): ModelCatalog {
        val options = providers.flatMap { provider -> provider.models.map { provider to it } }
        val match =
            sessionModel?.takeIf(String::isNotBlank)?.let { current ->
                options.firstOrNull { (_, option) -> option.id == current }
                    ?: options.firstOrNull { (_, option) -> option.resolvedModel == current }
            } ?: options.firstOrNull()
        return copy(
            selected =
                match?.let { (provider, option) ->
                    ModelSelection(
                        provider.slug,
                        option.id,
                        selected?.thinkingLevel,
                        selected?.reasoningSummary ?: false,
                    )
                }
        )
    }

    fun filtered(query: String): List<ModelProvider> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return providers
        return providers.mapNotNull { provider ->
            val providerMatches =
                provider.name.lowercase().contains(needle) ||
                    provider.slug.lowercase().contains(needle)
            val models =
                if (providerMatches) provider.models
                else provider.models.filter { it.id.lowercase().contains(needle) }
            provider.takeIf { models.isNotEmpty() }?.copy(models = models)
        }
    }

    companion object {
        fun fromJson(value: JsonObject): ModelCatalog {
            val selectedModel = value.string("model")
            val selectedProvider = value.string("provider")
            val providers =
                (value["providers"] as? JsonArray)
                    ?.mapNotNull { it as? JsonObject }
                    ?.filter { it["authenticated"]?.jsonPrimitive?.booleanOrNull != false }
                    ?.mapNotNull { provider ->
                        val slug =
                            provider.string("slug")?.takeIf(String::isNotBlank)
                                ?: return@mapNotNull null
                        val unavailable =
                            (provider["unavailable_models"] as? JsonArray)
                                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                                ?.toSet()
                                .orEmpty()
                        val models =
                            (provider["models"] as? JsonArray)
                                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                                ?.filter(String::isNotBlank)
                                ?.distinct()
                                ?.map { ModelOption(it, it in unavailable) }
                                .orEmpty()
                        if (models.isEmpty()) return@mapNotNull null
                        ModelProvider(slug, provider.string("name") ?: slug, models)
                    }
                    .orEmpty()
            return ModelCatalog(
                selected =
                    if (!selectedModel.isNullOrBlank() && !selectedProvider.isNullOrBlank())
                        ModelSelection(
                            selectedProvider,
                            selectedModel,
                            ThinkingLevel.fromApiValue(value.string("thinking_level")),
                            value["reasoning_summary"]?.jsonPrimitive?.booleanOrNull ?: false,
                        )
                    else null,
                providers = providers,
            )
        }
    }
}

fun modelSwitchValue(selection: ModelSelection): String =
    "${selection.model} --provider ${selection.provider} --session"

sealed interface ChatItem {
    data class Message(
        val role: String,
        val text: String,
        val id: String? = null,
        val timestamp: java.time.Instant? = null,
        val uiKey: String? = null,
        val pendingCanonical: Boolean = false,
        val reasoning: String? = null,
        val reasoningIsSummary: Boolean = false,
    ) : ChatItem

    data class Tool(
        val id: String?,
        val name: String,
        val state: String,
        val arguments: String? = null,
        val result: String? = null,
        val error: String? = null,
        val startedAt: java.time.Instant? = null,
        val completedAt: java.time.Instant? = null,
        val durationMs: Long? = null,
        val durationEstimated: Boolean = false,
        val deriveDuration: Boolean = true,
        val batchId: String? = null,
        val reasoning: String? = null,
        val reasoningIsSummary: Boolean = false,
    ) : ChatItem {
        val final: Boolean
            get() = state in setOf("completed", "failed", "cancelled")
    }

    data class ParallelToolGroup(
        val id: String,
        val tools: List<Tool>,
        val reasoning: String? = null,
        val reasoningIsSummary: Boolean = false,
    ) : ChatItem {
        val final: Boolean
            get() = tools.all(Tool::final)
    }

    data class ToolGroup(val operations: List<ChatItem>) : ChatItem {
        val callCount: Int
            get() = operations.sumOf(::toolCallCount)

        val roundCount: Int
            get() = operations.size
    }

    data class Status(val text: String, val timestamp: java.time.Instant? = null) : ChatItem
}

private fun toolCallCount(item: ChatItem): Int =
    when (item) {
        is ChatItem.Tool -> 1
        is ChatItem.ParallelToolGroup -> item.tools.size
        else -> 0
    }

data class ToolValueRow(val name: String, val value: String) {
    val summary: String
        get() = "${name.singleLine()}: ${value.singleLine()}"
}

data class ToolValuePreview(val first: ToolValueRow, val remainingFields: Int)

fun toolValuePreview(rows: List<ToolValueRow>): ToolValuePreview? =
    rows.firstOrNull()?.let { ToolValuePreview(it, rows.size - 1) }

fun toolValueRows(content: String?, fallbackName: String): List<ToolValueRow> {
    val raw = content?.takeIf(String::isNotBlank) ?: return emptyList()
    val parsed = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
    if (parsed is JsonObject && parsed.isNotEmpty())
        return parsed.map { (name, value) ->
            ToolValueRow(name = name.singleLine(), value = value.toolValue())
        }
    return listOf(ToolValueRow(fallbackName, raw))
}

private val prettyToolJson = Json { prettyPrint = true }

fun prettyToolValue(value: String): String =
    runCatching {
            prettyToolJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(value))
        }
        .getOrDefault(value)

private fun JsonElement.toolValue(): String =
    when (this) {
        is JsonPrimitive -> content
        else -> toString()
    }

private fun String.singleLine(): String = replace(Regex("\\R+"), " ").trim()

private fun isCompletedOperation(item: ChatItem): Boolean =
    when (item) {
        is ChatItem.Tool -> item.final
        is ChatItem.ParallelToolGroup -> item.final
        else -> false
    }

fun toolCountBreakdown(operations: List<ChatItem>): Map<String, Int> {
    val counts = linkedMapOf<String, Int>()
    operations.forEach { operation ->
        val tools =
            when (operation) {
                is ChatItem.Tool -> listOf(operation)
                is ChatItem.ParallelToolGroup -> operation.tools
                else -> emptyList()
            }
        tools.forEach { counts[it.name] = (counts[it.name] ?: 0) + 1 }
    }
    return counts
}

fun attachReasoningToToolOperations(items: List<ChatItem>): List<ChatItem> {
    val result = mutableListOf<ChatItem>()
    val pending = mutableListOf<ChatItem.Message>()

    fun flushPending() {
        result += pending
        pending.clear()
    }

    fun reasoning(): Pair<String, Boolean>? =
        pending
            .mapNotNull { message ->
                message.reasoning?.takeIf(String::isNotBlank)?.let {
                    it to message.reasoningIsSummary
                }
            }
            .takeIf { it.isNotEmpty() }
            ?.let { parts -> parts.joinToString("\n") { it.first } to parts.all { it.second } }

    items.forEach { item ->
        if (item is ChatItem.Message && item.text.isBlank() && !item.reasoning.isNullOrBlank()) {
            pending += item
            return@forEach
        }
        when (item) {
            is ChatItem.Tool -> {
                val attached = reasoning()
                result +=
                    if (attached == null) item
                    else item.copy(reasoning = attached.first, reasoningIsSummary = attached.second)
                pending.clear()
            }
            is ChatItem.ParallelToolGroup -> {
                val attached = reasoning()
                result +=
                    if (attached == null) item
                    else item.copy(reasoning = attached.first, reasoningIsSummary = attached.second)
                pending.clear()
            }
            else -> {
                flushPending()
                result += item
            }
        }
    }
    flushPending()
    return result
}

fun operationReasoning(item: ChatItem): Pair<String, Boolean>? =
    when (item) {
        is ChatItem.Tool ->
            item.reasoning?.takeIf(String::isNotBlank)?.let { it to item.reasoningIsSummary }
        is ChatItem.ParallelToolGroup ->
            item.reasoning?.takeIf(String::isNotBlank)?.let { it to item.reasoningIsSummary }
        else -> null
    }

fun groupTimeline(items: List<ChatItem>, minimumGroupSize: Int = 4): List<ChatItem> {
    val result = mutableListOf<ChatItem>()
    val completed = mutableListOf<ChatItem>()
    fun flush() {
        if (completed.sumOf(::toolCallCount) >= minimumGroupSize)
            result += ChatItem.ToolGroup(completed.toList())
        else result += completed
        completed.clear()
    }
    items.forEach { item ->
        if (isCompletedOperation(item)) completed += item
        else {
            flush()
            result += item
        }
    }
    flush()
    return result
}

private fun mergeTool(started: ChatItem.Tool, update: ChatItem.Tool): ChatItem.Tool {
    if (started.final && !update.final) return started
    val startedAt = started.startedAt ?: update.startedAt
    val completedAt = update.completedAt ?: started.completedAt
    val measured =
        if (
            update.deriveDuration &&
                update.durationMs == null &&
                startedAt != null &&
                completedAt != null
        )
            java.time.Duration.between(startedAt, completedAt).toMillis().takeIf { it >= 0 }
        else null
    return update.copy(
        name = update.name.takeUnless { it == "tool" } ?: started.name,
        arguments = update.arguments ?: started.arguments,
        result = update.result ?: started.result,
        error = update.error ?: started.error,
        startedAt = startedAt,
        completedAt = completedAt,
        durationMs = update.durationMs ?: measured ?: started.durationMs,
        durationEstimated = update.durationEstimated || started.durationEstimated,
        deriveDuration = update.deriveDuration,
        batchId = update.batchId ?: started.batchId,
        reasoning = update.reasoning ?: started.reasoning,
        reasoningIsSummary =
            if (update.reasoning != null) update.reasoningIsSummary else started.reasoningIsSummary,
    )
}

private fun compatibleParallelStart(first: ChatItem.Tool, next: ChatItem.Tool): Boolean =
    !first.final &&
        !next.final &&
        (first.batchId == null || next.batchId == null || first.batchId == next.batchId)

fun upsertTool(items: List<ChatItem>, tool: ChatItem.Tool): List<ChatItem> {
    if (tool.id != null) {
        items.forEachIndexed { index, item ->
            when (item) {
                is ChatItem.Tool ->
                    if (item.id == tool.id)
                        return items.toMutableList().apply { this[index] = mergeTool(item, tool) }
                is ChatItem.ParallelToolGroup -> {
                    val child = item.tools.indexOfFirst { it.id == tool.id }
                    if (child >= 0) {
                        val updated = item.tools.toMutableList()
                        updated[child] = mergeTool(updated[child], tool)
                        return items.toMutableList().apply {
                            this[index] = item.copy(tools = updated)
                        }
                    }
                }
                else -> Unit
            }
        }
    }
    if (!tool.final) {
        when (val last = items.lastOrNull()) {
            is ChatItem.Tool ->
                if (compatibleParallelStart(last, tool)) {
                    val groupId =
                        tool.batchId ?: last.batchId ?: "parallel:${last.id ?: items.size}"
                    return items.dropLast(1) +
                        ChatItem.ParallelToolGroup(groupId, listOf(last, tool))
                }
            is ChatItem.ParallelToolGroup ->
                if (
                    !last.final &&
                        last.tools.lastOrNull()?.let { compatibleParallelStart(it, tool) } == true
                )
                    return items.dropLast(1) + last.copy(tools = last.tools + tool)
            else -> Unit
        }
    }
    return items + tool
}

fun formatClockTime(
    timestamp: java.time.Instant,
    zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    locale: java.util.Locale = java.util.Locale.getDefault(),
): String =
    java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)
        .withLocale(locale)
        .withZone(zoneId)
        .format(timestamp)

data class ApprovalRequest(
    val sessionId: String,
    val command: String,
    val description: String,
    val allowPermanent: Boolean,
)

data class ClarifyRequest(
    val requestId: String,
    val question: String,
    val choices: List<String> = emptyList(),
)

data class ConnectionConfig(val baseUrl: String, val token: String = "") {
    val normalizedBaseUrl: String
        get() {
            val trimmed = baseUrl.trim().trimEnd('/')
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed
            val scheme = uri.scheme?.lowercase() ?: return trimmed
            val host = uri.host?.lowercase()?.trimEnd('.') ?: return trimmed
            if (
                uri.userInfo != null ||
                    uri.rawQuery != null ||
                    uri.rawFragment != null ||
                    uri.rawPath !in setOf("", "/")
            )
                return trimmed
            val defaultPort =
                (scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)
            return URI(scheme, null, host, if (defaultPort) -1 else uri.port, null, null, null)
                .toString()
        }

    fun isAllowedEndpoint(): Boolean {
        val uri = runCatching { URI(normalizedBaseUrl) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.lowercase()?.trim('[', ']')?.trimEnd('.') ?: return false
        if (
            uri.userInfo != null ||
                uri.rawQuery != null ||
                uri.rawFragment != null ||
                uri.rawPath !in setOf("", "/")
        )
            return false
        if (scheme == "https") return true
        if (scheme != "http") return false
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".ts.net"))
            return true
        if (!host.matches(Regex("[0-9.]+")) && ':' !in host) return false
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        return when (address) {
            is Inet4Address -> {
                val o = address.address.map { it.toInt() and 0xff }
                o[0] == 10 ||
                    o[0] == 127 ||
                    (o[0] == 169 && o[1] == 254) ||
                    (o[0] == 172 && o[1] in 16..31) ||
                    (o[0] == 192 && o[1] == 168) ||
                    (o[0] == 100 && o[1] in 64..127)
            }
            is Inet6Address ->
                address.isLoopbackAddress ||
                    address.isLinkLocalAddress ||
                    ((address.address[0].toInt() and 0xfe) == 0xfc)
            else -> false
        }
    }
}

fun isSafeExternalUrl(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    return when (uri.scheme?.lowercase()) {
        "http",
        "https" -> uri.host != null && uri.userInfo == null
        "mailto" -> uri.schemeSpecificPart?.isNotBlank() == true
        else -> false
    }
}

internal fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

fun buildSessionTree(sessions: List<HarnessSession>): Map<String, List<HarnessSession>> {
    val byParent = mutableMapOf<String, MutableList<HarnessSession>>()
    for (s in sessions) {
        val parent = s.parentSessionId ?: continue
        byParent.getOrPut(parent) { mutableListOf() } += s
    }
    return byParent
}

fun mergeSessionsById(
    existing: List<HarnessSession>,
    additions: List<HarnessSession>,
): List<HarnessSession> {
    val additionsById = additions.associateBy(HarnessSession::id).toMutableMap()
    return existing.map { additionsById.remove(it.id) ?: it } + additionsById.values
}

fun directChildSessions(sessions: List<HarnessSession>, sessionId: String): List<HarnessSession> =
    sortSessionsForOverview(sessions.filter { it.parentSessionId == sessionId })

fun childCount(sessions: List<HarnessSession>, sessionId: String): Int {
    val tree = buildSessionTree(sessions)
    val visited = mutableSetOf<String>()
    val queue = ArrayDeque<String>()
    queue.addAll(tree[sessionId].orEmpty().map { it.id })
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        if (!visited.add(id)) continue
        queue.addAll(tree[id].orEmpty().map { it.id })
    }
    return visited.size
}

fun rootSessions(sessions: List<HarnessSession>): List<HarnessSession> =
    sessions.filter { it.parentSessionId == null }

fun sessionWithChildren(
    parent: HarnessSession,
    tree: Map<String, List<HarnessSession>>,
): List<HarnessSession> {
    val result = mutableListOf(parent)
    val children = tree[parent.id].orEmpty()
    for (child in children) {
        result += sessionWithChildren(child, tree)
    }
    return result
}

data class TreeNode(val session: HarnessSession, val depth: Int)

fun sessionTreeWithDepth(sessions: List<HarnessSession>, parentId: String): List<TreeNode> {
    val tree = buildSessionTree(sessions)
    val result = mutableListOf<TreeNode>()
    fun walk(id: String, depth: Int) {
        val session = sessions.firstOrNull { it.id == id } ?: return
        result += TreeNode(session, depth)
        for (child in tree[id].orEmpty()) {
            walk(child.id, depth + 1)
        }
    }
    walk(parentId, 0)
    return result
}
