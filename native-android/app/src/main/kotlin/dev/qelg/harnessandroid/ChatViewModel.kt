package dev.qelg.harnessandroid

import android.app.Application
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.qelg.harnessandroid.data.*
import dev.qelg.harnessandroid.voice.*
import java.io.Closeable
import java.io.File
import java.time.Instant
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@JvmInline value class ErrorMessage(val text: String)

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

data class VoiceMessageTarget(
    val storedSessionId: String,
    val runtimeSessionId: String,
    val model: String?,
    val connectionVersion: Long,
)

data class QueuedMessage(
    val id: Long,
    val text: String,
    val mode: MessageQueueMode,
    val expectedUserMessageOccurrence: Int,
    val submitting: Boolean = true,
)

internal fun interface MessageSubmitter {
    suspend fun submit(
        sessionId: String,
        text: String,
        model: String?,
        queueMode: MessageQueueMode?,
    ): JsonObject
}

/** Central-state view model. All mutations name their owning [LocalUiState] or [HarnessState]. */
class ChatViewModel
private constructor(
    application: Application,
    private val savedState: SavedStateHandle,
    initialState: ChatUiState?,
    private val messageSubmitter: MessageSubmitter?,
    private val internal: Unit,
) : AndroidViewModel(application) {
    constructor(
        application: Application,
        savedState: SavedStateHandle,
    ) : this(application, savedState, null, null, Unit)

    internal constructor(
        application: Application,
        savedState: SavedStateHandle,
        initialState: ChatUiState,
        messageSubmitter: MessageSubmitter,
    ) : this(application, savedState, initialState, messageSubmitter, Unit)

    private val whisperModelStore = WhisperModelStore(application)
    private val _state =
        MutableStateFlow(
            initialState
                ?: ChatUiState(
                    ui =
                        LocalUiState(
                            selectedSessionId =
                                savedState.get<String>("selectedId")?.let(::SessionId),
                            whisperModel = whisperModelStore.load(),
                            whisperThreadCount = whisperModelStore.loadThreadCount(),
                        )
                )
        )
    val state: StateFlow<ChatUiState> = _state.asStateFlow()
    private var sequence = 0L
    private var connectionJob: Job? = null
    private var eventJob: Job? = null
    private var refreshJob: Job? = null
    private var modelsJob: Job? = null
    private var containersJob: Job? = null
    private var usageJob: Job? = null
    private var chatGptUsageJob: Job? = null
    private var client: HarnessClient? = null
    private var connectionVersion = 0L
    private var selectionGeneration = 0L
    private var appStarted = false
    private val credentials = SecureCredentials(application)
    private val draftStore = DraftStore(application)
    private val readStateStore = ReadStateStore(application)
    private var draftNamespace = ""
    private val draftRevisions = mutableMapOf<Pair<String, SessionId>, Long>()
    private val updateManager = UpdateManager(application)
    private val app = application
    private val localWhisper = LocalWhisper(application)
    private val voiceJobStore = VoiceJobStore(application)
    private var voiceTranscription: ActiveVoiceTranscription? = null
    private var voiceSequence = 0L
    private var overviewCursor = 0L
    private val voiceReceiver =
        object : android.content.BroadcastReceiver() {
            override fun onReceive(
                context: android.content.Context,
                intent: android.content.Intent,
            ) {
                handleVoiceJobUpdate(intent)
            }
        }
    val updateState: UpdateManager
        get() = updateManager

    init {
        ContextCompat.registerReceiver(
            application,
            voiceReceiver,
            android.content.IntentFilter(VoiceTranscriptionService.ACTION_UPDATE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        restoreVoiceJob()
        credentials.load()?.let(::connect)
        viewModelScope.launch {
            updateManager.state.collect { value ->
                _state.update { it.copy(ui = it.ui.copy(updateState = value)) }
            }
        }
    }

    fun onAppStarted() {
        appStarted = true
        if (state.value.harness.connection is ConnectionState.Connected)
            client?.watchEvents(overviewCursor)
    }

    fun onAppStopped() {
        appStarted = false
        client?.stopWatching()
    }

    fun reportError(error: Throwable) =
        _state.update {
            it.copy(ui = it.ui.copy(error = ErrorMessage(error.message ?: "Unknown error")))
        }

    fun setSearch(value: String) = _state.update { it.copy(ui = it.ui.copy(search = value)) }

    fun toggleShowReasoning() =
        _state.update { it.copy(ui = it.ui.copy(showReasoning = !it.ui.showReasoning)) }

    fun toggleShowArchived() =
        _state.update { it.copy(ui = it.ui.copy(showArchived = !it.ui.showArchived)) }

    fun hideTree() = _state.update { it.copy(ui = it.ui.copy(treeParentSessionId = null)) }

    fun backFromChat() =
        _state.update {
            it.copy(ui = it.ui.copy(selectedSessionId = null, treeParentSessionId = null))
        }

    fun showTree(id: SessionId) {
        if (
            directChildSessions(state.value, id).isEmpty() &&
                state.value.harness.sessionsById[id]?.children is ChildrenLoadState.Loaded
        ) {
            select(id)
        } else {
            _state.update { it.copy(ui = it.ui.copy(treeParentSessionId = id)) }
            loadChildSessions(id.value)
        }
    }

    fun select(id: SessionId, selectedFromTree: Boolean = false) {
        if (state.value.harness.sessionsById[id] == null) return
        savedState["selectedId"] = id.value
        val generation = ++selectionGeneration
        _state.update { current ->
            val begun =
                ChatReducer.beginSessionLoad(
                    current,
                    id.value,
                    current.harness.lastAppliedEventId?.value,
                    generation,
                )
            begun.copy(
                ui =
                    begun.ui.copy(
                        selectedSessionId = id,
                        error = null,
                        treeParentSessionId =
                            if (selectedFromTree) begun.ui.treeParentSessionId else null,
                    )
            )
        }
        val api = client ?: return
        refreshModels()
        // These calls deliberately have independent completion paths. A usable message timeline
        // must not wait for /events, while the shared generation barrier stays pending until both
        // authoritative responses have succeeded.
        viewModelScope.launch {
            runCatching { api.history(id.value) }
                .onSuccess { messages ->
                    _state.update { current ->
                        if (
                            client !== api ||
                                current.ui.selectedSessionId != id ||
                                !ChatReducer.hasMatchingHistoryLoad(current, id.value, generation)
                        )
                            current
                        else {
                            val loaded =
                                ChatReducer.completeHistory(current, id.value, messages, generation)
                            val secret = pendingSecretFromHistoryRows(messages)
                            loaded.copy(
                                ui =
                                    loaded.ui.copy(
                                        pendingSecrets =
                                            if (secret == null) loaded.ui.pendingSecrets - id
                                            else loaded.ui.pendingSecrets + (id to secret)
                                    )
                            )
                        }
                    }
                }
                .onFailure { error ->
                    _state.update { current ->
                        if (client !== api || current.ui.selectedSessionId != id) current
                        else
                            ChatReducer.failHistory(
                                current,
                                id.value,
                                generation,
                                ErrorMessage(error.message ?: "History could not be loaded"),
                            )
                    }
                }
        }
        viewModelScope.launch {
            runCatching { api.sessionEvents(id.value) }
                .onSuccess { events ->
                    _state.update { current ->
                        if (client !== api || current.ui.selectedSessionId != id) current
                        else ChatReducer.completeEventDetails(current, id.value, events, generation)
                    }
                }
                .onFailure { error ->
                    _state.update { current ->
                        if (client !== api || current.ui.selectedSessionId != id) current
                        else
                            ChatReducer.failEventDetails(
                                current,
                                id.value,
                                generation,
                                ErrorMessage(error.message ?: "Events could not be loaded"),
                            )
                    }
                }
        }
    }

    fun selectContainerSession(container: HarnessContainer) {
        val id = SessionId(container.sessionId)
        if (state.value.harness.sessionsById.containsKey(id)) select(id)
        else reportError(IllegalStateException("Container session is not in the current snapshot"))
    }

    fun connect(config: ConnectionConfig) {
        if (!config.isAllowedEndpoint()) {
            reportError(
                IllegalArgumentException(
                    "Use HTTPS, or HTTP only for localhost, private LAN, or Tailscale endpoints."
                )
            )
            return
        }
        credentials.save(config)
        cancelConnectionWork()
        client?.stopWatching()
        dispatchClose(client)
        val api = HarnessClient(config, viewModelScope)
        client = api
        val version = ++connectionVersion
        draftNamespace = config.normalizedBaseUrl
        val drafts = draftStore.load(draftNamespace)
        val reads = readStateStore.load(draftNamespace)
        overviewCursor = 0L
        _state.update { current ->
            current.copy(
                ui =
                    current.ui.copy(
                        configured = true,
                        sessionUi =
                            drafts
                                .mapKeys { SessionId(it.key) }
                                .mapValues { SessionUiState(draft = it.value) },
                        readUpdates = reads.mapKeys { SessionId(it.key) },
                        pendingSecrets = emptyMap(),
                        unreadCounts = emptyMap(),
                        deletingContainerIds = emptySet(),
                        approval = null,
                        clarify = null,
                        error = null,
                        reconnectSeconds = null,
                    ),
                harness =
                    current.harness.copy(
                        connection = ConnectionState.Connecting,
                        lastAppliedEventId = null,
                        sessionsById = emptyMap(),
                        resources = HarnessResources(),
                    ),
            )
        }
        eventJob =
            viewModelScope.launch {
                api.events.collect { event ->
                    if (client === api && connectionVersion == version) {
                        // The socket may reconnect immediately after a failure. Only expose a
                        // durable cursor for resume after the reducer has returned from every
                        // semantic branch (including its normal failure/error reductions).
                        handleTransport(event)
                        if (event.durable) api.acknowledgeCursor(event.cursor)
                    }
                }
            }
        connectionJob =
            viewModelScope.launch {
                runCatching {
                        api.connect()
                        api.sessionsSnapshot()
                    }
                    .onSuccess { snapshot ->
                        if (client !== api || connectionVersion != version) return@onSuccess
                        val stateLoadId = LoadId(++sequence)
                        val parsed =
                            snapshot.sessions.map(HarnessSession::fromJson).map { row ->
                                sessionDataFromTransport(
                                    row,
                                    SynchronizedData.PendingHistory(
                                        row.sessionState
                                            ?: row.active
                                                .takeIf { it }
                                                ?.let {
                                                    HarnessSessionState(
                                                        row.id,
                                                        "running",
                                                        eventId = row.eventId,
                                                    )
                                                },
                                        eventId(snapshot.cursor),
                                        stateLoadId,
                                    ),
                                )
                            }
                        val sessionIds = parsed.map(SessionData::id)
                        _state.update { current ->
                            val merged = ChatReducer.mergeSnapshot(current, parsed, snapshot.cursor)
                            // This is the sole snapshot-to-stream acknowledgement. It happens on
                            // initial connection before watchEvents; refresh snapshots never move
                            // it.
                            val baselined =
                                ChatReducer.establishSubscriptionBaseline(merged, snapshot.cursor)
                            overviewCursor = baselined.harness.lastAppliedEventId?.value ?: 0L
                            baselined.copy(
                                harness =
                                    baselined.harness.copy(connection = ConnectionState.Connected)
                            )
                        }
                        if (client !== api || connectionVersion != version) return@onSuccess
                        // Every auxiliary request is independent of the chat connection. In
                        // particular, /session-states is a fallback: failure leaves its records
                        // pending (and retryable), not a failed connection.
                        viewModelScope.launch {
                            runCatching { api.sessionStates() }
                                .onSuccess { states ->
                                    if (client === api && connectionVersion == version)
                                        _state.update {
                                            ChatReducer.completeSessionStateSync(
                                                it,
                                                sessionIds,
                                                states,
                                                stateLoadId,
                                            )
                                        }
                                }
                                .onFailure { error ->
                                    if (client === api && connectionVersion == version)
                                        _state.update {
                                            ChatReducer.failSessionStateSync(
                                                it,
                                                sessionIds,
                                                stateLoadId,
                                                ErrorMessage(
                                                    error.message
                                                        ?: "Session states could not be loaded"
                                                ),
                                            )
                                        }
                                }
                        }
                        refreshModels()
                        refreshContainers()
                        refreshChatGptUsage(api, version)
                        recoverCompletedVoiceJob()
                        if (appStarted) api.watchEvents(overviewCursor)
                        savedState.get<String>("selectedId")?.let { saved ->
                            overviewSessions(_state.value)
                                .firstOrNull { it.id.value == saved }
                                ?.let { select(it.id) }
                        }
                    }
                    .onFailure { error ->
                        if (client === api && connectionVersion == version) {
                            _state.update {
                                it.copy(
                                    harness =
                                        it.harness.copy(
                                            connection =
                                                ConnectionState.Failed(
                                                    ErrorMessage(
                                                        error.message ?: "Connection failed"
                                                    )
                                                )
                                        )
                                )
                            }
                            reportError(error)
                        }
                    }
            }
    }

    private fun cancelConnectionWork() {
        connectionJob?.cancel()
        eventJob?.cancel()
        refreshJob?.cancel()
        modelsJob?.cancel()
        containersJob?.cancel()
        usageJob?.cancel()
        chatGptUsageJob?.cancel()
    }

    fun disconnect() {
        cancelConnectionWork()
        client?.stopWatching()
        dispatchClose(client)
        client = null
        connectionVersion++
        selectionGeneration++
        credentials.clear()
        savedState["selectedId"] = null
        _state.value =
            ChatUiState(
                ui =
                    LocalUiState(
                        whisperModel = whisperModelStore.load(),
                        whisperThreadCount = whisperModelStore.loadThreadCount(),
                    )
            )
    }

    fun createSession() {
        val current = state.value
        if (isStateConnecting(current) || selectedSession(current)?.active == true) return
        val api = client ?: return reportError(IllegalStateException("Not connected"))
        viewModelScope.launch {
            runCatching { api.createSession(state.value.harness.resources.models.value?.selected) }
                .onSuccess { row ->
                    val session = HarnessSession.fromJson(row)
                    _state.update {
                        ChatReducer.mergeSessions(it, listOf(sessionDataFromTransport(session)))
                            .copy(ui = it.ui.copy(selectedSessionId = SessionId(session.id)))
                    }
                    select(SessionId(session.id))
                }
                .onFailure(::reportError)
        }
    }

    fun refreshModels() {
        val api = client ?: return
        val version = connectionVersion
        val id = state.value.ui.selectedSessionId?.value
        modelsJob?.cancel()
        _state.update {
            it.copy(
                harness =
                    it.harness.copy(
                        resources =
                            it.harness.resources.copy(
                                models =
                                    it.harness.resources.models.copy(loading = true, error = null)
                            )
                    )
            )
        }
        modelsJob =
            viewModelScope.launch {
                runCatching { api.modelOptions(id) }
                    .onSuccess { value ->
                        if (client === api && connectionVersion == version)
                            _state.update { current ->
                                val catalog = value.copy(selected = null)
                                var next =
                                    current.copy(
                                        harness =
                                            current.harness.copy(
                                                resources =
                                                    current.harness.resources.copy(
                                                        models = RequestState(catalog)
                                                    )
                                            )
                                    )
                                id?.let { sessionId ->
                                    next =
                                        ChatReducer.setSessionModelSelection(
                                            next,
                                            SessionId(sessionId),
                                            value.selected,
                                        )
                                }
                                next
                            }
                    }
                    .onFailure { error ->
                        if (client === api && connectionVersion == version)
                            _state.update {
                                it.copy(
                                    harness =
                                        it.harness.copy(
                                            resources =
                                                it.harness.resources.copy(
                                                    models =
                                                        it.harness.resources.models.copy(
                                                            loading = false,
                                                            error =
                                                                ErrorMessage(
                                                                    error.message
                                                                        ?: "Models could not be loaded"
                                                                ),
                                                        )
                                                )
                                        )
                                )
                            }
                    }
            }
    }

    fun selectModel(selection: ModelSelection) {
        val id = state.value.ui.selectedSessionId ?: return
        _state.update { ChatReducer.setSessionModelSelection(it, id, selection) }
        client?.let { api ->
            viewModelScope.launch {
                runCatching { api.selectModel(id.value, selection) }.onFailure(::reportError)
            }
        }
    }

    fun loadChildSessions(sessionId: String) {
        val api = client ?: return
        val version = connectionVersion
        val id = SessionId(sessionId)
        when (state.value.harness.sessionsById[id]?.children) {
            is ChildrenLoadState.Loading,
            is ChildrenLoadState.Loaded -> return
            else -> Unit
        }
        val generation = LoadId(++sequence)
        _state.update { state ->
            state.harness.sessionsById[id]?.let { record ->
                state.copy(
                    harness =
                        state.harness.copy(
                            sessionsById =
                                state.harness.sessionsById +
                                    (id to
                                        record.copy(
                                            children =
                                                ChildrenLoadState.Loading(
                                                    generation,
                                                    state.harness.lastAppliedEventId,
                                                )
                                        ))
                        )
                )
            } ?: state
        }
        viewModelScope.launch {
            // Both results are one authoritative child-load transaction. In particular, a
            // failed state request after successful child discovery must leave the parent
            // retryable rather than stranded in Loading.
            runCatching {
                    coroutineScope {
                        val children = async {
                            api.childSessions(sessionId).map(HarnessSession::fromJson)
                        }
                        val states = async { api.sessionStates() }
                        children.await() to states.await()
                    }
                }
                .onSuccess { (rows, fetchedStates) ->
                    val childIds = rows.map { SessionId(it.id) }
                    _state.update { current ->
                        val loading =
                            current.harness.sessionsById[id]?.children as? ChildrenLoadState.Loading
                        if (
                            client !== api ||
                                connectionVersion != version ||
                                loading?.loadId != generation
                        )
                            current
                        else {
                            val additions =
                                rows.map { row ->
                                    sessionDataFromTransport(
                                        row,
                                        SynchronizedData.PendingHistory(
                                            row.sessionState
                                                ?: row.active
                                                    .takeIf { it }
                                                    ?.let {
                                                        HarnessSessionState(
                                                            row.id,
                                                            "running",
                                                            eventId = row.eventId,
                                                        )
                                                    },
                                            loading.boundary,
                                            generation,
                                        ),
                                    )
                                }
                            val withChildren = ChatReducer.mergeSessions(current, additions)
                            val merged =
                                ChatReducer.completeSessionStateSync(
                                    withChildren,
                                    childIds,
                                    fetchedStates,
                                    generation,
                                )
                            val parent = merged.harness.sessionsById[id] ?: return@update current
                            merged.copy(
                                harness =
                                    merged.harness.copy(
                                        sessionsById =
                                            merged.harness.sessionsById +
                                                (id to
                                                    parent.copy(
                                                        children =
                                                            ChildrenLoadState.Loaded(
                                                                merged.harness.lastAppliedEventId
                                                            )
                                                    ))
                                    )
                            )
                        }
                    }
                }
                .onFailure { error ->
                    _state.update { current ->
                        val loading =
                            current.harness.sessionsById[id]?.children as? ChildrenLoadState.Loading
                        if (
                            client !== api ||
                                connectionVersion != version ||
                                loading?.loadId != generation
                        )
                            current
                        else {
                            val parent = current.harness.sessionsById[id] ?: return@update current
                            current.copy(
                                harness =
                                    current.harness.copy(
                                        sessionsById =
                                            current.harness.sessionsById +
                                                (id to
                                                    parent.copy(
                                                        children =
                                                            ChildrenLoadState.Failed(
                                                                current.harness.lastAppliedEventId,
                                                                ErrorMessage(
                                                                    error.message
                                                                        ?: "Children could not be loaded"
                                                                ),
                                                            )
                                                    ))
                                    )
                            )
                        }
                    }
                }
        }
    }

    fun loadSessionEvents(force: Boolean = false) {
        val api = client ?: return
        val id = state.value.ui.selectedSessionId ?: return
        if (!force && eventDetailsFor(state.value, id).isNotEmpty()) return
        val generation = ++selectionGeneration
        _state.update { ChatReducer.beginEventDetails(it, id.value, generation) }
        viewModelScope.launch {
            runCatching { api.sessionEvents(id.value) }
                .onSuccess { events ->
                    _state.update {
                        ChatReducer.completeEventDetails(it, id.value, events, generation)
                    }
                }
                .onFailure { error ->
                    _state.update {
                        ChatReducer.failEventDetails(
                            it,
                            id.value,
                            generation,
                            ErrorMessage(error.message ?: "Events could not be loaded"),
                        )
                    }
                }
        }
    }

    fun setDraft(text: String) {
        val id = state.value.ui.selectedSessionId ?: return
        val key = draftNamespace to id
        draftRevisions[key] = (draftRevisions[key] ?: 0) + 1
        draftStore.save(draftNamespace, id.value, text)
        mutateSelectedUi { it.copy(draft = text) }
    }

    fun setSecretDraft(text: String) = mutateSelectedUi { it.copy(secretDraft = text) }

    fun sendSecret(text: String) {
        val id = state.value.ui.selectedSessionId ?: return
        val request = state.value.ui.pendingSecrets[id] ?: return
        val api = client ?: return reportError(IllegalStateException("Not connected"))
        if (text.isBlank() || sessionUi(state.value, id).uploadingSecret) return
        val version = connectionVersion
        mutateSessionUi(id) { it.copy(uploadingSecret = true) }
        viewModelScope.launch {
            runCatching { api.submitSecret(request.eventId, request.identifier, text) }
                .onSuccess {
                    if (client === api && connectionVersion == version) {
                        // The prompt remains authoritative until retrieve-secret completes.
                        mutateSessionUi(id) { it.copy(secretDraft = "", uploadingSecret = false) }
                    }
                }
                .onFailure { error ->
                    if (client === api && connectionVersion == version) {
                        mutateSessionUi(id) { it.copy(uploadingSecret = false) }
                        reportError(error)
                    }
                }
        }
    }

    private fun mutateSelectedUi(transform: (SessionUiState) -> SessionUiState) =
        _state.update { state ->
            val id = state.ui.selectedSessionId ?: return@update state
            state.copy(
                ui =
                    state.ui.copy(
                        sessionUi = state.ui.sessionUi + (id to transform(sessionUi(state, id)))
                    )
            )
        }

    private fun mutateSessionUi(id: SessionId, transform: (SessionUiState) -> SessionUiState) =
        _state.update { state ->
            state.copy(
                ui =
                    state.ui.copy(
                        sessionUi = state.ui.sessionUi + (id to transform(sessionUi(state, id)))
                    )
            )
        }

    fun send(text: String, queueMode: MessageQueueMode? = null) {
        val snapshot = state.value
        val id = snapshot.ui.selectedSessionId ?: return
        val clean = text.trim()
        val active = selectedSession(snapshot)?.active == true
        if (clean.isEmpty() || sessionUi(snapshot, id).sending || active != (queueMode != null))
            return
        val submission =
            DraftSubmission(
                draftNamespace,
                connectionVersion,
                id.value,
                draftRevisions[draftNamespace to id] ?: 0,
                text,
                ++sequence,
                queueMode,
            )
        val api = client
        if (api == null && messageSubmitter == null)
            return reportError(IllegalStateException("Not connected"))
        val version = connectionVersion
        val runtimeId = selectedRuntimeId() ?: id.value
        mutateSelectedUi { it.copy(submission = submission) }
        viewModelScope.launch {
            runCatching {
                    messageSubmitter?.submit(
                        runtimeId,
                        clean,
                        snapshot.harness.sessionsById[id]?.modelSelection?.value?.model,
                        queueMode,
                    )
                        ?: api!!.submit(
                            runtimeId,
                            clean,
                            snapshot.harness.sessionsById[id]?.modelSelection?.value?.model,
                            queueMode,
                        )
                }
                .onSuccess { projection ->
                    if (
                        (messageSubmitter != null || client === api) && version == connectionVersion
                    ) {
                        clearDraftIfCurrent(id, submission)
                        _state.update {
                            ChatReducer.acknowledgeSubmission(it, id.value, submission, projection)
                        }
                    }
                }
                .onFailure { error ->
                    if (
                        (messageSubmitter != null || client === api) && version == connectionVersion
                    ) {
                        _state.update { current ->
                            val reduced =
                                ChatReducer.finishSubmission(current, id.value, submission, false)
                            ChatReducer.cancelTransientTools(reduced, id.value)
                                .copy(
                                    ui =
                                        reduced.ui.copy(
                                            error =
                                                ErrorMessage(
                                                    error.message ?: "Message could not be sent"
                                                )
                                        )
                                )
                        }
                    }
                }
        }
    }

    private fun submissionMatchesProjection(
        submission: DraftSubmission,
        event: GatewayEvent,
    ): Boolean {
        if (event.type != "message.user") return false
        val projection = event.messageProjection ?: return false
        val text =
            (projection["content"] ?: projection["text"])?.jsonPrimitive?.contentOrNull.orEmpty()
        return text == submission.text &&
            MessageQueueMode.fromApiValue(projection.string("queue_mode")) == submission.queueMode
    }

    private fun clearPersistedDraftIfCurrent(
        current: ChatUiState,
        id: SessionId,
        submission: DraftSubmission,
    ) {
        val revision = draftRevisions[draftNamespace to id] ?: 0
        if (
            !canClearDraft(
                submission,
                draftNamespace,
                connectionVersion,
                revision,
                draftFor(current, id),
            )
        )
            return
        draftRevisions[draftNamespace to id] = submission.revision + 1
        draftStore.save(submission.namespace, id.value, "")
    }

    private fun clearDraftIfCurrent(id: SessionId, submission: DraftSubmission) =
        clearPersistedDraftIfCurrent(state.value, id, submission)

    fun approve(choice: String) {
        val api = client ?: return reportError(IllegalStateException("Not connected"))
        viewModelScope.launch {
            runCatching { api.approve(choice) }
                .onSuccess { _state.update { it.copy(ui = it.ui.copy(approval = null)) } }
                .onFailure(::reportError)
        }
    }

    fun answerClarify(text: String) {
        if (text.isBlank()) return
        // Harness currently has no clarify-response endpoint; retain the request and tell the user.
        _state.update {
            it.copy(
                ui = it.ui.copy(error = ErrorMessage("Harness does not expose clarify responses."))
            )
        }
    }

    fun archiveSession(sessionId: String) {
        val api = client ?: return
        viewModelScope.launch {
            runCatching { api.archiveSession(sessionId) }
                .onSuccess { state -> _state.update { ChatReducer.mergeSessionState(it, state) } }
                .onFailure(::reportError)
        }
    }

    fun currentVoiceMessageTarget(): VoiceMessageTarget? =
        state.value.ui.selectedSessionId?.let { id ->
            VoiceMessageTarget(
                storedSessionId = id.value,
                runtimeSessionId =
                    state.value.harness.sessionsById[id]?.summary?.value?.runtimeId ?: id.value,
                model = state.value.harness.sessionsById[id]?.modelSelection?.value?.model,
                connectionVersion = connectionVersion,
            )
        }

    fun startVoiceRecording(target: VoiceMessageTarget? = null) {
        check(voiceTranscription == null) { "A voice recording is already active" }
        check(voiceJobStore.load() == null) { "A voice transcription is already active" }
        val actual = target ?: currentVoiceMessageTarget() ?: return
        val id = (++voiceSequence).toString()
        val file = File(app.filesDir, "voice/$id.pcm")
        val recorder =
            LocalAudioRecorder(
                file,
                { samples -> updateVoiceRecording(id, samples) },
                {
                    viewModelScope.launch {
                        updateVoiceStatus(id, "Maximum recording duration reached; finishing…")
                        stopVoiceRecording()
                    }
                },
            )
        val model = state.value.ui.whisperModel
        val job =
            VoiceJob(
                id,
                actual.storedSessionId,
                actual.runtimeSessionId,
                actual.model,
                model.id,
                state.value.ui.whisperThreadCount,
                file.path,
                0,
                phase = VoiceJobPhase.RECORDING,
            )
        voiceJobStore.save(job)
        voiceTranscription = ActiveVoiceTranscription(id, recorder, actual)
        _state.update {
            it.copy(
                ui =
                    it.ui.copy(
                        voiceRecording = true,
                        transcribing = false,
                        transcriptionStatus = null,
                        transcriptionProgress = null,
                        transcriptionProgressLabel = "0:00 recorded",
                        transcriptionElapsedMs = null,
                        transcriptionText = null,
                        voiceTargetSessionId = SessionId(actual.storedSessionId),
                    )
            )
        }
        runCatching { recorder.start() }
            .onFailure {
                cancelVoiceOperation(id)
                throw it
            }
    }

    fun stopVoiceRecording() {
        val active = voiceTranscription ?: return
        if (!state.value.ui.voiceRecording) return
        _state.update {
            it.copy(
                ui =
                    it.ui.copy(
                        voiceRecording = false,
                        transcribing = true,
                        transcriptionStatus = "Preparing local Whisper…",
                    )
            )
        }
        viewModelScope.launch {
            runCatching { active.recorder?.stop() ?: error("Recorder unavailable") }
                .onSuccess { tail ->
                    val job =
                        voiceJobStore
                            .load()
                            ?.copy(
                                phase = VoiceJobPhase.TRANSCRIBING,
                                totalSamples = tail.totalSamples,
                                audioPath = tail.audioFile.path,
                            ) ?: return@onSuccess
                    voiceJobStore.save(job)
                    _state.update {
                        it.copy(
                            ui =
                                it.ui.copy(
                                    transcriptionProgress = 0f,
                                    transcriptionProgressLabel =
                                        "0:00 of ${formatVoiceDuration(tail.totalSamples)}",
                                )
                        )
                    }
                    VoiceTranscriptionService.start(app, job)
                }
                .onFailure { error ->
                    cancelVoiceOperation(active.id)
                    reportError(error)
                }
        }
    }

    fun cancelVoiceRecordingIfCapturing() {
        if (state.value.ui.voiceRecording) cancelVoiceOperation(voiceTranscription?.id)
    }

    private fun updateVoiceRecording(id: String, samples: Long) {
        if (voiceTranscription?.id != id) return
        voiceJobStore
            .load()
            ?.takeIf { it.id == id }
            ?.let { voiceJobStore.save(it.copy(totalSamples = samples)) }
        _state.update {
            it.copy(
                ui =
                    it.ui.copy(
                        transcriptionProgressLabel = "${formatVoiceDuration(samples)} recorded"
                    )
            )
        }
    }

    private fun handleVoiceJobUpdate(intent: android.content.Intent) {
        val id = intent.getStringExtra(VoiceTranscriptionService.EXTRA_JOB_ID) ?: return
        if (voiceTranscription?.id != id) return
        val phase =
            intent.getStringExtra(VoiceTranscriptionService.EXTRA_PHASE)?.let {
                runCatching { VoiceJobPhase.valueOf(it) }.getOrNull()
            } ?: return
        val job = voiceJobStore.load() ?: return
        val completed = intent.getLongExtra(VoiceTranscriptionService.EXTRA_COMPLETED_SAMPLES, 0)
        val total =
            intent.getLongExtra(VoiceTranscriptionService.EXTRA_TOTAL_SAMPLES, job.totalSamples)
        val transcript = intent.getStringExtra(VoiceTranscriptionService.EXTRA_TRANSCRIPT)
        val elapsed =
            intent.getLongExtra(VoiceTranscriptionService.EXTRA_ELAPSED_MS, -1).takeIf { it >= 0 }
        voiceJobStore.save(
            job.copy(
                phase = phase,
                completedSamples = completed,
                totalSamples = total,
                elapsedMs = elapsed,
                transcript = transcript,
                error = intent.getStringExtra(VoiceTranscriptionService.EXTRA_ERROR),
            )
        )
        when (phase) {
            VoiceJobPhase.TRANSCRIBING ->
                _state.update {
                    it.copy(
                        ui =
                            it.ui.copy(
                                voiceRecording = false,
                                transcribing = true,
                                transcriptionStatus =
                                    it.ui.transcriptionStatus
                                        ?: "Transcribing locally with Whisper…",
                                transcriptionProgress =
                                    if (total > 0) completed.toFloat() / total else 0f,
                                transcriptionProgressLabel =
                                    "${formatVoiceDuration(completed)} of ${formatVoiceDuration(total)}" +
                                        (elapsed?.let {
                                            " • ${formatElapsedDuration(it)} transcribing"
                                        } ?: ""),
                                transcriptionElapsedMs = elapsed,
                                transcriptionText = transcript,
                            )
                    )
                }
            VoiceJobPhase.COMPLETE -> {
                val text = transcript?.trim().orEmpty()
                if (text.isBlank()) {
                    cancelVoiceOperation(id)
                    reportError(IllegalStateException("Whisper did not detect any speech"))
                } else {
                    sendVoiceMessage(activeTarget(job), text)
                    voiceJobStore.clear()
                    clearVoiceTranscription(id)
                }
            }
            VoiceJobPhase.FAILED -> {
                cancelVoiceOperation(id)
                reportError(IllegalStateException(job.error ?: "Voice transcription failed"))
            }
            VoiceJobPhase.CANCELED -> {
                voiceJobStore.clear()
                clearVoiceTranscription(id)
            }
            VoiceJobPhase.RECORDING -> Unit
        }
    }

    private fun activeTarget(job: VoiceJob) =
        VoiceMessageTarget(
            job.storedSessionId,
            job.runtimeSessionId,
            job.targetModel,
            connectionVersion,
        )

    private fun recoverCompletedVoiceJob() {
        val job = voiceJobStore.load()?.takeIf { it.phase == VoiceJobPhase.COMPLETE } ?: return
        val text = job.transcript?.trim().orEmpty()
        if (text.isBlank()) return
        if (voiceTranscription == null)
            voiceTranscription = ActiveVoiceTranscription(job.id, null, activeTarget(job))
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
                        ui =
                            it.ui.copy(
                                transcribing = true,
                                voiceTargetSessionId = SessionId(job.storedSessionId),
                                transcriptionStatus = "Transcribing locally with Whisper…",
                                transcriptionProgress =
                                    if (job.totalSamples > 0)
                                        (job.completedSamples.toFloat() / job.totalSamples)
                                            .coerceIn(0f, 1f)
                                    else 0f,
                                transcriptionElapsedMs = job.elapsedMs,
                                transcriptionText = job.transcript,
                            )
                    )
                }
            }
            VoiceJobPhase.COMPLETE -> {
                voiceTranscription = ActiveVoiceTranscription(job.id, null, activeTarget(job))
                _state.update {
                    it.copy(ui = it.ui.copy(voiceTargetSessionId = SessionId(job.storedSessionId)))
                }
            }
            else -> Unit
        }
    }

    private fun updateVoiceStatus(id: String, status: String) {
        if (voiceTranscription?.id == id)
            _state.update { it.copy(ui = it.ui.copy(transcriptionStatus = status)) }
    }

    private fun clearVoiceTranscription(id: String) {
        if (voiceTranscription?.id == id) {
            voiceTranscription = null
            _state.update {
                it.copy(
                    ui =
                        it.ui.copy(
                            voiceRecording = false,
                            transcribing = false,
                            transcriptionStatus = null,
                            transcriptionProgress = null,
                            transcriptionProgressLabel = null,
                            transcriptionElapsedMs = null,
                            transcriptionText = null,
                            voiceTargetSessionId = null,
                        )
                )
            }
        }
    }

    private fun cancelVoiceOperation(id: String?) {
        val active = voiceTranscription?.takeIf { id == null || it.id == id } ?: return
        active.recorder?.discard()
        VoiceTranscriptionService.cancel(app)
        voiceJobStore.clear()
        clearVoiceTranscription(active.id)
    }

    private fun sendVoiceMessage(target: VoiceMessageTarget, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (target.connectionVersion != connectionVersion)
            return reportError(
                IllegalStateException("Connection changed before voice message was ready")
            )
        val id = SessionId(target.storedSessionId)
        val current = state.value
        if (current.harness.sessionsById[id]?.state?.value?.running == true)
            return reportError(
                IllegalStateException("That session is already processing a message")
            )
        if (current.ui.selectedSessionId == id) {
            send(clean)
            return
        }
        val api = client ?: return reportError(IllegalStateException("Not connected"))
        val version = connectionVersion
        val key = draftNamespace to id
        val existing = sessionUi(current, id)
        val draftWasEmpty = existing.draft.isBlank()
        val revision = (draftRevisions[key] ?: 0) + if (draftWasEmpty) 1 else 0
        if (draftWasEmpty) {
            draftRevisions[key] = revision
            draftStore.save(draftNamespace, id.value, clean)
        }
        // Capture this exact token before launching. A later foreground/background send may
        // replace the session UI token, and this callback must never acknowledge that send.
        val submission =
            DraftSubmission(draftNamespace, version, id.value, revision, clean, ++sequence)
        _state.update { state ->
            val ui = sessionUi(state, id)
            if (ui.submission != null || ui.awaitingRunStart) state
            else
                state.copy(
                    ui =
                        state.ui.copy(
                            sessionUi =
                                state.ui.sessionUi +
                                    (id to
                                        ui.copy(
                                            draft = if (ui.draft.isBlank()) clean else ui.draft,
                                            submission = submission,
                                        ))
                        )
                )
        }
        if (sessionUi(state.value, id).submission != submission) return
        viewModelScope.launch {
            runCatching { api.submit(target.runtimeSessionId, clean, target.model) }
                .onSuccess { projection ->
                    if (client === api && version == connectionVersion) {
                        clearDraftIfCurrent(id, submission)
                        _state.update {
                            ChatReducer.acknowledgeSubmission(it, id.value, submission, projection)
                        }
                    }
                }
                .onFailure { error ->
                    if (client === api && version == connectionVersion) {
                        val ownsSubmission = sessionUi(state.value, id).submission == submission
                        if (ownsSubmission)
                            _state.update { currentState ->
                                val reduced =
                                    ChatReducer.finishSubmission(
                                        currentState,
                                        id.value,
                                        submission,
                                        acknowledgeDraft = false,
                                    )
                                ChatReducer.cancelTransientTools(reduced, id.value)
                                    .copy(
                                        ui =
                                            reduced.ui.copy(
                                                error =
                                                    ErrorMessage(
                                                        error.message
                                                            ?: "Voice message could not be sent"
                                                    )
                                            )
                                    )
                            }
                    }
                }
        }
    }

    fun selectWhisperModel(model: WhisperModel) {
        whisperModelStore.save(model)
        _state.update { it.copy(ui = it.ui.copy(whisperModel = model)) }
    }

    fun selectWhisperThreadCount(value: Int) {
        require(WhisperCpuConfig.isValid(value))
        whisperModelStore.saveThreadCount(value)
        _state.update { it.copy(ui = it.ui.copy(whisperThreadCount = value)) }
    }

    fun isWhisperModelDownloaded(model: WhisperModel): Boolean = localWhisper.isDownloaded(model)

    private fun refreshChatGptUsage(
        api: HarnessClient? = client,
        version: Long = connectionVersion,
    ) {
        val actual = api ?: return
        viewModelScope.launch {
            runCatching { actual.chatGptUsage() }
                .onSuccess { value ->
                    if (client === actual && version == connectionVersion)
                        _state.update {
                            it.copy(
                                harness =
                                    it.harness.copy(
                                        resources =
                                            it.harness.resources.copy(
                                                chatGptUsage = RequestState(value)
                                            )
                                    )
                            )
                        }
                }
        }
    }

    fun refresh() {
        val api = client ?: return
        val version = connectionVersion
        refreshJob?.cancel()
        refreshJob =
            viewModelScope.launch {
                runCatching { api.sessionsSnapshot() }
                    .onSuccess { snapshot ->
                        if (client === api && connectionVersion == version)
                            _state.update {
                                ChatReducer.mergeSnapshot(
                                    it,
                                    snapshot.sessions
                                        .map(HarnessSession::fromJson)
                                        .map(::sessionDataFromTransport),
                                    snapshot.cursor,
                                )
                            }
                    }
                    .onFailure(::reportError)
            }
        refreshModels()
        refreshContainers()
        refreshChatGptUsage()
    }

    fun refreshContainers() {
        val api = client ?: return
        val version = connectionVersion
        containersJob?.cancel()
        _state.update {
            it.copy(
                harness =
                    it.harness.copy(
                        resources =
                            it.harness.resources.copy(
                                containers =
                                    it.harness.resources.containers.copy(
                                        loading = true,
                                        error = null,
                                    )
                            )
                    )
            )
        }
        containersJob =
            viewModelScope.launch {
                runCatching { api.containers() }
                    .onSuccess { values ->
                        if (client === api && connectionVersion == version)
                            _state.update {
                                it.copy(
                                    harness =
                                        it.harness.copy(
                                            resources =
                                                it.harness.resources.copy(
                                                    containers = RequestState(values)
                                                )
                                        )
                                )
                            }
                    }
                    .onFailure { error ->
                        if (client === api && connectionVersion == version)
                            _state.update {
                                it.copy(
                                    harness =
                                        it.harness.copy(
                                            resources =
                                                it.harness.resources.copy(
                                                    containers =
                                                        it.harness.resources.containers.copy(
                                                            loading = false,
                                                            error =
                                                                ErrorMessage(
                                                                    error.message
                                                                        ?: "Container storage could not be loaded"
                                                                ),
                                                        )
                                                )
                                        )
                                )
                            }
                    }
            }
    }

    fun deleteContainer(containerId: String) {
        val api = client ?: return
        if (containerId in state.value.ui.deletingContainerIds) return
        val version = connectionVersion
        _state.update {
            it.copy(
                ui = it.ui.copy(deletingContainerIds = it.ui.deletingContainerIds + containerId),
                harness =
                    it.harness.copy(
                        resources =
                            it.harness.resources.copy(
                                containers = it.harness.resources.containers.copy(error = null)
                            )
                    ),
            )
        }
        viewModelScope.launch {
            runCatching { api.deleteContainer(containerId) }
                .onSuccess {
                    if (client === api && connectionVersion == version)
                        _state.update { current ->
                            current.copy(
                                ui =
                                    current.ui.copy(
                                        deletingContainerIds =
                                            current.ui.deletingContainerIds - containerId
                                    ),
                                harness =
                                    current.harness.copy(
                                        resources =
                                            current.harness.resources.copy(
                                                containers =
                                                    current.harness.resources.containers.copy(
                                                        value =
                                                            current.harness.resources.containers
                                                                .value
                                                                .orEmpty()
                                                                .filterNot {
                                                                    it.containerId == containerId
                                                                }
                                                    )
                                            )
                                    ),
                            )
                        }
                }
                .onFailure { error ->
                    if (client === api && connectionVersion == version)
                        _state.update { current ->
                            current.copy(
                                ui =
                                    current.ui.copy(
                                        deletingContainerIds =
                                            current.ui.deletingContainerIds - containerId
                                    ),
                                harness =
                                    current.harness.copy(
                                        resources =
                                            current.harness.resources.copy(
                                                containers =
                                                    current.harness.resources.containers.copy(
                                                        error =
                                                            ErrorMessage(
                                                                error.message
                                                                    ?: "Container could not be deleted"
                                                            )
                                                    )
                                            )
                                    ),
                            )
                        }
                }
        }
    }

    fun markRead(sessionId: String) {
        val id = SessionId(sessionId)
        val current = state.value
        if (current.ui.selectedSessionId != id || !selectedSessionContentComplete(current)) return
        val session = current.harness.sessionsById[id]?.toView() ?: return
        val readAt =
            maxOf(
                    java.time.Instant.now(),
                    session.updatedAt?.let {
                        runCatching { java.time.Instant.parse(it) }.getOrNull()
                    } ?: java.time.Instant.now(),
                )
                .toString()
        readStateStore.save(draftNamespace, sessionId, readAt)
        _state.update {
            it.copy(
                ui =
                    it.ui.copy(
                        unreadCounts = it.ui.unreadCounts - id,
                        readUpdates = it.ui.readUpdates + (id to readAt),
                    )
            )
        }
        client?.let { api ->
            val version = connectionVersion
            viewModelScope.launch {
                runCatching { api.markSessionRead(sessionId) }
                    .onSuccess { value ->
                        if (client === api && connectionVersion == version)
                            _state.update { ChatReducer.mergeSessionState(it, value) }
                    }
            }
        }
    }

    fun interrupt() {
        val api = client ?: return
        viewModelScope.launch { runCatching { api.interrupt() }.onFailure(::reportError) }
    }

    fun reconnectNow() {
        _state.update { it.copy(ui = it.ui.copy(reconnectSeconds = null)) }
        client?.reconnectNow()
    }

    fun checkForUpdate() = viewModelScope.launch { updateManager.checkForUpdate() }

    fun downloadUpdate() = viewModelScope.launch { updateManager.downloadAndInstall() }

    fun resetUpdateState() = updateManager.reset()

    private fun submitProjection(event: GatewayEvent): JsonObject? =
        when (event.type) {
            "message.user" ->
                buildJsonObject {
                    put("event_name", "chat.message.user.created")
                    put("role", "user")
                    put("content", event.payload["text"] ?: JsonPrimitive(""))
                    event.payload["message_id"]?.let { put("id", it) }
                    event.payload["run_id"]?.let { put("run_id", it) }
                    event.payload["sequence"]?.let { put("sequence", it) }
                    event.sourceEventId?.let { put("source_event_id", it) }
                }
            "message.reasoning" ->
                buildJsonObject {
                    put("event_name", "chat.message.assistant.created")
                    put("role", "assistant")
                    put("content", "")
                    put("reasoning", event.payload["text"] ?: JsonPrimitive(""))
                    put("reasoning_is_summary", event.payload["is_summary"] ?: JsonPrimitive(false))
                    event.payload["message_id"]?.let { put("id", it) }
                    event.payload["run_id"]?.let { put("run_id", it) }
                    event.payload["sequence"]?.let { put("sequence", it) }
                    event.sourceEventId?.let { put("source_event_id", it) }
                }
            "message.complete" ->
                buildJsonObject {
                    put("event_name", "chat.message.assistant.created")
                    put("role", "assistant")
                    put(
                        "content",
                        event.payload["text"] ?: event.payload["content"] ?: JsonPrimitive(""),
                    )
                    event.payload["message_id"]?.let { put("id", it) }
                    event.payload["run_id"]?.let { put("run_id", it) }
                    event.payload["sequence"]?.let { put("sequence", it) }
                    event.sourceEventId?.let { put("source_event_id", it) }
                }
            else -> null
        }

    /** Reduce every gateway notification before applying selected-session presentation effects. */
    private fun handleTransport(event: GatewayEvent) {
        if (event.durable) event.cursor?.let { overviewCursor = maxOf(overviewCursor, it) }
        when (event.type) {
            "connection.lost" -> {
                _state.update {
                    it.copy(
                        ui =
                            it.ui.copy(
                                error = ErrorMessage("Connection lost; reconnecting…"),
                                reconnectSeconds = null,
                            ),
                        harness = it.harness.copy(connection = ConnectionState.Reconnecting(1)),
                    )
                }
                // HarnessClient owns the account-wide websocket retry loop; never downgrade it
                // to the legacy per-session SSE watcher.
                return
            }
            "connection.retry_scheduled" -> {
                _state.update {
                    it.copy(
                        ui =
                            it.ui.copy(
                                reconnectSeconds =
                                    event.payload["seconds"]
                                        ?.jsonPrimitive
                                        ?.intOrNull
                                        ?.coerceAtLeast(0)
                            ),
                        harness = it.harness.copy(connection = ConnectionState.Reconnecting(1)),
                    )
                }
                return
            }
            "connection.retry_started" -> {
                _state.update {
                    it.copy(
                        ui = it.ui.copy(reconnectSeconds = null),
                        harness = it.harness.copy(connection = ConnectionState.Reconnecting(1)),
                    )
                }
                return
            }
            "connection.restored" -> {
                _state.update {
                    it.copy(
                        ui = it.ui.copy(error = null, reconnectSeconds = null),
                        harness = it.harness.copy(connection = ConnectionState.Connected),
                    )
                }
                refresh()
                selectedSession(state.value)?.let { select(it.id) }
                return
            }
        }
        // Normalize every existing-session notification before both reduction and semantic
        // routing. The stored id remains the single key even after runtime rotation.
        val stored = if (event.type == "session.created") null else storedSessionId(event)
        val normalized = normalizeTransportEvent(event, stored)
        val createdStateLoad =
            if (normalized.type == "session.created" && !normalized.sessionId.isNullOrBlank())
                LoadId(++sequence)
            else null
        // Never replace the server's websocket message projection with a synthetic row.
        val enriched =
            normalized.copy(
                messageProjection = normalized.messageProjection ?: submitProjection(normalized)
            )
        _state.update { current ->
            // A reconnect can replay the final durable notification. Its raw/cursor source is
            // already authoritative, so never repeat semantic effects such as unread counts.
            if (ChatReducer.hasAppliedDurableEvent(current, enriched)) return@update current
            val acknowledgedSubmission =
                stored?.let { id ->
                    sessionUi(current, id).submission?.takeIf {
                        enriched.durable && submissionMatchesProjection(it, enriched)
                    }
                }
            // Persistence is cleared while the old submission/draft is still authoritative;
            // mergeTransport then clears only the in-memory projection. A later POST response is
            // idempotent because the guarded revision no longer matches.
            if (stored != null && acknowledgedSubmission != null)
                clearPersistedDraftIfCurrent(current, stored, acknowledgedSubmission)
            var next = current
            if (enriched.type == "session.created") {
                val raw = enriched.rawEvent
                val payload = raw?.get("payload") as? JsonObject ?: JsonObject(enriched.payload)
                val tags = raw?.get("tags") as? JsonObject
                val observed = eventId(enriched.cursor)
                val session =
                    SessionData(
                        id = SessionId(enriched.sessionId.orEmpty()),
                        name =
                            ObservedValue(payload.string("title") ?: "Untitled session", observed),
                        parentSessionId =
                            ObservedValue(
                                tags?.string("parent_session")?.let(::SessionId),
                                observed,
                            ),
                        tags =
                            ObservedValue(
                                (payload["tags"] as? JsonArray)
                                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                                    ?.toSet()
                                    .orEmpty(),
                                observed,
                            ),
                        summary =
                            ObservedValue(
                                SessionSummary(
                                    updatedAt =
                                        raw?.get("created_at_ms")?.jsonPrimitive?.longOrNull?.let {
                                            Instant.ofEpochMilli(it).toString()
                                        }
                                ),
                                observed,
                            ),
                        state =
                            SynchronizedData.PendingHistory(
                                null,
                                observed,
                                createdStateLoad ?: LoadId(++sequence),
                            ),
                    )
                next = ChatReducer.mergeSession(next, session, enriched.cursor)
            }
            next = ChatReducer.mergeTransport(next, enriched)
            // Preserve details when their page has already loaded, including background sessions.
            if (
                stored != null &&
                    enriched.rawEvent != null &&
                    eventDetailsFor(next, stored).isNotEmpty()
            )
                next =
                    ChatReducer.appendEventDetail(
                        next,
                        stored.value,
                        SessionEvent.fromJson(enriched.rawEvent),
                    )
            when (enriched.type) {
                "session.created" -> Unit
                "message.delta" ->
                    stored?.let {
                        next = ChatReducer.appendAssistantDelta(next, it.value, enriched)
                    }
                "session.renamed" ->
                    stored?.let { id ->
                        enriched.payload["title"]?.jsonPrimitive?.contentOrNull?.let { title ->
                            next = ChatReducer.renameSession(next, id.value, title, enriched.cursor)
                        }
                    }
                "session.rotated" -> {
                    val runtime = enriched.payload["new_session_id"]?.jsonPrimitive?.contentOrNull
                    if (stored != null && !runtime.isNullOrBlank()) {
                        next = ChatReducer.setRuntimeSessionId(next, stored.value, runtime)
                        if (next.ui.selectedSessionId == stored) reloadSelectedHistory(runtime)
                    }
                }
                "session.state" ->
                    stored?.let { id ->
                        val value =
                            HarnessSessionState.fromJson(JsonObject(enriched.payload))
                                .copy(sessionId = id.value)
                        next = ChatReducer.mergeSessionState(next, value)
                        if (value.finished) {
                            next = ChatReducer.cancelTransientTools(next, id.value)
                            next = next.copy(ui = next.ui.copy(approval = null, clarify = null))
                            viewModelScope.launch {
                                delay(1_000)
                                refreshChatGptUsage()
                            }
                        }
                    }
                "session.active" -> Unit // state updates are authoritative
                "session.inactive" ->
                    stored?.let {
                        next = ChatReducer.cancelTransientTools(next, it.value)
                        if (next.ui.selectedSessionId == it)
                            next = next.copy(ui = next.ui.copy(approval = null, clarify = null))
                    }
                "message.complete" ->
                    stored?.let {
                        next = ChatReducer.cancelTransientTools(next, it.value)
                        if (next.ui.selectedSessionId != it) next = incrementUnread(next, it)
                        else next = next.copy(ui = next.ui.copy(approval = null, clarify = null))
                    }
                "secret.ask" ->
                    stored?.let { id ->
                        val request =
                            PendingSecret(
                                enriched.payload["event_id"]?.jsonPrimitive?.longOrNull
                                    ?: enriched.cursor
                                    ?: 0L,
                                enriched.payload["identifier"]
                                    ?.jsonPrimitive
                                    ?.contentOrNull
                                    .orEmpty(),
                                enriched.payload["description"]
                                    ?.jsonPrimitive
                                    ?.contentOrNull
                                    .orEmpty(),
                                enriched.payload["container"]
                                    ?.jsonPrimitive
                                    ?.contentOrNull
                                    .orEmpty(),
                            )
                        if (request.identifier.isNotBlank() && request.description.isNotBlank())
                            next =
                                next.copy(
                                    ui =
                                        next.ui.copy(
                                            pendingSecrets =
                                                next.ui.pendingSecrets + (id to request)
                                        )
                                )
                    }
                "tool.start",
                "tool.complete",
                "tool.failed",
                "tool.error",
                "tool.cancelled" ->
                    stored?.let { id ->
                        val name = enriched.payload["name"]?.jsonPrimitive?.contentOrNull
                        if (name == "retrieve-secret" && enriched.type == "tool.complete")
                            next =
                                next.copy(
                                    ui = next.ui.copy(pendingSecrets = next.ui.pendingSecrets - id)
                                )
                        // A durable tool row with a canonical message projection already
                        // renders from canonical history. Only genuinely transient/unprojected
                        // tool notifications need an overlay.
                        if (
                            name != "clarify" &&
                                (!enriched.durable || enriched.messageProjection == null)
                        )
                            next =
                                ChatReducer.addTransientOverlay(
                                    next,
                                    id.value,
                                    toolFromEvent(enriched),
                                )
                    }
                "clarify.request" ->
                    parseClarifyRequest(enriched.payload)?.let {
                        next = next.copy(ui = next.ui.copy(clarify = it))
                    }
                "clarify.expire" -> {
                    val requestId =
                        enriched.payload["request_id"]?.jsonPrimitive?.contentOrNull
                            ?: enriched.payload["id"]?.jsonPrimitive?.contentOrNull
                    if (requestId != null && next.ui.clarify?.requestId == requestId)
                        next = next.copy(ui = next.ui.copy(clarify = null))
                }
                "approval.request" ->
                    stored?.let { id ->
                        next =
                            next.copy(
                                ui =
                                    next.ui.copy(
                                        approval =
                                            ApprovalRequest(
                                                id.value,
                                                enriched.payload["command"]
                                                    ?.jsonPrimitive
                                                    ?.contentOrNull
                                                    .orEmpty(),
                                                enriched.payload["description"]
                                                    ?.jsonPrimitive
                                                    ?.contentOrNull
                                                    .orEmpty(),
                                                enriched.payload["allow_permanent"]
                                                    ?.jsonPrimitive
                                                    ?.booleanOrNull == true,
                                            )
                                    )
                            )
                    }
                "session.info" ->
                    stored?.let { id ->
                        next =
                            ChatReducer.setLiveUsage(
                                next,
                                id.value,
                                LiveTokenUsage.fromSessionInfo(JsonObject(enriched.payload)),
                            )
                        val provider = enriched.payload["provider"]?.jsonPrimitive?.contentOrNull
                        val model = enriched.payload["model"]?.jsonPrimitive?.contentOrNull
                        if (!provider.isNullOrBlank() && !model.isNullOrBlank()) {
                            val selection =
                                modelSelectionFromSessionInfo(
                                    provider,
                                    model,
                                    next.harness.sessionsById[id]?.modelSelection?.value,
                                )
                            next =
                                ChatReducer.setSessionModelSelection(
                                    next,
                                    id,
                                    selection,
                                    enriched.cursor,
                                )
                        }
                    }
                "error" -> {
                    stored?.let { next = ChatReducer.cancelTransientTools(next, it.value) }
                    next =
                        next.copy(
                            ui =
                                next.ui.copy(
                                    approval = null,
                                    clarify = null,
                                    error =
                                        ErrorMessage(
                                            enriched.payload["message"]
                                                ?.jsonPrimitive
                                                ?.contentOrNull ?: "Unknown Harness error"
                                        ),
                                )
                        )
                }
            }
            next
        }
        createdStateLoad?.let { load ->
            enriched.sessionId?.takeIf(String::isNotBlank)?.let {
                synchronizeCreatedSessionState(SessionId(it), load)
            }
        }
    }

    private fun synchronizeCreatedSessionState(id: SessionId, loadId: LoadId) {
        val api = client ?: return
        val version = connectionVersion
        viewModelScope.launch {
            runCatching { api.sessionStates() }
                .onSuccess { states ->
                    if (client === api && connectionVersion == version)
                        _state.update {
                            ChatReducer.completeSessionStateSync(it, listOf(id), states, loadId)
                        }
                }
        }
    }

    private fun storedSessionId(event: GatewayEvent): SessionId? {
        val rawTags = event.rawEvent?.get("tags") as? JsonObject
        listOf("stored_session_id", "stored_id")
            .firstNotNullOfOrNull {
                event.payload[it]?.jsonPrimitive?.contentOrNull
                    ?: rawTags?.get(it)?.jsonPrimitive?.contentOrNull
            }
            ?.let(::SessionId)
            ?.let {
                return it
            }
        val incoming = event.sessionId ?: return null
        val sessions = state.value.harness.sessionsById
        return sessions.values.firstOrNull { it.summary.value.runtimeId == incoming }?.id
            ?: SessionId(incoming)
    }

    private fun selectedRuntimeId(): String? =
        selectedSessionId(state.value)?.let { id ->
            state.value.harness.sessionsById[id]?.summary?.value?.runtimeId ?: id.value
        }

    private fun incrementUnread(state: ChatUiState, id: SessionId): ChatUiState {
        val record = state.harness.sessionsById[id] ?: return state
        if (record.state.value?.running == true) return state
        return state.copy(
            ui =
                state.ui.copy(
                    unreadCounts =
                        state.ui.unreadCounts + (id to ((state.ui.unreadCounts[id] ?: 0) + 1))
                )
        )
    }

    private fun reloadSelectedHistory(runtimeId: String) {
        val session = selectedSession(state.value) ?: return
        val generation = ++selectionGeneration
        _state.update {
            ChatReducer.beginHistory(
                it,
                session.id.value,
                it.harness.lastAppliedEventId?.value,
                generation,
            )
        }
        val api = client ?: return
        viewModelScope.launch {
            runCatching { api.history(runtimeId) }
                .onSuccess { rows ->
                    _state.update {
                        ChatReducer.completeHistory(it, session.id.value, rows, generation)
                    }
                }
                .onFailure { error ->
                    _state.update {
                        ChatReducer.failHistory(
                            it,
                            session.id.value,
                            generation,
                            ErrorMessage(error.message ?: "History could not be loaded"),
                        )
                    }
                }
        }
    }

    private fun eventTimestamp(event: GatewayEvent): Instant =
        (event.rawEvent ?: JsonObject(event.payload)).instant() ?: Instant.now()

    private fun toolFromEvent(event: GatewayEvent): ChatItem.Tool {
        val kind =
            when (event.type) {
                "tool.start" -> "running"
                "tool.failed",
                "tool.error" -> "failed"
                "tool.cancelled" -> "cancelled"
                else -> "completed"
            }
        return ChatItem.Tool(
            id =
                listOf("tool_call_id", "tool_id", "call_id", "id").firstNotNullOfOrNull {
                    event.payload[it]?.jsonPrimitive?.contentOrNull
                },
            name =
                listOf("name", "tool", "tool_name").firstNotNullOfOrNull {
                    event.payload[it]?.jsonPrimitive?.contentOrNull
                } ?: "tool",
            state = kind,
            arguments = event.payload["arguments"]?.toString(),
            result = event.payload["result"]?.toString(),
            error = event.payload["error"]?.jsonPrimitive?.contentOrNull,
            startedAt = if (kind == "running") eventTimestamp(event) else null,
            completedAt = if (kind != "running") eventTimestamp(event) else null,
            batchId = event.payload["batch_id"]?.jsonPrimitive?.contentOrNull,
        )
    }

    companion object {
        /** Keep runtime aliases at the transport boundary; reducers only see stored session ids. */
        internal fun normalizeTransportEvent(
            event: GatewayEvent,
            storedSessionId: SessionId?,
        ): GatewayEvent =
            if (event.type == "session.created" || storedSessionId == null) event
            else event.copy(sessionId = storedSessionId.value)
    }

    override fun onCleared() {
        if (state.value.ui.voiceRecording) cancelVoiceOperation(null)
        cancelConnectionWork()
        runCatching { app.unregisterReceiver(voiceReceiver) }
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
    sessions: List<SessionView>,
): String =
    runtimeToStored[eventId]
        ?: sessions.firstOrNull { it.id.value == eventId || it.runtimeId == eventId }?.id?.value
        ?: eventId

internal fun remapUnread(unread: Map<String, Int>, sessions: List<SessionView>): Map<String, Int> =
    unread.entries.fold(emptyMap()) { result, (key, count) ->
        val session = sessions.firstOrNull { it.id.value == key || it.runtimeId == key }
        if (session?.sessionState?.running == true || session?.active == true) return@fold result
        val storedId = session?.id?.value ?: key
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
