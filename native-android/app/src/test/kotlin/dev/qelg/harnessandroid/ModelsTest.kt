package dev.qelg.harnessandroid

import dev.qelg.harnessandroid.data.ChatItem
import dev.qelg.harnessandroid.data.ConnectionConfig
import dev.qelg.harnessandroid.data.DraftSubmission
import dev.qelg.harnessandroid.data.HarnessSession
import dev.qelg.harnessandroid.data.ModelCatalog
import dev.qelg.harnessandroid.data.ModelSelection
import dev.qelg.harnessandroid.data.ThinkingLevel
import dev.qelg.harnessandroid.data.ToolValuePreview
import dev.qelg.harnessandroid.data.ToolValueRow
import dev.qelg.harnessandroid.data.applySessionModelOverrides
import dev.qelg.harnessandroid.data.attachReasoningToToolOperations
import dev.qelg.harnessandroid.data.canClearDraft
import dev.qelg.harnessandroid.data.canMarkSessionRead
import dev.qelg.harnessandroid.data.confirmedReadAt
import dev.qelg.harnessandroid.data.filterSessions
import dev.qelg.harnessandroid.data.formatSessionUpdate
import dev.qelg.harnessandroid.data.groupTimeline
import dev.qelg.harnessandroid.data.isSafeExternalUrl
import dev.qelg.harnessandroid.data.isSessionUpdateRead
import dev.qelg.harnessandroid.data.modelCatalogForSession
import dev.qelg.harnessandroid.data.modelSelectionFromSessionInfo
import dev.qelg.harnessandroid.data.modelSwitchValue
import dev.qelg.harnessandroid.data.operationReasoning
import dev.qelg.harnessandroid.data.prettyToolValue
import dev.qelg.harnessandroid.data.prioritizeSessionsWithDrafts
import dev.qelg.harnessandroid.data.sessionModelForLineage
import dev.qelg.harnessandroid.data.sessionsWithModelSelection
import dev.qelg.harnessandroid.data.sortSessionsForOverview
import dev.qelg.harnessandroid.data.toolCountBreakdown
import dev.qelg.harnessandroid.data.toolValuePreview
import dev.qelg.harnessandroid.data.toolValueRows
import dev.qelg.harnessandroid.data.updateDrafts
import dev.qelg.harnessandroid.data.upsertTool
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    @Test
    fun searchMatchesTitlePreviewSourceAndIdCaseInsensitively() {
        val sessions =
            listOf(
                HarnessSession("1", "Release Work", preview = "APK ready"),
                HarnessSession("delegate-42", "Notes", source = "delegate_task"),
            )
        assertEquals(listOf("1"), filterSessions(sessions, "apk").map { it.id })
        assertEquals(listOf("1"), filterSessions(sessions, "RELEASE").map { it.id })
        assertEquals(listOf("delegate-42"), filterSessions(sessions, "DELEGATE").map { it.id })
        assertEquals(listOf("delegate-42"), filterSessions(sessions, "42").map { it.id })
    }

    @Test
    fun childAndCompressionSessionFieldsAreDecoded() {
        val session =
            HarnessSession.fromJson(
                buildJsonObject {
                    put("id", "child")
                    put("source", "delegate_task")
                    put("parent_session_id", "root")
                    put("end_reason", "compression")
                }
            )

        assertEquals("delegate_task", session.source)
        assertEquals("root", session.parentSessionId)
        assertEquals("compression", session.endReason)
    }

    @Test
    fun sessionTokenUsageFieldsAreDecodedFromApiMetadata() {
        val session =
            HarnessSession.fromJson(
                buildJsonObject {
                    put("id", "usage")
                    put("input_tokens", 100)
                    put("output_tokens", 20)
                    put("cache_read_tokens", 300)
                    put("cache_write_tokens", 10)
                    put("reasoning_tokens", 5)
                    put("estimated_cost_usd", 0.42)
                    put("actual_cost_usd", 0.4)
                    put("api_call_count", 2)
                }
            )

        assertEquals(100L, session.inputTokens)
        assertEquals(20L, session.outputTokens)
        assertEquals(300L, session.cacheReadTokens)
        assertEquals(10L, session.cacheWriteTokens)
        assertEquals(5L, session.reasoningTokens)
        assertEquals(0.42, session.estimatedCostUsd!!, 0.0)
        assertEquals(0.4, session.actualCostUsd!!, 0.0)
        assertEquals(2, session.apiCallCount)
        assertEquals(430L, session.cumulativeTokenUsage?.totalTokens)
        assertEquals(430L, initialTokenUsage(session)?.cumulative?.totalTokens)
    }

    @Test
    fun sessionWithoutReportedTokenFieldsHasNoCumulativeUsage() {
        val session = HarnessSession.fromJson(buildJsonObject { put("id", "empty") })

        assertEquals(null, session.cumulativeTokenUsage)
    }

    @Test
    fun sessionListActivityFieldsAreDecoded() {
        val current =
            HarnessSession.fromJson(
                buildJsonObject {
                    put("id", "current")
                    put("title", "Current")
                    put("last_active", 1784390400)
                    put("started_at", 1784380000)
                }
            )
        val legacy =
            HarnessSession.fromJson(
                buildJsonObject {
                    put("id", "legacy")
                    put("title", "Legacy")
                    put("started_at", 1784380000)
                }
            )

        assertEquals("1784390400", current.updatedAt)
        assertEquals("1784380000", legacy.updatedAt)
    }

    @Test
    fun sessionsAreSortedNewestFirstByLatestUpdate() {
        val sessions =
            listOf(
                HarnessSession("old", "Old", updatedAt = "2026-07-18T08:00:00Z"),
                HarnessSession("unknown", "Unknown"),
                HarnessSession("new", "New", updatedAt = "2026-07-18T10:00:00Z"),
                HarnessSession("middle", "Middle", updatedAt = "2026-07-18T09:00:00Z"),
            )

        assertEquals(
            listOf("new", "middle", "old", "unknown"),
            sortSessionsForOverview(sessions).map(HarnessSession::id),
        )
    }

    @Test
    fun liveSessionsAreSortedBeforeNewerInactiveSessions() {
        val sessions =
            listOf(
                HarnessSession("inactive-new", "Inactive", updatedAt = "2026-07-18T10:00:00Z"),
                HarnessSession(
                    "live-old",
                    "Live old",
                    updatedAt = "2026-07-18T08:00:00Z",
                    active = true,
                ),
                HarnessSession(
                    "live-new",
                    "Live new",
                    updatedAt = "2026-07-18T09:00:00Z",
                    active = true,
                ),
            )

        assertEquals(
            listOf("live-new", "live-old", "inactive-new"),
            sortSessionsForOverview(sessions).map(HarnessSession::id),
        )
    }

    @Test
    fun latestUpdateIsFormattedInTheDevicesZone() {
        assertEquals(
            "18.07.2026, 12:00",
            formatSessionUpdate(
                "2026-07-18T10:00:00Z",
                java.time.ZoneId.of("Europe/Berlin"),
                java.util.Locale.GERMANY,
            ),
        )
        assertEquals(null, formatSessionUpdate("not-a-time"))
    }

    @Test
    fun sessionIsReadOnlyWhenAndroidConfirmedItAfterTheLatestUpdate() {
        val session = HarnessSession("chat", "Chat", updatedAt = "2026-07-18T10:00:00Z")

        assertTrue(!isSessionUpdateRead(session, null))
        assertTrue(!isSessionUpdateRead(session, "2026-07-18T09:59:59Z"))
        assertTrue(isSessionUpdateRead(session, "2026-07-18T10:00:00Z"))
        assertTrue(isSessionUpdateRead(session, "2026-07-18T10:00:01Z"))
    }

    @Test
    fun readConfirmationRequiresSuccessfullyLoadedSelectedHistory() {
        assertTrue(!canMarkSessionRead(null, "chat", "chat"))
        assertTrue(!canMarkSessionRead("other", "chat", "chat"))
        assertTrue(!canMarkSessionRead("chat", "other", "chat"))
        assertTrue(canMarkSessionRead("chat", "chat", "chat"))
    }

    @Test
    fun confirmedReadTimestampCoversFutureServerUpdate() {
        val session = HarnessSession("chat", "Chat", updatedAt = "2026-07-18T10:01:00Z")

        assertEquals(
            "2026-07-18T10:01:00Z",
            confirmedReadAt(session, Instant.parse("2026-07-18T10:00:00Z")),
        )
    }

    @Test
    fun nonFiniteNumericSessionUpdateIsUnknownAndUnread() {
        val session = HarnessSession("chat", "Chat", updatedAt = "NaN")

        assertTrue(!isSessionUpdateRead(session, "2026-07-18T10:00:00Z"))
        assertEquals(null, formatSessionUpdate("Infinity"))
        assertEquals(null, formatSessionUpdate("1e308"))
        assertEquals(null, formatSessionUpdate("4102444801"))
    }

    @Test
    fun sessionsWithDraftsArePlacedFirstWithoutChangingGroupOrder() {
        val sessions =
            listOf(
                HarnessSession("1", "First"),
                HarnessSession("2", "Second"),
                HarnessSession("3", "Third"),
                HarnessSession("4", "Fourth"),
            )

        val sorted = prioritizeSessionsWithDrafts(sessions, mapOf("2" to "draft", "4" to "other"))

        assertEquals(listOf("2", "4", "1", "3"), sorted.map { it.id })
    }

    @Test
    fun inactiveDraftsDoNotOvertakeLiveSessions() {
        val sessions =
            listOf(
                HarnessSession("draft", "Draft", updatedAt = "2026-07-18T10:00:00Z"),
                HarnessSession("live", "Live", updatedAt = "2026-07-18T08:00:00Z", active = true),
            )

        assertEquals(
            listOf("live", "draft"),
            prioritizeSessionsWithDrafts(sessions, mapOf("draft" to "unfinished"))
                .map(HarnessSession::id),
        )
    }

    @Test
    fun blankDraftsDoNotAffectSessionOrder() {
        val sessions = listOf(HarnessSession("1", "First"), HarnessSession("2", "Second"))

        assertEquals(
            listOf("1", "2"),
            prioritizeSessionsWithDrafts(sessions, mapOf("2" to "  \n")).map { it.id },
        )
    }

    @Test
    fun draftUpdatesAreIsolatedPerSessionAndBlankTextRemovesOnlyThatDraft() {
        val initial = mapOf("one" to "first", "two" to "second")

        assertEquals(
            mapOf("one" to "changed", "two" to "second"),
            updateDrafts(initial, "one", "changed"),
        )
        assertEquals(mapOf("two" to "second"), updateDrafts(initial, "one", " \n "))
    }

    @Test
    fun draftClearRequiresUnchangedRevisionNamespaceAndConnection() {
        val submitted = DraftSubmission("server-a", 7, "chat", 3, "hello")

        assertTrue(canClearDraft(submitted, "server-a", 7, 3, "hello"))
        assertTrue(!canClearDraft(submitted, "server-a", 7, 5, "hello"))
        assertTrue(!canClearDraft(submitted, "server-b", 7, 3, "hello"))
        assertTrue(!canClearDraft(submitted, "server-a", 8, 3, "hello"))
    }

    @Test
    fun toolArgumentsBecomeOneCompactRowPerTopLevelArgument() {
        assertEquals(
            listOf(
                "path: /tmp/example",
                "offset: 3",
                "options: {\"recursive\":true}",
                "query: first line second line",
            ),
            toolValueRows(
                    """{"path":"/tmp/example","offset":3,"options":{"recursive":true},"query":"first line\nsecond line"}""",
                    fallbackName = "arguments",
                )
                .map(ToolValueRow::summary),
        )
    }

    @Test
    fun toolArgumentNamesAndUnicodeLineSeparatorsStayOnOneLine() {
        assertEquals(
            listOf("bad name: value", "unicode name: one two three"),
            toolValueRows(
                    """{"bad\nname":"value","unicode\u2028name":"one\u2029two\u0085three"}""",
                    fallbackName = "arguments",
                )
                .map(ToolValueRow::summary),
        )
    }

    @Test
    fun unstructuredToolArgumentsUseSingleLineFallback() {
        assertEquals(
            listOf("arguments: raw value"),
            toolValueRows("raw\nvalue", "arguments").map(ToolValueRow::summary),
        )
        assertEquals(emptyList<ToolValueRow>(), toolValueRows("  \n ", "arguments"))
    }

    @Test
    fun toolValuesKeepFullContentAndExposeCompactSummaries() {
        val rows =
            toolValueRows(
                """{"message":"first\nsecond","payload":{"ok":true}}""",
                fallbackName = "answer",
            )

        assertEquals(
            listOf(
                ToolValueRow("message", "first\nsecond"),
                ToolValueRow("payload", "{\"ok\":true}"),
            ),
            rows,
        )
        assertEquals(
            listOf("message: first second", "payload: {\"ok\":true}"),
            rows.map { it.summary },
        )
    }

    @Test
    fun unstructuredToolValueUsesLabelButPreservesDetailContent() {
        val rows = toolValueRows("line one\nline two", fallbackName = "answer")

        assertEquals(listOf(ToolValueRow("answer", "line one\nline two")), rows)
        assertEquals("answer: line one line two", rows.single().summary)
    }

    @Test
    fun toolValuePreviewKeepsFirstFieldAndCountsTheRest() {
        val rows =
            listOf(
                ToolValueRow("first", "one"),
                ToolValueRow("second", "two"),
                ToolValueRow("third", "three"),
            )

        assertEquals(ToolValuePreview(rows.first(), 2), toolValuePreview(rows))
        assertEquals(ToolValuePreview(rows.first(), 0), toolValuePreview(rows.take(1)))
        assertEquals(null, toolValuePreview(emptyList()))
    }

    @Test
    fun toolValueJsonIsPrettyPrintedWhilePlainTextIsPreserved() {
        assertEquals(
            """{
    "name": "Hermes",
    "nested": {
        "ok": true
    }
}""",
            prettyToolValue("""{"name":"Hermes","nested":{"ok":true}}"""),
        )
        assertEquals("plain\ntext", prettyToolValue("plain\ntext"))
    }

    @Test
    fun fourConsecutiveToolsBecomeOneExpandableGroup() {
        val tools = (1..4).map { ChatItem.Tool("$it", "terminal", "completed", result = "details") }
        val blocks = groupTimeline(tools)
        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is ChatItem.ToolGroup)
        assertEquals(4, (blocks.single() as ChatItem.ToolGroup).callCount)
    }

    @Test
    fun textSplitsToolRuns() {
        val items =
            listOf(
                ChatItem.Tool("1", "terminal", "completed", result = ""),
                ChatItem.Message("assistant", "done"),
                ChatItem.Tool("2", "file", "completed", result = ""),
            )
        assertEquals(3, groupTimeline(items).size)
    }

    @Test
    fun completionReplacesMatchingToolStartInsteadOfDuplicatingIt() {
        val startedAt = Instant.parse("2026-07-17T10:00:00Z")
        val started =
            listOf<ChatItem>(
                ChatItem.Tool(
                    "call-1",
                    "terminal",
                    "running",
                    arguments = "input",
                    startedAt = startedAt,
                )
            )
        val updated =
            upsertTool(
                started,
                ChatItem.Tool(
                    "call-1",
                    "terminal",
                    "completed",
                    result = "output",
                    completedAt = Instant.parse("2026-07-17T10:00:01.250Z"),
                ),
            )
        assertEquals(1, updated.size)
        val completed = updated.single() as ChatItem.Tool
        assertEquals("completed", completed.state)
        assertEquals(1250L, completed.durationMs)
        assertEquals("input", completed.arguments)
        assertEquals("output", completed.result)
    }

    @Test
    fun overlappingStartsBecomeOnePersistentParallelGroup() {
        val first =
            ChatItem.Tool("1", "terminal", "running", arguments = "one", startedAt = Instant.EPOCH)
        val second =
            ChatItem.Tool("2", "read_file", "running", arguments = "two", startedAt = Instant.EPOCH)
        val started = upsertTool(upsertTool(emptyList(), first), second)
        val group = started.single() as ChatItem.ParallelToolGroup
        assertEquals(listOf("1", "2"), group.tools.map { it.id })

        val partiallyComplete =
            upsertTool(
                started,
                first.copy(
                    state = "completed",
                    result = "ok",
                    completedAt = Instant.EPOCH.plusSeconds(2),
                ),
            )
        val retained = partiallyComplete.single() as ChatItem.ParallelToolGroup
        assertEquals(listOf("completed", "running"), retained.tools.map { it.state })
    }

    @Test
    fun sequentialToolsAreNotGroupedAsParallel() {
        val first = ChatItem.Tool("1", "terminal", "completed", result = "ok")
        val second = ChatItem.Tool("2", "read_file", "running", arguments = "path")
        assertEquals(listOf(first, second), upsertTool(listOf(first), second))
    }

    @Test
    fun reasoningOnlyMessageAttachesToTheFollowingSingleTool() {
        val items =
            attachReasoningToToolOperations(
                listOf(
                    ChatItem.Message(
                        "assistant",
                        "",
                        reasoning = "Inspect the repository",
                        reasoningIsSummary = true,
                    ),
                    ChatItem.Tool("1", "terminal", "completed"),
                )
            )

        val tool = items.single() as ChatItem.Tool
        assertEquals("Inspect the repository", tool.reasoning)
        assertTrue(tool.reasoningIsSummary)
    }

    @Test
    fun reasoningOnlyMessageAttachesToTheFollowingParallelRound() {
        val parallel =
            ChatItem.ParallelToolGroup(
                "batch",
                listOf(
                    ChatItem.Tool("1", "terminal", "completed"),
                    ChatItem.Tool("2", "read_file", "completed"),
                ),
            )

        val items =
            attachReasoningToToolOperations(
                listOf(ChatItem.Message("assistant", "", reasoning = "Read both files"), parallel)
            )

        val attached = items.single() as ChatItem.ParallelToolGroup
        assertEquals("Read both files", attached.reasoning)
        assertFalse(attached.reasoningIsSummary)
    }

    @Test
    fun groupedToolsExposeTheLastAttachedReasoningInTheirHeader() {
        val rounds =
            (1..4).flatMap { round ->
                listOf<ChatItem>(
                    ChatItem.Message(
                        "assistant",
                        "",
                        reasoning = "Plan $round",
                        reasoningIsSummary = true,
                    ),
                    ChatItem.Tool("$round", "terminal", "completed"),
                )
            }

        val group =
            groupTimeline(attachReasoningToToolOperations(rounds)).single() as ChatItem.ToolGroup

        assertEquals(4, group.callCount)
        assertEquals(
            "Plan 4" to true,
            group.operations.asReversed().firstNotNullOf(::operationReasoning),
        )
    }

    @Test
    fun completedParallelGroupMovesIntoSummaryAtomicallyAndCountsChildren() {
        val parallel =
            ChatItem.ParallelToolGroup(
                "batch-1",
                listOf(
                    ChatItem.Tool("1", "terminal", "completed", result = "a"),
                    ChatItem.Tool("2", "terminal", "completed", result = "b"),
                ),
            )
        val items =
            listOf<ChatItem>(
                ChatItem.Tool("0", "read_file", "completed", result = "x"),
                parallel,
                ChatItem.Tool("3", "patch", "completed", result = "y"),
            )
        val summary = groupTimeline(items, minimumGroupSize = 4).single() as ChatItem.ToolGroup
        assertEquals(items, summary.operations)
        assertEquals(4, summary.callCount)
        assertEquals(3, summary.roundCount)
        assertEquals(
            linkedMapOf("read_file" to 1, "terminal" to 2, "patch" to 1),
            toolCountBreakdown(summary.operations),
        )
    }

    @Test
    fun activeParallelGroupNeverMovesIntoCompletedSummary() {
        val active =
            ChatItem.ParallelToolGroup(
                "batch-1",
                listOf(
                    ChatItem.Tool("1", "terminal", "completed", result = "a"),
                    ChatItem.Tool("2", "read_file", "running", arguments = "b"),
                ),
            )
        val blocks =
            groupTimeline(
                listOf(
                    ChatItem.Tool("a", "patch", "completed", result = ""),
                    ChatItem.Tool("b", "patch", "completed", result = ""),
                    ChatItem.Tool("c", "patch", "completed", result = ""),
                    ChatItem.Tool("d", "patch", "completed", result = ""),
                    active,
                )
            )
        assertTrue(blocks.first() is ChatItem.ToolGroup)
        assertEquals(active, blocks.last())
    }

    @Test
    fun equivalentServerUrlsShareOneNormalizedNamespace() {
        assertEquals(
            "https://example.com",
            ConnectionConfig(" HTTPS://EXAMPLE.COM:443/ ").normalizedBaseUrl,
        )
        assertEquals(
            "http://example.com",
            ConnectionConfig("http://Example.Com:80").normalizedBaseUrl,
        )
        assertEquals(
            "https://example.com:8443",
            ConnectionConfig("https://EXAMPLE.com:8443/").normalizedBaseUrl,
        )
    }

    @Test
    fun insecurePublicHttpEndpointIsRejected() {
        assertTrue(ConnectionConfig("https://example.com").isAllowedEndpoint())
        assertTrue(ConnectionConfig("http://192.168.1.2:9119").isAllowedEndpoint())
        assertTrue(ConnectionConfig("http://100.90.1.2:9119").isAllowedEndpoint())
        assertTrue(ConnectionConfig("http://server.tail1234.ts.net:9119").isAllowedEndpoint())
        assertTrue(!ConnectionConfig("http://example.com").isAllowedEndpoint())
        assertTrue(!ConnectionConfig("https://user:pass@example.com").isAllowedEndpoint())
        assertTrue(!ConnectionConfig("https://example.com/hermes").isAllowedEndpoint())
        assertTrue(!ConnectionConfig("https://example.com?token=secret").isAllowedEndpoint())
    }

    @Test
    fun sessionDecodesItsPersistedModel() {
        val session =
            HarnessSession.fromJson(
                buildJsonObject {
                    put("id", "chat")
                    put("model", "deep")
                }
            )

        assertEquals("deep", session.model)
    }

    @Test
    fun sessionActivityIsDecodedForLiveIndicators() {
        val session =
            HarnessSession.fromJson(
                buildJsonObject {
                    put("id", "live")
                    put("title", "Live elsewhere")
                    put("active", true)
                }
            )
        assertTrue(session.active)
    }

    @Test
    fun switchingSessionsRestoresEachSessionsModelAndNeverCarriesThePreviousSelection() {
        val catalog =
            ModelCatalog(
                selected = ModelSelection("api_server", "fast"),
                providers =
                    listOf(
                        dev.qelg.harnessandroid.data.ModelProvider(
                            "api_server",
                            "Hermes API Server",
                            listOf(
                                dev.qelg.harnessandroid.data.ModelOption("default"),
                                dev.qelg.harnessandroid.data.ModelOption("fast"),
                                dev.qelg.harnessandroid.data.ModelOption("deep"),
                            ),
                        )
                    ),
            )

        assertEquals(
            ModelSelection("api_server", "deep"),
            modelCatalogForSession(catalog, HarnessSession("b", "B", model = "deep")).selected,
        )
        assertEquals(
            ModelSelection("api_server", "default"),
            modelCatalogForSession(catalog, HarnessSession("c", "C")).selected,
        )
    }

    @Test
    fun resumedLineageUsesTheLatestCompressionChildsModelUnlessExplicitlyOverridden() {
        val sessions =
            listOf(
                HarnessSession("root", "Chat", model = "fast"),
                HarnessSession("tip", "Chat", parentSessionId = "root", model = "deep"),
            )

        assertEquals("deep", sessionModelForLineage("root", "tip", sessions, emptyMap()))
        assertEquals(
            "default",
            sessionModelForLineage("root", "tip", sessions, mapOf("root" to "default")),
        )
    }

    @Test
    fun serverRefreshCannotOverwriteAnUnpersistedExplicitModelSelection() {
        val staleServerSessions =
            listOf(
                HarnessSession("a", "A", model = "fast"),
                HarnessSession("b", "B", model = "deep"),
            )

        assertEquals(
            listOf("deep", "deep"),
            applySessionModelOverrides(staleServerSessions, mapOf("a" to "deep")).map { it.model },
        )
    }

    @Test
    fun explicitModelSelectionOnlyChangesTheCurrentSession() {
        val sessions =
            listOf(
                HarnessSession("a", "A", model = "fast"),
                HarnessSession("b", "B", model = "deep"),
            )

        assertEquals(
            listOf("default", "deep"),
            sessionsWithModelSelection(sessions, "a", ModelSelection("api_server", "default")).map {
                it.model
            },
        )
    }

    @Test
    fun modelCatalogDecodesConfiguredProvidersAndUnavailableModels() {
        val payload =
            Json.parseToJsonElement(
                    """{
                    "model":"gpt-5.6-sol",
                    "provider":"openai-codex",
                    "thinking_level":"low",
                    "providers":[
                      {"slug":"openai-codex","name":"OpenAI Codex","authenticated":true,
                       "models":["gpt-5.6-sol","gpt-5.5"]},
                      {"slug":"nous","name":"Nous Portal","authenticated":true,
                       "models":["Hermes-4.3-36B"],"unavailable_models":["Hermes-4.3-36B"]},
                      {"slug":"anthropic","name":"Anthropic","authenticated":false,
                       "models":["claude-sonnet-4.6"]}
                    ]
                }"""
                )
                .jsonObject

        val catalog = ModelCatalog.fromJson(payload)

        assertEquals(
            ModelSelection("openai-codex", "gpt-5.6-sol", ThinkingLevel.Low),
            catalog.selected,
        )
        assertEquals(listOf("openai-codex", "nous"), catalog.providers.map { it.slug })
        assertTrue(catalog.providers[1].models.single().unavailable)
    }

    @Test
    fun modelCatalogFiltersModelsByProviderNameAndModelId() {
        val catalog =
            ModelCatalog(
                providers =
                    listOf(
                        dev.qelg.harnessandroid.data.ModelProvider(
                            "anthropic",
                            "Anthropic",
                            listOf(dev.qelg.harnessandroid.data.ModelOption("claude-sonnet-4.6")),
                        ),
                        dev.qelg.harnessandroid.data.ModelProvider(
                            "openai-codex",
                            "OpenAI Codex",
                            listOf(dev.qelg.harnessandroid.data.ModelOption("gpt-5.6-sol")),
                        ),
                    )
            )

        assertEquals(listOf("anthropic"), catalog.filtered("ANTHROPIC").map { it.slug })
        assertEquals(listOf("openai-codex"), catalog.filtered("5.6-sol").map { it.slug })
    }

    @Test
    fun sessionInfoKeepsThinkingAndReasoningDisplayPreferences() {
        val current =
            ModelSelection(
                "chatgpt-codex",
                "old-model",
                ThinkingLevel.High,
                reasoningSummary = true,
            )

        assertEquals(
            ModelSelection(
                "chatgpt-codex",
                "new-model",
                ThinkingLevel.High,
                reasoningSummary = true,
            ),
            modelSelectionFromSessionInfo("chatgpt-codex", "new-model", current),
        )
    }

    @Test
    fun modelSwitchIsExplicitlySessionScoped() {
        assertEquals(
            "gpt-5.6-sol --provider openai-codex --session",
            modelSwitchValue(ModelSelection("openai-codex", "gpt-5.6-sol")),
        )
    }

    @Test
    fun markdownLinksOnlyAllowExplicitSafeSchemes() {
        assertTrue(isSafeExternalUrl("https://example.com"))
        assertTrue(isSafeExternalUrl("mailto:person@example.com"))
        assertTrue(!isSafeExternalUrl("javascript:alert(1)"))
        assertTrue(!isSafeExternalUrl("intent://settings"))
        assertTrue(!isSafeExternalUrl("//example.com"))
    }

    @Test
    fun encryptedPushRequiresHttpsEnrollment() {
        assertTrue(ConnectionConfig("https://harness.example").supportsEncryptedPush())
        assertFalse(ConnectionConfig("http://harness.example.ts.net").supportsEncryptedPush())
    }
}
