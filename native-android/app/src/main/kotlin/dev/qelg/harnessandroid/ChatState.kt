package dev.qelg.harnessandroid

import dev.qelg.harnessandroid.data.*
import dev.qelg.harnessandroid.voice.WhisperCpuConfig
import dev.qelg.harnessandroid.voice.WhisperModel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@JvmInline value class EventId(val value: Long)

@JvmInline value class LoadId(val value: Long)

/** Valid connection phases; configuration remains local UI knowledge. */
sealed interface ConnectionState {
    data object NotConfigured : ConnectionState

    data object Connecting : ConnectionState

    data object Connected : ConnectionState

    data class Reconnecting(val attempt: Int) : ConnectionState

    data class Failed(val message: ErrorMessage) : ConnectionState
}

sealed interface ChildrenLoadState {
    data object NotLoaded : ChildrenLoadState

    data class Loading(val loadId: LoadId, val boundary: EventId?) : ChildrenLoadState

    data class Loaded(val boundary: EventId?) : ChildrenLoadState

    data class Failed(val boundary: EventId?, val message: ErrorMessage) : ChildrenLoadState
}

/** Per-session local interaction state. It deliberately contains neither messages nor queues. */
data class SessionUiState(
    val draft: String = "",
    val submission: DraftSubmission? = null,
    /** A direct POST was accepted, but its authoritative running state has not arrived yet. */
    val awaitingRunStart: Boolean = false,
    val secretDraft: String = "",
    val uploadingSecret: Boolean = false,
) {
    val sending: Boolean
        get() = submission != null || awaitingRunStart
}

data class ObservedValue<T>(val value: T, val observedThrough: EventId?)

sealed interface SynchronizedData<out T> {
    val value: T

    data class PendingHistory<T>(
        override val value: T,
        val liveAfterExclusive: EventId?,
        val loadId: LoadId,
        val error: ErrorMessage? = null,
    ) : SynchronizedData<T>

    data class PendingGap<T>(
        override val value: T,
        val completeThroughInclusive: EventId,
        val liveAfterExclusive: EventId,
        val loadId: LoadId,
        val error: ErrorMessage? = null,
    ) : SynchronizedData<T>

    data class Complete<T>(override val value: T) : SynchronizedData<T>
}

data class SessionSummary(
    val updatedAt: String? = null,
    val source: String? = null,
    val preview: String? = null,
    /** Runtime routing is operational metadata, not a second session authority. */
    val runtimeId: String? = null,
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
)

/** Transient transport-only entries are keyed by their run/message identity, never a timeline. */
data class TransientOverlayKey(val kind: String, val identity: String)

/** A durable event retained verbatim for details and causation lookup. */
data class RawSessionEvent(val sourceId: EventId, val raw: JsonObject)

/** The server's canonical message projection, tied to its durable source event. */
data class MessageProjection(val sourceId: EventId, val raw: JsonObject)

sealed interface SessionLoadPart {
    data object Pending : SessionLoadPart

    data object Succeeded : SessionLoadPart

    data class Failed(val error: ErrorMessage) : SessionLoadPart
}

/** The two independent requests made when opening a session share one generation barrier. */
data class SessionLoadBarrier(
    val loadId: LoadId,
    val messages: SessionLoadPart,
    val events: SessionLoadPart,
) {
    val complete: Boolean
        get() = messages is SessionLoadPart.Succeeded && events is SessionLoadPart.Succeeded
}

data class SessionContent(
    val rawEventsById: Map<EventId, RawSessionEvent> = emptyMap(),
    val canonicalMessagesBySourceEventId: Map<EventId, MessageProjection> = emptyMap(),
    val transientOverlaysByKey: Map<TransientOverlayKey, ChatItem> = emptyMap(),
    val eventDetails: SynchronizedData<List<SessionEvent>?> = SynchronizedData.Complete(null),
    /** Present while opening this session; completion requires both messages and events. */
    val loadBarrier: SessionLoadBarrier? = null,
    val liveUsage: LiveTokenUsage? = null,
)

data class SessionData(
    val id: SessionId,
    /** False only for a durable-event placeholder awaiting session/children authority. */
    val resolved: Boolean = true,
    val name: ObservedValue<String>,
    val parentSessionId: ObservedValue<SessionId?>,
    val tags: ObservedValue<Set<String>>,
    val summary: ObservedValue<SessionSummary>,
    /** Selection belongs to this session; the provider catalog is only shared resources. */
    val modelSelection: ObservedValue<ModelSelection?> = ObservedValue(null, null),
    val state: SynchronizedData<HarnessSessionState?>,
    val content: SynchronizedData<SessionContent>? = null,
    val children: ChildrenLoadState = ChildrenLoadState.NotLoaded,
)

data class RequestState<T>(
    val value: T? = null,
    val loading: Boolean = false,
    val error: ErrorMessage? = null,
)

data class HarnessResources(
    val containers: RequestState<List<HarnessContainer>> = RequestState(),
    val models: RequestState<ModelCatalog> = RequestState(value = ModelCatalog()),
    val chatGptUsage: RequestState<ChatGptUsage> = RequestState(),
)

data class HarnessState(
    val connection: ConnectionState = ConnectionState.NotConfigured,
    /** highest durable cursor fully reduced */
    val lastAppliedEventId: EventId? = null,
    val sessionsById: Map<SessionId, SessionData> = emptyMap(),
    val resources: HarnessResources = HarnessResources(),
)

data class PendingSecret(
    val eventId: Long,
    val identifier: String,
    val description: String,
    val container: String,
)

data class LocalUiState(
    val configured: Boolean = false,
    val search: String = "",
    val selectedSessionId: SessionId? = null,
    val treeParentSessionId: SessionId? = null,
    val sessionUi: Map<SessionId, SessionUiState> = emptyMap(),
    val pendingSecrets: Map<SessionId, PendingSecret> = emptyMap(),
    val unreadCounts: Map<SessionId, Int> = emptyMap(),
    val readUpdates: Map<SessionId, String> = emptyMap(),
    val deletingContainerIds: Set<String> = emptySet(),
    val voiceRecording: Boolean = false,
    val transcribing: Boolean = false,
    val transcriptionStatus: String? = null,
    val transcriptionProgress: Float? = null,
    val transcriptionProgressLabel: String? = null,
    val transcriptionElapsedMs: Long? = null,
    val transcriptionText: String? = null,
    val voiceTargetSessionId: SessionId? = null,
    val whisperModel: WhisperModel = WhisperModel.Base,
    val whisperThreadCount: Int = WhisperCpuConfig.AUTOMATIC,
    val approval: ApprovalRequest? = null,
    val clarify: ClarifyRequest? = null,
    val error: ErrorMessage? = null,
    val reconnectSeconds: Int? = null,
    val updateState: UpdateState = UpdateState(),
    val showArchived: Boolean = false,
    val showReasoning: Boolean = true,
)

