@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.qelg.harnessandroid

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.qelg.harnessandroid.data.*
import dev.qelg.harnessandroid.push.PushRegistration
import dev.qelg.harnessandroid.voice.LocalAudioRecorder
import dev.qelg.harnessandroid.voice.WhisperModel
import java.text.NumberFormat
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var requestedSessionId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedSessionId = intent.sessionId()
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                HarnessApp(
                    requestedSessionId,
                    consumeRequestedSession = { requestedSessionId = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedSessionId = intent.sessionId()
    }

    companion object {
        const val EXTRA_SESSION_ID = "dev.qelg.harnessandroid.SESSION_ID"
    }
}

private fun Intent.sessionId(): String? =
    getStringExtra(MainActivity.EXTRA_SESSION_ID)?.takeIf(String::isNotBlank)

@Composable
private fun HarnessApp(
    requestedSessionId: String?,
    consumeRequestedSession: () -> Unit,
    vm: ChatViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pushRegistrationRequested by rememberSaveable { mutableStateOf(false) }
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(state.configured, state.connecting, state.error) {
        if (
            state.configured &&
                !state.connecting &&
                state.error == null &&
                !pushRegistrationRequested
        ) {
            pushRegistrationRequested = true
            if (Build.VERSION.SDK_INT >= 33) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            PushRegistration.register(context as MainActivity)
        }
    }
    LaunchedEffect(requestedSessionId, state.connecting, state.sessions) {
        val sessionId = requestedSessionId ?: return@LaunchedEffect
        if (state.connecting) return@LaunchedEffect
        val session = state.sessions.firstOrNull { it.id == sessionId } ?: return@LaunchedEffect
        if (state.selectedId != sessionId) vm.select(session)
        consumeRequestedSession()
    }
    Box(
        Modifier.fillMaxSize()
            .windowInsetsPadding(contentInsets(WindowInsets.safeDrawing, WindowInsets.ime))
    ) {
        if (!state.configured) ConnectionScreen(vm::connect) else MainScreen(state, vm)
    }
}

internal fun contentInsets(safeDrawing: WindowInsets, ime: WindowInsets): WindowInsets =
    safeDrawing.union(ime)

internal fun Modifier.fullScreenDetailBackground(active: Boolean): Modifier =
    if (active) clearAndSetSemantics {} else this

@Composable
private fun ConnectionScreen(connect: (ConnectionConfig) -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth()
                .widthIn(max = 520.dp)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(40.dp))
            Text("Connect to Harness", style = MaterialTheme.typography.headlineMedium)
            Text("Sessions, message history, and streaming responses use the qelg/harness API.")
            OutlinedTextField(
                url,
                { url = it },
                Modifier.fillMaxWidth(),
                label = { Text("Harness URL") },
                placeholder = { Text("https://harness.example.ts.net:8000") },
                singleLine = true,
            )
            OutlinedTextField(
                token,
                { token = it },
                Modifier.fillMaxWidth(),
                label = { Text("Bearer token (optional)") },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            )
            Button(
                { connect(ConnectionConfig(baseUrl = url, token = token)) },
                enabled = url.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Connect")
            }
            Text(
                "If supplied, the bearer token is encrypted with Android Keystore.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MainScreen(state: ChatUiState, vm: ChatViewModel) {
    var showSessions by rememberSaveable { mutableStateOf(state.selectedId == null) }
    LaunchedEffect(state.selectedId) { if (state.selectedId != null) showSessions = false }
    val inTree = state.treeParentId != null
    val inChat = state.selectedId != null
    val treeSessions =
        remember(state.sessions, state.treeParentId) {
            state.treeParentId?.let { sessionTreeWithDepth(state.sessions, it) }.orEmpty()
        }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 760.dp
        BackHandler(enabled = !wide && (!showSessions) && (inChat || inTree)) {
            if (inChat) {
                vm.backFromChat()
                if (!inTree) showSessions = true
            } else if (inTree) {
                showSessions = true
                vm.hideTree()
            }
        }
        if (wide) {
            Row {
                SessionPane(state, vm, Modifier.width(300.dp).fillMaxHeight()) {}
                VerticalDivider()
                if (inTree) {
                    TreePane(state, vm, treeSessions, Modifier.width(300.dp).fillMaxHeight())
                    VerticalDivider()
                }
                ChatPane(state, vm, Modifier.weight(1f))
            }
        } else {
            when {
                showSessions || (!inTree && !inChat) ->
                    SessionPane(state, vm, Modifier.fillMaxSize()) { showSessions = false }
                inTree && !inChat -> {
                    TreePane(state, vm, treeSessions, Modifier.fillMaxSize())
                }
                else -> {
                    val onBack: () -> Unit = {
                        vm.backFromChat()
                        if (!inTree) showSessions = true
                    }
                    ChatPane(state, vm, Modifier.fillMaxSize(), onBack = onBack)
                }
            }
        }
    }
    UpdateDialog(state.updateState, vm::downloadUpdate, vm::resetUpdateState)
}

