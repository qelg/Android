package dev.qelg.harnessandroid

import android.app.Application
import androidx.lifecycle.*
import dev.qelg.harnessandroid.data.*
import dev.qelg.harnessandroid.voice.*
import java.io.Closeable
import java.time.Instant
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@JvmInline value class ErrorMessage(val text: String)

data class VoiceMessageTarget(
    val storedSessionId: String,
    val runtimeSessionId: String,
    val model: String?,
    val connectionVersion: Long,
)

internal fun dispatchClose(
    closeable: Closeable?,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    if (closeable == null) return
    dispatcher.dispatch(EmptyCoroutineContext) { closeable.close() }
}

internal data class TokenUsageRefreshIdentity(
    val connectionVersion: Long,
    val selectionVersion: Long,
    val runtimeId: String?,
    val selectedId: String?,
    val storedId: String?,
)

internal fun isCurrentTokenUsageRefresh(
    expected: TokenUsageRefreshIdentity,
    current: TokenUsageRefreshIdentity,
): Boolean = expected == current

internal fun TokenUsageState.clearPersistedTokenDetails(): TokenUsageState =
    copy(cumulative = null, systemPrompt = null)

internal fun initialTokenUsage(session: HarnessSession): TokenUsageState? =
    session.cumulativeTokenUsage?.let { TokenUsageState(cumulative = it) }

internal suspend fun <T> runVoiceTranscription(
    setTranscribing: (Boolean) -> Unit,
    operation: suspend () -> T,
): Result<T> {
    setTranscribing(true)
    return try {
        runCatching { operation() }
    } finally {
        setTranscribing(false)
    }
}

data class ChatUiState(
    val configured: Boolean = false,
    val connecting: Boolean = false,
    val sessions: List<HarnessSession> = emptyList(),
    val search: String = "",
    val selectedId: String? = null,
    val treeParentId: String? = null,
    val drafts: Map<String, String> = emptyMap(),
    val unreadCounts: Map<String, Int> = emptyMap(),
    val readUpdates: Map<String, String> = emptyMap(),
    val historyLoadedFor: String? = null,
    val title: String = "Harness Android",
    val items: List<ChatItem> = emptyList(),
    val timelines: Map<String, List<ChatItem>> = emptyMap(),
    val active: Boolean = false,
    val activeSessionIds: Set<String> = emptySet(),
    val modelCatalog: ModelCatalog = ModelCatalog(),
    val modelLoading: Boolean = false,
    val transcribing: Boolean = false,
    val transcriptionStatus: String? = null,
    val whisperModel: WhisperModel = WhisperModel.Base,
    val approval: ApprovalRequest? = null,
    val clarify: ClarifyRequest? = null,
    val error: ErrorMessage? = null,
    val reconnectSeconds: Int? = null,
    val updateState: UpdateState = UpdateState(),
    val tokenUsage: TokenUsageState? = null,
    val sessionEvents: List<SessionEvent> = emptyList(),
    val sessionEventsFor: String? = null,
    val sessionEventsLoading: Boolean = false,
    val sessionEventsError: ErrorMessage? = null,
)

internal fun ChatUiState.timelineFor(sessionId: String): List<ChatItem> =
    if (selectedId == sessionId) items else timelines[sessionId].orEmpty()

internal fun ChatUiState.withTimeline(sessionId: String, timeline: List<ChatItem>): ChatUiState =
    copy(
        items = if (selectedId == sessionId) timeline else items,
        timelines = timelines + (sessionId to timeline),
    )

internal fun ChatUiState.withCurrentItems(timeline: List<ChatItem>): ChatUiState =
    selectedId?.let { withTimeline(it, timeline) } ?: copy(items = timeline)