data class ChatUiState(
    val ui: LocalUiState = LocalUiState(),
    val harness: HarnessState = HarnessState(),
)

fun sessionId(value: String): SessionId = SessionId(value)

fun eventId(value: Long?): EventId? = value?.let(::EventId)

fun loadId(value: Long): LoadId = LoadId(value)

/** API-boundary conversion. HarnessSession remains a transport DTO only. */
fun sessionDataFromTransport(
    candidate: HarnessSession,
    stateSynchronization: SynchronizedData<HarnessSessionState?>? = null,
): SessionData {
    val observed = eventId(candidate.eventId)
    return SessionData(
        id = SessionId(candidate.id),
        name = ObservedValue(candidate.title, observed),
        parentSessionId = ObservedValue(candidate.parentSessionId?.let(::SessionId), observed),
        tags = ObservedValue(candidate.tags, observed),
        summary = ObservedValue(candidate.toSummary(), observed),
        state =
            stateSynchronization
                ?: SynchronizedData.Complete(
                    candidate.sessionState
                        ?: candidate.active
                            .takeIf { it }
                            ?.let {
                                HarnessSessionState(
                                    sessionId = candidate.id,
                                    state = "running",
                                    eventId = candidate.eventId,
                                )
                            }
                ),
    )
}

/**
 * Retain cheap durable facts for a session observed before its authoritative parent/name row.
 * Content deliberately stays null: an unopened placeholder must not accumulate history.
 */
fun unresolvedSessionData(id: SessionId, observed: EventId?): SessionData =
    SessionData(
        id = id,
        resolved = false,
        name = ObservedValue("", observed),
        parentSessionId = ObservedValue(null, observed),
        tags = ObservedValue(emptySet(), observed),
        summary = ObservedValue(SessionSummary(), observed),
        state = SynchronizedData.Complete(null),
    )

/**
 * A container proves that a session can be opened, but not that it is a root session. Keep it
 * unresolved until a snapshot or child response supplies parent authority while allocating content
 * so opening it can hydrate both history resources.
 */
fun sessionDataFromContainer(container: HarnessContainer): SessionData =
    unresolvedSessionData(SessionId(container.sessionId), null)
        .copy(
            name = ObservedValue(containerSessionTitle(container), null),
            content = SynchronizedData.Complete(SessionContent()),
        )

fun HarnessSession.toSummary() =
    SessionSummary(
        updatedAt,
        source,
        preview,
        runtimeId,
        endReason,
        model,
        inputTokens,
        outputTokens,
        cacheReadTokens,
        cacheWriteTokens,
        reasoningTokens,
        estimatedCostUsd,
        actualCostUsd,
        apiCallCount,
        cumulativeTokenUsage,
    )

/** Deliberate presentation type; it is not retained in [ChatUiState]. */
data class SessionView(
    val id: SessionId,
    val title: String,
    val updatedAt: String?,
    val source: String?,
    val preview: String?,
    val active: Boolean,
    val runtimeId: String?,
    val parentSessionId: SessionId?,
    val tags: Set<String>,
    val endReason: String?,
    val model: String?,
    val sessionState: HarnessSessionState?,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val reasoningTokens: Long,
    val estimatedCostUsd: Double?,
    val actualCostUsd: Double?,
    val apiCallCount: Int,
    val cumulativeTokenUsage: CumulativeTokenUsage?,
)

fun SessionData.toView(): SessionView =
    SessionView(
        id,
        name.value,
        summary.value.updatedAt,
        summary.value.source,
        summary.value.preview,
        state.value?.running == true,
        summary.value.runtimeId,
        parentSessionId.value,
        tags.value,
        summary.value.endReason,
        modelSelection.value?.model ?: summary.value.model,
        state.value,
        summary.value.inputTokens,
        summary.value.outputTokens,
        summary.value.cacheReadTokens,
        summary.value.cacheWriteTokens,
        summary.value.reasoningTokens,
        summary.value.estimatedCostUsd,
        summary.value.actualCostUsd,
        summary.value.apiCallCount,
        summary.value.cumulativeTokenUsage,
    )

/** Pure view selectors. They create presentation values, not retained duplicate state. */
fun overviewSessions(state: ChatUiState): List<SessionView> =
    state.harness.sessionsById.values
        .asSequence()
        .filter(SessionData::resolved)
        .map(SessionData::toView)
        .toList()
        .sortedWith(
            compareByDescending<SessionView> { it.active }
                .thenByDescending { it.updatedAt?.let(::parseSessionUpdateInstantForView) }
        )

fun selectedSessionId(state: ChatUiState): SessionId? = state.ui.selectedSessionId

fun selectedSession(state: ChatUiState): SessionView? =
    selectedSessionId(state)?.let { state.harness.sessionsById[it]?.toView() }

fun sessionUi(state: ChatUiState, id: SessionId): SessionUiState =
    state.ui.sessionUi[id] ?: SessionUiState()

fun sessionContent(data: SessionData?): SessionContent? = data?.content?.value

fun sessionTimeline(state: ChatUiState, id: SessionId): List<ChatItem> {
    val content = sessionContent(state.harness.sessionsById[id]) ?: return emptyList()
    return messagesFromHistoryRows(
        content.canonicalMessagesBySourceEventId
            .toSortedMap(compareBy(EventId::value))
            .values
            .map(MessageProjection::raw)
            .toList()
    ) +
        content.transientOverlaysByKey
            .toSortedMap(compareBy<TransientOverlayKey> { it.kind }.thenBy { it.identity })
            .values
}

fun selectedTimeline(state: ChatUiState): List<ChatItem> =
    selectedSessionId(state)?.let { sessionTimeline(state, it) }.orEmpty()

fun directChildSessions(state: ChatUiState, parentId: SessionId): List<SessionView> =
    overviewSessions(state).filter { it.parentSessionId == parentId }

private fun parseSessionUpdateInstantForView(value: String): java.time.Instant? =
    runCatching { java.time.Instant.parse(value) }.getOrNull()
        ?: value.toDoubleOrNull()?.let { java.time.Instant.ofEpochMilli((it * 1000).toLong()) }

fun filterSessionViews(sessions: List<SessionView>, query: String): List<SessionView> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return sessions
    return sessions.filter {
        it.title.lowercase().contains(needle) ||
            it.preview?.lowercase()?.contains(needle) == true ||
            it.source?.lowercase()?.contains(needle) == true ||
            it.id.value.lowercase().contains(needle)
    }
}

fun visibleSessionViews(sessions: List<SessionView>, showArchived: Boolean): List<SessionView> =
    sessions.filter { showArchived || it.sessionState?.archived != true }

