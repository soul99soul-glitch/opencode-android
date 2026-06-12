package com.opencode.android.ui.screen

import android.annotation.SuppressLint
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
import android.util.Log
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
import androidx.compose.foundation.layout.widthIn
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
import com.opencode.android.data.api.PromptSendResult
import com.opencode.android.data.model.*
import com.opencode.android.data.repository.PreferencesRepository
import com.opencode.android.runtime.RuntimeCompanionClient
import com.opencode.android.ui.component.*
import com.opencode.android.ui.screen.chat.ChatStateHolder
import com.opencode.android.ui.screen.chat.ChatTimelineEvent
import com.opencode.android.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import androidx.compose.ui.res.stringResource
import com.opencode.android.R

/** Local UI model for attached files/images */
private data class AttachmentItem(
    val mime: String,
    val dataUri: String,
    val filename: String,
    val preview: Any? = null,
)

/** Short display names for agent modes */
private fun shortAgent(agent: String?): String = when (agent) {
    "orchestrator" -> "Orch"
    "build"        -> "Build"
    "plan"         -> "Plan"
    "designer"     -> "Dsn"
    "fixer"        -> "Fix"
    "explorer"     -> "Exp"
    "librarian"    -> "Lib"
    "oracle"       -> "Ora"
    "councillor"   -> "Cou"
    "code"         -> "Code"
    null           -> "Build"
    else          -> agent.take(5)
}

private enum class AgentActivityStatus {
    Ready,
    Thinking,
    Tool,
    Generating,
    Stalled,
    Complete,
}

internal fun resolveModelForSend(
    selectedModel: ModelRef?,
    providers: List<Provider>,
    endpointMode: ConnectionMode,
    localProviderProfile: LocalProviderProfile? = null,
): ModelRef? {
    fun isAvailable(model: ModelRef?): Boolean {
        if (model == null) return false
        return providers.any { provider ->
            provider.id == model.providerID && provider.models.containsKey(model.modelID)
        }
    }

    selectedModel?.takeIf(::isAvailable)?.let { return it }

    if (endpointMode != ConnectionMode.LOCAL_BUNDLED) return null

    val profile = localProviderProfile?.takeIf { it.enabled } ?: return null
    return profile.modelIds
        .asSequence()
        .map { ModelRef(LocalProviderDefaults.PROVIDER_ID, it) }
        .firstOrNull(::isAvailable)
}

internal fun hasDanglingEmptyAssistant(messages: List<Message>): Boolean =
    messages.any { it.info.role == "assistant" && it.parts.isEmpty() }

internal fun JsonObject.stringField(name: String): String? =
    runCatching { get(name)?.jsonPrimitive?.content }.getOrNull()

internal fun JsonObject.objectField(name: String): JsonObject? =
    runCatching { get(name)?.jsonObject }.getOrNull()

internal fun JsonObject.eventSessionId(): String? =
    stringField("sessionID")
        ?: stringField("sessionId")
        ?: objectField("part")?.stringField("sessionID")
        ?: objectField("part")?.stringField("sessionId")
        ?: objectField("message")?.stringField("sessionID")
        ?: objectField("message")?.stringField("sessionId")

internal fun JsonObject.eventMessageRole(): String? =
    stringField("role")
        ?: objectField("message")?.stringField("role")
        ?: objectField("info")?.stringField("role")
        ?: objectField("message")?.objectField("info")?.stringField("role")

internal fun JsonObject.eventMessageId(): String? =
    stringField("messageID")
        ?: stringField("messageId")
        ?: stringField("message_id")
        ?: objectField("part")?.stringField("messageID")
        ?: objectField("part")?.stringField("messageId")
        ?: objectField("part")?.stringField("message_id")
        ?: objectField("message")?.stringField("id")
        ?: objectField("message")?.objectField("info")?.stringField("id")

internal fun JsonObject.eventPartId(): String? =
    stringField("partID")
        ?: stringField("partId")
        ?: stringField("part_id")
        ?: objectField("part")?.stringField("id")

internal fun JsonObject.deltaField(): String? =
    stringField("field")
        ?: stringField("partField")
        ?: deltaPartType()?.let { if (it == "reasoning") "text" else it }

