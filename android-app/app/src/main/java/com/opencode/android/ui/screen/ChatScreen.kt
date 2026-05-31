package com.opencode.android.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.android.data.api.OpenCodeApi
import com.opencode.android.data.model.*
import com.opencode.android.data.repository.PreferencesRepository
import com.opencode.android.ui.component.*
import com.opencode.android.ui.theme.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Local UI model for attached files/images */
private data class AttachmentItem(
    val mime: String,
    val dataUri: String,
    val filename: String,
    val preview: Any? = null,
)

/** Short display names for agent modes */
private fun shortAgent(agent: String?): String = when (agent) {
    "orchestrator" -> "orch"
    "designer"    -> "dsn"
    "fixer"       -> "fix"
    "explorer"    -> "exp"
    "librarian"   -> "lib"
    "oracle"      -> "ora"
    "councillor"  -> "cou"
    "code"        -> "code"
    "plan"        -> "plan"
    null          -> "code"
    else          -> agent.take(5)
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun ChatScreen(sessionId: String, sessionTitle: String?, onBack: () -> Unit, onSubagentNavigate: (sessionId: String) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PreferencesRepository(context) }
    val json = remember { Json { ignoreUnknownKeys = true; isLenient = true } }
    val c = LocalOcColors.current

    var displayTitle by remember { mutableStateOf(sessionTitle?.ifBlank { null } ?: "新会话") }
    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isSending by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    var streamingText by remember { mutableStateOf("") }
    var selectedAgent by remember { mutableStateOf<String?>(null) }
    var availableAgents by remember { mutableStateOf<List<AgentInfo>>(emptyList()) }
    var allKnownAgents by remember { mutableStateOf<List<AgentInfo>>(emptyList()) }
    var skills by remember { mutableStateOf<List<SkillInfo>>(emptyList()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var inputFocused by remember { mutableStateOf(false) }
    var attachments by remember { mutableStateOf<List<AttachmentItem>>(emptyList()) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var subtaskWorkerSid by remember { mutableStateOf<String?>(null) }

    // Parse @agent or /command prefix from input
    val parsedInput = remember(inputText) {
        val trimmed = inputText.trimStart()
        when {
            // @agentname at start: "@oracle review this" → agent=oracle, text="review this"
            trimmed.startsWith("@") -> {
                val spaceIdx = trimmed.indexOf(' ')
                if (spaceIdx > 1) {
                    val agentPart = trimmed.substring(1, spaceIdx)
                    val rest = trimmed.substring(spaceIdx + 1).trimStart()
                    Triple(agentPart, rest, true) // isAgent=true
                } else if (spaceIdx == -1 && trimmed.length > 1) {
                    // Just typing @agent — no space yet
                    Triple(trimmed.substring(1), "", true)
                } else null
            }
            // /command at start: "/review this" → agent=oracle, text="this"
            trimmed.startsWith("/") -> {
                val spaceIdx = trimmed.indexOf(' ')
                val cmd = if (spaceIdx > 0) trimmed.substring(1, spaceIdx)
                    else trimmed.substring(1)
                val rest = if (spaceIdx > 0) trimmed.substring(spaceIdx + 1).trimStart() else ""
                Triple(cmd, rest, false) // isAgent=false, is slash command
            }
            else -> null
        }
    }
    val parsedAgent = parsedInput?.let { (name, _, isAgent) ->
        if (isAgent) name else when (name) {
            "review" -> "oracle"
            "fix" -> "fixer"
            "find", "search" -> "explorer"
            "explain", "docs" -> "librarian"
            "plan" -> "plan"
            else -> null
        }
    }
    val parsedRest = parsedInput?.second?.ifBlank { null }

    // Helper to process picked URIs
    fun processUris(uris: List<Uri>) {
        uris.forEach { uri ->
            try {
                val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@forEach
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val dataUri = "data:$mimeType;base64,$base64"
                val filename = uri.lastPathSegment?.substringAfterLast("/") ?: "file"
                val preview = if (mimeType.startsWith("image/")) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } else null
                attachments = attachments + AttachmentItem(mime = mimeType, dataUri = dataUri, filename = filename, preview = preview)
            } catch (_: Exception) {}
        }
    }

    // Image picker
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> processUris(uris); showAttachMenu = false }

    // File picker
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> processUris(uris); showAttachMenu = false }

    // Provider/model picker state
    var providers by remember { mutableStateOf<List<Provider>>(emptyList()) }
    var showModelPicker by remember { mutableStateOf(false) }
    var expandedProviderId by remember { mutableStateOf<String?>(null) }
    var selectedModel by remember { mutableStateOf<ModelRef?>(null) }

    // Detect current agent from messages
    val currentAgent = messages.firstOrNull()?.info?.agent

    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show error as snackbar, auto-dismiss
    LaunchedEffect(errorMsg) {
        errorMsg?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            errorMsg = null
        }
    }

    // Load messages + providers + agents + initial model
    LaunchedEffect(sessionId) {
        val config = prefs.config.first()
        val api = OpenCodeApi(config)
        api.getMessages(sessionId)
            .onSuccess { msgs ->
                messages = msgs.takeLast(50)
                // Extract model from last assistant message (syncs with desktop/other clients)
                msgs.lastOrNull { it.info.role == "assistant" }?.info?.let { info ->
                    if (info.providerID != null && info.modelID != null) {
                        selectedModel = ModelRef(info.providerID, info.modelID)
                    }
                }
            }
            .onFailure { errorMsg = it.message }
        // Fetch configured providers
        api.fetchConfiguredProviders()
            .onSuccess { providers = it }
        // If no model from history, use defaults from settings
        if (selectedModel == null) {
            val defProvider = prefs.defaultModelProvider.first()
            val defModel = prefs.defaultModelId.first()
            if (defProvider.isNotBlank() && defModel.isNotBlank()) {
                selectedModel = ModelRef(defProvider, defModel)
            }
        }
        // Fetch available agents (primary, non-hidden only)
        api.fetchAgents()
            .onSuccess { allAgents ->
                availableAgents = allAgents.filter { it.mode == "primary" && !it.hidden }
                allKnownAgents = allAgents.filter { !it.hidden && it.mode != "primary" }
            }
        // Fetch skills for / command autocomplete
        api.fetchSkills()
            .onSuccess { skills = it }
        isLoading = false
        api.close()
    }

    // Scroll to bottom after messages load — reversed layout: index 0 = newest
    LaunchedEffect(messages, isLoading) {
        if (!isLoading && messages.isNotEmpty()) {
            snapshotFlow { listState.layoutInfo.totalItemsCount }
                .first { it > 0 }
            withFrameNanos { }
            listState.scrollToItem(0)
        }
    }

    // SSE events
    LaunchedEffect(sessionId) {
        prefs.config.flatMapLatest { config ->
            val api = OpenCodeApi(config)
            flow {
                try { api.sessionEvents().collect { emit(it) } }
                finally { api.close() }
            }
        }.collect { eventData ->
            try {
                val obj = json.parseToJsonElement(eventData).jsonObject
                val type = obj["type"]?.jsonPrimitive?.content ?: ""
                when (type) {
                    "message.part.delta" -> {
                        streamingText += obj["delta"]?.jsonPrimitive?.content ?: ""
                    }
                    "session.status" -> {
                        val status = obj["status"]?.jsonPrimitive?.content
                        if (status == "idle" || status == "completed") {
                            isSending = false
                            streamingText = ""
                            scope.launch {
                                val cfg = prefs.config.first()
                                val api = OpenCodeApi(cfg)
                                api.getMessages(sessionId).onSuccess { messages = it }
                                api.close()
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // Auto-scroll on new messages/streaming — reversed layout: index 0 = newest
    LaunchedEffect(messages.size, streamingText) {
        if (streamingText.isEmpty() && messages.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(50)
        if (listState.layoutInfo.totalItemsCount > 0) {
            listState.scrollToItem(0)
        }
    }

    // Track if user has scrolled up (away from newest)
    val canScrollToBottom by remember {
        derivedStateOf {
            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
            first > 0
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .statusBarsPadding()
            .imePadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            // ── Top bar ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).pressable { onBack() },
                    contentAlignment = Alignment.Center,
                ) {
                    // Chevron back: <
                    Canvas(Modifier.size(20.dp)) {
                        val stroke = 1.5.dp.toPx()
                        drawLine(
                            c.ink,
                            androidx.compose.ui.geometry.Offset(size.width * 0.6f, size.height * 0.18f),
                            androidx.compose.ui.geometry.Offset(size.width * 0.22f, size.height * 0.5f),
                            strokeWidth = stroke,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        )
                        drawLine(
                            c.ink,
                            androidx.compose.ui.geometry.Offset(size.width * 0.22f, size.height * 0.5f),
                            androidx.compose.ui.geometry.Offset(size.width * 0.6f, size.height * 0.82f),
                            strokeWidth = stroke,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        displayTitle,
                        style = OcType.rowTitle,
                        color = c.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Model info
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (selectedModel != null || messages.firstOrNull()?.info?.modelID != null) {
                            Box(
                                Modifier.pressable {
                                    if (providers.isNotEmpty()) showModelPicker = !showModelPicker
                                }
                            ) {
                                val modelLabel = selectedModel?.modelID
                                    ?: messages.firstOrNull()?.info?.modelID
                                    ?: ""
                                Text(
                                    modelLabel,
                                    style = OcType.mono.copy(fontSize = 11.sp),
                                    color = if (providers.isNotEmpty()) c.ink2 else c.ink3,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                // Agent toggle pill — click to cycle through available agents
                if (availableAgents.size > 1 && selectedAgent != null) {
                    Box(
                        Modifier
                            .pressable {
                                val currentIdx = availableAgents.indexOfFirst { it.name == selectedAgent }
                                val nextIdx = (currentIdx + 1) % availableAgents.size
                                selectedAgent = availableAgents[nextIdx].name
                            }
                            .background(c.surface2, RoundedCornerShape(8.dp))
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        AnimatedContent(
                            targetState = selectedAgent,
                            transitionSpec = {
                                (slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Up, tween(200)) +
                                    fadeIn(tween(150))) togetherWith
                                    (slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Up, tween(200)) +
                                        fadeOut(tween(100)))
                            },
                            label = "agent",
                        ) { agent ->
                            Text(
                                shortAgent(agent),
                                style = OcType.monoStrong.copy(fontSize = 12.sp),
                                color = c.ink,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                // Stop button inline removed — now part of send button
            }

            // Model picker — outside the top bar Row, between Row and Hairline
            AnimatedVisibility(visible = showModelPicker) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(c.surface2)
                        .border(1.dp, c.line)
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp)
                ) {
                    providers.forEach { provider ->
                        val isExpanded = expandedProviderId == provider.id
                        val isProviderSelected = selectedModel?.providerID == provider.id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .pressable {
                                    expandedProviderId = if (isExpanded) null else provider.id
                                }
                                .background(if (isProviderSelected && !isExpanded) c.bg else c.surface2)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                provider.name.ifBlank { provider.id },
                                style = OcType.mono.copy(fontSize = 12.sp),
                                color = if (isProviderSelected) c.accent else c.ink2,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (isExpanded) "−" else "+",
                                style = OcType.mono.copy(fontSize = 12.sp),
                                color = c.ink4,
                            )
                        }
                        AnimatedVisibility(visible = isExpanded) {
                            Column {
                                provider.models.keys.forEach { modelId ->
                                    val isSelected = selectedModel?.providerID == provider.id && selectedModel?.modelID == modelId
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .pressable {
                                                selectedModel = ModelRef(provider.id, modelId)
                                                showModelPicker = false
                                                expandedProviderId = null
                                            }
                                            .background(if (isSelected) c.bg else c.surface2)
                                            .padding(horizontal = 24.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            modelId,
                                            style = OcType.mono.copy(fontSize = 11.sp),
                                            color = if (isSelected) c.accent else c.ink3,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Hairline()

            // ── Content ──
            Box(Modifier.weight(1f)) {
                when {
                    isLoading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                        }
                    }
                    messages.isEmpty() && streamingText.isEmpty() && !isSending -> {
                        EmptyChatState { inputText = it }
                    }
                    else -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            reverseLayout = true,
                        ) {
                            // Newest messages first (reversed) — streaming/thinking goes BEFORE messages
                            if (streamingText.isNotEmpty()) {
                                item(key = "streaming") { StreamingBubble(streamingText) }
                            }
                            if (isSending && streamingText.isEmpty()) {
                                item(key = "thinking") {
                                    Row(
                                        Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        OnlineDot()
                                        Text("Thinking…", style = OcType.mono, color = c.ink3)
                                    }
                                }
                            }
                            items(messages.asReversed(), key = { it.info.id }) { msg ->
                                MessageBubble(
                                    msg,
                                    onSubagentClick = { sid, _ ->
                                        val target = if (sid == sessionId || sid.isBlank()) subtaskWorkerSid else sid
                                        if (target != null) {
                                            onSubagentNavigate(target)
                                        } else {
                                            // Lazy lookup: search for subtask worker on click
                                            scope.launch {
                                                val api = OpenCodeApi(prefs.config.first())
                                                val found = api.listSessions().getOrNull()
                                                    ?.firstOrNull { it.title.startsWith("Subtask worker from $sessionId") }
                                                    ?.id
                                                api.close()
                                                if (found != null) {
                                                    subtaskWorkerSid = found
                                                    onSubagentNavigate(found)
                                                } else {
                                                    errorMsg = "Subagent session not found"
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                        }

                        // Scroll-to-bottom FAB
                        Box(
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                        ) {
                            androidx.compose.animation.AnimatedVisibility(
                            visible = canScrollToBottom,
                                enter = fadeIn() + scaleIn(),
                                exit = fadeOut() + scaleOut(),
                            ) {
                                Box(
                                    Modifier
                                        .size(44.dp)
                                        .pressable {
                                            scope.launch {
                                                listState.animateScrollToItem(0)
                                            }
                                        }
                                        .shadow(4.dp, RoundedCornerShape(14.dp))
                                        .background(c.bg, RoundedCornerShape(14.dp))
                                        .border(1.dp, c.line, RoundedCornerShape(14.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("↓", style = OcType.title, color = c.ink2)
                                }
                            }
                        }
                    }
                }
            }

            // ── Bottom input area ──
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(c.surface)
                    .navigationBarsPadding()
            ) {
                // Attachment preview row
                AnimatedVisibility(visible = attachments.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        attachments.forEachIndexed { index, item ->
                            Box(
                                Modifier
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(c.surface2)
                                    .border(1.dp, c.line, RoundedCornerShape(8.dp)),
                            ) {
                                if (item.mime.startsWith("image/") && item.preview is android.graphics.Bitmap) {
                                    Image(
                                        bitmap = item.preview.asImageBitmap(),
                                        contentDescription = item.filename,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(8.dp)),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    )
                                } else {
                                    Box(
                                        Modifier
                                            .height(48.dp)
                                            .padding(horizontal = 10.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            item.filename.take(12),
                                            style = OcType.mono.copy(fontSize = 10.sp),
                                            color = c.ink2,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                // X button
                                Box(
                                    Modifier
                                        .size(18.dp)
                                        .align(Alignment.TopEnd)
                                        .pressable {
                                            attachments = attachments.filterIndexed { i, _ -> i != index }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("×", style = OcType.monoStrong.copy(fontSize = 12.sp), color = c.ink4)
                                }
                            }
                        }
                    }
                }

                // ── @agent / /command selector panel ──
                val showCmdPanel = !isSending && inputText.trimStart().let {
                    (it.startsWith("@") || it.startsWith("/")) && it.length <= 20 &&
                        !it.contains(" ") // Hide panel once user types space (committed to choice)
                }
                AnimatedVisibility(
                    visible = showCmdPanel,
                    enter = fadeIn(tween(120)) + expandVertically(tween(120)),
                    exit = fadeOut(tween(100)) + shrinkVertically(tween(100)),
                ) {
                    val isAgentPanel = inputText.trimStart().startsWith("@")
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.raised)
                            .border(1.dp, c.line, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                    ) {
                        Text(
                            if (isAgentPanel) "SUBTASK" else "COMMANDS",
                            style = OcType.mono.copy(fontSize = 10.sp),
                            color = c.ink4,
                        )
                        Spacer(Modifier.height(8.dp))
                        if (isAgentPanel) {
                            // ── @ agent panel: show all available agents ──
                            val agents = allKnownAgents
                            if (agents.isEmpty()) {
                                Text("Loading agents…", style = OcType.mono.copy(fontSize = 12.sp), color = c.ink4)
                            } else {
                                agents.forEach { ag ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .pressable { inputText = "@${ag.name} " }
                                            .padding(horizontal = 12.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Text("@${ag.name}", style = OcType.monoStrong.copy(fontSize = 13.sp), color = c.accent)
                                    }
                                }
                            }
                        } else {
                            // ── / command panel: show real skills + mapped commands ──
                            val typed = inputText.trimStart().removePrefix("/").trim()
                            // Mapped agent commands (these route to specific subagents)
                            val agentCommands = listOf(
                                "review" to "Code review",
                                "fix" to "Fix bugs",
                                "find" to "Search codebase",
                                "explain" to "Explain code",
                                "plan" to "Create plan",
                            )
                            // Real skills from server, filtered by typed text
                            val filteredSkills = if (typed.isEmpty()) {
                                skills.take(6)
                            } else {
                                skills.filter { it.name.contains(typed, ignoreCase = true) }.take(6)
                            }
                            // Show agent commands first, then relevant skills
                            val agentToShow = if (typed.isEmpty()) agentCommands.take(3)
                                else agentCommands.filter { it.first.startsWith(typed, ignoreCase = true) }

                            val showDivider = agentToShow.isNotEmpty() && filteredSkills.isNotEmpty()

                            // Agent commands
                            agentToShow.forEach { (cmd, desc) ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .pressable { inputText = "/$cmd " }
                                        .padding(horizontal = 12.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text("/$cmd", style = OcType.monoStrong.copy(fontSize = 13.sp), color = c.accent)
                                    Text(desc, style = OcType.mono.copy(fontSize = 12.sp), color = c.ink2)
                                }
                            }

                            // Divider
                            if (showDivider) {
                                Spacer(Modifier.height(4.dp))
                                Text("SKILLS", style = OcType.mono.copy(fontSize = 10.sp), color = c.ink4,
                                    modifier = Modifier.padding(horizontal = 12.dp))
                                Spacer(Modifier.height(4.dp))
                            }

                            // Skill items
                            if (filteredSkills.isEmpty() && typed.isNotEmpty()) {
                                Text("No matching skills", style = OcType.mono.copy(fontSize = 12.sp),
                                    color = c.ink4, modifier = Modifier.padding(horizontal = 12.dp))
                            } else {
                                filteredSkills.forEach { skill ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .pressable { inputText = "/${skill.name} " }
                                            .padding(horizontal = 12.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Text("/${skill.name}", style = OcType.monoStrong.copy(fontSize = 13.sp), color = c.accent)
                                        Text(skill.description.take(50), style = OcType.mono.copy(fontSize = 11.sp),
                                            color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }

                // ── @agent / /command indicator pill ──
                if (parsedInput != null && parsedAgent != null && !showCmdPanel) {
                    val label = if (parsedInput.third) "@${parsedAgent}" else "/${parsedInput.first}"
                    Row(
                        Modifier
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(c.accent.copy(alpha = 0.10f))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(label, style = OcType.mono.copy(fontSize = 11.sp), color = c.accent)
                        Text(parsedRest ?: "Type to prompt ${shortAgent(parsedAgent ?: "")}…",
                            style = OcType.mono.copy(fontSize = 11.sp), color = c.ink3)
                    }
                    Spacer(Modifier.height(4.dp))
                }

                // Input bar: [+ expandable] [text field...] [send]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // + button group: morphs from circle to pill, icons slide right
                    run {
                        val animMs = 180
                        val pillWidth by animateDpAsState(
                            targetValue = if (showAttachMenu) 114.dp else 44.dp,
                            animationSpec = tween(animMs),
                            label = "pillWidth",
                        )
                        Box(
                            Modifier
                                .width(pillWidth)
                                .height(44.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(c.surface2),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            // + / × toggle — pinned to left, always 44×44
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .pressable(enabled = !isSending) {
                                        showAttachMenu = !showAttachMenu
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                val rotation by animateFloatAsState(
                                    targetValue = if (showAttachMenu) 45f else 0f,
                                    animationSpec = tween(animMs),
                                    label = "plusRotation",
                                )
                                Text(
                                    "+",
                                    style = OcType.body.copy(fontSize = 20.sp, color = c.ink4),
                                    modifier = Modifier.graphicsLayer {
                                        rotationZ = rotation
                                        translationY = -7f
                                    },
                                )
                            }
                            // Icon buttons — slide out to the right of + (start at x=44dp)
                            if (showAttachMenu) {
                                Row(
                                    Modifier.padding(start = 44.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Box(
                                        Modifier
                                            .size(30.dp)
                                            .pressable(enabled = !isSending) {
                                                imagePicker.launch("image/*")
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Outlined.Image,
                                            contentDescription = "Image",
                                            tint = c.ink3,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    Box(
                                        Modifier
                                            .size(30.dp)
                                            .pressable(enabled = !isSending) {
                                                filePicker.launch("*/*")
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Outlined.AttachFile,
                                            contentDescription = "File",
                                            tint = c.ink3,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    Spacer(Modifier.width(7.dp))
                                }
                            }
                        }
                    }
                    // Input field container
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp, max = 96.dp)
                            .background(c.surface2, RoundedCornerShape(26.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        BasicTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { inputFocused = it.isFocused },
                            textStyle = OcType.body.copy(color = c.ink),
                            maxLines = 4,
                            enabled = !isSending,
                            cursorBrush = SolidColor(c.accent),
                            decorationBox = { inner ->
                                if (inputText.isEmpty()) {
                                    Text("Ask opencode…", style = OcType.body, color = c.ink4)
                                }
                                inner()
                            },
                        )
                    }
                    // Send / Stop button — morphs between send arrow and stop square
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSending) c.accent
                                else if (inputText.isNotBlank()) c.accent
                                else c.surface2
                            )
                            .pressable(
                                enabled = isSending || inputText.isNotBlank(),
                            ) {
                                if (isSending) {
                                    scope.launch {
                                        val cfg = prefs.config.first()
                                        val api = OpenCodeApi(cfg)
                                        api.abort(sessionId)
                                        api.close()
                                        isSending = false
                                        streamingText = ""
                                    }
                                    return@pressable
                                }
                                if (inputText.isBlank()) return@pressable
                                // Use parsed text + agent when @agent or /command detected
                                val sendText: String
                                val sendAgent: String?
                                if (parsedInput != null) {
                                    sendText = parsedRest ?: ""  // Strip prefix, never send @agent
                                    sendAgent = parsedAgent ?: selectedAgent
                                } else {
                                    sendText = inputText
                                    sendAgent = selectedAgent
                                }
                                if (sendText.isBlank()) return@pressable
                                inputText = ""
                                isSending = true
                                streamingText = ""
                                // Build parts: text + attachments
                                val parts = mutableListOf<PromptPart>()
                                val myAttachments = attachments.toList()
                                parts.add(PromptPart(type = "text", text = sendText))
                                myAttachments.forEach { att ->
                                    parts.add(PromptPart(type = "file", mime = att.mime, url = att.dataUri, filename = att.filename))
                                }
                                attachments = emptyList()
                                val msgParts = mutableListOf<MessagePart>()
                                if (sendText.isNotBlank()) msgParts.add(MessagePart(type = "text", text = sendText))
                                myAttachments.forEach { att ->
                                    msgParts.add(MessagePart(type = "file", mime = att.mime, url = att.dataUri, filename = att.filename))
                                }
                                val userMsg = Message(
                                    info = MessageInfo(id = "local_${System.currentTimeMillis()}", role = "user"),
                                    parts = msgParts
                                )
                                messages = messages + userMsg
                                scope.launch {
                                    val cfg = prefs.config.first()
                                    val api = OpenCodeApi(cfg)
                                    api.sendPrompt(sessionId, parts, agent = sendAgent, model = selectedModel)
                                        .onSuccess { assistantMsg ->
                                            messages = messages + assistantMsg
                                            isSending = false
                                            // Update selected model from response
                                            assistantMsg.info.let { info ->
                                                if (info.providerID != null && info.modelID != null) {
                                                    selectedModel = ModelRef(info.providerID, info.modelID)
                                                }
                                            }
                                            // Refresh title if still placeholder
                                            if (displayTitle == "新会话") {
                                                api.getSession(sessionId).onSuccess { s ->
                                                    val t = s.title.substringBefore(" - ").ifBlank { s.slug }
                                                    if (t.isNotBlank() && t != "new session") displayTitle = t
                                                }
                                            }
                                        }
                                        .onFailure {
                                            val api2 = OpenCodeApi(cfg)
                                            api2.getMessages(sessionId).onSuccess { messages = it }
                                            api2.close()
                                            val msg = it.message ?: "Unknown error"
                                            errorMsg = if (msg.contains("No suitable converter") || msg.contains("SerializationException"))
                                                "Unexpected server response"
                                            else msg.take(120)
                                            isSending = false
                                        }
                                    api.close()
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        val hasContent = inputText.isNotBlank()
                        AnimatedContent(
                            targetState = isSending to hasContent,
                            transitionSpec = {
                                if (initialState.first != targetState.first) {
                                    // Icon shape change (↑→■): scale morph
                                    (scaleIn(tween(100)) + fadeIn(tween(100))) togetherWith
                                        (scaleOut(tween(100)) + fadeOut(tween(100)))
                                } else {
                                    // Color-only change: instant crossfade
                                    fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                                }
                            },
                            label = "sendStop",
                        ) { (sending, content) ->
                            val tint = when {
                                sending || content -> c.accentInk
                                else -> c.ink4
                            }
                            Text(
                                if (sending) "■" else "↑",
                                style = OcType.body.copy(color = tint),
                            )
                        }
                    }
                }
            }
        }

        // Snackbar
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp),
        ) { data ->
            Box(
                Modifier
                    .background(c.accent, RoundedCornerShape(0.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(data.visuals.message, style = OcType.mono, color = c.accentInk)
            }
        }
    }
}