fun prioritizeSessionViewsWithDrafts(
    sessions: List<SessionView>,
    drafts: Map<SessionId, SessionUiState>,
): List<SessionView> {
    val (live, inactive) = sessions.partition(SessionView::active)
    fun withDraft(values: List<SessionView>) =
        values.partition { drafts[it.id]?.draft?.isNotBlank() == true }
    val (liveDraft, liveOther) = withDraft(live)
    val (inactiveDraft, inactiveOther) = withDraft(inactive)
    return liveDraft + liveOther + inactiveDraft + inactiveOther
}

fun rootSessionViews(sessions: List<SessionView>): List<SessionView> =
    sessions.filter { it.parentSessionId == null }

data class SessionViewTreeNode(val session: SessionView, val depth: Int)

fun sessionViewTreeWithDepth(
    sessions: List<SessionView>,
    parentId: SessionId,
): List<SessionViewTreeNode> {
    val children = sessions.groupBy(SessionView::parentSessionId)
    val result = mutableListOf<SessionViewTreeNode>()
    fun walk(id: SessionId, depth: Int) {
        val session = sessions.firstOrNull { it.id == id } ?: return
        result += SessionViewTreeNode(session, depth)
        children[id].orEmpty().forEach { walk(it.id, depth + 1) }
    }
    walk(parentId, 0)
    return result
}

fun childViewCount(sessions: List<SessionView>, sessionId: SessionId): Int {
    val children = sessions.groupBy(SessionView::parentSessionId)
    val byId = sessions.associateBy(SessionView::id)
    val visited = mutableSetOf<SessionId>()
    val queue = ArrayDeque(children[sessionId].orEmpty().map(SessionView::id))
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        if (!visited.add(id)) continue
        queue.addAll(children[id].orEmpty().map(SessionView::id))
    }
    return visited.count { id -> "namer" !in byId[id]?.tags.orEmpty() }
}

fun isSessionViewRead(session: SessionView, readAt: String?): Boolean {
    val updated = session.updatedAt?.let(::parseSessionUpdateInstantForView) ?: return false
    val read = readAt?.let(::parseSessionUpdateInstantForView) ?: return false
    return read >= updated
}

fun selectedSessionContentComplete(state: ChatUiState): Boolean =
    selectedSessionId(state)?.let {
        state.harness.sessionsById[it]?.content is SynchronizedData.Complete<*>
    } == true