internal fun JsonObject.deltaPartType(): String? {
    val part = objectField("part")
    val partType = part?.stringField("type")
        ?: stringField("partType")
        ?: stringField("part_type")
        ?: stringField("type")
    if (partType != null) return partType

    val partId = stringField("partID")
        ?: stringField("partId")
        ?: stringField("part_id")
        ?: part?.stringField("id")
    return when {
        partId?.startsWith("text", ignoreCase = true) == true -> "text"
        partId?.startsWith("reasoning", ignoreCase = true) == true -> "reasoning"
        partId?.startsWith("thinking", ignoreCase = true) == true -> "reasoning"
        else -> null
    }
}

internal data class StreamPartSnapshot(
    val type: String,
    val text: String,
    val messageId: String? = null,
)

internal fun JsonObject.messageInfoSnapshot(): MessageInfo? {
    val info = objectField("info") ?: objectField("message")?.objectField("info") ?: return null
    val id = info.stringField("id") ?: return null
    return MessageInfo(
        id = id,
        role = info.stringField("role") ?: stringField("role") ?: "unknown",
        sessionID = info.stringField("sessionID") ?: info.stringField("sessionId") ?: eventSessionId(),
        providerID = info.stringField("providerID") ?: info.stringField("providerId"),
        modelID = info.stringField("modelID") ?: info.stringField("modelId"),
        agent = info.stringField("agent"),
    )
}

internal fun JsonObject.messagePartSnapshot(json: Json): MessagePart? {
    val partObj = objectField("part") ?: return null
    val decoded = runCatching { json.decodeFromJsonElement(MessagePart.serializer(), partObj) }.getOrNull()
    val fallback = MessagePart(
        type = partObj.stringField("type") ?: return null,
        text = partObj.stringField("text"),
        mime = partObj.stringField("mime"),
        url = partObj.stringField("url"),
        filename = partObj.stringField("filename"),
        id = partObj.stringField("id"),
        sessionID = partObj.stringField("sessionID") ?: partObj.stringField("sessionId"),
        messageID = partObj.stringField("messageID") ?: partObj.stringField("messageId") ?: partObj.stringField("message_id"),
        tool = partObj.stringField("tool"),
        callID = partObj.stringField("callID") ?: partObj.stringField("callId") ?: partObj.stringField("call_id"),
        state = decoded?.state,
    )
    val part = decoded ?: fallback
    return part.copy(
        sessionID = part.sessionID ?: eventSessionId(),
        messageID = part.messageID ?: eventMessageId(),
    )
}

internal fun JsonObject.streamPartSnapshot(): StreamPartSnapshot? {
    val part = objectField("part") ?: this
    val type = part.stringField("type")
        ?: stringField("partType")
        ?: stringField("part_type")
        ?: deltaPartType()
        ?: return null
    val text = part.stringField("text")
        ?: stringField("text")
        ?: return null
    return StreamPartSnapshot(type = type, text = text, messageId = eventMessageId())
}

