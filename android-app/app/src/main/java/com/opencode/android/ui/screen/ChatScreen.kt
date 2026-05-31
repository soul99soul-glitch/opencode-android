package com.opencode.android.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.SolidColor
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
fun ChatScreen(sessionId: String, sessionTitle: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PreferencesRepository(context) }
    val json = remember { Json { ignoreUnknownKeys = true; isLenient = true } }
    val c = LocalOcColors.current

    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isSending by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    var streamingText by remember { mutableStateOf("") }
    var selectedAgent by remember { mutableStateOf("build") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var inputFocused by remember { mutableStateOf(false) }

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

    // Load messages
    LaunchedEffect(sessionId) {
        val config = prefs.config.first()
        val api = OpenCodeApi(config)
        api.getMessages(sessionId)
            .onSuccess { messages = it.takeLast(50) }
            .onFailure { errorMsg = it.message }
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
                        sessionTitle ?: "Chat",
                        style = OcType.rowTitle,
                        color = c.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Agent + model info — only show top-level agents (not subagents)
                    val displayAgent = currentAgent?.let {
                        if (it == "code" || it == "plan" || it == "orchestrator") shortAgent(it) else null
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (displayAgent != null) {
                            Text(displayAgent, style = OcType.monoStrong.copy(fontSize = 11.sp), color = c.accent)
                        }
                        if (messages.firstOrNull()?.info?.modelID != null) {
                            if (displayAgent != null) Text("·", style = OcType.mono.copy(fontSize = 11.sp), color = c.ink4)
                            Text(
                                messages.first()!!.info!!.modelID!!,
                                style = OcType.mono.copy(fontSize = 11.sp),
                                color = c.ink3,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                // Agent toggle pill — click to cycle Build/Plan/Orch
                Box(
                    Modifier
                        .pressable {
                            selectedAgent = when (selectedAgent) {
                                "build" -> "plan"
                                "plan" -> "orch"
                                else -> "build"
                            }
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
                            when (agent) {
                                "plan" -> "Plan"
                                "orch" -> "Orch"
                                else -> "Build"
                            },
                            style = OcType.monoStrong.copy(fontSize = 12.sp),
                            color = c.ink,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                if (isSending) {
                    Box(
                        Modifier.size(44.dp).pressable {
                            scope.launch {
                                val cfg = prefs.config.first()
                                val api = OpenCodeApi(cfg)
                                api.abort(sessionId)
                                api.close()
                                isSending = false
                                streamingText = ""
                            }
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("■", style = OcType.monoStrong.copy(fontSize = 14.sp), color = c.signal)
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
                            items(messages.asReversed(), key = { it.info.id }) { MessageBubble(it) }
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

            // ── Bottom input bar ──
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.surface)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .navigationBarsPadding()
                    .imePadding(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
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
                // Send button
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (inputText.isNotBlank() && !isSending) c.signal else c.surface2
                        )
                        .pressable(
                            enabled = inputText.isNotBlank() && !isSending,
                        ) {
                            if (inputText.isBlank() || isSending) return@pressable
                            val content = inputText
                            inputText = ""
                            isSending = true
                            streamingText = ""
                            val userMsg = Message(
                                info = MessageInfo(id = "local_${System.currentTimeMillis()}", role = "user"),
                                parts = listOf(MessagePart(type = "text", text = content))
                            )
                            messages = messages + userMsg
                            scope.launch {
                                val cfg = prefs.config.first()
                                val api = OpenCodeApi(cfg)
                                api.sendPrompt(sessionId, content)
                                    .onSuccess { assistantMsg ->
                                        messages = messages + assistantMsg
                                        isSending = false
                                    }
                                    .onFailure {
                                        val api2 = OpenCodeApi(cfg)
                                        api2.getMessages(sessionId).onSuccess { messages = it }
                                        api2.close()
                                        errorMsg = it.message
                                        isSending = false
                                    }
                                api.close()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("↑", style = OcType.body.copy(color = if (inputText.isNotBlank() && !isSending) c.accentInk else c.ink4))
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
                    .background(c.accent, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(data.visuals.message, style = OcType.mono, color = c.accentInk)
            }
        }
    }
}
