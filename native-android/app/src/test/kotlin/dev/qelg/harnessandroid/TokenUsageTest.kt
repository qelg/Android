package dev.qelg.harnessandroid

import dev.qelg.harnessandroid.data.ContextBreakdown
import dev.qelg.harnessandroid.data.ContextCategory
import dev.qelg.harnessandroid.data.CumulativeTokenUsage
import dev.qelg.harnessandroid.data.LiveTokenUsage
import dev.qelg.harnessandroid.data.TokenUsageState
import dev.qelg.harnessandroid.data.ToolDefinitions
import dev.qelg.harnessandroid.data.ToolSection
import dev.qelg.harnessandroid.data.ToolSummary
import dev.qelg.harnessandroid.data.usageBarData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenUsageTest {
    @Test
    fun contextBreakdownSeparatesBaseConversationAndFreeCapacity() {
        val breakdown =
            ContextBreakdown.fromJson(
                Json.parseToJsonElement(
                        """{"categories":[{"id":"system_prompt","label":"System prompt","tokens":18000},{"id":"tool_definitions","label":"Tool definitions","tokens":7000},{"id":"conversation","label":"Conversation","tokens":25000}],"context_used":50000,"context_max":100000,"estimated_total":50000,"model":"test-model"}"""
                    )
                    .jsonObject
            )

        assertEquals(25_000L, breakdown.baseTokens)
        assertEquals(25_000L, breakdown.conversationTokens)
        assertEquals(50_000L, breakdown.freeTokens)
        assertEquals(50, breakdown.usedPercent)
        assertEquals("test-model", breakdown.model)
    }

    @Test
    fun cumulativeUsageIncludesCachedPromptTrafficWithoutDoubleCountingReasoning() {
        val usage =
            CumulativeTokenUsage.fromJson(
                Json.parseToJsonElement(
                        """{"input_tokens":84200,"output_tokens":52000,"cache_read_tokens":1694100,"cache_write_tokens":12000,"reasoning_tokens":18000,"api_call_count":34}"""
                    )
                    .jsonObject
            )

        assertEquals(1_790_300L, usage.processedInputTokens)
        assertEquals(1_842_300L, usage.totalTokens)
        assertEquals(95, usage.cacheHitPercent)
        assertEquals(18_000L, usage.reasoningTokens)
        assertEquals(34, usage.apiCalls)
    }

    @Test
    fun missingProviderCacheMetricsDegradeToZero() {
        val usage =
            CumulativeTokenUsage.fromJson(
                Json.parseToJsonElement("""{"input_tokens":120,"output_tokens":30}""").jsonObject
            )

        assertEquals(0L, usage.cacheReadTokens)
        assertEquals(0L, usage.cacheWriteTokens)
        assertEquals(150L, usage.totalTokens)
        assertNull(usage.cacheHitPercent)
    }

    @Test
    fun liveUsageReadsCurrentContextAndCumulativeCounters() {
        val usage =
            LiveTokenUsage.fromSessionInfo(
                Json.parseToJsonElement(
                        """{"usage":{"input":420,"output":80,"total":500,"calls":3,"context_used":20000,"context_max":100000,"context_percent":20}}"""
                    )
                    .jsonObject
            )

        assertEquals(20_000L, usage?.contextUsed)
        assertEquals(100_000L, usage?.contextMax)
        assertEquals(20, usage?.contextPercent)
        assertEquals(500L, usage?.totalTokens)
        assertEquals(3, usage?.calls)
    }

    @Test
    fun runUsageWithoutContextWindowIsNotTreatedAsLiveContext() {
        val usage =
            LiveTokenUsage.fromSessionInfo(
                Json.parseToJsonElement(
                        """{"usage":{"input_tokens":420,"output_tokens":80,"total_tokens":500}}"""
                    )
                    .jsonObject
            )

        assertNull(usage)
    }

    @Test
    fun freshLiveOccupancyWinsOverAnOlderContextBreakdown() {
        val state =
            dev.qelg.harnessandroid.data.TokenUsageState(
                context = ContextBreakdown(emptyList(), 40_000, 100_000, 40_000),
                live = LiveTokenUsage(65_000, 100_000, 65, 0, 0),
            )

        assertEquals(65_000L, state.currentContext?.used)
        assertEquals(100_000L, state.currentContext?.max)
        assertEquals(65, state.currentContext?.percent)
    }

    @Test
    fun cumulativeUsageKeepsUsageBarAccessibleWithoutContextWindow() {
        val state =
            TokenUsageState(
                cumulative =
                    CumulativeTokenUsage.fromJson(
                        Json.parseToJsonElement(
                                """{"input_tokens":100,"output_tokens":20,"cache_read_tokens":300,"cache_write_tokens":10}"""
                            )
                            .jsonObject
                    )
            )

        val bar = state.usageBarData()

        assertEquals(null, bar?.context)
        assertEquals(430L, bar?.totalTokens)
        assertNull(TokenUsageState().usageBarData())
    }

    @Test
    fun compressionChainUsageAddsEachSegmentExactlyOnce() {
        val root =
            CumulativeTokenUsage.fromJson(
                Json.parseToJsonElement(
                        """{"input_tokens":100,"output_tokens":20,"cache_read_tokens":300,"cache_write_tokens":10,"api_call_count":2}"""
                    )
                    .jsonObject
            )
        val tip =
            CumulativeTokenUsage.fromJson(
                Json.parseToJsonElement(
                        """{"input_tokens":50,"output_tokens":10,"cache_read_tokens":150,"cache_write_tokens":5,"api_call_count":1}"""
                    )
                    .jsonObject
            )

        val combined = root + tip

        assertEquals(615L, combined.processedInputTokens)
        assertEquals(645L, combined.totalTokens)
        assertEquals(3, combined.apiCalls)
    }

    @Test
    fun onlySystemPromptCategoryWithContentIsExpandable() {
        val systemPrompt = ContextCategory("system_prompt", "System prompt", 1_000)
        val memory = ContextCategory("memory", "Memory", 100)

        assertTrue(isSystemPromptExpandable(systemPrompt, "Full prompt"))
        assertFalse(isSystemPromptExpandable(systemPrompt, "  "))
        assertFalse(isSystemPromptExpandable(memory, "Full prompt"))
    }

    @Test
    fun onlyToolDefinitionsCategoryWithRowsIsExpandable() {
        val toolsCategory = ContextCategory("tool_definitions", "Tool definitions", 1_000)
        val systemPrompt = ContextCategory("system_prompt", "System prompt", 100)
        val definitions =
            ToolDefinitions(
                sections =
                    listOf(ToolSection("files", listOf(ToolSummary("read_file", "Read a file.")))),
                total = 1,
            )

        assertTrue(isToolDefinitionsExpandable(toolsCategory, definitions))
        assertFalse(isToolDefinitionsExpandable(systemPrompt, definitions))
        assertFalse(isToolDefinitionsExpandable(toolsCategory, ToolDefinitions(emptyList(), 0)))
    }

    @Test
    fun storedSessionRotationClearsPersistedDetailsImmediately() {
        val live = LiveTokenUsage(1_000, 10_000, 10, 200, 2)
        val usage =
            TokenUsageState(
                cumulative =
                    CumulativeTokenUsage.fromJson(
                        Json.parseToJsonElement(
                                """{"input_tokens":100,"output_tokens":20,"api_call_count":2}"""
                            )
                            .jsonObject
                    ),
                live = live,
                systemPrompt = "Secret prompt",
            )

        val cleared = usage.clearPersistedTokenDetails()

        assertNull(cleared.cumulative)
        assertNull(cleared.systemPrompt)
        assertEquals(live, cleared.live)
    }

    @Test
    fun delayedUsageResponseIsRejectedAfterAnySessionBoundaryChanges() {
        val expected =
            TokenUsageRefreshIdentity(
                connectionVersion = 1,
                selectionVersion = 2,
                runtimeId = "runtime-1",
                selectedId = "selected-1",
                storedId = "stored-1",
            )

        assertTrue(isCurrentTokenUsageRefresh(expected, expected))
        assertFalse(isCurrentTokenUsageRefresh(expected, expected.copy(connectionVersion = 2)))
        assertFalse(isCurrentTokenUsageRefresh(expected, expected.copy(selectionVersion = 3)))
        assertFalse(isCurrentTokenUsageRefresh(expected, expected.copy(runtimeId = "runtime-2")))
        assertFalse(isCurrentTokenUsageRefresh(expected, expected.copy(selectedId = "selected-2")))
        assertFalse(isCurrentTokenUsageRefresh(expected, expected.copy(storedId = "stored-2")))
    }
}