@Composable
internal fun SessionArchiveSwipeBox(
    archived: Boolean,
    onArchive: () -> Unit,
    content: @Composable () -> Unit,
) {
    val dismissState =
        rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (value == SwipeToDismissBoxValue.EndToStart && !archived) onArchive()
                false
            }
        )
    SwipeToDismissBox(
        state = dismissState,
        modifier = Modifier.fillMaxWidth(),
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = !archived,
        backgroundContent = {
            if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                Box(
                    Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Archive", color = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Default.Archive,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        },
    ) {
        content()
    }
}

@Composable
private fun SessionPane(
    state: ChatUiState,
    vm: ChatViewModel,
    modifier: Modifier,
    selected: () -> Unit,
) {
    var showArchived by rememberSaveable { mutableStateOf(false) }
    val allSessions =
        remember(state.sessions, state.search, state.drafts, showArchived) {
            val matching = filterSessions(state.sessions, state.search)
            prioritizeSessionsWithDrafts(
                filterArchivedSessions(matching, showArchived),
                state.drafts,
            )
        }
    val sessions = remember(allSessions) { rootSessions(allSessions) }
    val childCounts =
        remember(allSessions) { sessions.associateWith { childCount(allSessions, it.id) } }
    Column(modifier) {
        TopAppBar(
            title = { Text("Sessions") },
            windowInsets = WindowInsets(0, 0, 0, 0),
            actions = {
                IconButton({ showArchived = !showArchived }) {
                    Icon(
                        Icons.Default.Archive,
                        if (showArchived) "Hide archived sessions" else "Show archived sessions",
                        tint =
                            if (showArchived) MaterialTheme.colorScheme.primary
                            else LocalContentColor.current,
                    )
                }
                IconButton(vm::refresh) { Icon(Icons.Default.Refresh, "Refresh") }
                IconButton(vm::checkForUpdate) {
                    Icon(Icons.Default.SystemUpdate, "Check for updates")
                }
                IconButton(vm::disconnect) { Icon(Icons.AutoMirrored.Filled.Logout, "Disconnect") }
            },
        )
        OutlinedTextField(
            state.search,
            vm::setSearch,
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("Search") },
            singleLine = true,
        )
        Button(
            {
                vm.createSession()
                selected()
            },
            Modifier.fillMaxWidth().padding(12.dp),
        ) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("New session")
        }
        if (state.connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f)) {
            items(sessions, key = { it.id }) { session ->
                val draft = state.drafts[session.id]?.takeIf(String::isNotBlank)
                val unread = state.unreadCounts[session.id] ?: 0
                val updated = session.updatedAt?.let(::formatSessionUpdate)
                val read = isSessionRead(session, state.readUpdates[session.id])
                val children = childCounts[session] ?: 0
                val archived = session.sessionState?.archived == true
                SessionArchiveSwipeBox(
                    archived = archived,
                    onArchive = { vm.archiveSession(session.id) },
                ) {
                    ListItem(
                        headlineContent = { Text(session.title, maxLines = 1) },
                        supportingContent = {
                            Column {
                                if (draft != null)
                                    Text(
                                        "Draft · ${draft.trim()}",
                                        maxLines = 2,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                else session.preview?.let { Text(it, maxLines = 2) }
                                session.source
                                    ?.takeIf { it.isNotBlank() && it != "mobile" }
                                    ?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.tertiary,
                                        )
                                    }
                                session.sessionState?.let {
                                    Text(
                                        formatSessionState(it),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                                updated?.let {
                                    Text(
                                        "Latest $it",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        leadingContent = {
                            if (session.active) Badge { Text("LIVE") }
                            else
                                Icon(
                                    if (session.id == state.selectedId)
                                        Icons.AutoMirrored.Filled.Chat
                                    else Icons.Default.History,
                                    null,
                                )
                        },
                        trailingContent = {
                            Column(horizontalAlignment = Alignment.End) {
                                if (archived) Badge { Text("ARCHIVED") }
                                if (draft != null) Badge { Text("DRAFT") }
                                if (children > 0) {
                                    Badge {
                                        Text(
                                            "$children ${if (children == 1) "child" else "children"}"
                                        )
                                    }
                                }
                                if (unread > 0) {
                                    Badge { Text("$unread unread") }
                                } else if (session.sessionState?.unread == true) {
                                    Badge { Text("Unread") }
                                } else if (session.sessionState?.read == "read") {
                                    Text(
                                        "Read",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else if (
                                    session.sessionState == null && updated != null && !read
                                ) {
                                    Badge { Text("Unread") }
                                } else if (
                                    session.sessionState == null && updated != null && read
                                ) {
                                    Text(
                                        "Read",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        modifier =
                            Modifier.clickable {
                                vm.showTree(session)
                                selected()
                            },
                        colors =
                            ListItemDefaults.colors(
                                containerColor =
                                    if (session.id == state.selectedId)
                                        MaterialTheme.colorScheme.secondaryContainer
                                    else Color.Transparent
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun TreePane(
    state: ChatUiState,
    vm: ChatViewModel,
    nodes: List<TreeNode>,
    modifier: Modifier,
) {
    Column(modifier) {
        TopAppBar(
            title = {
                val parent = nodes.firstOrNull()?.session
                Text(parent?.title ?: "Sessions", maxLines = 1)
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            navigationIcon = {
                IconButton(vm::hideTree) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to sessions")
                }
            },
        )
        if (state.connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f)) {
            items(nodes, key = { it.session.id }) { (session, depth) ->
                val draft = state.drafts[session.id]?.takeIf(String::isNotBlank)
                val unread = state.unreadCounts[session.id] ?: 0
                val updated = session.updatedAt?.let(::formatSessionUpdate)
                val read = isSessionRead(session, state.readUpdates[session.id])
                val children = remember(state.sessions) { childCount(state.sessions, session.id) }
                val indent = (depth * 24).dp
                ListItem(
                    headlineContent = {
                        Row {
                            if (depth > 0) Spacer(Modifier.width(indent))
                            Icon(
                                if (depth == 0) Icons.Default.AccountTree
                                else Icons.Default.SubdirectoryArrowRight,
                                null,
                                Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(session.title, maxLines = 1)
                        }
                    },
                    supportingContent = {
                        Row {
                            if (depth > 0) Spacer(Modifier.width(indent + 28.dp))
                            Column {
                                if (draft != null)
                                    Text(
                                        "Draft · ${draft.trim()}",
                                        maxLines = 2,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                else session.preview?.let { Text(it, maxLines = 2) }
                                val kind =
                                    when {
                                        session.endReason == "compression" -> "Compression session"
                                        session.parentSessionId != null -> "Child session"
                                        session.source?.isNotBlank() == true &&
                                            session.source != "mobile" -> session.source
                                        else -> null
                                    }
                                kind?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                                session.sessionState?.let {
                                    Text(
                                        formatSessionState(it),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                                updated?.let {
                                    Text(
                                        "Latest $it",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    },
                    trailingContent = {
                        Column(horizontalAlignment = Alignment.End) {
                            if (draft != null) Badge { Text("DRAFT") }
                            if (children > 0) {
                                Badge {
                                    Text("$children ${if (children == 1) "child" else "children"}")
                                }
                            }
                            if (unread > 0) {
                                Badge { Text("$unread unread") }
                            } else if (session.sessionState?.unread == true) {
                                Badge { Text("Unread") }
                            } else if (session.sessionState?.read == "read") {
                                Text(
                                    "Read",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else if (session.sessionState == null && updated != null && !read) {
                                Badge { Text("Unread") }
                            } else if (session.sessionState == null && updated != null && read) {
                                Text(
                                    "Read",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    modifier = Modifier.clickable { vm.select(session, selectedFromTree = true) },
                    colors =
                        ListItemDefaults.colors(
                            containerColor =
                                if (session.id == state.selectedId)
                                    MaterialTheme.colorScheme.secondaryContainer
                                else Color.Transparent
                        ),
                )
            }
        }
    }
}

@Composable
private fun ChatPane(
    state: ChatUiState,
    vm: ChatViewModel,
    modifier: Modifier,
    onBack: (() -> Unit)? = null,
) {
    val input = state.selectedId?.let(state.drafts::get).orEmpty()
    var showModels by rememberSaveable(state.selectedId) { mutableStateOf(false) }
    var showWhisperModels by rememberSaveable { mutableStateOf(false) }
    var showUsageDetails by rememberSaveable(state.selectedId) { mutableStateOf(false) }
    var fullScreenDetail by remember(state.selectedId) { mutableStateOf<FullScreenDetail?>(null) }
    var sessionDetail by remember(state.selectedId) { mutableStateOf<SessionDetailPage?>(null) }
    val selectedSession = state.sessions.firstOrNull { it.id == state.selectedId }
    val blocks =
        remember(state.items) { groupTimeline(attachReasoningToToolOperations(state.items)) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val unread = state.selectedId?.let { state.unreadCounts[it] } ?: 0
    val selectedUpdatedAt = state.sessions.firstOrNull { it.id == state.selectedId }?.updatedAt
    var followLatest by remember(state.selectedId) { mutableStateOf(true) }
    LaunchedEffect(list, state.selectedId) {
        snapshotFlow { list.isScrollInProgress to !list.canScrollForward }
            .distinctUntilChanged()
            .collect { (scrolling, atBottom) ->
                if (scrolling || atBottom) followLatest = atBottom
                if (atBottom) state.selectedId?.let(vm::markRead)
            }
    }
    LaunchedEffect(
        blocks.size,
        (blocks.lastOrNull() as? ChatItem.Message)?.text,
        state.active,
        state.selectedId,
    ) {
        if (followLatest && (blocks.isNotEmpty() || state.active)) {
            list.scrollToItem((blocks.size - 1 + if (state.active) 1 else 0).coerceAtLeast(0))
            state.selectedId?.let(vm::markRead)
        }
    }
    LaunchedEffect(
        unread,
        followLatest,
        state.selectedId,
        selectedUpdatedAt,
        state.historyLoadedFor,
    ) {
        if (
            followLatest &&
                state.historyLoadedFor == state.selectedId &&
                (unread > 0 || selectedUpdatedAt != null)
        ) {
            state.selectedId?.let(vm::markRead)
        }
    }
    BackHandler(enabled = sessionDetail != null) {
        sessionDetail =
            when (sessionDetail) {
                is SessionDetailPage.EventPayload -> SessionDetailPage.Events
                SessionDetailPage.Events,
                SessionDetailPage.Children -> SessionDetailPage.Overview
                SessionDetailPage.Overview -> null
                null -> null
            }
    }
    Box(modifier) {
        Column(
            Modifier.fillMaxSize()
                .fullScreenDetailBackground(
                    active = fullScreenDetail != null || sessionDetail != null
                )
        ) {
            TopAppBar(
                navigationIcon = {
                    onBack?.let {
                        IconButton(it) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Sessions") }
                    }
                },
                title = {
                    Column(
                        Modifier.clickable(
                            enabled = selectedSession != null,
                            onClickLabel = "Open session details",
                            role = Role.Button,
                        ) {
                            sessionDetail = SessionDetailPage.Overview
                        }
                    ) {
                        Text(state.title, maxLines = 1)
                        state.modelCatalog.selected?.let {
                            Text(
                                buildString {
                                    append(it.model)
                                    it.thinkingLevel?.let { level ->
                                        append(" · ").append(level.displayName).append(" thinking")
                                    }
                                },
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                actions = {
                    IconButton(onClick = { vm.toggleShowReasoning() }) {
                        Icon(
                            if (state.showReasoning) Icons.Default.Visibility
                            else Icons.Default.VisibilityOff,
                            if (state.showReasoning) "Hide reasoning" else "Show reasoning",
                        )
                    }
                    IconButton(
                        onClick = { showWhisperModels = true },
                        enabled = !state.transcribing,
                    ) {
                        Icon(Icons.Default.SettingsVoice, "Choose local Whisper model")
                    }
                    IconButton(
                        onClick = {
                            showModels = true
                            vm.refreshModels()
                        },
                        enabled = !state.active && !state.connecting,
                    ) {
                        Icon(Icons.Default.SmartToy, "Choose model")
                    }
                    if (state.active) IconButton(vm::interrupt) { Icon(Icons.Default.Stop, "Stop") }
                },
            )
            state.error?.let { error ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(error.text)
                            state.reconnectSeconds?.let { seconds ->
                                Text(
                                    "Next connection attempt in $seconds s",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                        state.reconnectSeconds?.let {
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = vm::reconnectNow) { Text("Connect now") }
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = list,
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(blocks, key = ::timelineKey) { _, item ->
                        TimelineItem(item, showReasoning = state.showReasoning) { tool ->
                            fullScreenDetail = FullScreenDetail.ToolCall(tool)
                        }
                    }
                    if (state.active)
                        item(key = "working") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Harness is working…")
                            }
                        }
                }
                if (unread > 0 && !followLatest)
                    AssistChip(
                        onClick = {
                            followLatest = true
                            scope.launch {
                                list.animateScrollToItem(
                                    (blocks.size - 1 + if (state.active) 1 else 0).coerceAtLeast(0)
                                )
                                state.selectedId?.let(vm::markRead)
                            }
                        },
                        label = {
                            Text("$unread new ${if (unread == 1) "message" else "messages"}")
                        },
                        leadingIcon = { Icon(Icons.Default.ArrowDownward, null) },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                    )
            }
            HorizontalDivider()
            state.tokenUsage?.let { usage ->
                ContextUsageBar(usage, onClick = { showUsageDetails = true })
            }
            if (state.transcribing) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(state.transcriptionStatus ?: "Transcribing locally with Whisper…")
                }
            }
            state.approval?.let { approval ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            approval.description,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (approval.command.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        SelectionContainer {
                            Text(approval.command, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AssistChip(onClick = { vm.approve("once") }, label = { Text("Allow once") })
                        if (approval.allowPermanent)
                            AssistChip(
                                onClick = { vm.approve("always") },
                                label = { Text("Always") },
                            )
                        AssistChip(onClick = { vm.approve("deny") }, label = { Text("Deny") })
                    }
                }
            }
            state.clarify?.let { clarify ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.AutoMirrored.Filled.Help,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            clarify.question,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (clarify.choices.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            clarify.choices.forEach { choice ->
                                AssistChip(
                                    onClick = { vm.answerClarify(choice) },
                                    label = { Text(choice) },
                                    Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                VoiceButton(vm, enabled = !state.transcribing && !state.connecting && !state.active)
                OutlinedTextField(
                    input,
                    vm::setDraft,
                    Modifier.weight(1f),
                    placeholder = {
                        Text(
                            state.clarify?.question?.takeIf(String::isNotBlank) ?: "Message Harness"
                        )
                    },
                    maxLines = 6,
                )
                IconButton(
                    { if (state.clarify != null) vm.answerClarify(input) else vm.send(input) },
                    enabled = input.isNotBlank() && !state.connecting && !state.active,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
        when (val detail = fullScreenDetail) {
            is FullScreenDetail.Context ->
                ContextDetailScreen(detail.page, state.tokenUsage) { fullScreenDetail = null }
            is FullScreenDetail.ToolCall ->
                ToolCallScreen(
                    tool = currentToolForDetail(state.items, detail.tool),
                    onDismiss = { fullScreenDetail = null },
                )
            null -> Unit
        }
        when (val detail = sessionDetail) {
            SessionDetailPage.Overview ->
                selectedSession?.let { session ->
                    val children = state.childSessions[session.id]
                    SessionDetailScreen(
                        session = session,
                        eventCount =
                            state.sessionEvents
                                .takeIf { state.sessionEventsFor == session.id }
                                ?.size,
                        childCount = children?.size,
                        childError = state.childSessionsErrors[session.id],
                        onLoadChildren = { vm.loadChildSessions(session.id) },
                        onOpenChildren = { sessionDetail = SessionDetailPage.Children },
                        onOpenEvents = { sessionDetail = SessionDetailPage.Events },
                        onArchive = { vm.archiveSession(session.id) },
                        onDismiss = { sessionDetail = null },
                    )
                }
            SessionDetailPage.Children ->
                selectedSession?.let { session ->
                    SessionChildrenScreen(
                        session = session,
                        children = state.childSessions[session.id].orEmpty(),
                        onOpenChild = { child ->
                            sessionDetail = null
                            vm.select(child, selectedFromTree = state.treeParentId != null)
                        },
                        onDismiss = { sessionDetail = SessionDetailPage.Overview },
                    )
                }
            SessionDetailPage.Events ->
                selectedSession?.let { session ->
                    SessionEventsScreen(
                        session = session,
                        events =
                            state.sessionEvents
                                .takeIf { state.sessionEventsFor == session.id }
                                .orEmpty(),
                        loading = state.sessionEventsLoading,
                        error =
                            state.sessionEventsError.takeIf {
                                state.sessionEventsFor == session.id
                            },
                        onLoad = vm::loadSessionEvents,
                        onRetry = { vm.loadSessionEvents(force = true) },
                        onOpenEvent = { sessionDetail = SessionDetailPage.EventPayload(it) },
                        onDismiss = { sessionDetail = SessionDetailPage.Overview },
                    )
                }
            is SessionDetailPage.EventPayload ->
                SessionEventPayloadScreen(detail.event) { sessionDetail = SessionDetailPage.Events }
            null -> Unit
        }
    }
    if (showModels) {
        ModelPickerDialog(
            catalog = state.modelCatalog,
            loading = state.modelLoading,
            onRefresh = vm::refreshModels,
            onConfigure = vm::selectModel,
            onSelect = {
                vm.selectModel(it)
                showModels = false
            },
            onDismiss = { showModels = false },
        )
    }
    if (showWhisperModels) {
        WhisperModelDialog(
            selected = state.whisperModel,
            isDownloaded = vm::isWhisperModelDownloaded,
            onSelect = vm::selectWhisperModel,
            onDismiss = { showWhisperModels = false },
        )
    }
    if (showUsageDetails) {
        TokenUsageBottomSheet(
            usage = state.tokenUsage,
            onOpenDetail = { page ->
                showUsageDetails = false
                fullScreenDetail = FullScreenDetail.Context(page)
            },
            onDismiss = { showUsageDetails = false },
        )
    }
}

@Composable
private fun ContextUsageBar(usage: TokenUsageState, onClick: () -> Unit) {
    val bar = usage.usageBarData() ?: return
    val context = usage.context
    val window = bar.context
    if (window == null) {
        Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Usage", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    "${formatTokenCount(bar.totalTokens ?: 0L)} tokens",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    val used = window.used
    val max = window.max
    if (max <= 0L) {
        Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Current context", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    "${formatTokenCount(used)} tokens",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    val estimatedBase = context?.baseTokens ?: 0L
    val estimatedConversation = context?.conversationTokens ?: 0L
    val estimatedUsed = estimatedBase + estimatedConversation
    val hasBreakdown = estimatedUsed > 0L
    val base = if (hasBreakdown) used.toDouble() * estimatedBase / estimatedUsed else 0.0
    val conversation = if (hasBreakdown) (used - base).coerceAtLeast(0.0) else 0.0
    val unknown = if (hasBreakdown) 0.0 else used.toDouble()
    val free = (max - used).coerceAtLeast(0L).toDouble()
    val percent = window.percent
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Context", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    "${formatTokenCount(used)} / ${formatTokenCount(max)} · $percent%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(5.dp))
            Row(
                Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(99.dp)),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                if (base > 0.0)
                    Spacer(
                        Modifier.weight(base.toFloat())
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary)
                    )
                if (conversation > 0.0)
                    Spacer(
                        Modifier.weight(conversation.toFloat())
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.tertiary)
                    )
                if (unknown > 0.0)
                    Spacer(
                        Modifier.weight(unknown.toFloat())
                            .fillMaxHeight()
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                    )
                if (free > 0.0)
                    Spacer(
                        Modifier.weight(free.toFloat())
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
            }
        }
    }
}

private sealed interface SessionDetailPage {
    data object Overview : SessionDetailPage

    data object Children : SessionDetailPage

    data object Events : SessionDetailPage

    data class EventPayload(val event: SessionEvent) : SessionDetailPage
}

@Composable
internal fun SessionDetailScreen(
    session: HarnessSession,
    eventCount: Int?,
    childCount: Int? = null,
    childError: ErrorMessage? = null,
    onLoadChildren: () -> Unit = {},
    onOpenChildren: () -> Unit = {},
    onOpenEvents: () -> Unit,
    onArchive: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    LaunchedEffect(session.id) { onLoadChildren() }
    BackHandler(onBack = onDismiss)
    FullScreenDetailContainer {
        Column(Modifier.fillMaxSize()) {
            DetailHeader(session.title, "Session details", onDismiss) {
                val archived = session.sessionState?.archived == true
                IconButton(onArchive, enabled = !archived) {
                    Icon(
                        Icons.Default.Archive,
                        if (archived) "Session is archived" else "Archive session",
                    )
                }
            }
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    ListItem(
                        headlineContent = { Text("Child sessions") },
                        supportingContent = {
                            Text(
                                when {
                                    childError != null ->
                                        "Child sessions could not be loaded. Tap to retry."
                                    childCount == null -> "Loading child sessions…"
                                    childCount == 1 -> "1 session has this session as its parent"
                                    else -> "$childCount sessions have this session as their parent"
                                }
                            )
                        },
                        leadingContent = { Icon(Icons.Default.AccountTree, null) },
                        trailingContent = {
                            if ((childCount ?: 0) > 0)
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                        },
                        modifier =
                            Modifier.fillMaxWidth()
                                .clickable(
                                    enabled = (childCount ?: 0) > 0 || childError != null,
                                    onClickLabel =
                                        if (childError == null) "Open child sessions"
                                        else "Retry child sessions",
                                    role = Role.Button,
                                    onClick = {
                                        if (childError == null) onOpenChildren()
                                        else onLoadChildren()
                                    },
                                ),
                    )
                }
                item { HorizontalDivider() }
                item {
                    ListItem(
                        headlineContent = { Text("Events") },
                        supportingContent = {
                            Text(
                                eventCount?.let {
                                    "$it low-level ${if (it == 1) "event" else "events"}"
                                } ?: "Inspect low-level events and JSON payloads"
                            )
                        },
                        leadingContent = { Icon(Icons.Default.DataObject, null) },
                        trailingContent = {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                        },
                        modifier =
                            Modifier.fillMaxWidth()
                                .clickable(
                                    onClickLabel = "Open session events",
                                    role = Role.Button,
                                    onClick = onOpenEvents,
                                ),
                    )
                }
                item { HorizontalDivider() }
                item { SessionProperty("Session ID", session.id) }
                item {
                    SessionProperty(
                        "Status",
                        session.sessionState?.let(::formatSessionState)
                            ?: if (session.active) "Live" else "Inactive",
                    )
                }
                session.model?.takeIf(String::isNotBlank)?.let { model ->
                    item { SessionProperty("Model", model) }
                }
                session.source?.takeIf(String::isNotBlank)?.let { source ->
                    item { SessionProperty("Source", source) }
                }
                session.updatedAt?.let(::formatSessionUpdate)?.let { updated ->
                    item { SessionProperty("Latest update", updated) }
                }
            }
        }
    }
}

@Composable
internal fun SessionChildrenScreen(
    session: HarnessSession,
    children: List<HarnessSession>,
    onOpenChild: (HarnessSession) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    FullScreenDetailContainer {
        Column(Modifier.fillMaxSize()) {
            DetailHeader(session.title, "Child sessions", onDismiss)
            HorizontalDivider()
            if (children.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No child sessions.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(children, key = { it.id }) { child ->
                        ListItem(
                            headlineContent = { Text(child.title, maxLines = 1) },
                            supportingContent = {
                                Column {
                                    child.preview?.takeIf(String::isNotBlank)?.let {
                                        Text(it, maxLines = 2)
                                    }
                                    child.sessionState?.let {
                                        Text(
                                            formatSessionState(it),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.tertiary,
                                        )
                                    }
                                }
                            },
                            leadingContent = { Icon(Icons.Default.SubdirectoryArrowRight, null) },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (child.sessionState?.archived == true) {
                                        Badge { Text("ARCHIVED") }
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                                }
                            },
                            modifier =
                                Modifier.fillMaxWidth().clickable(
                                    onClickLabel = "Open ${child.title}",
                                    role = Role.Button,
                                ) {
                                    onOpenChild(child)
                                },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionProperty(label: String, value: String) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = {
            SelectionContainer {
                Text(value, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        },
    )
}

@Composable
internal fun SessionEventsScreen(
    session: HarnessSession,
    events: List<SessionEvent>,
    loading: Boolean,
    error: ErrorMessage?,
    onLoad: () -> Unit,
    onRetry: () -> Unit,
    onOpenEvent: (SessionEvent) -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(session.id) { onLoad() }
    val listState = rememberLazyListState()
    val arrows = remember(events) { eventCausationArrows(events) }
    val gutterWidth =
        if (arrows.isEmpty()) 0.dp
        else (32 + (arrows.maxOfOrNull { it.lane } ?: 0).inc() * 12).coerceAtMost(112).dp
    val eventIndexesByKey =
        remember(events) {
            events.indices.associateBy { index -> eventRowKey(index, events[index]) }
        }
    BackHandler(onBack = onDismiss)
    FullScreenDetailContainer {
        Column(Modifier.fillMaxSize()) {
            DetailHeader(session.title, "Events", onDismiss) {
                IconButton(onRetry, enabled = !loading) {
                    Icon(Icons.Default.Refresh, "Refresh events")
                }
            }
            HorizontalDivider()
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                error != null && events.isEmpty() ->
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Default.ErrorOutline, null)
                        Spacer(Modifier.height(8.dp))
                        Text("Events could not be loaded")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            error.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onRetry) { Text("Retry") }
                    }
                !loading && events.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No events for this session.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                else ->
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            state = listState,
                            contentPadding = PaddingValues(start = gutterWidth),
                        ) {
                            error?.let { currentError ->
                                item(key = "event-error") {
                                    Text(
                                        currentError.text,
                                        Modifier.fillMaxWidth().padding(12.dp),
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            itemsIndexed(
                                events,
                                key = { index, event -> eventRowKey(index, event) },
                            ) { _, event ->
                                ListItem(
                                    headlineContent = { Text(event.displayName) },
                                    supportingContent = {
                                        Column {
                                            event.timestamp?.let { Text(formatEventTime(it)) }
                                            event.originator?.let {
                                                Text(
                                                    "Originator: $it",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color =
                                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    },
                                    trailingContent = {
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                                    },
                                    modifier =
                                        Modifier.fillMaxWidth().clickable(
                                            onClickLabel = "Open ${event.displayName} JSON payload",
                                            role = Role.Button,
                                        ) {
                                            onOpenEvent(event)
                                        },
                                )
                            }
                        }
                        EventCausationCanvas(
                            events = events,
                            arrows = arrows,
                            listState = listState,
                            gutterWidth = gutterWidth,
                            eventIndexesByKey = eventIndexesByKey,
                        )
                    }
            }
        }
    }
}

private fun eventRowKey(index: Int, event: SessionEvent): String =
    "event-row:$index:${event.id ?: "unknown"}"

@Composable
private fun EventCausationCanvas(
    events: List<SessionEvent>,
    arrows: List<EventCausationArrow>,
    listState: LazyListState,
    gutterWidth: Dp,
    eventIndexesByKey: Map<String, Int>,
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    val descriptions =
        remember(events, arrows) {
            arrows.joinToString(". ") { arrow ->
                "${events[arrow.sourceIndex].displayName} caused by ${events[arrow.targetIndex].displayName}"
            }
        }
    Canvas(
        Modifier.fillMaxSize().testTag("causation-arrows").semantics {
            contentDescription = descriptions
        }
    ) {
        // Read layoutInfo in the draw phase so every fractional scroll offset invalidates drawing
        // without waiting for a recomposition of the event list.
        val layoutInfo = listState.layoutInfo
        val centers =
            layoutInfo.visibleItemsInfo
                .mapNotNull { item ->
                    val index = eventIndexesByKey[item.key as? String] ?: return@mapNotNull null
                    index to (item.offset + item.size / 2f)
                }
                .toMap()
        val segments =
            visibleEventCausationSegments(
                arrows = arrows,
                visibleCenters = centers,
                viewportTop = layoutInfo.viewportStartOffset.toFloat().coerceAtLeast(0f),
                viewportBottom = layoutInfo.viewportEndOffset.toFloat().coerceAtMost(size.height),
            )
        val rowEdge = gutterWidth.toPx() - 2.dp.toPx()
        val laneCount = (arrows.maxOfOrNull { it.lane } ?: -1) + 1
        val availableLaneWidth = (rowEdge - 8.dp.toPx()).coerceAtLeast(1.dp.toPx())
        val laneSpacing =
            if (laneCount <= 1) 0f else availableLaneWidth / laneCount.coerceAtLeast(1)
        val arrowSize = 6.dp.toPx()
        val strokeWidth = 2.dp.toPx()
        segments.forEach { segment ->
            val arrow = segment.arrow
            val laneX = rowEdge - 12.dp.toPx() - arrow.lane * laneSpacing
            val color = if (arrow.lane % 2 == 0) primary else secondary
            drawLine(
                color,
                start = androidx.compose.ui.geometry.Offset(laneX, segment.sourceY),
                end = androidx.compose.ui.geometry.Offset(laneX, segment.targetY),
                strokeWidth = strokeWidth,
            )
            if (segment.sourceVisible) {
                drawCircle(
                    color = color,
                    radius = 3.dp.toPx(),
                    center = androidx.compose.ui.geometry.Offset(laneX, segment.sourceY),
                )
            }
            val pointsUp = segment.targetY <= segment.sourceY
            val tailY = segment.targetY + if (pointsUp) arrowSize else -arrowSize
            drawLine(
                color,
                start = androidx.compose.ui.geometry.Offset(laneX, segment.targetY),
                end = androidx.compose.ui.geometry.Offset(laneX - arrowSize, tailY),
                strokeWidth = strokeWidth,
            )
            drawLine(
                color,
                start = androidx.compose.ui.geometry.Offset(laneX, segment.targetY),
                end = androidx.compose.ui.geometry.Offset(laneX + arrowSize, tailY),
                strokeWidth = strokeWidth,
            )
        }
    }
}

@Composable
internal fun SessionEventPayloadScreen(event: SessionEvent, onDismiss: () -> Unit) {
    var wrapLines by remember(event.raw) { mutableStateOf(true) }
    BackHandler(onBack = onDismiss)
    FullScreenDetailContainer {
        Column(Modifier.fillMaxSize()) {
            DetailHeader(event.displayName, event.timestamp?.let(::formatEventTime), onDismiss) {
                TextButton({ wrapLines = !wrapLines }) {
                    Text(if (wrapLines) "No wrap" else "Wrap")
                }
            }
            HorizontalDivider()
            SelectionContainer(Modifier.weight(1f)) {
                Box(
                    Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .then(
                            if (wrapLines) Modifier
                            else Modifier.horizontalScroll(rememberScrollState())
                        )
                ) {
                    Text(
                        event.prettyJson,
                        Modifier.then(if (wrapLines) Modifier.fillMaxWidth() else Modifier)
                            .padding(16.dp),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        softWrap = wrapLines,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailHeader(
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        actions()
    }
}

internal fun formatEventTime(
    timestamp: java.time.Instant,
    zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    locale: java.util.Locale = java.util.Locale.getDefault(),
): String =
    java.time.format.DateTimeFormatter.ofLocalizedDateTime(
            java.time.format.FormatStyle.MEDIUM,
            java.time.format.FormatStyle.SHORT,
        )
        .withLocale(locale)
        .withZone(zoneId)
        .format(timestamp)

internal enum class ContextDetailPage {
    SystemPrompt,
    ToolDefinitions,
}

private sealed interface FullScreenDetail {
    data class Context(val page: ContextDetailPage) : FullScreenDetail

    data class ToolCall(val tool: ChatItem.Tool) : FullScreenDetail
}

@Composable
private fun TokenUsageBottomSheet(
    usage: TokenUsageState?,
    onOpenDetail: (ContextDetailPage) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = usage?.context
    val window = usage?.currentContext
    val cumulative = usage?.cumulative
    val used = window?.used ?: 0L
    val max = window?.max ?: 0L
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Context & usage", style = MaterialTheme.typography.headlineSmall)
            if (window != null) {
                val percent = window.percent
                Text("Current context", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (max > 0L)
                        "${formatTokenCount(used)} / ${formatTokenCount(max)} tokens · $percent%"
                    else "${formatTokenCount(used)} tokens · context limit unavailable",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (context?.categories.isNullOrEmpty()) {
                    Text(
                        "Detailed breakdown currently unavailable",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    context.categories.forEach { category ->
                        val page =
                            when {
                                isSystemPromptExpandable(category, usage?.systemPrompt) ->
                                    ContextDetailPage.SystemPrompt
                                isToolDefinitionsExpandable(category, usage?.toolDefinitions) ->
                                    ContextDetailPage.ToolDefinitions
                                else -> null
                            }
                        UsageDetailRow(
                            category.label,
                            category.tokens,
                            category.tokens.percentOf(if (max > 0L) max else used),
                            onClick = page?.let { selected -> { onOpenDetail(selected) } },
                            onClickLabel =
                                when (page) {
                                    ContextDetailPage.SystemPrompt -> "Show full system prompt"
                                    ContextDetailPage.ToolDefinitions -> "Show tool definitions"
                                    null -> null
                                },
                        )
                    }
                }
                if (max > 0L) {
                    UsageDetailRow(
                        "Free",
                        (max - used).coerceAtLeast(0L),
                        (max - used).coerceAtLeast(0L).percentOf(max),
                    )
                }
                Text(
                    "Category values are approximate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("Context usage is available after the first completed model call.")
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("Total conversation usage", style = MaterialTheme.typography.titleMedium)
            if (cumulative != null) {
                Text(
                    "${formatTokenCount(cumulative.totalTokens)} tokens",
                    style = MaterialTheme.typography.headlineSmall,
                )
                UsageDetailRow("Prompt processed", cumulative.processedInputTokens)
                UsageDetailRow("Uncached input", cumulative.inputTokens)
                UsageDetailRow("Read from cache", cumulative.cacheReadTokens)
                UsageDetailRow("Written to cache", cumulative.cacheWriteTokens)
                UsageDetailRow("Model output", cumulative.outputTokens)
                UsageDetailRow("Of which reasoning", cumulative.reasoningTokens)
                Text(
                    buildString {
                        append("${cumulative.apiCalls} model calls")
                        cumulative.cacheHitPercent?.let { append(" · $it% cache hit") }
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("No persisted token totals are available yet.")
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
internal fun ContextDetailScreen(
    page: ContextDetailPage,
    usage: TokenUsageState?,
    onDismiss: () -> Unit,
) {
    when (page) {
        ContextDetailPage.SystemPrompt ->
            FullScreenContextDetailScreen(title = "System prompt", onDismiss = onDismiss) {
                Text(
                    usage?.systemPrompt ?: "Details currently unavailable.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        ContextDetailPage.ToolDefinitions ->
            FullScreenContextDetailScreen(title = "Tool definitions", onDismiss = onDismiss) {
                val definitions = usage?.toolDefinitions
                if (definitions == null) {
                    Text("Details currently unavailable.")
                } else {
                    Text(
                        "${definitions.total} tools",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    definitions.sections.forEach { section ->
                        Text(
                            section.name,
                            Modifier.padding(top = 16.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        section.tools.forEach { tool ->
                            Text(
                                tool.name,
                                Modifier.padding(top = 8.dp),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            if (tool.description.isNotBlank()) {
                                Text(
                                    tool.description,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
    }
}

@Composable
internal fun FullScreenDetailContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxSize().testTag("full-screen-detail"),
        color = MaterialTheme.colorScheme.background,
        content = content,
    )
}

@Composable
internal fun FullScreenContextDetailScreen(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onDismiss)
    FullScreenDetailContainer {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            HorizontalDivider()
            Box(
                Modifier.fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                SelectionContainer { Column(Modifier.fillMaxWidth(), content = content) }
            }
        }
    }
}

@Composable
private fun UsageDetailRow(
    label: String,
    tokens: Long,
    percent: Int? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
) {
    val modifier =
        if (onClick != null)
            Modifier.fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .clickable(
                    onClickLabel = onClickLabel ?: "Show details",
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(vertical = 8.dp)
        else Modifier.fillMaxWidth()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Text(
            buildString {
                append(formatTokenCount(tokens))
                percent?.let { append(" · $it%") }
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun isSystemPromptExpandable(category: ContextCategory, systemPrompt: String?): Boolean =
    category.id == "system_prompt" && !systemPrompt.isNullOrBlank()

internal fun isToolDefinitionsExpandable(
    category: ContextCategory,
    definitions: ToolDefinitions?,
): Boolean = category.id == "tool_definitions" && definitions?.sections?.isNotEmpty() == true

private fun Long.percentOf(total: Long): Int? =
    if (total > 0L) ((toDouble() / total) * 100).toInt().coerceIn(0, 100) else null

private fun formatTokenCount(value: Long): String = NumberFormat.getIntegerInstance().format(value)

internal fun timelineKey(index: Int, item: ChatItem): String =
    when (item) {
        is ChatItem.Message ->
            when {
                item.uiKey != null -> encodedTimelineKey("message-live", item.uiKey)
                item.id != null -> encodedTimelineKey("message-id", item.id)
                else -> encodedTimelineKey("message-fallback", item.role, item.timestamp, index)
            }
        is ChatItem.Tool ->
            if (item.id != null) encodedTimelineKey("tool-id", item.id)
            else encodedTimelineKey("tool-fallback", item.name, item.startedAt, index)
        is ChatItem.ParallelToolGroup -> encodedTimelineKey("parallel", item.id)
        is ChatItem.ToolGroup ->
            encodedTimelineKey(
                "tool-group",
                *item.operations
                    .mapIndexed { child, operation -> timelineKey(child, operation) }
                    .toTypedArray(),
            )
        is ChatItem.Status -> encodedTimelineKey("status", item.timestamp, item.text, index)
    }

private fun encodedTimelineKey(kind: String, vararg identities: Any?): String = buildString {
    append(kind)
    identities.forEach { identity ->
        val value = identity?.toString().orEmpty()
        append('|').append(value.length).append(':').append(value)
    }
}

@Composable
private fun ModelPickerDialog(
    catalog: ModelCatalog,
    loading: Boolean,
    onRefresh: () -> Unit,
    onConfigure: (ModelSelection) -> Unit,
    onSelect: (ModelSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    val providers = remember(catalog, search) { catalog.filtered(search) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Choose model", Modifier.weight(1f))
                IconButton(onRefresh, enabled = !loading) {
                    Icon(Icons.Default.Refresh, "Refresh models")
                }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("Thinking level", style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThinkingLevel.entries.forEach { level ->
                        FilterChip(
                            selected = catalog.selected?.thinkingLevel == level,
                            onClick = {
                                catalog.selected?.let {
                                    onConfigure(it.copy(thinkingLevel = level))
                                }
                            },
                            enabled = catalog.selected != null && !loading,
                            label = { Text(level.displayName) },
                        )
                    }
                }
                catalog.selected?.let { selection ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !loading) {
                            onConfigure(
                                selection.copy(reasoningSummary = !selection.reasoningSummary)
                            )
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Request reasoning summary",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "Ask ChatGPT to provide a summary of its reasoning process",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = selection.reasoningSummary,
                            onCheckedChange = null,
                            enabled = !loading,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    search,
                    { search = it },
                    Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text("Search providers and models") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!loading && providers.isEmpty()) {
                    Text(
                        "No configured models found.",
                        Modifier.padding(vertical = 24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                        providers.forEach { provider ->
                            item(key = "provider-${provider.slug}") {
                                Text(
                                    provider.name,
                                    Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            }
                            items(provider.models, key = { "${provider.slug}/${it.id}" }) { model ->
                                val selection =
                                    ModelSelection(
                                        provider.slug,
                                        model.id,
                                        catalog.selected?.thinkingLevel,
                                        catalog.selected?.reasoningSummary ?: false,
                                    )
                                val selected = selection == catalog.selected
                                ListItem(
                                    headlineContent = { Text(model.id) },
                                    supportingContent = {
                                        if (model.unavailable) Text("Unavailable for this account")
                                    },
                                    leadingContent = {
                                        RadioButton(
                                            selected = selected,
                                            onClick = null,
                                            enabled = !model.unavailable && !loading,
                                        )
                                    },
                                    modifier =
                                        Modifier.clickable(
                                            enabled = !model.unavailable && !loading && !selected
                                        ) {
                                            onSelect(selection)
                                        },
                                    colors =
                                        ListItemDefaults.colors(
                                            containerColor =
                                                if (selected)
                                                    MaterialTheme.colorScheme.secondaryContainer
                                                else Color.Transparent
                                        ),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
    )
}

internal fun currentToolForDetail(items: List<ChatItem>, opened: ChatItem.Tool): ChatItem.Tool {
    val id = opened.id ?: return opened

    fun findIn(item: ChatItem): ChatItem.Tool? =
        when (item) {
            is ChatItem.Tool -> item.takeIf { it.id == id }
            is ChatItem.ParallelToolGroup -> item.tools.firstNotNullOfOrNull(::findIn)
            is ChatItem.ToolGroup -> item.operations.firstNotNullOfOrNull(::findIn)
            else -> null
        }

    return items.firstNotNullOfOrNull(::findIn) ?: opened
}

@Composable
private fun TimelineItem(
    item: ChatItem,
    showReasoning: Boolean,
    onOpenToolDetails: (ChatItem.Tool) -> Unit,
) {
    when (item) {
        is ChatItem.Message -> MessageCard(item, showReasoning)
        is ChatItem.Tool ->
            ToolCard(item, showReasoning = showReasoning, onOpenDetails = onOpenToolDetails)
        is ChatItem.ParallelToolGroup ->
            ParallelToolGroupCard(
                item,
                showReasoning = showReasoning,
                onOpenDetails = onOpenToolDetails,
            )
        is ChatItem.ToolGroup ->
            ToolSummaryCard(item, showReasoning = showReasoning, onOpenDetails = onOpenToolDetails)
        is ChatItem.Status ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                item.timestamp?.let { ClockText(it) }
            }
    }
}

internal fun shouldDisplayMessage(message: ChatItem.Message, showReasoning: Boolean): Boolean =
    message.text.isNotBlank() || (showReasoning && !message.reasoning.isNullOrBlank())

@Composable
private fun MessageCard(message: ChatItem.Message, showReasoning: Boolean) {
    if (!shouldDisplayMessage(message, showReasoning)) return
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.role == "user") Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color =
                if (message.role == "user") MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 680.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (showReasoning && !message.reasoning.isNullOrBlank()) {
                    Text(
                        if (message.reasoningIsSummary) "Reasoning summary" else "Reasoning",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    SelectionContainer {
                        Text(message.reasoning, style = MaterialTheme.typography.bodySmall)
                    }
                    if (message.text.isNotBlank()) Spacer(Modifier.height(8.dp))
                }
                if (message.text.isNotBlank()) {
                    if (shouldRenderMarkdown(message)) MarkdownText(message.text)
                    else SelectionContainer { Text(message.text) }
                }
                message.timestamp?.let {
                    ClockText(it, Modifier.align(Alignment.End).padding(top = 2.dp))
                }
            }
        }
    }
}

data class ReasoningDisplayLine(val text: String, val bold: Boolean)

internal fun reasoningDisplayLines(text: String): List<ReasoningDisplayLine> =
    text.lines().map { raw ->
        val line = raw.trim()
        val bold = line.length >= 4 && line.startsWith("**") && line.endsWith("**")
        ReasoningDisplayLine(
            text = if (bold) line.substring(2, line.length - 2).trim() else line,
            bold = bold,
        )
    }

@Composable
private fun ReasoningTrace(text: String, summary: Boolean) {
    val lines = remember(text) { reasoningDisplayLines(text) }
    SelectionContainer {
        Column(Modifier.padding(vertical = 2.dp)) {
            lines.forEach { line ->
                Text(
                    line.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (line.bold) FontWeight.Bold else FontWeight.Normal,
                    color =
                        if (summary) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun ToolSummaryCard(
    group: ChatItem.ToolGroup,
    showReasoning: Boolean,
    onOpenDetails: (ChatItem.Tool) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val breakdown = remember(group.operations) { toolCountBreakdown(group.operations) }
    val lastReasoning =
        remember(group.operations) {
            group.operations.asReversed().firstNotNullOfOrNull(::operationReasoning)
        }
    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Build, null)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("${group.callCount} tool calls in ${group.roundCount} rounds")
                    if (showReasoning && lastReasoning != null)
                        ReasoningTrace(lastReasoning.first, lastReasoning.second)
                    Text(
                        breakdown.entries.joinToString(" · ") { "${it.key} ×${it.value}" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (expanded)
                group.operations.forEach { operation ->
                    when (operation) {
                        is ChatItem.Tool ->
                            ToolCard(
                                operation,
                                showReasoning = showReasoning,
                                onOpenDetails = onOpenDetails,
                            )
                        is ChatItem.ParallelToolGroup ->
                            ParallelToolGroupCard(
                                operation,
                                showReasoning = showReasoning,
                                onOpenDetails = onOpenDetails,
                            )
                        else -> Unit
                    }
                }
        }
    }
}

@Composable
private fun ParallelToolGroupCard(
    group: ChatItem.ParallelToolGroup,
    showReasoning: Boolean = false,
    onOpenDetails: (ChatItem.Tool) -> Unit,
) {
    var expanded by rememberSaveable(group.id) { mutableStateOf(true) }
    val complete = group.final
    val totalMs =
        if (complete && group.tools.all { it.durationMs != null }) {
            val first = group.tools.mapNotNull { it.startedAt }.minOrNull()
            val last = group.tools.mapNotNull { it.completedAt }.maxOrNull()
            if (first != null && last != null)
                java.time.Duration.between(first, last).toMillis().takeIf { it >= 0 }
            else null
        } else null
    Card(
        Modifier.fillMaxWidth().clickable { expanded = !expanded },
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AccountTree, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Parallel · ${group.tools.size} calls", Modifier.weight(1f))
                totalMs?.let {
                    Text(formatElapsed(it), style = MaterialTheme.typography.labelSmall)
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (showReasoning && !group.reasoning.isNullOrBlank())
                ReasoningTrace(group.reasoning, group.reasoningIsSummary)
            if (expanded)
                group.tools.forEach { ToolCard(it, nested = true, onOpenDetails = onOpenDetails) }
        }
    }
}

@Composable
private fun ToolCard(
    tool: ChatItem.Tool,
    nested: Boolean = false,
    showReasoning: Boolean = false,
    onOpenDetails: (ChatItem.Tool) -> Unit,
) {
    var now by remember(tool.id, tool.startedAt) { mutableStateOf(java.time.Instant.now()) }
    val requestRows = remember(tool.arguments) { toolValueRows(tool.arguments, "arguments") }
    val answerRows =
        remember(tool.result, tool.error) {
            buildList {
                addAll(toolValueRows(tool.result, "answer"))
                addAll(toolValueRows(tool.error, "error"))
            }
        }
    val requestPreview = remember(requestRows) { toolValuePreview(requestRows) }
    val answerPreview = remember(answerRows) { toolValuePreview(answerRows) }
    LaunchedEffect(tool.id, tool.state, tool.startedAt) {
        while (!tool.final && tool.startedAt != null) {
            now = java.time.Instant.now()
            kotlinx.coroutines.delay(250)
        }
    }
    val durationMs =
        tool.durationMs
            ?: if (!tool.final && tool.startedAt != null)
                java.time.Duration.between(tool.startedAt, now).toMillis().coerceAtLeast(0)
            else null
    ListItem(
        headlineContent = {
            Column {
                if (showReasoning && !tool.reasoning.isNullOrBlank())
                    ReasoningTrace(tool.reasoning, tool.reasoningIsSummary)
                Text(
                    buildString {
                        append(tool.name)
                        append(" · ")
                        append(tool.state)
                        durationMs?.let {
                            append(" · ")
                            if (tool.durationEstimated) append("≈ ")
                            append(formatElapsed(it))
                        }
                    }
                )
            }
        },
        leadingContent = { Icon(toolIcon(tool.name), null) },
        trailingContent = { (tool.startedAt ?: tool.completedAt)?.let { ClockText(it) } },
        supportingContent = {
            if (requestPreview != null || answerPreview != null)
                Column {
                    requestPreview?.let { CompactToolValuePreview(it) }
                    if (requestPreview != null && answerPreview != null)
                        Spacer(Modifier.height(4.dp))
                    answerPreview?.let { CompactToolValuePreview(it) }
                }
        },
        colors =
            ListItemDefaults.colors(
                containerColor =
                    if (nested) MaterialTheme.colorScheme.surfaceContainerHigh
                    else Color.Transparent
            ),
        modifier = Modifier.fillMaxWidth().clickable { onOpenDetails(tool) },
    )
}

@Composable
private fun CompactToolValuePreview(preview: ToolValuePreview) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            preview.first.summary,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        if (preview.remainingFields > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                "+${preview.remainingFields} fields",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            )
        }
    }
}

@Composable
internal fun ToolCallScreen(tool: ChatItem.Tool, onDismiss: () -> Unit) {
    var selectedValue by remember(tool.id) { mutableStateOf<String?>(null) }
    var now by remember(tool.id, tool.startedAt) { mutableStateOf(java.time.Instant.now()) }
    val requestRows = remember(tool.arguments) { toolValueRows(tool.arguments, "arguments") }
    val answerRows =
        remember(tool.result, tool.error) {
            buildList {
                addAll(toolValueRows(tool.result, "answer"))
                addAll(toolValueRows(tool.error, "error"))
            }
        }
    LaunchedEffect(tool.id, tool.state, tool.startedAt) {
        while (!tool.final && tool.startedAt != null) {
            now = java.time.Instant.now()
            kotlinx.coroutines.delay(250)
        }
    }
    val durationMs =
        tool.durationMs
            ?: if (!tool.final && tool.startedAt != null)
                java.time.Duration.between(tool.startedAt, now).toMillis().coerceAtLeast(0)
            else null
    val timestamp = tool.startedAt ?: tool.completedAt
    BackHandler(enabled = selectedValue == null, onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().fullScreenDetailBackground(active = selectedValue != null)) {
            FullScreenDetailContainer {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        Column(Modifier.weight(1f)) {
                            Text(tool.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                buildString {
                                    append(tool.state)
                                    durationMs?.let {
                                        append(" · ")
                                        if (tool.durationEstimated) append("≈ ")
                                        append(formatElapsed(it))
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        timestamp?.let { ClockText(it) }
                    }
                    HorizontalDivider()
                    Column(
                        Modifier.weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        Text("Request", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        if (requestRows.isEmpty())
                            Text(
                                "No fields",
                                style = MaterialTheme.typography.bodySmall,
                                color =
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                            )
                        else ToolDetailRows(requestRows) { selectedValue = it }
                        Spacer(Modifier.height(18.dp))
                        Text("Response", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        if (answerRows.isEmpty())
                            Text(
                                "No fields",
                                style = MaterialTheme.typography.bodySmall,
                                color =
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                            )
                        else ToolDetailRows(answerRows) { selectedValue = it }
                    }
                }
            }
        }
        selectedValue?.let { value ->
            ToolValueScreen(
                toolName = tool.name,
                timestamp = timestamp,
                value = value,
                onDismiss = { selectedValue = null },
            )
        }
    }
}

@Composable
private fun ToolDetailRows(rows: List<ToolValueRow>, onOpen: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            var truncated by remember(row.summary) { mutableStateOf(false) }
            Text(
                row.summary,
                modifier =
                    Modifier.fillMaxWidth()
                        .then(
                            if (truncated) Modifier.clickable { onOpen(row.value) } else Modifier
                        ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { truncated = it.hasVisualOverflow },
            )
        }
    }
}

@Composable
internal fun ToolValueScreen(
    toolName: String,
    timestamp: java.time.Instant?,
    value: String,
    onDismiss: () -> Unit,
) {
    var wrapLines by remember(value) { mutableStateOf(true) }
    val displayValue = remember(value) { prettyToolValue(value) }
    BackHandler(onBack = onDismiss)
    FullScreenDetailContainer {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text(toolName, style = MaterialTheme.typography.titleMedium)
                    timestamp?.let { ClockText(it) }
                }
                TextButton({ wrapLines = !wrapLines }) {
                    Text(if (wrapLines) "No wrap" else "Wrap")
                }
            }
            HorizontalDivider()
            SelectionContainer(Modifier.weight(1f)) {
                Box(
                    Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .then(
                            if (wrapLines) Modifier
                            else Modifier.horizontalScroll(rememberScrollState())
                        )
                ) {
                    Text(
                        displayValue,
                        Modifier.then(if (wrapLines) Modifier.fillMaxWidth() else Modifier)
                            .padding(16.dp),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        softWrap = wrapLines,
                    )
                }
            }
        }
    }
}

private fun formatElapsed(durationMs: Long): String = "%.1f s".format(durationMs / 1000.0)

@Composable
private fun ClockText(timestamp: java.time.Instant, modifier: Modifier = Modifier) {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val locale =
        remember(configuration) {
            configuration.locales.get(0)?.let {
                java.util.Locale.forLanguageTag(it.toLanguageTag())
            } ?: java.util.Locale.getDefault()
        }
    Text(
        formatClockTime(timestamp, locale = locale),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
    )
}

private fun toolIcon(name: String) =
    when {
        name.contains("file", true) ||
            name.contains("read", true) ||
            name.contains("write", true) -> Icons.Default.Description
        name.contains("web", true) || name.contains("search", true) -> Icons.Default.Language
        name.contains("image", true) || name.contains("media", true) -> Icons.Default.Image
        name.contains("git", true) -> Icons.Default.Source
        else -> Icons.Default.Terminal
    }

@Composable
private fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val markwon = remember(context) { markdownRenderer(context) }
    val rendered = remember(markwon, markdown) { markwon.toMarkdown(markdown) }
    val containsTable = remember(rendered) { containsMarkdownTable(rendered) }
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    AndroidView(
        factory = { context -> TextView(context).apply(::configureMarkdownTextView) },
        update = {
            it.setTextColor(
                android.graphics.Color.argb(
                    (color.alpha * 255).toInt(),
                    (color.red * 255).toInt(),
                    (color.green * 255).toInt(),
                    (color.blue * 255).toInt(),
                )
            )
            markwon.setParsedMarkdown(it, rendered)
        },
        modifier = modifier.then(if (containsTable) Modifier.fillMaxWidth() else Modifier),
    )
}

@Composable
private fun WhisperModelDialog(
    selected: WhisperModel,
    isDownloaded: (WhisperModel) -> Boolean,
    onSelect: (WhisperModel) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.SettingsVoice, null) },
        title = { Text("Local Whisper model") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Larger models generally improve transcription, but need more storage, memory, and time. The selected model downloads on first use. Audio stays on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                WhisperModel.All.forEach { model ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(role = Role.RadioButton) { onSelect(model) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected.id == model.id,
                            onClick = { onSelect(model) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(model.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                buildString {
                                    append(model.downloadSize)
                                    if (isDownloaded(model)) append(" · Downloaded")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

private data class VoiceRecording(val recorder: LocalAudioRecorder, val target: VoiceMessageTarget)

@Composable
private fun VoiceButton(vm: ChatViewModel, enabled: Boolean) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var recording by remember { mutableStateOf<VoiceRecording?>(null) }
    var pendingTarget by remember { mutableStateOf<VoiceMessageTarget?>(null) }

    fun discardRecording() {
        recording?.recorder?.discard()
        recording = null
        pendingTarget = null
    }

    fun start(target: VoiceMessageTarget) {
        pendingTarget = null
        runCatching { LocalAudioRecorder().also { it.start() } }
            .onSuccess { recording = VoiceRecording(it, target) }
            .onFailure {
                discardRecording()
                vm.reportError(IllegalStateException("Could not start voice recording", it))
            }
    }

    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val target = pendingTarget
            pendingTarget = null
            if (granted && target != null) start(target)
            else if (!granted)
                vm.reportError(IllegalStateException("Microphone permission is required"))
        }
    IconButton(
        onClick = {
            val active = recording
            if (active == null) {
                val target = vm.currentVoiceMessageTarget()
                if (target == null) {
                    vm.reportError(IllegalStateException("No session selected"))
                    return@IconButton
                }
                pendingTarget = target
                permission.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                recording = null
                scope.launch {
                    runCatching { active.recorder.stop() }
                        .onSuccess { samples -> vm.transcribeAndSend(samples, active.target) }
                        .onFailure(vm::reportError)
                }
            }
        },
        enabled = enabled || recording != null,
    ) {
        Icon(
            if (recording == null) Icons.Default.Mic else Icons.Default.Stop,
            if (recording == null) "Record with local Whisper" else "Stop recording",
            tint =
                if (recording == null) LocalContentColor.current
                else MaterialTheme.colorScheme.error,
        )
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) discardRecording()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            discardRecording()
        }
    }
}

@Composable
private fun UpdateDialog(updateState: UpdateState, onDownload: () -> Unit, onDismiss: () -> Unit) {
    if (
        !updateState.checking &&
            !updateState.available &&
            !updateState.upToDate &&
            !updateState.downloading &&
            updateState.error == null
    )
        return
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            when {
                updateState.checking || updateState.downloading ->
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                updateState.available -> Icon(Icons.Default.SystemUpdate, null)
                updateState.upToDate -> Icon(Icons.Default.CheckCircle, null)
                else -> Icon(Icons.Default.Error, null)
            }
        },
        title = {
            Text(
                when {
                    updateState.checking -> "Checking for updates…"
                    updateState.downloading -> "Downloading update…"
                    updateState.available -> "Update available"
                    updateState.upToDate -> "Up to date"
                    updateState.error != null -> "Update check failed"
                    else -> "Update"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    updateState.checking -> Text("Connecting to GitHub…")
                    updateState.downloading -> {
                        Text("Downloading Harness Android ${updateState.latestVersion ?: ""}")
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { updateState.downloadProgress },
                            Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${(updateState.downloadProgress * 100).toInt()}%",
                            Modifier.align(Alignment.CenterHorizontally),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    updateState.available ->
                        Text(
                            "A new version is available: ${updateState.latestVersion ?: "latest"} (versionCode ${updateState.latestVersionCode ?: "?"}).\n\nYour version: ${updateState.currentVersion}\n\nDownload and install?"
                        )
                    updateState.upToDate ->
                        Text(
                            "You have the latest version: ${updateState.currentVersion} (versionCode ${updateState.latestVersionCode ?: "?"})."
                        )
                    updateState.error != null -> Text(updateState.error ?: "Unknown error")
                }
            }
        },
        confirmButton = {
            when {
                updateState.available -> Button(onDownload) { Text("Download & Install") }
                updateState.downloading -> {}
                else -> TextButton(onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            if (updateState.available || updateState.error != null)
                TextButton(onDismiss) { Text("Cancel") }
        },
    )
}