@Composable
private fun AgentActivityIndicator(status: AgentActivityStatus, modifier: Modifier = Modifier) {
    val c = LocalOcColors.current
    val active = status == AgentActivityStatus.Thinking ||
        status == AgentActivityStatus.Tool ||
        status == AgentActivityStatus.Generating
    val label = stringResource(
        when (status) {
            AgentActivityStatus.Ready -> R.string.chat_status_ready
            AgentActivityStatus.Thinking -> R.string.chat_status_thinking
            AgentActivityStatus.Tool -> R.string.chat_status_tool
            AgentActivityStatus.Generating -> R.string.chat_status_generating
            AgentActivityStatus.Stalled -> R.string.chat_status_stalled
            AgentActivityStatus.Complete -> R.string.chat_status_complete
        },
    )

    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        OnlineDot(active = active)
        Text(
            label,
            style = OcType.mono.copy(fontSize = 11.sp),
            color = when (status) {
                AgentActivityStatus.Stalled -> c.ink2
                AgentActivityStatus.Complete, AgentActivityStatus.Ready -> c.ink4
                else -> c.accent
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val StandardPrimaryAgents = listOf("build", "plan", "orchestrator")

private fun primaryAgentRank(agent: AgentInfo): Int {
    val index = StandardPrimaryAgents.indexOf(agent.name)
    return if (index >= 0) index else StandardPrimaryAgents.size
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun ChatScreen(sessionId: String, sessionTitle: String?, onBack: () -> Unit, onSubagentNavigate: (sessionId: String) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PreferencesRepository(context) }
    val runtimeClient = remember { RuntimeCompanionClient(context) }
    val json = remember { Json { ignoreUnknownKeys = true; isLenient = true } }
    val c = LocalOcColors.current

    val newSessionLabel = stringResource(R.string.chat_new_session)
    var displayTitle by remember { mutableStateOf(sessionTitle?.ifBlank { null } ?: newSessionLabel) }
    val chatState = remember(sessionId) { ChatStateHolder(sessionId) }
    val messages = chatState.messages
    var isLoading by remember { mutableStateOf(true) }
    var isSending by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    val rawText = inputText.text
    var latestStreamText by remember(sessionId) { mutableStateOf("") }
    var latestReasoningText by remember(sessionId) { mutableStateOf("") }
    val reversedMessages by remember { derivedStateOf { messages.asReversed() } }
    var selectedAgent by remember { mutableStateOf<String?>(null) }
    var availableAgents by remember { mutableStateOf<List<AgentInfo>>(emptyList()) }
    var allKnownAgents by remember { mutableStateOf<List<AgentInfo>>(emptyList()) }
    var skills by remember { mutableStateOf<List<SkillInfo>>(emptyList()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var inputFocused by remember { mutableStateOf(false) }
    var attachments by remember { mutableStateOf<List<AttachmentItem>>(emptyList()) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var subtaskWorkerSid by remember { mutableStateOf<String?>(null) }
    var initialEndpointKey by remember(sessionId) { mutableStateOf<String?>(null) }
    var providers by remember { mutableStateOf<List<Provider>>(emptyList()) }
    var showModelPicker by remember { mutableStateOf(false) }
    var expandedProviderId by remember { mutableStateOf<String?>(null) }
    var selectedModel by remember { mutableStateOf<ModelRef?>(null) }
    val listState = rememberLazyListState()
    var lastAgentActivityAt by remember(sessionId) { mutableLongStateOf(System.currentTimeMillis()) }
    var statusClock by remember(sessionId) { mutableLongStateOf(System.currentTimeMillis()) }

    fun markAgentActivity(now: Long = System.currentTimeMillis()) {
        lastAgentActivityAt = now
        statusClock = now
    }

    LaunchedEffect(sessionId, isSending) {
        while (isSending) {
            delay(1_000)
            statusClock = System.currentTimeMillis()
        }
    }

    val agentActivityStatus by remember {
        derivedStateOf {
            val latestAssistant = messages.lastOrNull { it.role == "assistant" }
            val parts = latestAssistant?.visibleParts.orEmpty()
            val hasAssistantOutput = latestAssistant?.visibleParts?.any { part ->
                when (part.type) {
                    "text", "reasoning" -> !part.text.isNullOrBlank()
                    "tool", "tool-invocation", "tool-result" -> true
                    else -> false
                }
            } == true
            if (!isSending) {
                if (hasAssistantOutput) AgentActivityStatus.Complete else AgentActivityStatus.Ready
            } else if (statusClock - lastAgentActivityAt > 12_000) {
                AgentActivityStatus.Stalled
            } else {
                val completeToolStates = setOf("completed", "done", "failed", "error", "cancelled", "canceled")
                val hasRunningTool = parts.any { part ->
                    if (part.type != "tool" && part.type != "tool-invocation") return@any false
                    val state = part.state?.status?.lowercase()
                    state == null || state !in completeToolStates
                }
                val hasReasoning = parts.any { it.type == "reasoning" && !it.text.isNullOrBlank() }
                val hasText = parts.any { it.type == "text" && !it.text.isNullOrBlank() }
                when {
                    hasRunningTool -> AgentActivityStatus.Tool
                    hasText || latestStreamText.isNotBlank() -> AgentActivityStatus.Generating
                    hasReasoning || latestReasoningText.isNotBlank() -> AgentActivityStatus.Thinking
                    else -> AgentActivityStatus.Thinking
                }
            }
        }
    }

    // Parse @agent or /command prefix from input
    val parsedInput = remember(rawText) {
        val trimmed = rawText.trimStart()
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
                if (cmd.isBlank()) return@remember null
                val rest = if (spaceIdx > 0) trimmed.substring(spaceIdx + 1).trimStart() else ""
                Triple(cmd, rest, false)
            }
            else -> null
        }
    }
    val parsedAgent = parsedInput?.let { (name, _, isAgent) ->
        if (isAgent) name else {
            val lower = name.lowercase()
            when (lower) {
                "review" -> "oracle"
                "fix" -> "fixer"
                "find", "search" -> "explorer"
                "explain", "docs" -> "librarian"
                "plan" -> "plan"
                else -> null
            }
        }
    }
    val parsedRest = parsedInput?.second?.ifBlank { null }

    fun resetStreamBuffers() {
        latestStreamText = ""
        latestReasoningText = ""
    }

    fun markSseTextFlushed(text: String) {
        latestStreamText = text
        markAgentActivity()
    }

    fun markSseReasoningFlushed(text: String) {
        latestReasoningText = text
        markAgentActivity()
    }

    fun Message.firstText(): String? = parts.firstOrNull { it.type == "text" }?.text
    fun Message.hasStructuredParts(): Boolean = parts.any { it.type != "text" }
    fun Message.streamFallbackSignature(): String =
        parts.joinToString("|") { part ->
            listOf(
                part.type,
                part.id.orEmpty(),
                part.text.orEmpty().length.toString(),
                part.state?.status.orEmpty(),
                part.state?.output.orEmpty().length.toString(),
            ).joinToString(":")
        }

    fun Throwable?.isLocalRuntimeConnectFailure(): Boolean {
        val msg = this?.message.orEmpty()
        if (!msg.contains("127.0.0.1")) return false
        return listOf("connect timeout", "failed to connect", "connection refused", "connection timed out")
            .any { msg.contains(it, ignoreCase = true) }
    }

    suspend fun restartLocalRuntimeForRetry(error: Throwable?, forceLocal: Boolean = false): Boolean {
        if (!forceLocal && !error.isLocalRuntimeConnectFailure()) return false
        if (prefs.connectionMode.first() != ConnectionMode.LOCAL_BUNDLED) return false
        val local = prefs.localProfile.first()
        val provider = prefs.localProviderProfile.first()
        val providerApiKey = if (provider.hasApiKey) prefs.getLocalProviderApiKey(provider.presetId) else ""
        val serverPassword = prefs.getOrCreateLocalServerPassword()
        return runtimeClient.restartAndAwaitReady(
            port = local.bundledPort,
            workspaceName = local.workspacePath,
            workspaceTreeUri = local.workspaceTreeUri,
            providerProfile = provider,
            providerApiKey = providerApiKey,
            serverPassword = serverPassword,
        ).isSuccess
    }

    suspend fun modelForSend(endpoint: ActiveEndpoint): ModelRef? {
        val localProviderProfile = if (endpoint.mode == ConnectionMode.LOCAL_BUNDLED) {
            prefs.localProviderProfile.first()
        } else {
            null
        }
        return resolveModelForSend(
            selectedModel = selectedModel,
            providers = providers,
            endpointMode = endpoint.mode,
            localProviderProfile = localProviderProfile,
        ).also { selectedModel = it }
    }

    suspend fun reconcileSelectedModel(endpoint: ActiveEndpoint) {
        selectedModel = resolveModelForSend(
            selectedModel = selectedModel,
            providers = providers,
            endpointMode = endpoint.mode,
            localProviderProfile = if (endpoint.mode == ConnectionMode.LOCAL_BUNDLED) {
                prefs.localProviderProfile.first()
            } else {
                null
            },
        )
    }

    suspend fun syncServerMessagesAfterIdle() {
        delay(800)
        val api = OpenCodeApi(prefs.activeEndpoint.first())
        try {
            api.getMessages(sessionId).onSuccess { serverMsgs ->
                markAgentActivity()
                chatState.onServerMessages(serverMsgs).selectedModel?.let { selectedModel = it }
            }
        } finally {
            api.close()
        }
        if (prefs.connectionMode.first() == ConnectionMode.LOCAL_BUNDLED) {
            runCatching { prefs.syncMcpAndPluginsFromNative() }
        }
        delay(32)
        chatState.finishSettling()
        resetStreamBuffers()
    }

    LaunchedEffect(sessionId) {
        prefs.activeEndpoint.collect { endpoint ->
            val initial = initialEndpointKey
            if (initial == null) {
                initialEndpointKey = endpoint.identityKey
            } else if (endpoint.identityKey != initial) {
                onBack()
            }
        }
    }

    fun scrollNewestIntoViewIfPinned() {
        scope.launch {
            if (listState.firstVisibleItemIndex == 0 && listState.layoutInfo.totalItemsCount > 0) {
                listState.scrollToItem(0)
            }
        }
    }

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
        val endpoint = prefs.activeEndpoint.first()
        val api = OpenCodeApi(endpoint)
        try {
            api.getMessages(sessionId)
                .onSuccess { msgs ->
                    chatState.loadServerMessages(msgs.takeLast(50))
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
            reconcileSelectedModel(endpoint)
            // Fetch available agents (primary, non-hidden only)
            api.fetchAgents()
                .onSuccess { allAgents ->
                    availableAgents = allAgents
                        .filter { it.mode == "primary" && !it.hidden }
                        .sortedWith(compareBy(::primaryAgentRank, AgentInfo::name))
                    allKnownAgents = allAgents.filter { !it.hidden && it.mode != "primary" }
                    if (selectedAgent == null || availableAgents.none { it.name == selectedAgent }) {
                        val defaultAgent = prefs.defaultAgent.first().ifBlank { "build" }
                        selectedAgent = when {
                            availableAgents.any { it.name == defaultAgent } -> defaultAgent
                            availableAgents.any { it.name == "build" } -> "build"
                            else -> availableAgents.firstOrNull()?.name
                        }
                    }
                }
                .onFailure {
                    availableAgents = StandardPrimaryAgents.map { AgentInfo(name = it) }
                    if (selectedAgent == null) selectedAgent = "build"
                }
            // Fetch skills for / command autocomplete
            api.fetchSkills()
                .onSuccess { skills = it }
        } finally {
            api.close()
            isLoading = false
        }
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
        prefs.activeEndpoint.flatMapLatest { endpoint ->
            val api = OpenCodeApi(endpoint)
            api.sessionEvents()
                .retryWhen { cause, _ ->
                    if (endpoint.mode == ConnectionMode.LOCAL_BUNDLED && cause.isLocalRuntimeConnectFailure()) {
                        restartLocalRuntimeForRetry(cause)
                    }
                    delay(1_000)
                    true
                }
                .onCompletion { api.close() }
        }.collect { eventData ->
            try {
                val obj = json.parseToJsonElement(eventData).jsonObject
                val type = obj["type"]?.jsonPrimitive?.content ?: ""
                val props = obj["properties"]?.jsonObject
                // Filter: SSE /event is global — strictly match this session
                val evtSid = props?.eventSessionId().orEmpty()
                if (evtSid.isBlank() || evtSid != sessionId) return@collect
                when (type) {
                    "message.updated" -> {
                        markAgentActivity()
                        props?.messageInfoSnapshot()?.let { info ->
                            chatState.onTimelineEvent(ChatTimelineEvent.MessageUpdated(info)).selectedModel?.let { selectedModel = it }
                        }
                    }
                    "message.part.delta" -> {
                        markAgentActivity()
                        val eventProps = props ?: return@collect
                        val messageID = eventProps.eventMessageId() ?: return@collect
                        val partID = eventProps.eventPartId() ?: return@collect
                        val field = eventProps.deltaField() ?: return@collect
                        val delta = eventProps.get("delta")?.jsonPrimitive?.content ?: return@collect
                        chatState.onTimelineEvent(
                            ChatTimelineEvent.PartDelta(
                                sessionID = evtSid,
                                messageID = messageID,
                                partID = partID,
                                field = field,
                                delta = delta,
                            )
                        )
                        when (eventProps.deltaPartType()) {
                            "text" -> markSseTextFlushed(delta)
                            "reasoning" -> markSseReasoningFlushed(delta)
                        }
                        scrollNewestIntoViewIfPinned()
                    }
                    "message.part.updated" -> {
                        markAgentActivity()
                        val eventProps = props ?: return@collect
                        val part = eventProps.messagePartSnapshot(json)
                        if (part != null) {
                            chatState.onTimelineEvent(ChatTimelineEvent.PartUpdated(part)).selectedModel?.let { selectedModel = it }
                            when (part.type) {
                                "text" -> markSseTextFlushed(part.text.orEmpty())
                                "reasoning" -> markSseReasoningFlushed(part.text.orEmpty())
                            }
                            scrollNewestIntoViewIfPinned()
                        }
                    }
                    "message.part.removed" -> {
                        markAgentActivity()
                        val eventProps = props ?: return@collect
                        val messageID = eventProps.eventMessageId() ?: return@collect
                        val partID = eventProps.eventPartId() ?: return@collect
                        chatState.onTimelineEvent(ChatTimelineEvent.PartRemoved(messageID, partID))
                    }
                    "session.status" -> {
                        val status = props?.get("status")?.jsonObject?.get("type")?.jsonPrimitive?.content
                        markAgentActivity()
                        if (status != null) {
                            chatState.onTimelineEvent(ChatTimelineEvent.SessionStatusChanged(evtSid, status))
                        }
                        if (status == "idle" || status == "completed") {
                            isSending = false
                            chatState.onCompleted()
                            scope.launch { syncServerMessagesAfterIdle() }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("ChatScreen", "Failed to process OpenCode SSE event", e)
            }
        }
    }

    // Polling fallback: fetch messages every 500ms while sending.
    // Lightweight — only refreshes message list, streaming deltas left to SSE.
    LaunchedEffect(sessionId, isSending) {
        if (!isSending) return@LaunchedEffect
        var lastServerIds = ""
        var lastChangeAt = System.currentTimeMillis()
        while (isSending) {
            delay(500)
            if (!isSending) break
            try {
                val api = OpenCodeApi(prefs.activeEndpoint.first())
                try {
                    val result = api.getMessages(sessionId)
                    result.onSuccess { serverMsgs ->
                        val latestAssistant = serverMsgs.lastOrNull { it.info.role == "assistant" }
                        val sig = serverMsgs.joinToString("|") { it.info.id } + "_" +
                            latestAssistant?.streamFallbackSignature().orEmpty()
                        if (sig != lastServerIds) {
                            lastServerIds = sig
                            lastChangeAt = System.currentTimeMillis()
                            markAgentActivity(lastChangeAt)
                            if (latestAssistant?.hasStructuredParts() == true) {
                                chatState.onServerMessages(serverMsgs).selectedModel?.let { selectedModel = it }
                            } else if (latestStreamText.isEmpty()) {
                                latestAssistant?.firstText()?.takeIf { it.isNotBlank() }?.let { text ->
                                    markSseTextFlushed(text)
                                    chatState.onStreamDeltaFlush(text)
                                }
                            }
                        }
                    }
                } finally {
                    api.close()
                }
            } catch (_: Exception) {}
            // No change for 20s while sending → assume server is idle
            if (System.currentTimeMillis() - lastChangeAt > 20_000 && isSending) {
                val hadNoOutput = latestStreamText.isEmpty()
                isSending = false
                chatState.onCompleted()
                scope.launch { syncServerMessagesAfterIdle() }
                if (hadNoOutput) {
                    errorMsg = context.getString(R.string.chat_no_response)
                }
            }
        }
    }

    // Auto-scroll on inserted messages — streaming growth scrolls from the throttle loop.
    LaunchedEffect(messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
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
                    // Model + live agent activity.
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val modelLabel = selectedModel?.modelID
                            ?: messages.firstOrNull()?.modelID
                            ?: ""
                        if (modelLabel.isNotBlank()) {
                            Box(
                                Modifier
                                    .widthIn(max = 150.dp)
                                    .pressable {
                                        if (providers.isNotEmpty()) showModelPicker = !showModelPicker
                                    }
                            ) {
                                Text(
                                    modelLabel,
                                    style = OcType.mono.copy(fontSize = 11.sp),
                                    color = if (providers.isNotEmpty()) c.ink2 else c.ink3,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        AgentActivityIndicator(agentActivityStatus)
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
                    messages.isEmpty() && !isSending -> {
                        EmptyChatState { inputText = TextFieldValue(it) }
                    }
                    else -> {
                        Column(Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            reverseLayout = true,
                        ) {
                            items(reversedMessages, key = { it.renderId }) { msg ->
                                MessageBubble(
                                    msg,
                                    onSubagentClick = { sid, _ ->
                                        if (sid.isNotBlank() && sid != sessionId) {
                                            onSubagentNavigate(sid)
                                        } else {
                                            // Lazy lookup: find subtask session by known patterns
                                            scope.launch {
                                                val api = OpenCodeApi(prefs.activeEndpoint.first())
                                                val found = try {
                                                    val sessions = api.listSessions().getOrNull() ?: emptyList()
                                                    // Pattern 1: "Subtask worker from $sessionId" (old format)
                                                    // Pattern 2: title containing "(@agent subagent)" (new format)
                                                    sessions.firstOrNull { s ->
                                                        s.id != sessionId && (
                                                            s.title.startsWith("Subtask worker from $sessionId") ||
                                                                (s.title.contains("(@") && s.title.contains("subagent)"))
                                                        )
                                                    }?.id
                                                } finally {
                                                    api.close()
                                                }
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
                        } // close Column

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
                val showCmdPanel = !isSending && rawText.trimStart().let {
                    (it.startsWith("@") || it.startsWith("/")) && it.length <= 20 &&
                        !it.contains(" ") // Hide panel once user types space (committed to choice)
                }
                AnimatedVisibility(
                    visible = showCmdPanel,
                    enter = fadeIn(tween(120)) + expandVertically(tween(120)),
                    exit = fadeOut(tween(100)) + shrinkVertically(tween(100)),
                ) {
                    val isAgentPanel = rawText.trimStart().startsWith("@")
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.raised)
                            .border(1.dp, c.line, RoundedCornerShape(12.dp))
                            .padding(8.dp)
                                .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            if (isAgentPanel) stringResource(R.string.chat_panel_subtask) else stringResource(R.string.chat_panel_commands),
                            style = OcType.mono.copy(fontSize = 10.sp),
                            color = c.ink4,
                        )
                        Spacer(Modifier.height(8.dp))
                        if (isAgentPanel) {
                            // ── @ agent panel: show all available agents ──
                            val agents = allKnownAgents
                            if (agents.isEmpty()) {
                                Text(stringResource(R.string.chat_loading_agents), style = OcType.mono.copy(fontSize = 12.sp), color = c.ink4)
                            } else {
                                agents.forEach { ag ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .pressable {
                                                val t = "@${ag.name} "
                                                inputText = TextFieldValue(t, selection = TextRange(t.length))
                                            }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text("@${ag.name}", style = OcType.monoStrong.copy(fontSize = 13.sp), color = c.accent)
                                    }
                                }
                            }
                        } else {
                            // ── / command panel: show real skills + mapped commands ──
                            val typed = rawText.trimStart().removePrefix("/").trim()
                            // Mapped agent commands (these route to specific subagents)
                            val agentCommands = listOf(
                                "review" to stringResource(R.string.chat_cmd_review),
                                "fix" to stringResource(R.string.chat_cmd_fix),
                                "find" to stringResource(R.string.chat_cmd_find),
                                "explain" to stringResource(R.string.chat_cmd_explain),
                                "plan" to stringResource(R.string.chat_cmd_plan),
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
                                        .pressable {
                                            val t = "/$cmd "
                                            inputText = TextFieldValue(t, selection = TextRange(t.length))
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
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
                                Text(stringResource(R.string.chat_panel_skills), style = OcType.mono.copy(fontSize = 10.sp), color = c.ink4,
                                    modifier = Modifier.padding(horizontal = 12.dp))
                                Spacer(Modifier.height(4.dp))
                            }

                            // Skill items
                            if (filteredSkills.isEmpty() && typed.isNotEmpty()) {
                                Text(stringResource(R.string.chat_no_matching_skills), style = OcType.mono.copy(fontSize = 12.sp),
                                    color = c.ink4, modifier = Modifier.padding(horizontal = 12.dp))
                            } else {
                                filteredSkills.forEach { skill ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .pressable {
                                                val t = "/${skill.name} "
                                                inputText = TextFieldValue(t, selection = TextRange(t.length))
                                            }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
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
                        Text(parsedRest ?: stringResource(R.string.chat_input_prompt_placeholder, shortAgent(parsedAgent ?: "")),
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
                                if (inputText.text.isEmpty()) {
                                    Text(stringResource(R.string.chat_input_placeholder), style = OcType.body, color = c.ink4)
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
                                else if (rawText.isNotBlank()) c.accent
                                else c.surface2
                            )
                            .pressable(
                                enabled = isSending || rawText.isNotBlank(),
                            ) {
                                if (isSending) {
                                    scope.launch {
                                        val api = OpenCodeApi(prefs.activeEndpoint.first())
                                        try {
                                            api.abort(sessionId)
                                        } finally {
                                            api.close()
                                        }
                                        isSending = false
                                        resetStreamBuffers()
                                        chatState.onAbort()
                                    }
                                    return@pressable
                                }
                                if (rawText.isBlank()) return@pressable
                                // Send logic with forced delegation:
                                // - bubbleText: original user text for the message bubble
                                // - sendText: "Delegate...using task tool" — forces task capsule on server
                                val bubbleText: String   // text shown in user message bubble
                                val sendText: String      // text sent to server
                                val sendAgent: String     // API agent (always orchestrator for @cmd)
                                val displayAgent: String? // UI tag shown on user bubble
                                if (parsedInput != null) {
                                    val (name, rest, isAgent) = parsedInput
                                    if (isAgent) {
                                        // Force delegation to subagent via task tool
                                        sendText = "Delegate to @$name subagent using task tool: ${rest ?: ""}".trim()
                                        bubbleText = rawText
                                        sendAgent = selectedAgent ?: "orchestrator"
                                        displayAgent = name
                                    } else {
                                        val mapped = parsedAgent
                                        if (mapped != null) {
                                            // /command → force delegation to mapped agent
                                            sendText = "Delegate to @$mapped subagent using task tool: ${rest ?: ""}".trim()
                                            bubbleText = rawText
                                            sendAgent = selectedAgent ?: "orchestrator"
                                            displayAgent = mapped
                                        } else {
                                            // /skillname → send raw text for server skill handling
                                            val isSkill = skills.any { it.name.equals(name, ignoreCase = true) }
                                            sendText = rawText
                                            bubbleText = rawText
                                            sendAgent = selectedAgent ?: "orchestrator"
                                            displayAgent = null
                                        }
                                    }
                                } else {
                                    sendText = rawText
                                    bubbleText = rawText
                                    sendAgent = selectedAgent ?: "orchestrator"
                                    displayAgent = null
                                }
                                if (bubbleText.isBlank()) return@pressable
                                inputText = TextFieldValue("")
                                isSending = true
                                markAgentActivity()
                                resetStreamBuffers()
                                // Build parts: text + attachments
                                val parts = mutableListOf<PromptPart>()
                                val myAttachments = attachments.toList()
                                parts.add(PromptPart(type = "text", text = sendText))
                                myAttachments.forEach { att ->
                                    parts.add(PromptPart(type = "file", mime = att.mime, url = att.dataUri, filename = att.filename))
                                }
                                attachments = emptyList()
                                val msgParts = mutableListOf<MessagePart>()
                                if (bubbleText.isNotBlank()) msgParts.add(MessagePart(type = "text", text = bubbleText))
                                myAttachments.forEach { att ->
                                    msgParts.add(MessagePart(type = "file", mime = att.mime, url = att.dataUri, filename = att.filename))
                                }
                                val now = System.currentTimeMillis()
                                chatState.onLocalSend(
                                    now = now,
                                    bubbleText = bubbleText,
                                    sendText = sendText,
                                    displayAgent = displayAgent,
                                    sendAgent = sendAgent,
                                    userParts = msgParts,
                                )
                                scope.launch {
                                    suspend fun sendOnce(): Result<PromptSendResult> {
                                        val endpoint = prefs.activeEndpoint.first()
                                        val api = OpenCodeApi(endpoint)
                                        return try {
                                            if (endpoint.mode == ConnectionMode.LOCAL_BUNDLED) {
                                                api.getMessages(sessionId)
                                                    .getOrNull()
                                                    ?.takeIf(::hasDanglingEmptyAssistant)
                                                    ?.let { api.abort(sessionId) }
                                            }
                                            api.sendPrompt(
                                                sessionId = sessionId,
                                                parts = parts,
                                                agent = sendAgent,
                                                model = modelForSend(endpoint),
                                                allowAsyncLocalClose = endpoint.mode == ConnectionMode.LOCAL_BUNDLED,
                                            )
                                        } finally {
                                            api.close()
                                        }
                                    }

                                    var result = sendOnce()
                                    if (result.isFailure && restartLocalRuntimeForRetry(result.exceptionOrNull())) {
                                        result = sendOnce()
                                    }

                                    result
                                        .onSuccess { sendResult ->
                                            if (sendResult is PromptSendResult.Completed) {
                                                val msg = sendResult.message
                                                // Update selected model from response
                                                msg.info.let { info ->
                                                    if (info.providerID != null && info.modelID != null) {
                                                        selectedModel = ModelRef(info.providerID, info.modelID)
                                                    }
                                                }
                                                // Refresh title if still placeholder
                                                if (displayTitle == context.getString(R.string.chat_new_session)) {
                                                    val api = OpenCodeApi(prefs.activeEndpoint.first())
                                                    try {
                                                        api.getSession(sessionId).onSuccess { s ->
                                                            val t = s.title.substringBefore(" - ").ifBlank { s.slug }
                                                            if (t.isNotBlank() && t != "new session") displayTitle = t
                                                        }
                                                    } finally {
                                                        api.close()
                                                    }
                                                }
                                            }
                                        }
                                        .onFailure {
                                            if (latestStreamText.isEmpty() && chatState.onSendFailure()) {
                                                val msg = it.message ?: "Unknown error"
                                                errorMsg = if (msg.contains("No suitable converter") || msg.contains("SerializationException"))
                                                    context.getString(R.string.chat_unexpected_response)
                                                else msg.take(120)
                                                isSending = false
                                                resetStreamBuffers()
                                            }
                                        }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        val hasContent = inputText.text.isNotBlank()
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
