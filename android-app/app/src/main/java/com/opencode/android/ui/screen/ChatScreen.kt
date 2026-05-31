package com.opencode.android.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun ChatScreen(sessionId: String, sessionTitle: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PreferencesRepository(context) }
    val json = remember { Json { ignoreUnknownKeys = true; isLenient = true } }

    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isSending by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    var streamingText by remember { mutableStateOf("") }
    var selectedAgent by remember { mutableStateOf("build") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show error as snackbar, auto-dismiss
    LaunchedEffect(errorMsg) {
        errorMsg?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            errorMsg = null
        }
    }

    // Load messages once
    LaunchedEffect(sessionId) {
        val config = prefs.config.first()
        val api = OpenCodeApi(config)
        api.getMessages(sessionId).onSuccess { messages = it; errorMsg = null }.onFailure { errorMsg = it.message }
        isLoading = false
        api.close()
    }

    // SSE events - properly restarts on config changes
    LaunchedEffect(sessionId) {
        prefs.config.flatMapLatest { config ->
            val api = OpenCodeApi(config)
            flow {
                try {
                    api.sessionEvents().collect { emit(it) }
                } finally { api.close() }
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

    // Auto-scroll only if near bottom
    LaunchedEffect(messages.size, streamingText) {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0 && lastVisible >= total - 3) {
            listState.animateScrollToItem(total - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(sessionTitle ?: "Chat", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text("${selectedAgent.replaceFirstChar { it.uppercase() }} agent", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    FilterChip(
                        selected = selectedAgent == "plan",
                        onClick = { selectedAgent = if (selectedAgent == "plan") "build" else "plan" },
                        label = { Text(if (selectedAgent == "plan") "🔍 Plan" else "⚡ Build", style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Primary.copy(alpha = 0.2f), selectedLabelColor = Primary)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    if (isSending) {
                        IconButton(onClick = {
                            scope.launch {
                                val cfg = prefs.config.first()
                                val api = OpenCodeApi(cfg)
                                api.abort(sessionId); api.close()
                                isSending = false; streamingText = ""
                            }
                        }) { Icon(Icons.Default.StopCircle, "Stop", tint = Error) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it, containerColor = Error, contentColor = Background) } },
        bottomBar = {
            Surface(color = Surface, shadowElevation = 8.dp) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).imePadding(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = inputText, onValueChange = { inputText = it }, modifier = Modifier.weight(1f), placeholder = { Text("Ask OpenCode...", color = TextMuted) }, shape = RoundedCornerShape(24.dp), maxLines = 4, enabled = !isSending, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Primary, unfocusedBorderColor = Border, focusedContainerColor = SurfaceVariant, unfocusedContainerColor = SurfaceVariant, cursorColor = Primary, disabledContainerColor = SurfaceVariant.copy(alpha = 0.5f)))
                    FilledIconButton(onClick = {
                        if (inputText.isBlank() || isSending) return@FilledIconButton
                        val content = inputText; inputText = ""; isSending = true; streamingText = ""
                        scope.launch {
                            val cfg = prefs.config.first(); val api = OpenCodeApi(cfg)
                            api.sendPrompt(sessionId, content).onFailure { errorMsg = it.message; isSending = false }
                            api.close()
                        }
                    }, enabled = inputText.isNotBlank() && !isSending, shape = RoundedCornerShape(16.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Primary, disabledContainerColor = SurfaceVariant), modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.ArrowUpward, "Send", tint = if (inputText.isNotBlank() && !isSending) Background else TextMuted)
                    }
                }
            }
        },
        containerColor = Background
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Primary)
                messages.isEmpty() && streamingText.isEmpty() && !isSending -> EmptyChatState { inputText = it }
                else -> {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(messages, key = { it.id }) { MessageBubble(it) }
                        if (streamingText.isNotEmpty()) item(key = "streaming") { StreamingBubble(streamingText) }
                        if (isSending && streamingText.isEmpty()) item(key = "thinking") {
                            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Primary)
                                Text("Thinking...", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            }
                        }
                        item { Spacer(modifier = Modifier.height(8.dp)) }
                    }
                }
            }
        }
    }
}
