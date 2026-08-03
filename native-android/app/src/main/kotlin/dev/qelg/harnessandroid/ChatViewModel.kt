package dev.qelg.harnessandroid

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import dev.qelg.harnessandroid.data.*
import dev.qelg.harnessandroid.voice.*
import java.io.Closeable
import java.io.File
import java.time.Instant
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
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

private data class ActiveVoiceTranscription(
    val id: String,
    val recorder: LocalAudioRecorder?,
    val target: VoiceMessageTarget,
)

internal fun formatVoiceDuration(samples: Long): String {
    val seconds = samples.coerceAtLeast(0) / LocalAudioRecorder.SAMPLE_RATE
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

internal fun formatElapsedDuration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

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

data class QueuedMessage(
    val id: Long,
    val text: String,
    val mode: MessageQueueMode,
    val expectedUserMessageOccurrence: Int,
    val submitting: Boolean = true,
)

data class PendingSecret(
    val eventId: Long,
    val identifier: String,
    val description: String,
    val container: String,
)

internal fun interface MessageSubmitter {
    suspend fun submit(
        sessionId: String,
        text: String,
        model: String?,
        queueMode: MessageQueueMode?,
    )
}

data class ChatUiState(
    val configured: Boolean = false,
    val connecting: Boolean = false,
    val sessions: List<HarnessSession> = emptyList(),
    val childSessions: Map<String, List<HarnessSession>> = emptyMap(),
    val childSessionsLoading: Set<String> = emptySet(),
    val childSessionsErrors: Map<String, ErrorMessage> = emptyMap(),
    val containers: List<HarnessContainer> = emptyList(),
    val containersLoading: Boolean = false,
    val containersError: ErrorMessage? = null,
    val deletingContainerIds: Set<String> = emptySet(),
    val search: String = "",
    val chatGptUsage: ChatGptUsage? = null,
    val selectedId: String? = null,
    val treeParentId: String? = null,
    val drafts: Map<String, String> = emptyMap(),
    val queuedMessages: Map<String, List<QueuedMessage>> = emptyMap(),
    val pendingSecrets: Map<String, PendingSecret> = emptyMap(),
    val secretDrafts: Map<String, String> = emptyMap(),
    val uploadingSecretSessionIds: Set<String> = emptySet(),
    val submittingMessageSessionIds: Set<String> = emptySet(),
    val unreadCounts: Map<String, Int> = emptyMap(),
    val readUpdates: Map<String, String> = emptyMap(),
    val historyLoadedFor: String? = null,
    val title: String = "Harness Android",
    val items: List<ChatItem> = emptyList(),
    val timelines: Map<String, List<ChatItem>> = emptyMap(),
    val activeSessionIds: Set<String> = emptySet(),
    val modelCatalog: ModelCatalog = ModelCatalog(),
    val modelLoading: Boolean = false,
    val voiceRecording: Boolean = false,
    val transcribing: Boolean = false,
    val transcriptionStatus: String? = null,
    val transcriptionProgress: Float? = null,
    val transcriptionProgressLabel: String? = null,
    val transcriptionElapsedMs: Long? = null,
    val transcriptionText: String? = null,
    val voiceTargetSessionId: String? = null,
    val whisperModel: WhisperModel = WhisperModel.Base,
    val whisperThreadCount: Int = WhisperCpuConfig.AUTOMATIC,
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
    val showReasoning: Boolean = true,
) {
    val active: Boolean
        get() = selectedId != null && selectedId in activeSessionIds

    val selectedQueuedMessages: List<QueuedMessage>
        get() = selectedId?.let { queuedMessages[it] }.orEmpty()

    val selectedSecret: PendingSecret?
        get() = selectedId?.let { pendingSecrets[it] }

    val submittingMessage: Boolean
        get() = selectedId in submittingMessageSessionIds
}

internal fun ChatUiState.withQueuedMessage(sessionId: String, message: QueuedMessage): ChatUiState =
    copy(
        queuedMessages =
            queuedMessages + (sessionId to (queuedMessages[sessionId].orEmpty() + message))
    )

internal fun ChatUiState.updateQueuedMessage(
    sessionId: String,
    messageId: Long,
    transform: (QueuedMessage) -> QueuedMessage,
): ChatUiState {
    val queued = queuedMessages[sessionId].orEmpty()
    if (queued.none { it.id == messageId }) return this
    return copy(
        queuedMessages =
            queuedMessages +
                (sessionId to queued.map { if (it.id == messageId) transform(it) else it })
    )
}

internal fun ChatUiState.removeQueuedMessage(sessionId: String, messageId: Long): ChatUiState {
    val remaining = queuedMessages[sessionId].orEmpty().filterNot { it.id == messageId }
    val updated = queuedMessages.toMutableMap()
    if (remaining.isEmpty()) updated.remove(sessionId) else updated[sessionId] = remaining
    return copy(queuedMessages = updated)
}

internal fun ChatUiState.consumeQueuedMessage(sessionId: String, text: String): ChatUiState {
    val match = queuedMessages[sessionId].orEmpty().firstOrNull { it.text == text } ?: return this
    return removeQueuedMessage(sessionId, match.id)
}

internal fun ChatUiState.reconcileQueuedMessages(
    sessionId: String,
    timeline: List<ChatItem>,
): ChatUiState {
    val queued = queuedMessages[sessionId].orEmpty()
    if (queued.isEmpty()) return this
    val userMessageCounts =
        timeline
            .filterIsInstance<ChatItem.Message>()
            .filter { it.role == "user" }
            .groupingBy(ChatItem.Message::text)
            .eachCount()
    val remaining =
        queued.filter { (userMessageCounts[it.text] ?: 0) < it.expectedUserMessageOccurrence }
    if (remaining.size == queued.size) return this
    val updated = queuedMessages.toMutableMap()
    if (remaining.isEmpty()) updated.remove(sessionId) else updated[sessionId] = remaining
    return copy(queuedMessages = updated)
}

internal fun ChatUiState.timelineFor(sessionId: String): List<ChatItem> =
    if (selectedId == sessionId) items else timelines[sessionId].orEmpty()

internal fun ChatUiState.withTimeline(sessionId: String, timeline: List<ChatItem>): ChatUiState =
    copy(
        items = if (selectedId == sessionId) timeline else items,
        timelines = timelines + (sessionId to timeline),
    )

internal fun ChatUiState.withCurrentItems(timeline: List<ChatItem>): ChatUiState =
    selectedId?.let { withTimeline(it, timeline) } ?: copy(items = timeline)

internal fun newestSessionStates(
    fetched: List<HarnessSessionState>,
    current: List<HarnessSessionState>,
): List<HarnessSessionState> {
    val newest = current.associateBy(HarnessSessionState::sessionId).toMutableMap()
    fetched.forEach { candidate ->
        val existing = newest[candidate.sessionId]
        if (
            existing == null ||
                existing.eventId == null ||
                candidate.eventId == null ||
                candidate.eventId >= existing.eventId
        )
            newest[candidate.sessionId] = candidate
    }
    return newest.values.toList()
}

internal fun ChatUiState.withSessionState(sessionState: HarnessSessionState): ChatUiState {
    val existing = sessions.firstOrNull { it.id == sessionState.sessionId }?.sessionState
    if (
        existing?.eventId != null &&
            sessionState.eventId != null &&
            sessionState.eventId < existing.eventId
    )
        return this
    val activeIds =
        if (sessionState.running) activeSessionIds + sessionState.sessionId
        else activeSessionIds - sessionState.sessionId
    return copy(
        unreadCounts =
            if (sessionState.running) clearUnread(unreadCounts, sessionState.sessionId)
            else unreadCounts,
        sessions =
            sessions.map { session ->
                if (session.id == sessionState.sessionId)
                    session.copy(
                        updatedAt = sessionState.updatedAt ?: session.updatedAt,
                        active = sessionState.running,
                        endReason = sessionState.outcome ?: session.endReason,
                        sessionState = sessionState,
                    )
                else session
            },
        activeSessionIds = activeIds,
    )
}

class ChatViewModel
private constructor(
    application: Application,
    private val savedState: SavedStateHandle,
    initialState: ChatUiState?,
    initialRuntimeId: String?,
    private val messageSubmitter: MessageSubmitter?,
    restoreConnection: Boolean,
) : AndroidViewModel(application) {
    constructor(
        application: Application,
        savedState: SavedStateHandle,
    ) : this(application, savedState, null, null, null, true)

    internal constructor(
        application: Application,
        savedState: SavedStateHandle,
        initialState: ChatUiState,
        messageSubmitter: MessageSubmitter,
    ) : this(
        application,
        savedState,
        initialState,
        initialState.selectedId,
        messageSubmitter,
        false,
    )

    private val app = application
    private val credentials = SecureCredentials(application)
    private val draftStore = DraftStore(application)
    private val readStateStore = ReadStateStore(application)
    private val localWhisper = LocalWhisper(application)
    private val voiceJobStore = VoiceJobStore(application)
    private val whisperModelStore = WhisperModelStore(application)
    private val _state =
        MutableStateFlow(
            initialState
                ?: ChatUiState(
                    selectedId = savedState["selectedId"],
                    whisperModel = whisperModelStore.load(),
                    whisperThreadCount = whisperModelStore.loadThreadCount(),
                )
        )
    val state: StateFlow<ChatUiState> = _state.asStateFlow()
    val updateManager = UpdateManager(application)
    private var draftNamespace = ""
    private val draftRevisions = mutableMapOf<Pair<String, String>, Long>()
    private var client: HarnessClient? = null
    private var runtimeId: String? = initialRuntimeId
    private var usageStoredId: String? = null
    private var connectionJob: Job? = null
    private var eventJob: Job? = null
    private var selectionJob: Job? = null
    private var refreshJob: Job? = null
    private var usageJob: Job? = null
    private var chatGptUsageJob: Job? = null
    private var containersJob: Job? = null
    private var overviewCursor = 0L
    private var appStarted = false
    private var connectionVersion = 0L
    private var selectionVersion = 0L
    private var historyRequestVersion = 0L
    private var liveMessageSequence = 0L
    private var queuedMessageSequence = 0L
    private var voiceTranscriptionSequence = 0L
    private var voiceTranscription: ActiveVoiceTranscription? = null
    private val runtimeToStored = mutableMapOf<String, String>()
    private val sessionModelOverrides = mutableMapOf<String, String>()
    private val voiceJobReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                handleVoiceJobUpdate(intent)
            }
        }

    init {
        ContextCompat.registerReceiver(
            application,
            voiceJobReceiver,
            IntentFilter(VoiceTranscriptionService.ACTION_UPDATE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        restoreVoiceJob()
        if (restoreConnection) {
            credentials.load()?.let(::connect)
            syncUpdateState()
        }
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
        chatGptUsageJob?.cancel()
        containersJob?.cancel()
        client?.stopWatching()
        dispatchClose(client)
        runtimeId = null
        usageStoredId = null
        overviewCursor = 0L
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
                childSessions = emptyMap(),
                childSessionsLoading = emptySet(),
                childSessionsErrors = emptyMap(),
                containers = emptyList(),
                containersLoading = false,
                containersError = null,
                deletingContainerIds = emptySet(),
                drafts = drafts,
                queuedMessages = emptyMap(),
                pendingSecrets = emptyMap(),
                secretDrafts = emptyMap(),
                uploadingSecretSessionIds = emptySet(),
                submittingMessageSessionIds = emptySet(),
                unreadCounts = emptyMap(),
                readUpdates = readUpdates,
                historyLoadedFor = null,
                items = emptyList(),
                timelines = emptyMap(),
                activeSessionIds = emptySet(),
                approval = null,
                clarify = null,
                error = null,
                reconnectSeconds = null,
                tokenUsage = null,
                chatGptUsage = null,
            )
        }
        eventJob =
            viewModelScope.launch {
                next.events.collect {
                    if (client === next && connectionVersion == version) {
                        it.cursor?.let { cursor -> overviewCursor = maxOf(overviewCursor, cursor) }
                        handleEvent(it)
                    }
                }
            }
        connectionJob =
            viewModelScope.launch {
                runCatching {
                        next.connect()
                        refreshSessions(next, version)
                        refreshModels(next, null, version)
                        fetchContainers(next, version)
                    }
                    .onSuccess {
                        if (client === next && connectionVersion == version) {
                            _state.update { it.copy(connecting = false) }
                            refreshChatGptUsage()
                            restoreSelection()
                            recoverCompletedVoiceJob()
                            if (appStarted) next.watchEvents(overviewCursor)
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
        chatGptUsageJob?.cancel()
        containersJob?.cancel()
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
        _state.value =
            ChatUiState(
                whisperModel = whisperModelStore.load(),
                whisperThreadCount = whisperModelStore.loadThreadCount(),
            )
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
        client?.stopWatching()
        runtimeId = null
        _state.update { it.copy(selectedId = null, treeParentId = null) }
    }

    fun backFromChat() {
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

    fun setSecretDraft(text: String) {
        val sessionId = state.value.selectedId ?: return
        _state.update {
            val drafts = it.secretDrafts.toMutableMap()
            if (text.isBlank()) drafts.remove(sessionId) else drafts[sessionId] = text
            it.copy(secretDrafts = drafts)
        }
    }

    fun sendSecret(text: String) {
        val clean = text
        val sessionId = state.value.selectedId ?: return
        val request = state.value.pendingSecrets[sessionId] ?: return
        if (clean.isEmpty() || sessionId in state.value.uploadingSecretSessionIds) return
        val api = client ?: return
        val version = connectionVersion
        _state.update {
            it.copy(
                uploadingSecretSessionIds = it.uploadingSecretSessionIds + sessionId,
                error = null,
            )
        }
        viewModelScope.launch {
            runCatching { api.submitSecret(request.eventId, request.identifier, clean) }
                .onSuccess {
                    if (client === api && connectionVersion == version) {
                        _state.update { current ->
                            val drafts = current.secretDrafts.toMutableMap()
                            drafts.remove(sessionId)
                            current.copy(
                                secretDrafts = drafts,
                                uploadingSecretSessionIds =
                                    current.uploadingSecretSessionIds - sessionId,
                            )
                        }
                    }
                }
                .onFailure { error ->
                    if (client === api && connectionVersion == version) {
                        _state.update {
                            it.copy(
                                uploadingSecretSessionIds = it.uploadingSecretSessionIds - sessionId
                            )
                        }
                        showError(error)
                    }
                }
        }
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
        val snapshot = api.sessionsSnapshot()
        val result = snapshot.sessions
        if (client !== api || connectionVersion != version) return
        val knownChildren = state.value.childSessions.values.flatten()
        val parsed = result.map(HarnessSession::fromJson).filter { it.id.isNotBlank() }
        val baseSessions =
            applySessionModelOverrides(
                (parsed + knownChildren).distinctBy { it.id },
                sessionModelOverrides,
            )
        // New Harness versions include the latest state in each session. Fall back to
        // /session-states for older deployments that do not yet provide that field.
        val inlineStates = baseSessions.mapNotNull(HarnessSession::sessionState)
        val currentSessionStates = state.value.sessions.mapNotNull(HarnessSession::sessionState)
        val sessionStates =
            if (inlineStates.isNotEmpty() || baseSessions.isEmpty()) inlineStates
            else runCatching { api.sessionStates() }.getOrNull().orEmpty()
        val sessions =
            applySessionStates(
                baseSessions,
                newestSessionStates(sessionStates, currentSessionStates),
            )
        overviewCursor = maxOf(overviewCursor, snapshot.cursor ?: overviewCursorFor(sessions))
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
                connecting = false,
                modelCatalog =
                    if (selectedSession != null) it.modelCatalog.selectedFor(selectedModel)
                    else it.modelCatalog,
            )
        }
    }

    private fun overviewCursorFor(sessions: List<HarnessSession>): Long =
        sessions.maxOfOrNull { session ->
            maxOf(session.eventId ?: 0L, session.sessionState?.eventId ?: 0L)
        } ?: 0L

    fun onAppStarted() {
        appStarted = true
        client?.takeIf { !state.value.connecting }?.watchEvents(overviewCursor)
    }

    fun onAppStopped() {
        appStarted = false
        client?.stopWatching()
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
        refreshContainers()
        refreshChatGptUsage()
    }

    private suspend fun fetchContainers(api: HarnessClient, version: Long) {
        if (client !== api || connectionVersion != version) return
        _state.update { it.copy(containersLoading = true, containersError = null) }
        runCatching { api.containers() }
            .onSuccess { containers ->
                if (client === api && connectionVersion == version) {
                    _state.update {
                        it.copy(
                            containers = containers,
                            containersLoading = false,
                            containersError = null,
                        )
                    }
                }
            }
            .onFailure { error ->
                if (client === api && connectionVersion == version) {
                    _state.update {
                        it.copy(
                            containersLoading = false,
                            containersError =
                                ErrorMessage(
                                    error.message ?: "Container storage could not be loaded"
                                ),
                        )
                    }
                }
            }
    }

    fun refreshContainers() {
        val api = client ?: return
        val version = connectionVersion
        containersJob?.cancel()
        containersJob = viewModelScope.launch { fetchContainers(api, version) }
    }

    fun deleteContainer(containerId: String) {
        val api = client ?: return
        if (containerId in state.value.deletingContainerIds) return
        val version = connectionVersion
        _state.update {
            it.copy(
                deletingContainerIds = it.deletingContainerIds + containerId,
                containersError = null,
            )
        }
        viewModelScope.launch {
            runCatching { api.deleteContainer(containerId) }
                .onSuccess {
                    if (client === api && connectionVersion == version) {
                        _state.update {
                            it.copy(
                                containers =
                                    it.containers.filterNot { container ->
                                        container.containerId == containerId
                                    },
                                deletingContainerIds = it.deletingContainerIds - containerId,
                            )
                        }
                    }
                }
                .onFailure { error ->
                    if (client === api && connectionVersion == version) {
                        _state.update {
                            it.copy(
                                deletingContainerIds = it.deletingContainerIds - containerId,
                                containersError =
                                    ErrorMessage(error.message ?: "Container could not be deleted"),
                            )
                        }
                    }
                }
        }
    }

    /**
     * Load the initial or explicitly recovered message history and merge it with live items. Normal
     * WebSocket message events are applied directly and do not call this method; the history
     * endpoint remains useful when selecting a session or recovering a rotated runtime.
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
                        val updated =
                            if (items === it.items)
                                it.copy(connecting = false, historyLoadedFor = storedId)
                            else
                                it.withCurrentItems(items)
                                    .copy(connecting = false, historyLoadedFor = storedId)
                        updated.reconcileQueuedMessages(storedId, items)
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
                    val result = api.createSession(selection)
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
                            connecting = false,
                            tokenUsage = null,
                            sessions = listOf(session) + it.sessions,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(connecting = false) }
                    showError(error)
                }
        }
    }

    fun selectContainerSession(container: HarnessContainer) {
        if (state.value.connecting || client == null) return
        val session = sessionForContainer(container, state.value.sessions)
        _state.update { ui ->
            if (ui.sessions.any { it.id == session.id }) ui
            else ui.copy(sessions = mergeSessionsById(ui.sessions, listOf(session)))
        }
        select(session)
    }

    fun select(session: HarnessSession, selectedFromTree: Boolean = false) {
        if (state.value.connecting) return
        val api = client ?: return
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
                activeSessionIds =
                    if (session.active) it.activeSessionIds + session.id else it.activeSessionIds,
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
                        // A secret request may predate this app process. Rebuild the
                        // pending request from the already-loaded message timeline
                        // before starting the live stream, instead of relying only on
                        // the event callback or making a second history request.
                        val restoredSecret = pendingSecretFromHistoryRows(historyRows)
                        if (
                            selectionVersion != version ||
                                historyRequestVersion != historyVersion ||
                                client !== api
                        )
                            return@runCatching
                        _state.update {
                            val items = reconcileHistoryItems(history, it.items, baseline)
                            val withSecret =
                                if (restoredSecret == null)
                                    it.copy(pendingSecrets = it.pendingSecrets - session.id)
                                else
                                    it.copy(
                                        pendingSecrets =
                                            it.pendingSecrets + (session.id to restoredSecret)
                                    )
                            withSecret
                                .withCurrentItems(items)
                                .copy(
                                    connecting = false,
                                    historyLoadedFor = session.id,
                                    modelCatalog = it.modelCatalog.selectedFor(selectedModel),
                                )
                                .reconcileQueuedMessages(session.id, items)
                        }
                        runCatching { refreshModels(api, session, connectionVersion) }
                            .onFailure(::showError)
                        refreshTokenUsage()
                    }
                    .onFailure { if (selectionVersion == version && client === api) showError(it) }
            }
    }

    fun loadChildSessions(sessionId: String) {
        val api = client ?: return
        val current = state.value
        if (sessionId in current.childSessions || sessionId in current.childSessionsLoading) return
        val version = connectionVersion
        _state.update {
            it.copy(
                childSessionsLoading = it.childSessionsLoading + sessionId,
                childSessionsErrors = it.childSessionsErrors - sessionId,
            )
        }
        viewModelScope.launch {
            runCatching {
                    sortSessionsForOverview(
                        api.childSessions(sessionId).map(HarnessSession::fromJson).filter {
                            it.id.isNotBlank()
                        }
                    )
                }
                .onSuccess { children ->
                    if (client === api && connectionVersion == version) {
                        _state.update { ui ->
                            ui.copy(
                                sessions = mergeSessionsById(ui.sessions, children),
                                childSessions = ui.childSessions + (sessionId to children),
                                childSessionsLoading = ui.childSessionsLoading - sessionId,
                            )
                        }
                    }
                }
                .onFailure { error ->
                    if (client === api && connectionVersion == version) {
                        _state.update {
                            it.copy(
                                childSessionsLoading = it.childSessionsLoading - sessionId,
                                childSessionsErrors =
                                    it.childSessionsErrors +
                                        (sessionId to
                                            ErrorMessage(
                                                error.message
                                                    ?: "Child sessions could not be loaded"
                                            )),
                            )
                        }
                    }
                }
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

    fun send(text: String, queueMode: MessageQueueMode? = null) {
        val clean = text.trim()
        val current = state.value
        if (clean.isEmpty() || current.connecting || current.submittingMessage) return
        if (current.active != (queueMode != null)) return
        val storedId = current.selectedId ?: return
        val targetRuntimeId = runtimeId ?: storedId
        val model = current.modelCatalog.selected?.model
        val submittedDraft = captureDraftSubmission(storedId, text)
        val queuedMessage =
            queueMode?.let {
                val existingOccurrences =
                    current.timelineFor(storedId).count {
                        it is ChatItem.Message && it.role == "user" && it.text == clean
                    }
                val earlierQueued =
                    current.queuedMessages[storedId].orEmpty().count { it.text == clean }
                QueuedMessage(
                    id = ++queuedMessageSequence,
                    text = clean,
                    mode = it,
                    expectedUserMessageOccurrence = existingOccurrences + earlierQueued + 1,
                )
            }
        if (queuedMessage != null) {
            _state.update {
                it.withQueuedMessage(storedId, queuedMessage)
                    .copy(
                        submittingMessageSessionIds = it.submittingMessageSessionIds + storedId,
                        error = null,
                    )
            }
        } else {
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
                        activeSessionIds = it.activeSessionIds + storedId,
                        submittingMessageSessionIds = it.submittingMessageSessionIds + storedId,
                        error = null,
                    )
            }
        }
        viewModelScope.launch {
            runCatching {
                    messageSubmitter?.submit(targetRuntimeId, clean, model, queueMode)
                        ?: client?.submit(targetRuntimeId, clean, model, queueMode)
                        ?: error("Not connected")
                }
                .onSuccess {
                    _state.update { state ->
                        val updated =
                            if (queuedMessage == null) state
                            else
                                state.updateQueuedMessage(storedId, queuedMessage.id) {
                                    it.copy(submitting = false)
                                }
                        updated.copy(
                            submittingMessageSessionIds =
                                updated.submittingMessageSessionIds - storedId
                        )
                    }
                    submittedDraft?.let(::clearDraft)
                }
                .onFailure { error ->
                    val now = Instant.now()
                    _state.update {
                        val updated =
                            if (queuedMessage != null) {
                                it.removeQueuedMessage(storedId, queuedMessage.id)
                            } else {
                                val timeline = cancelRunningTools(it.timelineFor(storedId), now)
                                it.withTimeline(storedId, timeline)
                                    .copy(
                                        activeSessionIds = it.activeSessionIds - storedId,
                                        approval = null,
                                        clarify = null,
                                    )
                            }
                        updated.copy(
                            submittingMessageSessionIds =
                                updated.submittingMessageSessionIds - storedId
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

    fun selectWhisperThreadCount(configured: Int) {
        require(WhisperCpuConfig.isValid(configured))
        whisperModelStore.saveThreadCount(configured)
        _state.update { it.copy(whisperThreadCount = configured) }
    }

    fun toggleShowReasoning() {
        _state.update { it.copy(showReasoning = !it.showReasoning) }
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

    fun startVoiceRecording(target: VoiceMessageTarget) {
        check(voiceTranscription == null) { "A voice recording is already active" }
        check(voiceJobStore.load() == null) { "A voice transcription is already active" }
        val id = (++voiceTranscriptionSequence).toString()
        val model = state.value.whisperModel
        val threadCount = state.value.whisperThreadCount
        val audioFile = File(app.filesDir, "voice/$id.pcm")
        val recorder =
            LocalAudioRecorder(
                audioFile = audioFile,
                onSamplesRecorded = { samples -> updateVoiceRecording(id, samples) },
                onMaximumDuration = {
                    viewModelScope.launch {
                        updateVoiceStatus(
                            id,
                            "Maximum ${LocalAudioRecorder.MAX_RECORDING_MINUTES}-minute recording reached; finishing…",
                        )
                        stopVoiceRecording()
                    }
                },
            )
        voiceJobStore.save(
            VoiceJob(
                id = id,
                storedSessionId = target.storedSessionId,
                runtimeSessionId = target.runtimeSessionId,
                targetModel = target.model,
                modelId = model.id,
                threadCount = threadCount,
                audioPath = audioFile.path,
                totalSamples = 0,
                phase = VoiceJobPhase.RECORDING,
            )
        )
        voiceTranscription = ActiveVoiceTranscription(id, recorder, target)
        _state.update {
            it.copy(
                voiceRecording = true,
                transcribing = false,
                transcriptionStatus = null,
                transcriptionProgress = null,
                transcriptionProgressLabel = "0:00 recorded",
                transcriptionElapsedMs = null,
                transcriptionText = null,
                voiceTargetSessionId = target.storedSessionId,
            )
        }
        try {
            recorder.start()
        } catch (error: Throwable) {
            cancelVoiceOperation(id)
            throw error
        }
    }

    fun stopVoiceRecording() {
        val active = voiceTranscription ?: return
        if (!state.value.voiceRecording) return
        _state.update { it.copy(voiceRecording = false, transcribing = true) }
        viewModelScope.launch {
            runCatching { active.recorder!!.stop() }
                .onSuccess { tail ->
                    if (voiceTranscription?.id != active.id) return@onSuccess
                    val current = voiceJobStore.load()
                    if (current == null) {
                        showError(IllegalStateException("Voice transcription job disappeared"))
                        return@onSuccess
                    }
                    val job =
                        current.copy(
                            phase = VoiceJobPhase.TRANSCRIBING,
                            totalSamples = tail.totalSamples,
                            audioPath = tail.audioFile.path,
                        )
                    voiceJobStore.save(job)
                    _state.update {
                        it.copy(
                            transcriptionStatus = "Preparing local Whisper…",
                            transcriptionProgress = 0f,
                            transcriptionProgressLabel =
                                "0:00 of ${formatVoiceDuration(tail.totalSamples)}",
                        )
                    }
                    VoiceTranscriptionService.start(app, job)
                }
                .onFailure { error ->
                    cancelVoiceOperation(active.id)
                    showError(error)
                }
        }
    }

    fun cancelVoiceRecordingIfCapturing() {
        if (state.value.voiceRecording) cancelVoiceOperation(voiceTranscription?.id)
    }

    private fun updateVoiceRecording(id: String, samples: Long) {
        if (voiceTranscription?.id != id) return
        val job = voiceJobStore.load()?.takeIf { it.id == id } ?: return
        voiceJobStore.save(job.copy(totalSamples = samples))
        _state.update {
            it.copy(
                transcriptionProgress = null,
                transcriptionProgressLabel = "${formatVoiceDuration(samples)} recorded",
            )
        }
    }

    private fun handleVoiceJobUpdate(intent: Intent) {
        val id = intent.getStringExtra(VoiceTranscriptionService.EXTRA_JOB_ID) ?: return
        if (voiceTranscription?.id != id) return
        val phase =
            intent.getStringExtra(VoiceTranscriptionService.EXTRA_PHASE)?.let {
                runCatching { VoiceJobPhase.valueOf(it) }.getOrNull()
            } ?: return
        val completed = intent.getLongExtra(VoiceTranscriptionService.EXTRA_COMPLETED_SAMPLES, 0)
        val total = intent.getLongExtra(VoiceTranscriptionService.EXTRA_TOTAL_SAMPLES, 0)
        val elapsed =
            intent.getLongExtra(VoiceTranscriptionService.EXTRA_ELAPSED_MS, -1).takeIf { it >= 0 }
        val transcript = intent.getStringExtra(VoiceTranscriptionService.EXTRA_TRANSCRIPT)
        val error = intent.getStringExtra(VoiceTranscriptionService.EXTRA_ERROR)
        val job = voiceJobStore.load() ?: return
        voiceJobStore.save(
            job.copy(
                phase = phase,
                completedSamples = completed,
                totalSamples = total,
                elapsedMs = elapsed,
                transcript = transcript,
                error = error,
            )
        )
        when (phase) {
            VoiceJobPhase.TRANSCRIBING -> {
                _state.update {
                    it.copy(
                        voiceRecording = false,
                        transcribing = true,
                        transcriptionStatus =
                            it.transcriptionStatus ?: "Transcribing locally with Whisper…",
                        transcriptionProgress =
                            if (total > 0) (completed.toFloat() / total).coerceIn(0f, 1f) else 0f,
                        transcriptionProgressLabel =
                            "${formatVoiceDuration(completed)} of ${formatVoiceDuration(total)}" +
                                (elapsed?.let { value ->
                                    " • ${formatElapsedDuration(value)} transcribing"
                                } ?: ""),
                        transcriptionElapsedMs = elapsed,
                        transcriptionText =
                            intent.getStringExtra(VoiceTranscriptionService.EXTRA_TRANSCRIPT),
                    )
                }
            }
            VoiceJobPhase.COMPLETE -> {
                val text = transcript?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    sendVoiceMessage(activeTarget(job), text)
                    voiceJobStore.clear()
                    clearVoiceTranscription(id)
                } else
                    handleVoiceTranscriptionFailure(
                        id,
                        IllegalStateException("Whisper did not detect any speech"),
                    )
            }
            VoiceJobPhase.FAILED ->
                handleVoiceTranscriptionFailure(
                    id,
                    IllegalStateException(error ?: "Voice transcription failed"),
                )
            VoiceJobPhase.CANCELED -> clearVoiceTranscription(id)
            VoiceJobPhase.RECORDING -> Unit
        }
    }

    private fun activeTarget(job: VoiceJob) =
        VoiceMessageTarget(
            storedSessionId = job.storedSessionId,
            runtimeSessionId = job.runtimeSessionId,
            model = job.targetModel,
            connectionVersion = connectionVersion,
        )

    private fun recoverCompletedVoiceJob() {
        val job = voiceJobStore.load()?.takeIf { it.phase == VoiceJobPhase.COMPLETE } ?: return
        val text = job.transcript?.trim().orEmpty()
        if (text.isEmpty()) return
        if (voiceTranscription == null) {
            voiceTranscription = ActiveVoiceTranscription(job.id, null, activeTarget(job))
            _state.update { it.copy(voiceTargetSessionId = job.storedSessionId) }
        }
        sendVoiceMessage(activeTarget(job), text)
        voiceJobStore.clear()
        clearVoiceTranscription(job.id)
    }

    private fun restoreVoiceJob() {
        val job = voiceJobStore.load() ?: return
        when (job.phase) {
            VoiceJobPhase.TRANSCRIBING -> {
                voiceTranscription = ActiveVoiceTranscription(job.id, null, activeTarget(job))
                _state.update {
                    it.copy(
                        transcribing = true,
                        voiceTargetSessionId = job.storedSessionId,
                        transcriptionStatus = "Transcribing locally with Whisper…",
                        transcriptionProgress =
                            if (job.totalSamples > 0)
                                (job.completedSamples.toFloat() / job.totalSamples).coerceIn(0f, 1f)
                            else 0f,
                    )
                }
            }
            VoiceJobPhase.COMPLETE -> {
                voiceTranscription = ActiveVoiceTranscription(job.id, null, activeTarget(job))
                _state.update { it.copy(voiceTargetSessionId = job.storedSessionId) }
            }
            else -> Unit
        }
    }

    private fun handleVoiceTranscriptionFailure(id: String, error: Throwable) {
        if (voiceTranscription?.id != id) return
        cancelVoiceOperation(id)
        if (error !is CancellationException) showError(error)
    }

    private fun updateVoiceStatus(id: String, status: String) {
        if (voiceTranscription?.id == id) _state.update { it.copy(transcriptionStatus = status) }
    }

    private fun clearVoiceTranscription(id: String) {
        if (voiceTranscription?.id != id) return
        voiceTranscription = null
        clearVoiceState()
    }

    private fun cancelVoiceOperation(id: String?) {
        val active = voiceTranscription?.takeIf { id == null || it.id == id } ?: return
        voiceTranscription = null
        active.recorder?.discard()
        if (voiceJobStore.load()?.id == active.id) {
            VoiceTranscriptionService.cancel(app)
            voiceJobStore.clear()
        }
        clearVoiceState()
    }

    private fun clearVoiceState() {
        _state.update {
            it.copy(
                voiceRecording = false,
                transcribing = false,
                transcriptionStatus = null,
                transcriptionProgress = null,
                transcriptionProgressLabel = null,
                transcriptionElapsedMs = null,
                transcriptionText = null,
                voiceTargetSessionId = null,
            )
        }
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
                .copy(activeSessionIds = it.activeSessionIds + target.storedSessionId)
        }
        viewModelScope.launch {
            runCatching { api.submit(target.runtimeSessionId, clean, target.model) }
                .onSuccess {}
                .onFailure { error ->
                    val now = Instant.now()
                    _state.update {
                        val timeline =
                            cancelRunningTools(it.timelineFor(target.storedSessionId), now)
                        it.withTimeline(target.storedSessionId, timeline)
                            .copy(activeSessionIds = it.activeSessionIds - target.storedSessionId)
                    }
                    showError(error)
                }
        }
    }

    fun reportError(error: Throwable) = showError(error)

    fun reconnectNow() {
        _state.update { it.copy(reconnectSeconds = null) }
        client?.reconnectNow()
    }

    fun archiveSession(sessionId: String) {
        val api = client ?: return
        val version = connectionVersion
        viewModelScope.launch {
            runCatching { api.archiveSession(sessionId) }
                .onSuccess { archived ->
                    if (client === api && connectionVersion == version) {
                        _state.update { it.withSessionState(archived) }
                    }
                }
                .onFailure { if (client === api && connectionVersion == version) showError(it) }
        }
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
        _state.update { current ->
            val session = current.sessions.firstOrNull { it.id == sessionId }
            if (session?.sessionState?.running == true || session?.active == true) current
            else current.copy(unreadCounts = addUnread(current.unreadCounts, sessionId))
        }
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

    private fun refreshChatGptUsage() {
        val api = client ?: return
        val version = connectionVersion
        chatGptUsageJob?.cancel()
        chatGptUsageJob =
            viewModelScope.launch {
                val usage = runCatching { api.chatGptUsage() }.getOrNull() ?: return@launch
                if (client === api && connectionVersion == version) {
                    _state.update { it.copy(chatGptUsage = usage) }
                }
            }
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
        if (event.type == "connection.restored") {
            val api = client ?: return
            val version = connectionVersion
            viewModelScope.launch { runCatching { refreshSessions(api, version) } }
            return
        }
        if (event.type == "connection.lost") {
            // Older Harness deployments do not expose /events yet. Keep the
            // chat usable through the existing per-session SSE stream while
            // the account-wide connection retries.
            val selected = state.value.selectedId
            if (selected != null) client?.watchSession(runtimeId ?: selected, overviewCursor)
            return
        }

        // Keep the event-detail list live when it is already open. Unknown event
        // types are retained as raw events by the transport as well.
        val raw = event.rawEvent
        val rawSession = event.sessionId
        if (raw != null && rawSession != null && state.value.sessionEventsFor == rawSession) {
            val parsed = SessionEvent.fromJson(raw)
            _state.update { current ->
                val events = (current.sessionEvents + parsed).distinctBy { it.id ?: -1L }
                current.copy(sessionEvents = events.sortedBy { it.id ?: Long.MIN_VALUE })
            }
        }

        if (event.type == "session.created") {
            val sessionId = event.sessionId ?: return
            val rawPayload = raw?.get("payload") as? JsonObject
            val rawTags = raw?.get("tags") as? JsonObject
            val session =
                HarnessSession(
                    id = sessionId,
                    title = rawPayload?.string("title") ?: "Untitled session",
                    updatedAt =
                        raw?.get("created_at_ms")?.jsonPrimitive?.longOrNull?.let {
                            Instant.ofEpochMilli(it).toString()
                        },
                    parentSessionId = rawTags?.string("parent_session"),
                    tags =
                        (rawPayload?.get("tags") as? JsonArray)
                            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                            ?.toSet()
                            .orEmpty(),
                    eventId = raw?.get("id")?.jsonPrimitive?.longOrNull,
                )
            _state.update { current ->
                val sessions = mergeSessionsById(current.sessions, listOf(session))
                val parent = session.parentSessionId
                current.copy(
                    sessions = sessions,
                    childSessions =
                        parent?.let { id ->
                            current.childSessions +
                                (id to
                                    mergeSessionsById(
                                        current.childSessions[id].orEmpty(),
                                        listOf(session),
                                    ))
                        } ?: current.childSessions,
                )
            }
            return
        }
        if (event.type == "session.renamed") {
            val sessionId = event.sessionId ?: return
            val title =
                event.payload["title"]?.jsonPrimitive?.contentOrNull
                    ?: (raw?.get("payload") as? JsonObject)?.string("title")
                    ?: return
            _state.update { current ->
                current.copy(
                    sessions =
                        current.sessions.map {
                            if (it.id == sessionId) it.copy(title = title) else it
                        },
                    childSessions =
                        current.childSessions.mapValues { (_, children) ->
                            children.map { if (it.id == sessionId) it.copy(title = title) else it }
                        },
                )
            }
            return
        }

        if (event.type == "session.state") {
            val stored = storedSessionId(event) ?: return
            val sessionState =
                HarnessSessionState.fromJson(JsonObject(event.payload)).copy(sessionId = stored)
            if (!sessionState.running && !sessionState.finished) return
            val now = Instant.now()
            _state.update { current ->
                val updated = current.withSessionState(sessionState)
                if (sessionState.finished && updated.selectedId == stored)
                    updated
                        .withCurrentItems(cancelRunningTools(updated.items, now))
                        .copy(approval = null, clarify = null)
                else updated
            }
            if (sessionState.finished && state.value.selectedId == stored) refreshTokenUsage()
            if (sessionState.finished) {
                viewModelScope.launch {
                    delay(1_000)
                    refreshChatGptUsage()
                }
            }
            return
        }
        if (event.type == "secret.ask") {
            val eventId = event.payload["event_id"]?.jsonPrimitive?.longOrNull
            val identifier = event.payload["identifier"]?.jsonPrimitive?.contentOrNull
            val description = event.payload["description"]?.jsonPrimitive?.contentOrNull
            val container = event.payload["container"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val sessionId = event.sessionId
            if (
                sessionId != null &&
                    eventId != null &&
                    !identifier.isNullOrBlank() &&
                    !description.isNullOrBlank()
            ) {
                _state.update {
                    it.copy(
                        pendingSecrets =
                            it.pendingSecrets +
                                (sessionId to
                                    PendingSecret(eventId, identifier, description, container))
                    )
                }
            }
            return
        }
        val current = runtimeId
        if (event.sessionId != null && event.sessionId != current) {
            if (
                event.type == "tool.complete" &&
                    event.payload["name"]?.jsonPrimitive?.contentOrNull == "retrieve-secret"
            ) {
                _state.update { it.copy(pendingSecrets = it.pendingSecrets - event.sessionId) }
            }
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
                            activeSessionIds =
                                it.selectedId?.let { id -> it.activeSessionIds - id }
                                    ?: it.activeSessionIds,
                            approval = null,
                            clarify = null,
                        )
                }
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
                        modelSelectionFromSessionInfo(provider, model, it.modelCatalog.selected)
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
            "message.user" -> applyUserMessageEvent(event)
            "message.reasoning" -> applyReasoningEvent(event)
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
                                onlyPending = true,
                                canonical = true,
                            )
                        )
                        .copy(
                            activeSessionIds =
                                it.selectedId?.let { id -> it.activeSessionIds - id }
                                    ?: it.activeSessionIds,
                            approval = null,
                            clarify = null,
                        )
                }
                incrementUnread(state.value.selectedId)
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
                if (event.type == "tool.complete" && name == "retrieve-secret") {
                    event.sessionId?.let { sessionId ->
                        _state.update { current ->
                            if (sessionId !in current.pendingSecrets) current
                            else current.copy(pendingSecrets = current.pendingSecrets - sessionId)
                        }
                    }
                }
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
                _state.update {
                    it.copy(
                        activeSessionIds =
                            it.selectedId?.let { id -> it.activeSessionIds - id }
                                ?: it.activeSessionIds,
                        approval = null,
                        clarify = null,
                    )
                }
                showError(
                    IllegalStateException(
                        event.payload["message"]?.jsonPrimitive?.contentOrNull
                            ?: "Unknown Harness error"
                    )
                )
            }
        }
    }

    /** Apply the canonical user message from the WebSocket without reloading history. */
    private fun applyUserMessageEvent(event: GatewayEvent) {
        val sessionId = storedSessionId(event) ?: return
        if (state.value.selectedId != sessionId) return
        val text = event.payload["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val messageId =
            event.payload["message_id"]?.jsonPrimitive?.contentOrNull
                ?: event.rawEvent?.get("id")?.jsonPrimitive?.contentOrNull
        val timestamp = event.payload.instant()
        _state.update { current ->
            val timeline = current.items.toMutableList()
            val pendingIndex =
                timeline.indexOfLast {
                    it is ChatItem.Message &&
                        it.role == "user" &&
                        (it.id == messageId || (it.pendingCanonical && it.text == text))
                }
            val canonical =
                ChatItem.Message(
                    role = "user",
                    text = text,
                    id = messageId,
                    timestamp = timestamp,
                    uiKey =
                        if (pendingIndex >= 0) (timeline[pendingIndex] as ChatItem.Message).uiKey
                        else null,
                    pendingCanonical = false,
                )
            if (pendingIndex >= 0) timeline[pendingIndex] = canonical else timeline += canonical
            current
                .withCurrentItems(timeline)
                .consumeQueuedMessage(sessionId, text)
                .copy(activeSessionIds = current.activeSessionIds + sessionId)
        }
    }

    /** Preserve reasoning from a function-call assistant event without history reload. */
    private fun applyReasoningEvent(event: GatewayEvent) {
        val sessionId = storedSessionId(event) ?: return
        if (state.value.selectedId != sessionId) return
        val text = event.payload["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (text.isBlank()) return
        val messageId = event.payload["message_id"]?.jsonPrimitive?.contentOrNull
        val summary = event.payload["is_summary"]?.jsonPrimitive?.booleanOrNull == true
        _state.update { current ->
            val timeline = current.items.toMutableList()
            val index =
                timeline.indexOfLast {
                    it is ChatItem.Message &&
                        it.role == "assistant" &&
                        (it.id == messageId || it.text.isBlank())
                }
            val reasoning =
                if (index >= 0)
                    (timeline[index] as ChatItem.Message).let { existing ->
                        existing.copy(
                            id = messageId ?: existing.id,
                            reasoning =
                                listOfNotNull(existing.reasoning?.takeIf(String::isNotBlank), text)
                                    .joinToString("\n"),
                            reasoningIsSummary = summary,
                        )
                    }
                else
                    ChatItem.Message(
                        role = "assistant",
                        text = "",
                        id = messageId,
                        timestamp = event.payload.instant(),
                        reasoning = text,
                        reasoningIsSummary = summary,
                    )
            if (index >= 0) timeline[index] = reasoning else timeline += reasoning
            current.withCurrentItems(timeline)
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
        if (state.value.voiceRecording) cancelVoiceOperation(null)
        runCatching { app.unregisterReceiver(voiceJobReceiver) }
        dispatchClose(localWhisper)
        dispatchClose(client)
        client = null
        super.onCleared()
    }
}

internal fun pendingSecretFromHistoryRows(rows: List<JsonObject>): PendingSecret? {
    val pending = linkedMapOf<Long, PendingSecret>()
    rows.forEach { row ->
        when (row.string("event_name")) {
            "secret.ask" -> {
                val eventId = row["id"]?.jsonPrimitive?.longOrNull ?: return@forEach
                val metadata = row["metadata"] as? JsonObject ?: return@forEach
                val identifier = metadata.string("identifier") ?: return@forEach
                val description = row["content"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                pending[eventId] =
                    PendingSecret(
                        eventId = eventId,
                        identifier = identifier,
                        description = description,
                        container = metadata.string("container").orEmpty(),
                    )
            }
            "chat.message.tool.created" -> {
                if (row.string("tool") != "retrieve-secret") return@forEach
                val metadata = row["metadata"] as? JsonObject ?: return@forEach
                val askEventId = metadata["secret_ask_event_id"]?.jsonPrimitive?.longOrNull
                if (askEventId != null) pending.remove(askEventId)
            }
        }
    }
    return pending.values.lastOrNull()
}

internal fun messagesFromHistoryRow(row: JsonObject): List<ChatItem> {
    val role = row.string("role") ?: "assistant"
    val eventName = row.string("event_name")
    // queued.message is a pending command, not chat history. It becomes a
    // canonical user message only when the server releases it at its boundary.
    if (eventName == "queued.message") return emptyList()
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
    val reasoning = raw.reasoningContent()
    if (text.isNotBlank() || reasoning != null)
        result +=
            ChatItem.Message(
                role,
                text,
                row.string("id"),
                timestamp = timestamp,
                reasoning = reasoning?.text,
                reasoningIsSummary = reasoning?.isSummary ?: false,
            )
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
    onlyPending: Boolean = false,
    canonical: Boolean = false,
): List<ChatItem> {
    if (finalText.isBlank()) return items
    // A durable completion may be replayed after history has already loaded it.
    // Match its stable server ID first so the replay updates the existing item
    // instead of appending a second, plain streaming item.
    val stableIndex =
        id?.let { messageId ->
            items.indexOfLast {
                it is ChatItem.Message && it.role == "assistant" && it.id == messageId
            }
        } ?: -1
    if (stableIndex >= 0)
        return items.toMutableList().apply {
            val current = this[stableIndex] as ChatItem.Message
            this[stableIndex] =
                current.copy(
                    text = finalText,
                    timestamp = timestamp ?: current.timestamp,
                    pendingCanonical = if (canonical) false else current.pendingCanonical,
                )
        }
    val index =
        items.indexOfLast {
            it is ChatItem.Message &&
                it.role == "assistant" &&
                (!onlyPending || it.pendingCanonical)
        }
    if (index == -1)
        return items +
            ChatItem.Message(
                "assistant",
                finalText,
                id = id,
                timestamp = timestamp,
                uiKey = uiKey,
                pendingCanonical = !canonical,
            )
    return items.toMutableList().apply {
        val current = this[index] as ChatItem.Message
        this[index] =
            current.copy(
                text = finalText,
                id = id ?: current.id,
                timestamp = timestamp ?: current.timestamp,
                pendingCanonical = !canonical,
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
        val session = sessions.firstOrNull { it.id == key || it.runtimeId == key }
        if (session?.sessionState?.running == true || session?.active == true) return@fold result
        val storedId = session?.id ?: key
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