private fun canonicalRowToItem(row: JsonObject): ChatItem =
    ChatItem.Message(
        id =
            row["id"]?.jsonPrimitive?.contentOrNull
                ?: row["source_event_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        role = row.string("role") ?: "assistant",
        text = (row["content"] ?: row["text"])?.jsonPrimitive?.contentOrNull.orEmpty(),
    )

/** Queue projections are canonical durable events; a POST never creates one locally. */
internal fun queuedMessagesFor(state: ChatUiState, id: SessionId): List<QueuedMessage> {
    val content = sessionContent(state.harness.sessionsById[id]) ?: return emptyList()
    fun queuedEventId(row: JsonObject): EventId? =
        (row["queued_message_event_id"]
                ?: (row["metadata"] as? JsonObject)?.get("queued_message_event_id"))
            ?.jsonPrimitive
            ?.longOrNull
            ?.let(::EventId)
    val delivered =
        content.rawEventsById.values
            .map(RawSessionEvent::raw)
            .filter { it.string("name") == "chat.message.user.created" }
            .mapNotNull {
                it["causation_id"]?.jsonPrimitive?.longOrNull ?: queuedEventId(it)?.value
            }
            .map(::EventId)
            .toSet() +
            content.canonicalMessagesBySourceEventId.values
                .map(MessageProjection::raw)
                .filter { it.string("event_name") == "chat.message.user.created" }
                .mapNotNull(::queuedEventId)
                .toSet()
    return content.canonicalMessagesBySourceEventId
        .toSortedMap(compareBy(EventId::value))
        .mapNotNull { (source, projection) ->
            val row = projection.raw
            if (row.string("event_name") != "queued.message" || source in delivered) null
            else
                QueuedMessage(
                    id = source.value,
                    text = (row["content"] ?: row["text"])?.jsonPrimitive?.contentOrNull.orEmpty(),
                    mode =
                        MessageQueueMode.fromApiValue(row.string("queue_mode"))
                            ?: MessageQueueMode.AfterResponse,
                    expectedUserMessageOccurrence = 0,
                    submitting = false,
                )
        }
}

fun selectedModelCatalog(state: ChatUiState): ModelCatalog {
    val selected =
        selectedSessionId(state)?.let { state.harness.sessionsById[it]?.modelSelection?.value }
    return (state.harness.resources.models.value ?: ModelCatalog()).copy(selected = selected)
}

fun derivedUsageFor(state: ChatUiState, id: SessionId): TokenUsageState? {
    val record = state.harness.sessionsById[id] ?: return null
    val content = sessionContent(record)
    val snapshot =
        harnessUsageSnapshot(
            content
                ?.canonicalMessagesBySourceEventId
                ?.toSortedMap(compareBy(EventId::value))
                ?.values
                ?.map(MessageProjection::raw)
                ?.toList()
                .orEmpty()
        )
    val cumulative = snapshot.cumulative ?: record.summary.value.cumulativeTokenUsage
    val live = content?.liveUsage
    return if (cumulative == null && snapshot.context == null && live == null) null
    else TokenUsageState(context = snapshot.context, cumulative = cumulative, live = live)
}

/** Pure server merge operations; HTTP and websocket arrivals use the same normalized records. */
object ChatReducer {
    /** Merge container evidence without promoting an unknown child to an authoritative root. */
    fun mergeContainerSession(state: ChatUiState, container: HarnessContainer): ChatUiState {
        val candidate = sessionDataFromContainer(container)
        val old =
            state.harness.sessionsById[candidate.id] ?: return mergeSession(state, candidate, null)
        val merged =
            old.copy(
                // Container metadata is a title fallback only; it must never overwrite server
                // authority already present in a snapshot, child response, or durable event.
                name =
                    if (old.name.value.isBlank()) old.name.copy(value = candidate.name.value)
                    else old.name,
                content = old.content ?: candidate.content,
            )
        return state.copy(
            harness =
                state.harness.copy(
                    sessionsById = state.harness.sessionsById + (candidate.id to merged)
                )
        )
    }

    /**
     * A snapshot versions its summary fields, but is never a durable-event acknowledgement.
     * Refreshes can overlap a socket frame that is below the snapshot cursor and that frame must
     * still be reduced.
     */
    fun mergeSnapshot(
        state: ChatUiState,
        rows: List<SessionData>,
        snapshotCursor: Long?,
    ): ChatUiState = rows.fold(state) { current, row -> mergeSession(current, row, snapshotCursor) }

    /**
     * The initial /sessions response establishes the websocket resume point before a socket is
     * started. This must not be used by refreshes or after a durable stream cursor exists.
     */
    fun establishSubscriptionBaseline(state: ChatUiState, cursor: Long?): ChatUiState {
        val baseline = eventId(cursor) ?: return state
        if (state.harness.lastAppliedEventId != null) return state
        return state.copy(harness = state.harness.copy(lastAppliedEventId = baseline))
    }

    fun mergeSessions(state: ChatUiState, additions: List<SessionData>): ChatUiState =
        additions.fold(state) { current, row ->
            mergeSession(current, row, row.name.observedThrough?.value)
        }

    fun mergeSession(state: ChatUiState, candidate: SessionData, version: Long?): ChatUiState {
        if (candidate.id.value.isBlank()) return state
        val id = candidate.id
        val old = state.harness.sessionsById[id]
        val observed = eventId(version)
        if (
            old != null &&
                old.resolved &&
                (observed == null ||
                    (old.name.observedThrough?.value ?: Long.MIN_VALUE) > observed.value)
        ) {
            return candidate.state.value?.let {
                mergeSessionState(state, it.copy(sessionId = candidate.id.value))
            } ?: state
        }
        val base = old ?: candidate
        val oldSummary = base.summary.value
        val incomingSummary = candidate.summary.value
        val summary =
            incomingSummary.copy(
                updatedAt = incomingSummary.updatedAt ?: oldSummary.updatedAt,
                endReason = incomingSummary.endReason ?: oldSummary.endReason,
                cumulativeTokenUsage =
                    incomingSummary.cumulativeTokenUsage ?: oldSummary.cumulativeTokenUsage,
            )
        val newest = newestSessionState(base.state.value, candidate.state.value)
        // A placeholder can have a newer state/rename event than a child/snapshot response. That
        // response still resolves its parent identity, while the newer facts keep precedence.
        val hydrate = old != null && !old.resolved && candidate.resolved
        val takeName =
            old == null ||
                (observed != null &&
                    (base.name.observedThrough?.value ?: Long.MIN_VALUE) <= observed.value) ||
                (hydrate && base.name.value.isBlank())
        val takeParent =
            old == null ||
                (observed != null &&
                    (base.parentSessionId.observedThrough?.value ?: Long.MIN_VALUE) <=
                        observed.value) ||
                hydrate
        val takeTags =
            old == null ||
                (observed != null &&
                    (base.tags.observedThrough?.value ?: Long.MIN_VALUE) <= observed.value) ||
                hydrate
        val record =
            base.copy(
                resolved = base.resolved || candidate.resolved,
                name =
                    if (takeName)
                        ObservedValue(
                            candidate.name.value.ifBlank { base.name.value },
                            observed ?: base.name.observedThrough,
                        )
                    else base.name,
                parentSessionId =
                    if (takeParent)
                        ObservedValue(
                            candidate.parentSessionId.value,
                            observed ?: base.parentSessionId.observedThrough,
                        )
                    else base.parentSessionId,
                tags =
                    if (takeTags)
                        ObservedValue(
                            if (candidate.tags.value.isEmpty()) base.tags.value
                            else candidate.tags.value,
                            observed ?: base.tags.observedThrough,
                        )
                    else base.tags,
                summary = ObservedValue(summary, observed ?: base.summary.observedThrough),
                modelSelection =
                    ObservedValue(
                        candidate.modelSelection.value ?: base.modelSelection.value,
                        observed ?: base.modelSelection.observedThrough,
                    ),
                state = mergeStateSynchronization(base.state, candidate.state, newest),
            )
        return state.copy(
            harness = state.harness.copy(sessionsById = state.harness.sessionsById + (id to record))
        )
    }

    fun mergeSessionState(state: ChatUiState, value: HarnessSessionState): ChatUiState {
        val id = SessionId(value.sessionId)
        val old =
            state.harness.sessionsById[id] ?: unresolvedSessionData(id, eventId(value.eventId))
        val current = old.state.value
        if (
            value.eventId == null && current?.eventId != null ||
                value.eventId != null && (current?.eventId ?: Long.MIN_VALUE) > value.eventId
        )
            return state
        val next =
            old.copy(
                state = SynchronizedData.Complete(value),
                summary =
                    old.summary.copy(
                        value =
                            old.summary.value.copy(
                                updatedAt = value.updatedAt ?: old.summary.value.updatedAt,
                                endReason = value.outcome ?: old.summary.value.endReason,
                            )
                    ),
            )
        val currentUi = sessionUi(state, id)
        val ui =
            state.ui.copy(
                unreadCounts =
                    if (value.running) state.ui.unreadCounts - id else state.ui.unreadCounts,
                sessionUi =
                    if (currentUi.awaitingRunStart && (value.running || value.finished))
                        state.ui.sessionUi + (id to currentUi.copy(awaitingRunStart = false))
                    else state.ui.sessionUi,
            )
        return state.copy(
            ui = ui,
            harness = state.harness.copy(sessionsById = state.harness.sessionsById + (id to next)),
        )
    }

    /** Mark records whose server state is being authoritatively fetched. */
    fun beginSessionStateSync(
        state: ChatUiState,
        sessionIds: Collection<SessionId>,
        boundary: EventId?,
        loadId: LoadId,
    ): ChatUiState {
        val updates =
            state.harness.sessionsById.mapValues { (id, record) ->
                if (id !in sessionIds || record.state is SynchronizedData.PendingHistory) record
                else
                    record.copy(
                        state =
                            SynchronizedData.PendingHistory(record.state.value, boundary, loadId)
                    )
            }
        return state.copy(harness = state.harness.copy(sessionsById = updates))
    }

    /**
     * Complete only the matching authoritative state fetch; absent rows mean authoritative null.
     */
    fun completeSessionStateSync(
        state: ChatUiState,
        sessionIds: Collection<SessionId>,
        fetched: List<HarnessSessionState>,
        loadId: LoadId,
    ): ChatUiState {
        val matching =
            sessionIds.filter { id ->
                (state.harness.sessionsById[id]?.state as? SynchronizedData.PendingHistory)
                    ?.loadId == loadId
            }
        if (matching.isEmpty()) return state
        var result = state
        val fetchedById = fetched.associateBy { SessionId(it.sessionId) }
        matching.forEach { id ->
            fetchedById[id]?.let { value -> result = mergeSessionState(result, value) }
            val record = result.harness.sessionsById[id] ?: return@forEach
            val sync = record.state
            // mergeSessionState preserves a newer live event; only this matching load can close it.
            result =
                result.copy(
                    harness =
                        result.harness.copy(
                            sessionsById =
                                result.harness.sessionsById +
                                    (id to
                                        record.copy(state = SynchronizedData.Complete(sync.value)))
                        )
                )
        }
        return result
    }

    /** A failed fallback leaves state authority pending and therefore retryable. */
    fun failSessionStateSync(
        state: ChatUiState,
        sessionIds: Collection<SessionId>,
        loadId: LoadId,
        error: ErrorMessage,
    ): ChatUiState {
        val updates =
            state.harness.sessionsById.mapValues { (id, record) ->
                if (id !in sessionIds) record
                else {
                    val pending = record.state as? SynchronizedData.PendingHistory
                    if (pending?.loadId == loadId) record.copy(state = pending.copy(error = error))
                    else record
                }
            }
        return state.copy(harness = state.harness.copy(sessionsById = updates))
    }

    fun setSessionModelSelection(
        state: ChatUiState,
        sessionId: SessionId,
        selection: ModelSelection?,
        observed: Long? = null,
    ): ChatUiState {
        val old = state.harness.sessionsById[sessionId] ?: return state
        return state.copy(
            harness =
                state.harness.copy(
                    sessionsById =
                        state.harness.sessionsById +
                            (sessionId to
                                old.copy(
                                    modelSelection = ObservedValue(selection, eventId(observed))
                                ))
                )
        )
    }

    fun setRuntimeSessionId(
        state: ChatUiState,
        storedSessionId: String,
        runtimeSessionId: String?,
    ): ChatUiState {
        val id = SessionId(storedSessionId)
        val old = state.harness.sessionsById[id] ?: return state
        return state.copy(
            harness =
                state.harness.copy(
                    sessionsById =
                        state.harness.sessionsById +
                            (id to
                                old.copy(
                                    summary =
                                        old.summary.copy(
                                            value =
                                                old.summary.value.copy(runtimeId = runtimeSessionId)
                                        )
                                ))
                )
        )
    }

    fun appendEventDetail(state: ChatUiState, sessionId: String, event: SessionEvent): ChatUiState =
        updateContent(state, SessionId(sessionId)) { sync ->
            val content = sync?.value ?: return@updateContent sync
            val details = content.eventDetails
            val existing = details.value.orEmpty()
            val merged =
                (existing + event)
                    .distinctBy { it.id ?: Long.MIN_VALUE }
                    .sortedBy { it.id ?: Long.MIN_VALUE }
            syncContentLike(sync, content.copy(eventDetails = SynchronizedData.Complete(merged)))
        }

    fun renameSession(
        state: ChatUiState,
        sessionId: String,
        title: String,
        eventId: Long?,
    ): ChatUiState {
        val id = SessionId(sessionId)
        val record = state.harness.sessionsById[id] ?: unresolvedSessionData(id, eventId(eventId))
        return mergeSession(
            state,
            record.copy(name = ObservedValue(title, eventId(eventId))),
            eventId,
        )
    }

    /** Start a history-only refresh; it does not wait for an event-details request. */
    fun beginHistory(
        state: ChatUiState,
        sessionId: String,
        fromExclusive: Long?,
        generation: Long,
    ): ChatUiState =
        beginContentLoad(
            state,
            sessionId,
            fromExclusive,
            SessionLoadBarrier(
                LoadId(generation),
                SessionLoadPart.Pending,
                SessionLoadPart.Succeeded,
            ),
        )

    /** Opening a session always begins both authoritative requests under one generation. */
    fun beginSessionLoad(
        state: ChatUiState,
        sessionId: String,
        fromExclusive: Long?,
        generation: Long,
    ): ChatUiState =
        beginContentLoad(
            state,
            sessionId,
            fromExclusive,
            SessionLoadBarrier(
                LoadId(generation),
                SessionLoadPart.Pending,
                SessionLoadPart.Pending,
            ),
            beginEventDetails = true,
        )

    private fun beginContentLoad(
        state: ChatUiState,
        sessionId: String,
        fromExclusive: Long?,
        barrier: SessionLoadBarrier,
        beginEventDetails: Boolean = false,
    ): ChatUiState =
        updateContent(state, SessionId(sessionId)) { content ->
            val current = content?.value ?: SessionContent()
            val loaded =
                current.copy(
                    loadBarrier = barrier,
                    eventDetails =
                        if (beginEventDetails)
                            SynchronizedData.PendingHistory(
                                current.eventDetails.value,
                                null,
                                barrier.loadId,
                            )
                        else current.eventDetails,
                )
            if (fromExclusive == null) SynchronizedData.PendingHistory(loaded, null, barrier.loadId)
            else
                SynchronizedData.PendingGap(
                    loaded,
                    state.harness.lastAppliedEventId ?: EventId(fromExclusive),
                    EventId(fromExclusive),
                    barrier.loadId,
                )
        }

    fun failHistory(
        state: ChatUiState,
        sessionId: String,
        generation: Long,
        error: ErrorMessage,
    ): ChatUiState =
        updateContent(state, SessionId(sessionId)) { sync ->
            val barrier = sync?.value?.loadBarrier
            if (barrier?.loadId != LoadId(generation)) return@updateContent sync
            val value =
                sync.value.copy(
                    loadBarrier = barrier.copy(messages = SessionLoadPart.Failed(error))
                )
            when (sync) {
                is SynchronizedData.PendingHistory -> sync.copy(value = value, error = error)
                is SynchronizedData.PendingGap -> sync.copy(value = value, error = error)
                else -> sync
            }
        }

    /** Whether this response still owns the session's history synchronization generation. */
    fun hasMatchingHistoryLoad(state: ChatUiState, sessionId: String, generation: Long): Boolean =
        state.harness.sessionsById[SessionId(sessionId)]?.content?.value?.loadBarrier?.loadId ==
            LoadId(generation)

    fun completeHistory(
        state: ChatUiState,
        sessionId: String,
        rows: List<JsonObject>,
        generation: Long,
    ): ChatUiState =
        updateContent(state, SessionId(sessionId)) { sync ->
            val barrier = sync?.value?.loadBarrier
            if (barrier?.loadId != LoadId(generation)) return@updateContent sync
            val pendingValue = sync.value
            val merged = pendingValue.canonicalMessagesBySourceEventId.toMutableMap()
            rows.forEachIndexed { index, row ->
                val id = rowSourceId(row, index)
                merged[id] = MessageProjection(id, mergeCanonicalRow(merged[id]?.raw, row))
            }
            // History is canonical for this generation. Remove only overlays it identifies;
            // unrelated concurrent run overlays survive.
            val retainedOverlays =
                pendingValue.transientOverlaysByKey.filterValues { overlay ->
                    merged.values.none { projection ->
                        canonicalCoversOverlay(overlay, projection.raw)
                    }
                }
            completeContentLoad(
                sync,
                pendingValue.copy(
                    canonicalMessagesBySourceEventId = merged,
                    transientOverlaysByKey = retainedOverlays,
                    loadBarrier = barrier.copy(messages = SessionLoadPart.Succeeded),
                ),
            )
        }

    fun beginEventDetails(state: ChatUiState, sessionId: String, generation: Long): ChatUiState =
        updateContent(state, SessionId(sessionId)) { sync ->
            val current = sync?.value ?: SessionContent()
            syncContentLike(
                sync,
                current.copy(
                    eventDetails =
                        SynchronizedData.PendingHistory(
                            current.eventDetails.value,
                            null,
                            LoadId(generation),
                        )
                ),
            )
        }

    fun completeEventDetails(
        state: ChatUiState,
        sessionId: String,
        events: List<SessionEvent>,
        generation: Long,
    ): ChatUiState {
        val id = SessionId(sessionId)
        val existing = sessionContent(state.harness.sessionsById[id]) ?: return state
        if (
            (existing.eventDetails as? SynchronizedData.PendingHistory)?.loadId !=
                LoadId(generation)
        )
            return state
        val merged =
            events.fold(state) { current, event ->
                mergeRawEvent(current, id, event.raw, event.id, true)
            }
        return updateContent(merged, id) { sync ->
            val content = sync!!.value
            val barrier = content.loadBarrier
            val updated =
                content.copy(
                    eventDetails = SynchronizedData.Complete(events),
                    loadBarrier =
                        if (barrier?.loadId == LoadId(generation))
                            barrier.copy(events = SessionLoadPart.Succeeded)
                        else barrier,
                )
            completeContentLoad(sync, updated)
        }
    }

    fun failEventDetails(
        state: ChatUiState,
        sessionId: String,
        generation: Long,
        error: ErrorMessage,
    ): ChatUiState =
        updateContent(state, SessionId(sessionId)) { sync ->
            val content = sync?.value ?: return@updateContent sync
            val pending =
                content.eventDetails as? SynchronizedData.PendingHistory
                    ?: return@updateContent sync
            if (pending.loadId != LoadId(generation)) return@updateContent sync
            val barrier = content.loadBarrier
            syncContentLike(
                sync,
                content.copy(
                    eventDetails = pending.copy(error = error),
                    loadBarrier =
                        if (barrier?.loadId == LoadId(generation))
                            barrier.copy(events = SessionLoadPart.Failed(error))
                        else barrier,
                ),
            )
        }

    private fun completeContentLoad(
        sync: SynchronizedData<SessionContent>?,
        content: SessionContent,
    ): SynchronizedData<SessionContent> {
        val barrier = content.loadBarrier
        return if (barrier?.complete == true)
            SynchronizedData.Complete(content.copy(loadBarrier = null))
        else syncContentLike(sync, content)
    }

    /** True when replaying this durable record cannot produce another semantic effect. */
    fun hasAppliedDurableEvent(state: ChatUiState, event: GatewayEvent): Boolean {
        if (!event.durable) return false
        val cursor = eventId(event.cursor)
        if (
            cursor != null &&
                (state.harness.lastAppliedEventId?.value ?: Long.MIN_VALUE) >= cursor.value
        )
            return true
        val session = event.sessionId?.let(::SessionId) ?: return false
        val source = event.rawEvent?.get("id")?.jsonPrimitive?.longOrNull ?: event.sourceEventId
        return source != null &&
            source.let(::EventId) in
                sessionContent(state.harness.sessionsById[session])?.rawEventsById.orEmpty()
    }

    fun mergeTransport(state: ChatUiState, event: GatewayEvent): ChatUiState {
        val rawSession = event.sessionId ?: return advanceCursor(state, event.cursor, event.durable)
        val id = SessionId(rawSession)
        var result = state
        if (result.harness.sessionsById[id] == null) {
            result =
                result.copy(
                    harness =
                        result.harness.copy(
                            sessionsById =
                                result.harness.sessionsById +
                                    (id to unresolvedSessionData(id, eventId(event.cursor)))
                        )
                )
        }
        val record = result.harness.sessionsById.getValue(id)
        // Unknown-session placeholders retain state/name facts but no event detail, projections,
        // or overlays until authoritative session/children data makes them visible/openable.
        if (record.resolved || record.content != null) {
            result =
                mergeRawEvent(
                    result,
                    id,
                    event.rawEvent,
                    event.rawEvent?.get("id")?.jsonPrimitive?.longOrNull,
                    event.durable,
                )
            event.messageProjection?.let { projection ->
                val source =
                    EventId(
                        event.sourceEventId
                            ?: event.rawEvent?.get("id")?.jsonPrimitive?.longOrNull
                            ?: rowSourceId(projection, 0).value
                    )
                result =
                    updateContent(result, id) { sync ->
                        val content = sync?.value ?: SessionContent()
                        val overlay =
                            content.transientOverlaysByKey.filterValues { item ->
                                !canonicalMatches(item, event.type, projection)
                            }
                        if (!event.durable)
                            syncContentLike(
                                sync,
                                content.copy(
                                    transientOverlaysByKey =
                                        overlay +
                                            (overlayKey(canonicalRowToItem(projection)) to
                                                canonicalRowToItem(projection))
                                ),
                            )
                        else
                            syncContentLike(
                                sync,
                                content.copy(
                                    canonicalMessagesBySourceEventId =
                                        content.canonicalMessagesBySourceEventId +
                                            (source to
                                                MessageProjection(
                                                    source,
                                                    mergeCanonicalRow(
                                                        content.canonicalMessagesBySourceEventId[
                                                                source]
                                                            ?.raw,
                                                        projection,
                                                    ),
                                                )),
                                    transientOverlaysByKey = overlay,
                                ),
                            )
                    }
                if (event.durable && event.type == "message.user")
                    result = acknowledgeSubmissionFromProjection(result, rawSession, projection)
            }
        }
        return advanceCursor(result, event.cursor, event.durable)
    }

    fun acknowledgeSubmission(
        state: ChatUiState,
        sessionId: String,
        submission: DraftSubmission,
        projection: JsonObject?,
    ): ChatUiState {
        var result = state
        val id = SessionId(sessionId)
        if (projection != null)
            result =
                updateContent(result, id) { sync ->
                    val content = sync?.value ?: SessionContent()
                    val source = rowSourceId(projection, 0)
                    syncContentLike(
                        sync,
                        content.copy(
                            canonicalMessagesBySourceEventId =
                                content.canonicalMessagesBySourceEventId +
                                    (source to
                                        MessageProjection(
                                            source,
                                            mergeCanonicalRow(
                                                content.canonicalMessagesBySourceEventId[source]
                                                    ?.raw,
                                                projection,
                                            ),
                                        ))
                        ),
                    )
                }
        return finishSubmission(
            result,
            sessionId,
            submission,
            acknowledgeDraft = true,
            awaitRunStart = submission.queueMode == null,
        )
    }

    fun acknowledgeSubmissionFromProjection(
        state: ChatUiState,
        sessionId: String,
        projection: JsonObject,
    ): ChatUiState {
        val submission = sessionUi(state, SessionId(sessionId)).submission ?: return state
        val text =
            (projection["content"] ?: projection["text"])?.jsonPrimitive?.contentOrNull.orEmpty()
        return if (
            text == submission.text &&
                MessageQueueMode.fromApiValue(projection.string("queue_mode")) ==
                    submission.queueMode
        )
            finishSubmission(
                state,
                sessionId,
                submission,
                acknowledgeDraft = true,
                awaitRunStart = submission.queueMode == null,
            )
        else state
    }

    fun finishSubmission(
        state: ChatUiState,
        sessionId: String,
        submission: DraftSubmission,
        acknowledgeDraft: Boolean,
        awaitRunStart: Boolean = false,
    ): ChatUiState {
        val id = SessionId(sessionId)
        val current = sessionUi(state, id)
        if (current.submission != submission) return state
        val authoritativeState = state.harness.sessionsById[id]?.state?.value
        val keepRunStartFence =
            awaitRunStart &&
                authoritativeState?.running != true &&
                authoritativeState?.finished != true
        return state.copy(
            ui =
                state.ui.copy(
                    sessionUi =
                        state.ui.sessionUi +
                            (id to
                                current.copy(
                                    draft =
                                        if (acknowledgeDraft && current.draft == submission.text) ""
                                        else current.draft,
                                    submission = null,
                                    awaitingRunStart = keepRunStartFence,
                                ))
                )
        )
    }

    /** Deltas are presentation-only; their durable source remains in raw event history. */
    fun appendAssistantDelta(
        state: ChatUiState,
        sessionId: String,
        event: GatewayEvent,
    ): ChatUiState {
        val text =
            (event.payload["text"] ?: event.payload["delta"])
                ?.jsonPrimitive
                ?.contentOrNull
                .orEmpty()
        if (text.isEmpty()) return state
        val messageId =
            listOf("message_id", "id").firstNotNullOfOrNull {
                event.payload[it]?.jsonPrimitive?.contentOrNull
            }
        val runId = event.payload["run_id"]?.jsonPrimitive?.contentOrNull
        val sequence = event.payload["sequence"]?.jsonPrimitive?.contentOrNull
        val identity = "$sessionId:${runId ?: messageId ?: sequence ?: "assistant"}"
        return updateContent(state, SessionId(sessionId)) { sync ->
            val content = sync?.value ?: SessionContent()
            val key = TransientOverlayKey("assistant-delta", identity)
            val previous = content.transientOverlaysByKey[key] as? ChatItem.Message
            val next =
                (previous?.copy(text = previous.text + text)
                    ?: ChatItem.Message(
                        role = "assistant",
                        text = text,
                        id = messageId,
                        uiKey = "stream:$identity",
                        pendingCanonical = true,
                    ))
            syncContentLike(
                sync,
                content.copy(
                    transientOverlaysByKey = content.transientOverlaysByKey + (key to next)
                ),
            )
        }
    }

    fun cancelTransientTools(
        state: ChatUiState,
        sessionId: String,
        completedAt: java.time.Instant = java.time.Instant.now(),
    ): ChatUiState =
        updateContent(state, SessionId(sessionId)) { sync ->
            val content = sync?.value ?: return@updateContent sync
            val overlays =
                content.transientOverlaysByKey.mapValues { (_, item) ->
                    cancelRunningOverlayTool(item, completedAt)
                }
            syncContentLike(sync, content.copy(transientOverlaysByKey = overlays))
        }

    fun appendTransientAssistant(state: ChatUiState, sessionId: String, text: String): ChatUiState =
        updateContent(state, SessionId(sessionId)) { sync ->
            val content = sync?.value ?: SessionContent()
            val key = TransientOverlayKey("message", "assistant:$sessionId")
            val current = content.transientOverlaysByKey[key] as? ChatItem.Message
            val next =
                (current?.copy(text = current.text + text)
                    ?: ChatItem.Message(
                        "assistant",
                        text,
                        uiKey = "stream:$sessionId",
                        pendingCanonical = true,
                    ))
            syncContentLike(
                sync,
                content.copy(
                    transientOverlaysByKey = content.transientOverlaysByKey + (key to next)
                ),
            )
        }

    fun addTransientOverlay(state: ChatUiState, sessionId: String, overlay: ChatItem): ChatUiState =
        updateContent(state, SessionId(sessionId)) { sync ->
            val content = sync?.value ?: SessionContent()
            val key = overlayKey(overlay)
            val merged =
                if (overlay is ChatItem.Tool) {
                    val previous = content.transientOverlaysByKey[key]
                    previous?.let { upsertTool(listOf(it), overlay).single() } ?: overlay
                } else overlay
            syncContentLike(
                sync,
                content.copy(
                    transientOverlaysByKey = content.transientOverlaysByKey + (key to merged)
                ),
            )
        }

    fun setLiveUsage(state: ChatUiState, sessionId: String, usage: LiveTokenUsage?): ChatUiState =
        updateContent(state, SessionId(sessionId)) { sync ->
            val content = sync?.value ?: SessionContent()
            syncContentLike(sync, content.copy(liveUsage = usage))
        }

    private fun mergeRawEvent(
        state: ChatUiState,
        id: SessionId,
        raw: JsonObject?,
        rawId: Long?,
        durable: Boolean,
    ): ChatUiState {
        if (!durable || raw == null || rawId == null) return state
        return updateContent(state, id) { sync ->
            val content = sync?.value ?: SessionContent()
            val event = EventId(rawId)
            if (event in content.rawEventsById) syncContentLike(sync, content)
            else
                syncContentLike(
                    sync,
                    content.copy(
                        rawEventsById =
                            content.rawEventsById + (event to RawSessionEvent(event, raw))
                    ),
                )
        }
    }

    private fun advanceCursor(state: ChatUiState, cursor: Long?, durable: Boolean): ChatUiState {
        val event = eventId(cursor)
        return if (
            !durable ||
                event == null ||
                (state.harness.lastAppliedEventId?.value ?: Long.MIN_VALUE) >= event.value
        )
            state
        else state.copy(harness = state.harness.copy(lastAppliedEventId = event))
    }

    private fun updateContent(
        state: ChatUiState,
        id: SessionId,
        transform: (SynchronizedData<SessionContent>?) -> SynchronizedData<SessionContent>?,
    ): ChatUiState {
        val record = state.harness.sessionsById[id] ?: return state
        if (!record.resolved && record.content == null) return state
        return state.copy(
            harness =
                state.harness.copy(
                    sessionsById =
                        state.harness.sessionsById +
                            (id to record.copy(content = transform(record.content)))
                )
        )
    }

    private fun syncContentLike(
        old: SynchronizedData<SessionContent>?,
        value: SessionContent,
    ): SynchronizedData<SessionContent> =
        when (old) {
            is SynchronizedData.PendingHistory -> old.copy(value = value)
            is SynchronizedData.PendingGap -> old.copy(value = value)
            else -> SynchronizedData.Complete(value)
        }

    private fun mergeStateSynchronization(
        old: SynchronizedData<HarnessSessionState?>,
        incoming: SynchronizedData<HarnessSessionState?>,
        newest: HarnessSessionState?,
    ): SynchronizedData<HarnessSessionState?> =
        when (incoming) {
            is SynchronizedData.PendingHistory -> incoming.copy(value = newest)
            is SynchronizedData.PendingGap -> incoming.copy(value = newest)
            // A create/child DTO has no state authority when it carries an unversioned null.
            // Do not let it close the authoritative /session-states load already in flight.
            is SynchronizedData.Complete ->
                if (incoming.value == null && old is SynchronizedData.PendingHistory)
                    old.copy(value = newest)
                else SynchronizedData.Complete(newest)
        }

    private fun newestSessionState(
        a: HarnessSessionState?,
        b: HarnessSessionState?,
    ): HarnessSessionState? =
        when {
            a == null -> b
            b == null -> a
            a.eventId == null -> a
            b.eventId == null -> a
            b.eventId >= a.eventId -> b
            else -> a
        }

    private fun mergeCanonicalRow(old: JsonObject?, incoming: JsonObject): JsonObject =
        if (old == null) incoming
        else JsonObject(old + incoming.filterValues { it !is kotlinx.serialization.json.JsonNull })

    private fun rowSourceId(row: JsonObject, fallback: Int): EventId =
        EventId(
            row["source_event_id"]?.jsonPrimitive?.longOrNull
                ?: row["event_id"]?.jsonPrimitive?.longOrNull
                ?: row["id"]?.jsonPrimitive?.longOrNull
                ?: Long.MIN_VALUE + fallback
        )

    private fun overlayKey(item: ChatItem): TransientOverlayKey =
        when (item) {
            is ChatItem.Tool -> TransientOverlayKey("tool", item.id.orEmpty())
            is ChatItem.Message ->
                TransientOverlayKey("message", item.uiKey ?: "${item.role}:${item.text}")
            else -> TransientOverlayKey(item::class.simpleName.orEmpty(), item.toString())
        }

    private fun cancelRunningOverlayTool(item: ChatItem, completedAt: java.time.Instant): ChatItem =
        when (item) {
            is ChatItem.Tool ->
                if (item.final) item else item.copy(state = "cancelled", completedAt = completedAt)
            is ChatItem.ParallelToolGroup ->
                item.copy(
                    tools =
                        item.tools.map { tool ->
                            if (tool.final) tool
                            else tool.copy(state = "cancelled", completedAt = completedAt)
                        }
                )
            else -> item
        }

    private fun canonicalIdentities(row: JsonObject): Set<String> {
        val metadata = row["metadata"] as? JsonObject
        val tags = row["tags"] as? JsonObject
        return buildSet {
            listOf("run_id", "run", "tool_call_id", "tool_id", "message_id", "id", "sequence")
                .forEach { key ->
                    row[key]?.jsonPrimitive?.contentOrNull?.let(::add)
                    metadata?.get(key)?.jsonPrimitive?.contentOrNull?.let(::add)
                    tags?.get(key)?.jsonPrimitive?.contentOrNull?.let(::add)
                }
        }
    }

    private fun canonicalCoversOverlay(item: ChatItem, row: JsonObject): Boolean {
        val identities = canonicalIdentities(row)
        if (identities.isEmpty()) return false
        return when (item) {
            is ChatItem.Tool -> item.id != null && item.id in identities
            is ChatItem.Message -> {
                val streamIdentity = item.uiKey?.removePrefix("stream:")?.substringAfterLast(':')
                item.id in identities || streamIdentity in identities
            }
            is ChatItem.ParallelToolGroup -> item.tools.any { it.id != null && it.id in identities }
            else -> false
        }
    }

    private fun canonicalMatches(item: ChatItem, type: String, projection: JsonObject): Boolean {
        val projectionIdentity =
            listOf("run_id", "tool_call_id", "message_id", "sequence", "id").firstNotNullOfOrNull {
                projection[it]?.jsonPrimitive?.contentOrNull
            }
        return when (item) {
            is ChatItem.Message ->
                item.pendingCanonical &&
                    type == "message.complete" &&
                    item.role == "assistant" &&
                    (projectionIdentity == null ||
                        item.id == projectionIdentity ||
                        item.uiKey?.removePrefix("stream:")?.substringAfterLast(':') ==
                            projectionIdentity)
            is ChatItem.Tool -> projectionIdentity != null && item.id == projectionIdentity
            is ChatItem.ParallelToolGroup ->
                projectionIdentity != null && item.tools.any { it.id == projectionIdentity }
            else -> false
        }
    }
}

internal fun ChatUiState.withSessionState(value: HarnessSessionState): ChatUiState =
    ChatReducer.mergeSessionState(this, value)

internal fun newestSessionStates(
    fetched: List<HarnessSessionState>,
    current: List<HarnessSessionState>,
): List<HarnessSessionState> =
    (current + fetched)
        .groupBy { it.sessionId }
        .values
        .map { it.maxByOrNull { state -> state.eventId ?: Long.MAX_VALUE }!! }

fun draftFor(state: ChatUiState, id: SessionId): String = sessionUi(state, id).draft

fun draftValues(state: ChatUiState): Map<String, String> =
    state.ui.sessionUi.mapKeys { it.key.value }.mapValues { it.value.draft }

fun selectedSecret(state: ChatUiState): PendingSecret? =
    state.ui.selectedSessionId?.let { state.ui.pendingSecrets[it] }

fun eventDetailsFor(state: ChatUiState, id: SessionId): List<SessionEvent> =
    (state.harness.sessionsById[id]?.content?.value?.eventDetails?.value).orEmpty()

fun eventDetailsLoading(state: ChatUiState, id: SessionId): Boolean =
    state.harness.sessionsById[id]?.content?.value?.eventDetails is
        SynchronizedData.PendingHistory<*>

fun eventDetailsError(state: ChatUiState, id: SessionId): ErrorMessage? =
    when (val value = state.harness.sessionsById[id]?.content?.value?.eventDetails) {
        is SynchronizedData.PendingHistory -> value.error
        else -> null
    }

fun childrenFor(state: ChatUiState, id: SessionId): List<SessionView> =
    overviewSessions(state).filter { it.parentSessionId == id }

fun childrenLoadError(state: ChatUiState, id: SessionId): ErrorMessage? =
    (state.harness.sessionsById[id]?.children as? ChildrenLoadState.Failed)?.message

fun childrenLoaded(state: ChatUiState, id: SessionId): Boolean =
    state.harness.sessionsById[id]?.children is ChildrenLoadState.Loaded

fun isStateConnecting(state: ChatUiState): Boolean =
    state.harness.connection is ConnectionState.Connecting ||
        state.harness.connection is ConnectionState.Reconnecting

fun stateError(state: ChatUiState): ErrorMessage? = state.ui.error
