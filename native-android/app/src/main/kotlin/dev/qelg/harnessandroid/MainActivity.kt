@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.qelg.harnessandroid

import android.Manifest
import android.media.MediaRecorder
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.qelg.harnessandroid.data.*
import java.io.File
import java.text.NumberFormat
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                HarnessApp()
            }
        }
    }
}

@Composable
private fun HarnessApp(vm: ChatViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
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
private fun SessionPane(
    state: ChatUiState,
    vm: ChatViewModel,
    modifier: Modifier,
    selected: () -> Unit,
) {
    val allSessions =
        remember(state.sessions, state.search, state.drafts) {
            prioritizeSessionsWithDrafts(filterSessions(state.sessions, state.search), state.drafts)
        }
    val sessions = remember(allSessions) { rootSessions(allSessions) }
    val childCounts =
        remember(allSessions) { sessions.associateWith { childCount(allSessions, it.id) } }
    Column(modifier) {
        TopAppBar(
            title = { Text("Sessions") },
            windowInsets = WindowInsets(0, 0, 0, 0),
            actions = {
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
                val read = isSessionUpdateRead(session, state.readUpdates[session.id])
                val children = childCounts[session] ?: 0
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
                                if (session.id == state.selectedId) Icons.AutoMirrored.Filled.Chat
                                else Icons.Default.History,
                                null,
                            )
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
                            } else if (updated != null && !read) {
                                Badge { Text("Unread") }
                            } else if (updated != null && read) {
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
                val read = isSessionUpdateRead(session, state.readUpdates[session.id])
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
                            } else if (updated != null && !read) {
                                Badge { Text("Unread") }
                            } else if (updated != null && read) {
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
    var showUsageDetails by rememberSaveable(state.selectedId) { mutableStateOf(false) }
    var fullScreenDetail by remember(state.selectedId) { mutableStateOf<FullScreenDetail?>(null) }
    val blocks = remember(state.items) { groupTimeline(state.items) }
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
    Box(modifier) {
        Column(
            Modifier.fillMaxSize().fullScreenDetailBackground(active = fullScreenDetail != null)
        ) {
            TopAppBar(
                navigationIcon = {
                    onBack?.let {
                        IconButton(it) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Sessions") }
                    }
                },
                title = {
                    Column {
                        Text(state.title, maxLines = 1)
                        state.modelCatalog.selected?.let {
                            Text(
                                it.model,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                actions = {
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
                        TimelineItem(item) { tool ->
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
                    Text("Uploading and transcribing voice…")
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
                if (state.transcriptionEnabled) {
                    VoiceButton(
                        vm,
                        enabled = !state.transcribing && !state.connecting && !state.active,
                    ) { text ->
                        vm.send(text)
                    }
                }
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
    }
    if (showModels) {
        ModelPickerDialog(
            catalog = state.modelCatalog,
            loading = state.modelLoading,
            onRefresh = vm::refreshModels,
            onSelect = {
                vm.selectModel(it)
                showModels = false
            },
            onDismiss = { showModels = false },
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
                                val selection = ModelSelection(provider.slug, model.id)
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
private fun TimelineItem(item: ChatItem, onOpenToolDetails: (ChatItem.Tool) -> Unit) {
    when (item) {
        is ChatItem.Message -> MessageCard(item)
        is ChatItem.Tool -> ToolCard(item, onOpenDetails = onOpenToolDetails)
        is ChatItem.ParallelToolGroup ->
            ParallelToolGroupCard(item, onOpenDetails = onOpenToolDetails)
        is ChatItem.ToolGroup -> ToolSummaryCard(item, onOpenDetails = onOpenToolDetails)
        is ChatItem.Status ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                item.timestamp?.let { ClockText(it) }
            }
    }
}

@Composable
private fun MessageCard(message: ChatItem.Message) {
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
                if (shouldRenderMarkdown(message)) MarkdownText(message.text)
                else SelectionContainer { Text(message.text) }
                message.timestamp?.let {
                    ClockText(it, Modifier.align(Alignment.End).padding(top = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun ToolSummaryCard(group: ChatItem.ToolGroup, onOpenDetails: (ChatItem.Tool) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val breakdown = remember(group.operations) { toolCountBreakdown(group.operations) }
    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Build, null)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("${group.callCount} tool calls in ${group.roundCount} rounds")
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
                        is ChatItem.Tool -> ToolCard(operation, onOpenDetails = onOpenDetails)
                        is ChatItem.ParallelToolGroup ->
                            ParallelToolGroupCard(operation, onOpenDetails = onOpenDetails)
                        else -> Unit
                    }
                }
        }
    }
}

@Composable
private fun ParallelToolGroupCard(
    group: ChatItem.ParallelToolGroup,
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
            if (expanded)
                group.tools.forEach { ToolCard(it, nested = true, onOpenDetails = onOpenDetails) }
        }
    }
}

@Composable
private fun ToolCard(
    tool: ChatItem.Tool,
    nested: Boolean = false,
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
private fun VoiceButton(vm: ChatViewModel, enabled: Boolean, onText: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    fun discardRecording() {
        val active = recorder
        recorder = null
        runCatching { active?.stop() }
        runCatching { active?.release() }
        file?.delete()
        file = null
    }
    fun start() {
        val output = File.createTempFile("harness-voice-", ".m4a", context.cacheDir)
        @Suppress("DEPRECATION")
        val next =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
            else MediaRecorder()
        runCatching {
                next.setAudioSource(MediaRecorder.AudioSource.MIC)
                next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                next.setOutputFile(output.absolutePath)
                next.prepare()
                next.start()
                file = output
                recorder = next
            }
            .onFailure {
                runCatching { next.release() }
                output.delete()
                vm.reportError(IllegalStateException("Could not start voice recording", it))
            }
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) start()
        }
    IconButton(
        onClick = {
            val active = recorder
            if (active == null) permission.launch(Manifest.permission.RECORD_AUDIO)
            else {
                recorder = null
                val audio = file
                file = null
                val stopped = runCatching { active.stop() }.isSuccess
                runCatching { active.release() }
                if (!stopped || audio == null || audio.length() == 0L) {
                    audio?.delete()
                    vm.reportError(
                        IllegalStateException("Voice recording failed; nothing was uploaded")
                    )
                } else {
                    val bytes =
                        runCatching { audio.readBytes() }
                            .getOrElse {
                                audio.delete()
                                vm.reportError(it)
                                return@IconButton
                            }
                    vm.transcribe(bytes, "audio/mp4", { it.onSuccess(onText) }, { audio.delete() })
                }
            }
        },
        enabled = enabled,
    ) {
        Icon(
            if (recorder == null) Icons.Default.Mic else Icons.Default.Stop,
            if (recorder == null) "Record voice" else "Stop recording",
            tint =
                if (recorder == null) LocalContentColor.current else MaterialTheme.colorScheme.error,
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