class ChatViewModel(application: Application, private val savedState: SavedStateHandle) :
    AndroidViewModel(application) {
    private val credentials = SecureCredentials(application)
    private val draftStore = DraftStore(application)
    private val readStateStore = ReadStateStore(application)
    private val localWhisper = LocalWhisper(application)
    private val whisperModelStore = WhisperModelStore(application)
    private val _state =
        MutableStateFlow(
            ChatUiState(
                selectedId = savedState["selectedId"],
                whisperModel = whisperModelStore.load(),
            )
        )
    val state: StateFlow<ChatUiState> = _state.asStateFlow()
    val updateManager = UpdateManager(application)
    private var draftNamespace = ""
    private val draftRevisions = mutableMapOf<Pair<String, String>, Long>()
    private var client: HarnessClient? = null
    private var runtimeId: String? = null
    private var usageStoredId: String? = null
    private var connectionJob: Job? = null
    private var eventJob: Job? = null
    private var selectionJob: Job? = null
    private var refreshJob: Job? = null
    private var usageJob: Job? = null
    private val backgroundSessionJobs = mutableMapOf<String, Job>()
    private var connectionVersion = 0L
    private var selectionVersion = 0L
    private var historyRequestVersion = 0L
    private var liveMessageSequence = 0L
    private val runtimeToStored = mutableMapOf<String, String>()
    private val sessionModelOverrides = mutableMapOf<String, String>()

    init {
        credentials.load()?.let(::connect)
        syncUpdateState()
    }

    fun connect(config: ConnectionConfig) {
        if (!config.isAllowedEndpoint()) {
            _state.update {
                it.copy(
                    error =
                        ErrorMessage(
                            "Use HTTPS, or HTTP only for localhost, private LAN, or Tailscale endpoints."
                        )
                )
            }
            return
        }
        credentials.save(config)
        draftNamespace = config.normalizedBaseUrl
        val drafts = draftStore.load(draftNamespace)
        val readUpdates = readStateStore.load(draftNamespace)
        connectionJob?.cancel()
        eventJob?.cancel()
        selectionJob?.cancel()
        refreshJob?.cancel()
        usageJob?.cancel()
        backgroundSessionJobs.values.forEach(Job::cancel)
        backgroundSessionJobs.clear()
        dispatchClose(client)
        runtimeId = null
        usageStoredId = null
        runtimeToStored.clear()
        sessionModelOverrides.clear()
        val version = ++connectionVersion
        val next = HarnessClient(config, viewModelScope)
        client = next
        _state.update {
            it.copy(
                configured = true,
                connecting = true,
                sessions = emptyList(),
                drafts = drafts,
                unreadCounts = emptyMap(),
                readUpdates = readUpdates,
                historyLoadedFor = null,
                items = emptyList(),
                timelines = emptyMap(),
                activeSessionIds = emptySet(),
                approval = null,
                clarify = null,
                active = false,
                error = null,
                reconnectSeconds = null,
                tokenUsage = null,
            )
        }
        eventJob =
            viewModelScope.launch {
                next.events.collect {
                    if (client === next && connectionVersion == version) handleEvent(it)
                }
            }
        connectionJob =
            viewModelScope.launch {
                runCatching {
                        next.connect()
                        refreshSessions(next, version)
                        refreshModels(next, null, version)
                    }
                    .onSuccess {
                        if (client === next && connectionVersion == version) {
                            _state.update { it.copy(connecting = false) }
                            restoreSelection()
                        }
                    }
                    .onFailure {
                        if (client === next && connectionVersion == version) showError(it)
                    }
            }
    }

    private suspend fun restoreSelection() {
        val id = savedState.get<String>("selectedId") ?: return
        state.value.sessions.firstOrNull { it.id == id }?.let { select(it) }
    }

    fun disconnect() {
        connectionJob?.cancel()
        eventJob?.cancel()
        selectionJob?.cancel()
        refreshJob?.cancel()
        usageJob?.cancel()
        backgroundSessionJobs.values.forEach(Job::cancel)
        backgroundSessionJobs.clear()
        connectionVersion++
        selectionVersion++
        dispatchClose(client)
        client = null
        runtimeToStored.clear()
        sessionModelOverrides.clear()
        credentials.clear()
        runtimeId = null
        usageStoredId = null
        savedState["selectedId"] = null
        _state.value = ChatUiState(whisperModel = whisperModelStore.load())
    }

    fun setSearch(value: String) = _state.update { it.copy(search = value) }

    fun showTree(session: HarnessSession) {
        val tree = buildSessionTree(state.value.sessions)
        if (tree[session.id].isNullOrEmpty()) {
            select(session)
            return
        }
        _state.update { it.copy(treeParentId = session.id) }
    }

    fun hideTree() = _state.update { it.copy(treeParentId = null) }

    fun dismissChat() {
        monitorCurrentSessionIfActive()
        client?.stopWatching()
        runtimeId = null
        _state.update { it.copy(selectedId = null, treeParentId = null) }
    }

    fun backFromChat() {
        monitorCurrentSessionIfActive()
        client?.stopWatching()
        runtimeId = null
        _state.update {
            if (it.treeParentId != null) it.copy(selectedId = null)
            else it.copy(selectedId = null, treeParentId = null)
        }
    }

    fun setDraft(text: String) {
        val sessionId = state.value.selectedId ?: return
        val key = draftNamespace to sessionId
        draftRevisions[key] = (draftRevisions[key] ?: 0) + 1
        draftStore.save(draftNamespace, sessionId, text)
        _state.update { it.copy(drafts = updateDrafts(it.drafts, sessionId, text)) }
    }

    private fun captureDraftSubmission(sessionId: String, text: String): DraftSubmission? {
        if (state.value.drafts[sessionId] != text) return null
        val key = draftNamespace to sessionId
        return DraftSubmission(
            namespace = draftNamespace,
            connectionVersion = connectionVersion,
            sessionId = sessionId,
            revision = draftRevisions[key] ?: 0,
            text = text,
        )
    }

    private fun clearDraft(submitted: DraftSubmission) {
        val key = submitted.namespace to submitted.sessionId
        if (
            !canClearDraft(
                submitted,
                draftNamespace,
                connectionVersion,
                draftRevisions[key] ?: 0,
                state.value.drafts[submitted.sessionId],
            )
        )
            return
        draftRevisions[key] = submitted.revision + 1
        draftStore.save(submitted.namespace, submitted.sessionId, "")
        _state.update { it.copy(drafts = updateDrafts(it.drafts, submitted.sessionId, "")) }
    }

    private suspend fun refreshSessions(api: HarnessClient, version: Long) {
        val result = api.sessions()
        if (client !== api || connectionVersion != version) return
        val baseSessions =
            applySessionModelOverrides(
                result.map(HarnessSession::fromJson).filter { it.id.isNotBlank() },
                sessionModelOverrides,
            )
        // Keep the session list usable against older deployments, while preferring
        // the server-authoritative state projection whenever it is available.
        val sessionStates =
            runCatching { api.sessionStates() }.getOrNull()
                ?: state.value.sessions.mapNotNull(HarnessSession::sessionState)
        val sessions = applySessionStates(baseSessions, sessionStates)
        _state.update {
            val selectedSession = sessions.firstOrNull { session -> session.id == it.selectedId }
            val selectedModel =
                it.selectedId?.let { selectedId ->
                    sessionModelForLineage(selectedId, runtimeId, sessions, sessionModelOverrides)
                }
            it.copy(
                sessions = sessions,
                unreadCounts = remapUnread(it.unreadCounts, sessions),
                activeSessionIds =
                    sessions.filter(HarnessSession::active).mapTo(mutableSetOf()) { session ->
                        session.id
                    },
                active =
                    it.selectedId?.let { id ->
                        sessions.firstOrNull { session -> session.id == id }?.active
                    } ?: false,
                connecting = false,
                modelCatalog =
                    if (selectedSession != null) it.modelCatalog.selectedFor(selectedModel)
                    else it.modelCatalog,
            )
        }
    }

    private suspend fun refreshModels(api: HarnessClient, session: HarnessSession?, version: Long) {
        val selectedModel =
            session?.let {
                sessionModelForLineage(
                    it.id,
                    runtimeId,
                    state.value.sessions,
                    sessionModelOverrides,
                )
            }
        val catalog = api.modelOptions(session?.id)
        if (
            client !== api ||
                connectionVersion != version ||
                (session != null && state.value.selectedId != session.id)
        )
            return
        _state.update { it.copy(modelCatalog = catalog, modelLoading = false) }
    }

    fun refreshModels() {
        val api = client ?: return
        val version = connectionVersion
        val session = state.value.sessions.firstOrNull { it.id == state.value.selectedId }
        _state.update { it.copy(modelLoading = true) }
        viewModelScope.launch {
            runCatching { refreshModels(api, session, version) }
                .onFailure {
                    if (client === api) {
                        _state.update { state -> state.copy(modelLoading = false) }
                        showError(it)
                    }
                }
        }
    }

    fun selectModel(selection: ModelSelection) {
        val selectedId = state.value.selectedId ?: return
        sessionModelOverrides[selectedId] = selection.model
        viewModelScope.launch {
            runCatching { client?.selectModel(selectedId, selection) ?: error("Not connected") }
                .onFailure(::showError)
        }
        _state.update {
            it.copy(
                modelCatalog = it.modelCatalog.copy(selected = selection),
                sessions = sessionsWithModelSelection(it.sessions, selectedId, selection),
                error = null,
            )
        }
    }

    fun refresh() {
        val api = client ?: return
        val version = connectionVersion
        refreshJob?.cancel()
        refreshJob =
            viewModelScope.launch {
                runCatching { refreshSessions(api, version) }.onFailure(::showError)
            }
    }

    private fun scheduleRefresh() {
        val api = client ?: return
        val version = connectionVersion
        if (refreshJob?.isActive == true) return
        refreshJob =
            viewModelScope.launch {
                delay(250)
                runCatching { refreshSessions(api, version) }
                    .onFailure { if (client === api) showError(it) }
            }
    }

    /**
     * Reload the full message history from the server and merge it with live items. This is the
     * same code path used by [select] on initial load, so live updates and initial load stay
     * consistent.
     *
     * Motivation: the incremental live-event pipeline (message.delta, tool.start/complete,
     * clarify.request, etc.) can miss or drop state when the user enters a chat mid-turn or
     * reconnects after a transient disconnect. Replacing the items with the server-authoritative
     * history after each completed assistant turn avoids stale/duplicated items and clears ghost
     * clarify/tool cards without needing special-case cleanup.
     */
    private fun reloadHistory() {
        val api = client ?: return
        val storedId = state.value.selectedId ?: return
        val historySessionId = runtimeId ?: storedId
        val version = selectionVersion
        val requestVersion = ++historyRequestVersion
        val baseline = state.value.items
        viewModelScope.launch {
            runCatching {
                    val history = messagesFromHistoryRows(api.history(historySessionId))
                    if (
                        selectionVersion != version ||
                            historyRequestVersion != requestVersion ||
                            client !== api
                    )
                        return@runCatching
                    _state.update {
                        val items = reconcileHistoryItems(history, it.items, baseline)
                        if (items === it.items)
                            it.copy(connecting = false, historyLoadedFor = storedId)
                        else
                            it.withCurrentItems(items)
                                .copy(connecting = false, historyLoadedFor = storedId)
                    }
                }
                .onFailure {
                    if (
                        selectionVersion == version &&
                            historyRequestVersion == requestVersion &&
                            client === api
                    )
                        _state.update { it.copy(connecting = false) }
                }
        }
    }

    fun createSession() {
        if (state.value.active || state.value.connecting) return
        val api = client ?: return
        _state.update { it.copy(connecting = true, error = null) }
        viewModelScope.launch {
            runCatching {
                    val selection = state.value.modelCatalog.selected
                    val result = api.createSession(selection?.model)
                    val session = HarnessSession.fromJson(result)
                    runtimeId =
                        session.id.takeIf(String::isNotBlank)
                            ?: error("Harness returned no session ID")
                    val stored = runtimeId!!
                    usageStoredId = stored
                    runtimeToStored[runtimeId!!] = stored
                    savedState["selectedId"] = stored
                    _state.update {
                        it.copy(
                            selectedId = stored,
                            title = session.title,
                            items = emptyList(),
                            timelines = it.timelines + (stored to emptyList()),
                            historyLoadedFor = stored,
                            approval = null,
                            clarify = null,
                            active = false,
                            connecting = false,
                            tokenUsage = null,
                            sessions = listOf(session) + it.sessions,
                        )
                    }
                    api.watchSession(stored)
                }
                .onFailure { error ->
                    _state.update { it.copy(connecting = false) }
                    showError(error)
                }
        }
    }

    fun select(session: HarnessSession, selectedFromTree: Boolean = false) {
        if (state.value.connecting) return
        val api = client ?: return
        monitorCurrentSessionIfActive()
        backgroundSessionJobs.remove(session.id)?.cancel()
        selectionJob?.cancel()
        usageJob?.cancel()
        val version = ++selectionVersion
        runtimeId = session.id
        usageStoredId = session.id
        runtimeToStored[session.id] = session.id
        savedState["selectedId"] = session.id
        _state.update {
            val keepTimeline = it.selectedId == session.id
            it.copy(
                selectedId = session.id,
                title = session.title,
                connecting = true,
                items = if (keepTimeline) it.items else it.timelineFor(session.id),
                historyLoadedFor = null,
                approval = null,
                clarify = null,
                active = session.id in it.activeSessionIds || session.active,
                error = null,
                reconnectSeconds = null,
                tokenUsage = initialTokenUsage(session),
                modelCatalog = modelCatalogForSession(it.modelCatalog, session),
                treeParentId = treeParentAfterSelection(it.treeParentId, selectedFromTree),
                sessionEvents =
                    if (it.sessionEventsFor == session.id) it.sessionEvents else emptyList(),
                sessionEventsFor = if (it.sessionEventsFor == session.id) session.id else null,
                sessionEventsLoading = false,
                sessionEventsError = null,
            )
        }
        selectionJob =
            viewModelScope.launch {
                runCatching {
                        if (selectionVersion != version || client !== api) return@runCatching
                        val selectedModel =
                            sessionModelOverrides[session.id]
                                ?: session.model
                                ?: sessionModelForLineage(
                                    session.id,
                                    session.id,
                                    state.value.sessions,
                                    sessionModelOverrides,
                                )
                        val baseline = state.value.items
                        val historyVersion = ++historyRequestVersion
                        val historyRows = api.history(session.id)
                        val history = messagesFromHistoryRows(historyRows)
                        if (
                            selectionVersion != version ||
                                historyRequestVersion != historyVersion ||
                                client !== api
                        )
                            return@runCatching
                        _state.update {
                            val items = reconcileHistoryItems(history, it.items, baseline)
                            it.withCurrentItems(items)
                                .copy(
                                    connecting = false,
                                    historyLoadedFor = session.id,
                                    modelCatalog = it.modelCatalog.selectedFor(selectedModel),
                                )
                        }
                        api.watchSession(session.id, historyRows.latestEventId())
                        refreshTokenUsage()
                    }
                    .onFailure { if (selectionVersion == version && client === api) showError(it) }
            }
    }

    fun loadSessionEvents(force: Boolean = false) {
        val api = client ?: return
        val sessionId = state.value.selectedId ?: return
        val current = state.value
        if (!force && current.sessionEventsFor == sessionId && current.sessionEventsError == null)
            return
        _state.update {
            it.copy(
                sessionEventsLoading = true,
                sessionEventsError = null,
                sessionEvents =
                    if (it.sessionEventsFor == sessionId) it.sessionEvents else emptyList(),
            )
        }
        val version = selectionVersion
        viewModelScope.launch {
            runCatching { api.sessionEvents(sessionId) }
                .onSuccess { events ->
                    if (
                        client === api &&
                            selectionVersion == version &&
                            state.value.selectedId == sessionId
                    ) {
                        _state.update {
                            it.copy(
                                sessionEvents = events,
                                sessionEventsFor = sessionId,
                                sessionEventsLoading = false,
                                sessionEventsError = null,
                            )
                        }
                    }
                }
                .onFailure { error ->
                    if (
                        client === api &&
                            selectionVersion == version &&
                            state.value.selectedId == sessionId
                    ) {
                        _state.update {
                            it.copy(
                                sessionEventsFor = sessionId,
                                sessionEventsLoading = false,
                                sessionEventsError =
                                    ErrorMessage(
                                        error.message ?: "Session events could not be loaded"
                                    ),
                            )
                        }
                    }
                }
        }
    }

    fun send(text: String) {
        val clean = text.trim()
        val current = state.value
        if (clean.isEmpty() || current.active || current.connecting) return
        val storedId = current.selectedId ?: return
        val targetRuntimeId = runtimeId ?: storedId
        val model = current.modelCatalog.selected?.model
        val submittedDraft = captureDraftSubmission(storedId, text)
        val pending =
            ChatItem.Message(
                "user",
                clean,
                timestamp = Instant.now(),
                uiKey = "live:${++liveMessageSequence}",
                pendingCanonical = true,
            )
        _state.update {
            val timeline = it.timelineFor(storedId) + pending
            it.withTimeline(storedId, timeline)
                .copy(
                    active = if (it.selectedId == storedId) true else it.active,
                    activeSessionIds = it.activeSessionIds + storedId,
                    error = null,
                )
        }
        viewModelScope.launch {
            runCatching { client?.submit(targetRuntimeId, clean, model) ?: error("Not connected") }
                .onSuccess { submittedDraft?.let(::clearDraft) }
                .onFailure { error ->
                    val now = Instant.now()
                    _state.update {
                        val timeline = cancelRunningTools(it.timelineFor(storedId), now)
                        it.withTimeline(storedId, timeline)
                            .copy(
                                active = if (it.selectedId == storedId) false else it.active,
                                activeSessionIds = it.activeSessionIds - storedId,
                                approval = null,
                                clarify = null,
                            )
                    }
                    showError(error)
                }
        }
    }

    fun interrupt() =
        viewModelScope.launch { runCatching { client?.interrupt() }.onFailure(::showError) }

    fun approve(choice: String) =
        viewModelScope.launch {
            runCatching { client?.approve(choice) ?: error("Not connected") }
                .onSuccess { _state.update { it.copy(approval = null) } }
                .onFailure(::showError)
        }

    fun answerClarify(text: String) {
        if (text.isBlank()) return
        _state.update {
            it.copy(error = ErrorMessage("Harness does not expose clarify responses."))
        }
    }

    fun selectWhisperModel(model: WhisperModel) {
        whisperModelStore.save(model)
        _state.update { it.copy(whisperModel = model) }
    }

    fun isWhisperModelDownloaded(model: WhisperModel): Boolean = localWhisper.isDownloaded(model)

    fun currentVoiceMessageTarget(): VoiceMessageTarget? {
        val current = state.value
        val stored = current.selectedId ?: return null
        return VoiceMessageTarget(
            storedSessionId = stored,
            runtimeSessionId = runtimeId ?: stored,
            model = current.modelCatalog.selected?.model,
            connectionVersion = connectionVersion,
        )
    }

    fun transcribeAndSend(samples: FloatArray, target: VoiceMessageTarget) =
        viewModelScope.launch {
            val model = state.value.whisperModel
            val result =
                runVoiceTranscription(
                    setTranscribing = { active ->
                        _state.update {
                            it.copy(
                                transcribing = active,
                                transcriptionStatus =
                                    if (active) "Preparing Whisper ${model.displayName}…" else null,
                            )
                        }
                    },
                    operation = {
                        localWhisper.transcribe(samples, model) { status ->
                            _state.update { it.copy(transcriptionStatus = status) }
                        }
                    },
                )
            result.onSuccess { sendVoiceMessage(target, it) }.onFailure(::showError)
        }

    private fun sendVoiceMessage(target: VoiceMessageTarget, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (target.connectionVersion != connectionVersion) {
            showError(
                IllegalStateException("Connection changed before the voice message was ready")
            )
            return
        }
        val current = state.value
        if (target.storedSessionId in current.activeSessionIds) {
            showError(IllegalStateException("That session is already processing a message"))
            return
        }
        if (current.selectedId == target.storedSessionId && !current.connecting) {
            send(clean)
            return
        }
        val api = client
        if (api == null) {
            showError(IllegalStateException("Not connected"))
            return
        }
        val pending =
            ChatItem.Message(
                "user",
                clean,
                timestamp = Instant.now(),
                uiKey = "live:${++liveMessageSequence}",
                pendingCanonical = true,
            )
        _state.update {
            val timeline = it.timelineFor(target.storedSessionId) + pending
            it.withTimeline(target.storedSessionId, timeline)
                .copy(
                    activeSessionIds = it.activeSessionIds + target.storedSessionId,
                    active = if (it.selectedId == target.storedSessionId) true else it.active,
                )
        }
        viewModelScope.launch {
            runCatching { api.submit(target.runtimeSessionId, clean, target.model) }
                .onSuccess {
                    monitorBackgroundSession(api, target.storedSessionId, target.connectionVersion)
                    scheduleRefresh()
                }
                .onFailure { error ->
                    val now = Instant.now()
                    _state.update {
                        val timeline =
                            cancelRunningTools(it.timelineFor(target.storedSessionId), now)
                        it.withTimeline(target.storedSessionId, timeline)
                            .copy(
                                activeSessionIds = it.activeSessionIds - target.storedSessionId,
                                active =
                                    if (it.selectedId == target.storedSessionId) false
                                    else it.active,
                            )
                    }
                    showError(error)
                }
        }
    }

    private fun monitorCurrentSessionIfActive() {
        val current = state.value
        val stored = current.selectedId ?: return
        val api = client ?: return
        if (!current.active && stored !in current.activeSessionIds) return
        monitorBackgroundSession(api, stored, connectionVersion)
    }

    private fun monitorBackgroundSession(
        api: HarnessClient,
        storedSessionId: String,
        expectedConnectionVersion: Long,
    ) {
        backgroundSessionJobs.remove(storedSessionId)?.cancel()
        backgroundSessionJobs[storedSessionId] =
            viewModelScope.launch {
                try {
                    repeat(300) {
                        delay(1_000)
                        if (
                            client !== api ||
                                connectionVersion != expectedConnectionVersion ||
                                state.value.selectedId == storedSessionId
                        )
                            return@launch
                        val sessions = runCatching { api.sessions() }.getOrNull() ?: return@repeat
                        val session =
                            sessions.map(HarnessSession::fromJson).firstOrNull {
                                it.id == storedSessionId
                            }
                        if (session == null || session.active) return@repeat
                        val historyRows =
                            runCatching { api.history(storedSessionId) }.getOrNull()
                                ?: return@repeat
                        val history = messagesFromHistoryRows(historyRows)
                        if (
                            client !== api ||
                                connectionVersion != expectedConnectionVersion ||
                                state.value.selectedId == storedSessionId
                        )
                            return@launch
                        _state.update {
                            val existing = it.timelineFor(storedSessionId)
                            val timeline = reconcileHistoryItems(history, existing, existing)
                            it.withTimeline(storedSessionId, timeline)
                                .copy(activeSessionIds = it.activeSessionIds - storedSessionId)
                        }
                        incrementUnread(storedSessionId)
                        refreshSessions(api, expectedConnectionVersion)
                        return@launch
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    if (client === api && connectionVersion == expectedConnectionVersion) {
                        _state.update {
                            it.copy(activeSessionIds = it.activeSessionIds - storedSessionId)
                        }
                    }
                } finally {
                    if (
                        backgroundSessionJobs[storedSessionId] ===
                            kotlinx.coroutines.currentCoroutineContext()[Job]
                    ) {
                        backgroundSessionJobs.remove(storedSessionId)
                    }
                }
            }
    }

    fun reportError(error: Throwable) = showError(error)

    fun reconnectNow() {
        _state.update { it.copy(reconnectSeconds = null) }
        client?.reconnectNow()
    }

    fun markRead(sessionId: String) {
        val current = state.value
        if (!canMarkSessionRead(current.historyLoadedFor, current.selectedId, sessionId)) return
        val session = current.sessions.firstOrNull { it.id == sessionId } ?: return
        val serverState = session.sessionState
        if (serverState?.unread == true) {
            _state.update { ui ->
                ui.copy(
                    sessions =
                        ui.sessions.map {
                            if (it.id == sessionId)
                                it.copy(sessionState = serverState.copy(read = "read"))
                            else it
                        }
                )
            }
            val api = client
            val version = connectionVersion
            if (api != null) {
                viewModelScope.launch {
                    runCatching { api.markSessionRead(sessionId) }
                        .onSuccess { confirmed ->
                            if (client === api && connectionVersion == version) {
                                _state.update { ui ->
                                    ui.copy(
                                        sessions =
                                            ui.sessions.map {
                                                if (it.id == sessionId)
                                                    it.copy(sessionState = confirmed)
                                                else it
                                            }
                                    )
                                }
                            }
                        }
                        .onFailure {
                            if (client === api && connectionVersion == version) scheduleRefresh()
                        }
                }
            }
        }
        val readAt = confirmedReadAt(session)
        readStateStore.save(draftNamespace, sessionId, readAt)
        _state.update {
            val unread = clearUnread(it.unreadCounts, sessionId)
            it.copy(unreadCounts = unread, readUpdates = it.readUpdates + (sessionId to readAt))
        }
    }

    private fun incrementUnread(sessionId: String?) {
        if (sessionId == null) return
        _state.update { it.copy(unreadCounts = addUnread(it.unreadCounts, sessionId)) }
    }

    private fun storedSessionId(event: GatewayEvent): String? {
        val explicit =
            listOf("stored_session_id", "stored_id").firstNotNullOfOrNull {
                event.payload[it]?.jsonPrimitive?.contentOrNull
            }
        if (explicit != null) return explicit
        val eventId = event.sessionId ?: return null
        return resolveStoredSessionId(eventId, runtimeToStored, state.value.sessions)
    }

    private fun refreshTokenUsage() {
        val api = client ?: return
        val runtime = runtimeId ?: return
        val selected = state.value.selectedId ?: return
        val stored = usageStoredId ?: selected
        val identity =
            TokenUsageRefreshIdentity(
                connectionVersion,
                selectionVersion,
                runtime,
                selected,
                stored,
            )
        usageJob?.cancel()
        usageJob =
            viewModelScope.launch {
                val snapshot = runCatching { api.usageSnapshot(runtime) }.getOrNull()
                val toolDefinitions = runCatching { api.toolDefinitions(runtime) }.getOrNull()
                val currentIdentity =
                    TokenUsageRefreshIdentity(
                        connectionVersion,
                        selectionVersion,
                        runtimeId,
                        state.value.selectedId,
                        usageStoredId ?: state.value.selectedId,
                    )
                if (client !== api || !isCurrentTokenUsageRefresh(identity, currentIdentity))
                    return@launch
                _state.update {
                    val current = it.tokenUsage ?: TokenUsageState()
                    it.copy(
                        tokenUsage =
                            current.copy(
                                context = snapshot?.context ?: current.context,
                                cumulative = snapshot?.cumulative ?: current.cumulative,
                                toolDefinitions = toolDefinitions ?: current.toolDefinitions,
                            )
                    )
                }
            }
    }

    private fun handleEvent(event: GatewayEvent) {
        val current = runtimeId
        if (event.sessionId != null && event.sessionId != current) {
            val stored = storedSessionId(event)
            when (event.type) {
                "session.active" ->
                    stored?.let { id ->
                        _state.update { it.copy(activeSessionIds = it.activeSessionIds + id) }
                    }
                "session.inactive",
                "message.complete" ->
                    stored?.let { id ->
                        _state.update { it.copy(activeSessionIds = it.activeSessionIds - id) }
                    }
            }
            if (
                event.type in
                    setOf("session.active", "session.inactive", "message.delta", "message.complete")
            )
                scheduleRefresh()
            if (event.type == "message.complete") incrementUnread(stored)
            return
        }
        when (event.type) {
            "session.rotated" -> {
                val next = event.payload["new_session_id"]?.jsonPrimitive?.contentOrNull
                val stored = state.value.selectedId
                if (!next.isNullOrBlank() && stored != null) {
                    runtimeId = next
                    usageStoredId = next
                    runtimeToStored[next] = stored
                    reloadHistory()
                    refreshTokenUsage()
                }
            }
            "session.inactive" -> {
                val now = Instant.now()
                _state.update {
                    it.withCurrentItems(cancelRunningTools(it.items, now))
                        .copy(
                            active = false,
                            activeSessionIds =
                                it.selectedId?.let { id -> it.activeSessionIds - id }
                                    ?: it.activeSessionIds,
                            approval = null,
                            clarify = null,
                        )
                }
                scheduleRefresh()
                reloadHistory()
                refreshTokenUsage()
            }
            "session.info" -> {
                val model = event.payload["model"]?.jsonPrimitive?.contentOrNull
                val provider = event.payload["provider"]?.jsonPrimitive?.contentOrNull
                val latestStored =
                    event.payload["stored_session_id"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.takeIf(String::isNotBlank)
                val storedChanged = latestStored != null && latestStored != usageStoredId
                if (latestStored != null) {
                    usageStoredId = latestStored
                    (event.sessionId ?: runtimeId)?.let { runtimeToStored[it] = latestStored }
                }
                val liveUsage = LiveTokenUsage.fromSessionInfo(JsonObject(event.payload))
                _state.update {
                    val selected =
                        if (!model.isNullOrBlank() && !provider.isNullOrBlank())
                            ModelSelection(provider, model)
                        else it.modelCatalog.selected
                    val previousUsage =
                        if (storedChanged) it.tokenUsage?.clearPersistedTokenDetails()
                        else it.tokenUsage
                    val usage =
                        if (liveUsage != null)
                            (previousUsage ?: TokenUsageState()).copy(
                                context = null,
                                live = liveUsage,
                            )
                        else previousUsage
                    it.copy(
                        modelCatalog = it.modelCatalog.copy(selected = selected),
                        tokenUsage = usage,
                    )
                }
                refreshTokenUsage()
            }
            "clarify.request" -> {
                val parsed = parseClarifyRequest(event.payload)
                if (parsed != null) _state.update { it.copy(clarify = parsed) }
            }
            "clarify.expire" -> {
                // Server timed out or cancelled the prompt — dismiss the dialog
                val requestId = event.payload["request_id"]?.jsonPrimitive?.contentOrNull
                _state.update { current ->
                    if (requestId == null || current.clarify?.requestId == requestId)
                        current.copy(clarify = null)
                    else current
                }
            }
            "message.user" -> {
                reloadHistory()
                scheduleRefresh()
            }
            "message.delta" ->
                appendDelta(event.payload["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
            "message.complete" -> {
                val text =
                    (event.payload["text"] ?: event.payload["content"])
                        ?.jsonPrimitive
                        ?.contentOrNull
                        .orEmpty()
                val timestamp = event.payload.instant()
                val messageId =
                    listOf("message_id", "id").firstNotNullOfOrNull {
                        event.payload[it]?.jsonPrimitive?.contentOrNull
                    }
                _state.update {
                    it.withCurrentItems(
                            reconcileAssistantCompletion(
                                it.items,
                                text,
                                timestamp,
                                "live:${++liveMessageSequence}",
                                messageId,
                            )
                        )
                        .copy(
                            active = false,
                            activeSessionIds =
                                it.selectedId?.let { id -> it.activeSessionIds - id }
                                    ?: it.activeSessionIds,
                            approval = null,
                            clarify = null,
                        )
                }
                incrementUnread(state.value.selectedId)
                scheduleRefresh()
                reloadHistory()
                refreshTokenUsage()
            }
            "tool.start",
            "tool.complete",
            "tool.failed",
            "tool.error",
            "tool.cancelled" -> {
                val name =
                    listOf("name", "tool", "tool_name").firstNotNullOfOrNull {
                        event.payload[it]?.jsonPrimitive?.contentOrNull
                    } ?: "tool"
                // Clarify is an interactive prompt — the server sends a dedicated
                // clarify.request event with question + choices.  Do NOT rely on
                // tool.start here — its payload only carries a context label,
                // not the parseable arguments the dialog needs.
                if (name == "clarify") {
                    // tool.start / tool.complete / tool.failed for clarify —
                    // handled via clarify.request; just suppress the tool card
                    return
                }
                val id =
                    listOf("tool_call_id", "tool_id", "call_id", "id").firstNotNullOfOrNull {
                        event.payload[it]?.jsonPrimitive?.contentOrNull
                    }
                val state =
                    when (event.type) {
                        "tool.start" -> "running"
                        "tool.failed",
                        "tool.error" -> "failed"
                        "tool.cancelled" -> "cancelled"
                        else -> "completed"
                    }
                val eventTime = event.payload.instant() ?: Instant.now()
                val argument =
                    listOf("arguments", "args", "input", "request").firstNotNullOfOrNull {
                        event.payload[it]?.displayString()
                    }
                val result =
                    listOf("result", "output", "content").firstNotNullOfOrNull {
                        event.payload[it]?.displayString()
                    }
                val error = event.payload["error"]?.displayString()
                val batchId =
                    listOf("batch_id", "group_id", "parallel_group_id").firstNotNullOfOrNull {
                        event.payload[it]?.jsonPrimitive?.contentOrNull
                    }
                val tool =
                    ChatItem.Tool(
                        id,
                        name,
                        state,
                        arguments = if (state == "running") argument else null,
                        result = if (state == "completed") result else null,
                        error = if (state == "failed") error ?: result else null,
                        startedAt = if (state == "running") eventTime else null,
                        completedAt = if (state != "running") eventTime else null,
                        durationMs = event.payload["duration_ms"]?.jsonPrimitive?.longOrNull,
                        batchId = batchId,
                    )
                _state.update { it.withCurrentItems(upsertTool(it.items, tool)) }
            }
            "approval.request" ->
                current?.let { id ->
                    _state.update {
                        it.copy(
                            approval =
                                ApprovalRequest(
                                    id,
                                    event.payload["command"]
                                        ?.jsonPrimitive
                                        ?.contentOrNull
                                        .orEmpty(),
                                    event.payload["description"]
                                        ?.jsonPrimitive
                                        ?.contentOrNull
                                        .orEmpty(),
                                    event.payload["allow_permanent"]
                                        ?.jsonPrimitive
                                        ?.booleanOrNull == true,
                                )
                        )
                    }
                }
            "connection.lost" ->
                _state.update {
                    it.copy(
                        connecting = true,
                        error = ErrorMessage("Connection lost; reconnecting…"),
                        reconnectSeconds = null,
                    )
                }
            "connection.retry_scheduled" ->
                _state.update {
                    it.copy(
                        connecting = true,
                        reconnectSeconds =
                            event.payload["seconds"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0),
                    )
                }
            "connection.retry_started" ->
                _state.update { it.copy(connecting = true, reconnectSeconds = null) }
            "connection.restored" -> {
                _state.update { it.copy(connecting = false, error = null, reconnectSeconds = null) }
                refresh()
                state.value.selectedId?.let { id ->
                    state.value.sessions.firstOrNull { it.id == id }?.let(::select)
                }
            }
            "error" -> {
                _state.update { it.copy(active = false, approval = null, clarify = null) }
                showError(
                    IllegalStateException(
                        event.payload["message"]?.jsonPrimitive?.contentOrNull
                            ?: "Unknown Harness error"
                    )
                )
            }
        }
    }

    private fun appendDelta(text: String) {
        if (text.isEmpty()) return
        _state.update { state ->
            val items = state.items.toMutableList()
            val last = items.lastOrNull()
            if (last is ChatItem.Message && last.role == "assistant")
                items[items.lastIndex] = last.copy(text = last.text + text)
            else
                items +=
                    ChatItem.Message(
                        "assistant",
                        text,
                        timestamp = Instant.now(),
                        uiKey = "live:${++liveMessageSequence}",
                        pendingCanonical = true,
                    )
            state.withCurrentItems(items)
        }
    }

    fun checkForUpdate() = viewModelScope.launch { updateManager.checkForUpdate() }

    fun downloadUpdate() = viewModelScope.launch { updateManager.downloadAndInstall() }

    fun resetUpdateState() {
        updateManager.reset()
    }

    private fun syncUpdateState() {
        viewModelScope.launch {
            updateManager.state.collect { us -> _state.update { it.copy(updateState = us) } }
        }
    }

    private fun showError(error: Throwable) =
        _state.update {
            it.copy(
                connecting = false,
                error = ErrorMessage(error.message ?: error.toString()),
                reconnectSeconds = null,
            )
        }

    override fun onCleared() {
        backgroundSessionJobs.values.forEach(Job::cancel)
        backgroundSessionJobs.clear()
        dispatchClose(localWhisper)
        dispatchClose(client)
        client = null
        super.onCleared()
    }
}

internal fun messagesFromHistoryRow(row: JsonObject): List<ChatItem> {
    val role = row.string("role") ?: "assistant"
    val eventName = row.string("event_name")
    val timestamp = row.instant()
    if (role == "tool_request" || eventName == "tool.call.requested")
        return listOf(
            ChatItem.Tool(
                id = row.string("run_id") ?: row.string("tool_call_id"),
                name =
                    row.string("tool") ?: row.string("tool_name") ?: row.string("name") ?: "tool",
                state = "running",
                arguments = (row["content"] ?: row["input"])?.displayString(),
                startedAt = timestamp,
            )
        )
    if (role == "tool")
        return listOf(
            ChatItem.Tool(
                id = row.string("run_id") ?: row.string("tool_call_id"),
                name =
                    row.string("tool") ?: row.string("tool_name") ?: row.string("name") ?: "tool",
                state = row.string("state") ?: "completed",
                result = (row["content"] ?: row["text"])?.displayString().orEmpty(),
                error = row["error"]?.displayString(),
                completedAt = timestamp,
                durationMs = row["duration_ms"]?.jsonPrimitive?.longOrNull,
                durationEstimated = false,
            )
        )
    val raw = row["content"] ?: row["text"]
    val text = raw.assistantText()
    val result = mutableListOf<ChatItem>()
    if (text.isNotBlank())
        result += ChatItem.Message(role, text, row.string("id"), timestamp = timestamp)
    val tools =
        (row["tool_calls"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.map { call ->
                val function = call["function"] as? JsonObject
                ChatItem.Tool(
                    id = call.string("id"),
                    name = function?.string("name") ?: call.string("name") ?: "tool",
                    state = "running",
                    arguments = (function?.get("arguments") ?: call["input"])?.displayString(),
                    startedAt = timestamp,
                    batchId = row.string("id")?.let { "history:$it" },
                )
            }
            .orEmpty()
    when (tools.size) {
        0 -> Unit
        1 -> result += tools.single()
        else ->
            result +=
                ChatItem.ParallelToolGroup(
                    tools.first().batchId ?: "history:${tools.first().id ?: result.size}",
                    tools,
                )
    }
    return result
}

internal fun messagesFromHistoryRows(rows: List<JsonObject>): List<ChatItem> =
    rows.fold(emptyList()) { items, row ->
        messagesFromHistoryRow(row).fold(items) { current, item ->
            when (item) {
                is ChatItem.Tool -> {
                    val estimated =
                        item.completedAt != null &&
                            findTool(current, item.id)?.startedAt != null &&
                            java.time.Duration.between(
                                    findTool(current, item.id)!!.startedAt,
                                    item.completedAt,
                                )
                                .toMillis() >= 100
                    upsertTool(
                        current,
                        item.copy(durationEstimated = estimated, deriveDuration = estimated),
                    )
                }
                else -> current + item
            }
        }
    }

private fun findTool(items: List<ChatItem>, id: String?): ChatItem.Tool? {
    if (id == null) return null
    items.forEach { item ->
        when (item) {
            is ChatItem.Tool -> if (item.id == id) return item
            is ChatItem.ParallelToolGroup ->
                item.tools
                    .firstOrNull { it.id == id }
                    ?.let {
                        return it
                    }
            else -> Unit
        }
    }
    return null
}

internal fun reconcileAssistantCompletion(
    items: List<ChatItem>,
    finalText: String,
    timestamp: Instant? = null,
    uiKey: String? = null,
    id: String? = null,
): List<ChatItem> {
    if (finalText.isBlank()) return items
    val index = items.indexOfLast { it is ChatItem.Message && it.role == "assistant" }
    if (index == -1)
        return items +
            ChatItem.Message(
                "assistant",
                finalText,
                id = id,
                timestamp = timestamp,
                uiKey = uiKey,
                pendingCanonical = true,
            )
    return items.toMutableList().apply {
        val current = this[index] as ChatItem.Message
        this[index] =
            current.copy(
                text = finalText,
                id = id ?: current.id,
                timestamp = timestamp ?: current.timestamp,
                pendingCanonical = true,
            )
    }
}

private fun ChatItem.toolIds(): Set<String> =
    when (this) {
        is ChatItem.Tool -> setOfNotNull(id)
        is ChatItem.ParallelToolGroup -> tools.mapNotNullTo(linkedSetOf()) { it.id }
        else -> emptySet()
    }

private fun ChatItem.reconciliationKey(): String? =
    when (this) {
        is ChatItem.Message -> id?.let { "message:$it" } ?: uiKey?.let { "live-message:$it" }
        is ChatItem.Tool -> id?.let { "tool:$it" }
        is ChatItem.ParallelToolGroup -> "parallel:$id"
        is ChatItem.Status -> "status:$timestamp:$text"
        is ChatItem.ToolGroup -> null
    }

private fun changedSinceBaseline(item: ChatItem, index: Int, baseline: List<ChatItem>): Boolean {
    if (baseline.isEmpty()) return true
    val key = item.reconciliationKey()
    val previous =
        if (key != null) baseline.firstOrNull { it.reconciliationKey() == key }
        else baseline.getOrNull(index)?.takeIf { it::class == item::class }
    return previous == null || previous != item
}

internal fun mergeHistoryAndLive(
    history: List<ChatItem>,
    live: List<ChatItem>,
    baseline: List<ChatItem> = emptyList(),
): List<ChatItem> {
    val usedLiveMessages = mutableSetOf<Int>()
    val lastHistoryExactMessage =
        history.indices
            .filter { history[it] is ChatItem.Message }
            .associateBy {
                val message = history[it] as ChatItem.Message
                message.role to message.text
            }
    val canonical =
        history.mapIndexed { historyIndex, item ->
            if (item !is ChatItem.Message) return@mapIndexed item
            val match =
                live.indices.firstOrNull { index ->
                    if (index in usedLiveMessages) return@firstOrNull false
                    val candidate = live[index] as? ChatItem.Message ?: return@firstOrNull false
                    (item.id != null && candidate.id == item.id) ||
                        (candidate.id == null &&
                            candidate.role == item.role &&
                            candidate.text == item.text &&
                            historyIndex == lastHistoryExactMessage[item.role to item.text])
                }
            if (match == null) item
            else {
                usedLiveMessages += match
                val candidate = live[match] as ChatItem.Message
                item.copy(uiKey = candidate.uiKey ?: item.uiKey, pendingCanonical = false)
            }
        }
    var result = canonical
    live.forEachIndexed { index, item ->
        val changed = changedSinceBaseline(item, index, baseline)
        when (item) {
            is ChatItem.Message ->
                if (index !in usedLiveMessages && (item.pendingCanonical || changed)) result += item
            is ChatItem.Tool -> if (changed) result = upsertTool(result, item)
            is ChatItem.ParallelToolGroup -> if (changed) result = mergeParallelGroup(result, item)
            else -> if (changed) result += item
        }
    }
    return result
}

internal fun reconcileHistoryItems(
    history: List<ChatItem>,
    live: List<ChatItem>,
    baseline: List<ChatItem> = emptyList(),
): List<ChatItem> {
    val merged = mergeHistoryAndLive(history, live, baseline)
    return if (merged == live) live else merged
}

internal fun addUnread(unread: Map<String, Int>, sessionId: String): Map<String, Int> =
    unread + (sessionId to ((unread[sessionId] ?: 0) + 1))

internal fun clearUnread(unread: Map<String, Int>, sessionId: String): Map<String, Int> {
    if (sessionId !in unread) return unread
    return unread - sessionId
}

internal fun resolveStoredSessionId(
    eventId: String,
    runtimeToStored: Map<String, String>,
    sessions: List<HarnessSession>,
): String =
    runtimeToStored[eventId]
        ?: sessions.firstOrNull { it.id == eventId || it.runtimeId == eventId }?.id
        ?: eventId

internal fun remapUnread(
    unread: Map<String, Int>,
    sessions: List<HarnessSession>,
): Map<String, Int> =
    unread.entries.fold(emptyMap()) { result, (key, count) ->
        val storedId = sessions.firstOrNull { it.id == key || it.runtimeId == key }?.id ?: key
        result + (storedId to ((result[storedId] ?: 0) + count))
    }

private fun mergeParallelGroup(
    items: List<ChatItem>,
    live: ChatItem.ParallelToolGroup,
): List<ChatItem> {
    val liveIds = live.toolIds()
    val matching =
        items.indices.filter { index ->
            val ids = items[index].toolIds()
            ids.isNotEmpty() && ids.any(liveIds::contains)
        }
    if (matching.isEmpty()) return items + live

    var updated = items
    live.tools
        .filter { it.id != null && findTool(updated, it.id) != null }
        .forEach { updated = upsertTool(updated, it) }
    val refreshedMatching =
        updated.indices.filter { index ->
            val ids = updated[index].toolIds()
            ids.isNotEmpty() && ids.any(liveIds::contains)
        }
    val existingTools =
        refreshedMatching.flatMap { index ->
            when (val operation = updated[index]) {
                is ChatItem.Tool -> listOf(operation)
                is ChatItem.ParallelToolGroup -> operation.tools
                else -> emptyList()
            }
        }
    val byId = existingTools.mapNotNull { tool -> tool.id?.let { it to tool } }.toMap()
    val mergedTools =
        live.tools.map { tool -> tool.id?.let(byId::get) ?: tool } +
            existingTools.filter { it.id !in liveIds }
    val insertion = refreshedMatching.minOrNull() ?: updated.size
    val result = updated.toMutableList()
    refreshedMatching.sortedDescending().forEach(result::removeAt)
    result.add(insertion.coerceAtMost(result.size), live.copy(tools = mergedTools))
    return result
}

private fun cancelRunningTools(items: List<ChatItem>, completedAt: Instant): List<ChatItem> =
    items.map { item ->
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
            is ChatItem.ToolGroup ->
                item.copy(operations = cancelRunningTools(item.operations, completedAt))
            else -> item
        }
    }

internal fun Map<String, JsonElement>.instant(): Instant? =
    listOf("timestamp", "created_at", "created_at_ms", "updated_at", "time").firstNotNullOfOrNull {
        key ->
        val primitive = this[key] as? JsonPrimitive ?: return@firstNotNullOfOrNull null
        primitive.contentOrNull?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: primitive.doubleOrNull?.let { value ->
                val epochMillis =
                    when {
                        key.endsWith("_ms") -> value
                        kotlin.math.abs(value) >= 100_000_000_000_000_000.0 -> value / 1_000_000.0
                        kotlin.math.abs(value) >= 100_000_000_000_000.0 -> value / 1_000.0
                        kotlin.math.abs(value) >= 100_000_000_000.0 -> value
                        else -> value * 1_000.0
                    }
                Instant.ofEpochMilli(epochMillis.toLong())
            }
    }

internal fun List<JsonObject>.latestEventId(): Long? =
    maxOfOrNull { row -> row["id"]?.jsonPrimitive?.longOrNull ?: 0L }?.takeIf { it > 0L }

private fun JsonObject.instant(): Instant? = (this as Map<String, JsonElement>).instant()

private fun JsonElement.displayString(): String =
    (this as? JsonPrimitive)?.contentOrNull ?: toString()

internal fun parseClarifyRequest(payload: Map<String, JsonElement>): ClarifyRequest? {
    val requestId = payload["request_id"]?.jsonPrimitive?.contentOrNull ?: return null
    val question = payload["question"]?.jsonPrimitive?.contentOrNull ?: return null
    val choices =
        (payload["choices"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive?.contentOrNull }
            .orEmpty()
            .take(4)
    return ClarifyRequest(requestId, question, choices)
}
