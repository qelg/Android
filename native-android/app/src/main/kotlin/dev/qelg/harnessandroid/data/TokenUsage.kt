package dev.qelg.harnessandroid.data

import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private fun JsonObject.long(key: String): Long = this[key]?.jsonPrimitive?.longOrNull ?: 0L

private fun JsonObject.int(key: String): Int = this[key]?.jsonPrimitive?.intOrNull ?: 0

data class ContextCategory(val id: String, val label: String, val tokens: Long)

data class ContextBreakdown(
    val categories: List<ContextCategory>,
    val contextUsed: Long,
    val contextMax: Long,
    val estimatedTotal: Long,
    val model: String? = null,
) {
    val conversationTokens: Long
        get() = categories.filter { it.id == "conversation" }.sumOf(ContextCategory::tokens)

    val baseTokens: Long
        get() = categories.filterNot { it.id == "conversation" }.sumOf(ContextCategory::tokens)

    val freeTokens: Long
        get() = (contextMax - contextUsed).coerceAtLeast(0L)

    val usedPercent: Int
        get() =
            if (contextMax > 0L)
                ((contextUsed.toDouble() / contextMax) * 100).roundToInt().coerceIn(0, 100)
            else 0

    companion object {
        fun fromJson(value: JsonObject): ContextBreakdown {
            val categories =
                (value["categories"] as? JsonArray)
                    ?.mapNotNull { it as? JsonObject }
                    ?.mapNotNull { category ->
                        val id =
                            category["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        ContextCategory(
                            id = id,
                            label = category["label"]?.jsonPrimitive?.contentOrNull ?: id,
                            tokens = category.long("tokens"),
                        )
                    }
                    .orEmpty()
            return ContextBreakdown(
                categories = categories,
                contextUsed = value.long("context_used"),
                contextMax = value.long("context_max"),
                estimatedTotal = value.long("estimated_total"),
                model = value["model"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }
}

data class CumulativeTokenUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val reasoningTokens: Long,
    val apiCalls: Int,
    private val cacheMetricsReported: Boolean,
) {
    val processedInputTokens: Long
        get() = inputTokens + cacheReadTokens + cacheWriteTokens

    val totalTokens: Long
        get() = processedInputTokens + outputTokens

    val cacheHitPercent: Int?
        get() =
            if (cacheMetricsReported && processedInputTokens > 0L)
                ((cacheReadTokens.toDouble() / processedInputTokens) * 100)
                    .roundToInt()
                    .coerceIn(0, 100)
            else null

    operator fun plus(other: CumulativeTokenUsage): CumulativeTokenUsage =
        CumulativeTokenUsage(
            inputTokens = inputTokens + other.inputTokens,
            outputTokens = outputTokens + other.outputTokens,
            cacheReadTokens = cacheReadTokens + other.cacheReadTokens,
            cacheWriteTokens = cacheWriteTokens + other.cacheWriteTokens,
            reasoningTokens = reasoningTokens + other.reasoningTokens,
            apiCalls = apiCalls + other.apiCalls,
            cacheMetricsReported = cacheMetricsReported || other.cacheMetricsReported,
        )

    companion object {
        fun fromJsonOrNull(value: JsonObject): CumulativeTokenUsage? =
            if (
                listOf(
                        "input_tokens",
                        "output_tokens",
                        "cache_read_tokens",
                        "cache_write_tokens",
                        "reasoning_tokens",
                        "api_call_count",
                    )
                    .any(value::containsKey)
            )
                fromJson(value)
            else null

        fun fromJson(value: JsonObject): CumulativeTokenUsage =
            CumulativeTokenUsage(
                inputTokens = value.long("input_tokens"),
                outputTokens = value.long("output_tokens"),
                cacheReadTokens = value.long("cache_read_tokens"),
                cacheWriteTokens = value.long("cache_write_tokens"),
                reasoningTokens = value.long("reasoning_tokens"),
                apiCalls = value.int("api_call_count"),
                cacheMetricsReported =
                    value.containsKey("cache_read_tokens") ||
                        value.containsKey("cache_write_tokens"),
            )
    }
}

data class ConversationTokenDetails(val usage: CumulativeTokenUsage, val systemPrompt: String?)

data class ToolSummary(val name: String, val description: String)

data class ToolSection(val name: String, val tools: List<ToolSummary>)

data class ToolDefinitions(val sections: List<ToolSection>, val total: Int) {
    companion object {
        fun fromJson(value: JsonObject): ToolDefinitions {
            val sections =
                (value["sections"] as? JsonArray)
                    ?.mapNotNull { it as? JsonObject }
                    ?.map { section ->
                        ToolSection(
                            name = section["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            tools =
                                (section["tools"] as? JsonArray)
                                    ?.mapNotNull { it as? JsonObject }
                                    ?.mapNotNull { tool ->
                                        val name =
                                            tool["name"]?.jsonPrimitive?.contentOrNull
                                                ?: return@mapNotNull null
                                        ToolSummary(
                                            name,
                                            tool["description"]
                                                ?.jsonPrimitive
                                                ?.contentOrNull
                                                .orEmpty(),
                                        )
                                    }
                                    .orEmpty(),
                        )
                    }
                    .orEmpty()
            return ToolDefinitions(sections.filter { it.tools.isNotEmpty() }, value.int("total"))
        }
    }
}

data class LiveTokenUsage(
    val contextUsed: Long,
    val contextMax: Long,
    val contextPercent: Int,
    val totalTokens: Long,
    val calls: Int,
) {
    companion object {
        fun fromSessionInfo(value: JsonObject): LiveTokenUsage? {
            val usage = value["usage"] as? JsonObject ?: return null
            if (usage.long("context_max") <= 0L) return null
            return LiveTokenUsage(
                contextUsed = usage.long("context_used"),
                contextMax = usage.long("context_max"),
                contextPercent = usage.int("context_percent"),
                totalTokens = usage.long("total"),
                calls = usage.int("calls"),
            )
        }
    }
}

data class ContextWindow(val used: Long, val max: Long, val percent: Int)

data class TokenUsageState(
    val context: ContextBreakdown? = null,
    val cumulative: CumulativeTokenUsage? = null,
    val live: LiveTokenUsage? = null,
    val systemPrompt: String? = null,
    val toolDefinitions: ToolDefinitions? = null,
) {
    val currentContext: ContextWindow?
        get() {
            live
                ?.takeIf { it.contextUsed > 0L && it.contextMax > 0L }
                ?.let {
                    return ContextWindow(
                        it.contextUsed,
                        it.contextMax,
                        it.contextPercent.coerceIn(0, 100),
                    )
                }
            return context
                ?.takeIf { it.contextMax > 0L }
                ?.let { ContextWindow(it.contextUsed, it.contextMax, it.usedPercent) }
        }
}

data class UsageBarData(val context: ContextWindow?, val totalTokens: Long?)

fun TokenUsageState.usageBarData(): UsageBarData? {
    val context = currentContext
    val total = cumulative?.totalTokens
    return if (context != null || total != null) UsageBarData(context, total) else null
}
