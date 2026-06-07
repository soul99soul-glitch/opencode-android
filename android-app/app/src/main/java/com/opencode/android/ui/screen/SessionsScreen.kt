package com.opencode.android.ui.screen

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.android.data.api.OpenCodeApi
import com.opencode.android.data.model.ActiveEndpoint
import com.opencode.android.data.model.ConnectionMode
import com.opencode.android.data.model.Session
import com.opencode.android.data.repository.PreferencesRepository
import com.opencode.android.runtime.RuntimeCompanionClient
import com.opencode.android.ui.component.*
import com.opencode.android.ui.theme.LocalOcColors
import com.opencode.android.ui.theme.OcButtonShape
import com.opencode.android.ui.theme.OcType
import kotlinx.coroutines.flow.first
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

import java.util.*

@Composable
fun SessionsScreen(onSessionClick: (String, String?) -> Unit, onSettingsClick: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PreferencesRepository(context) }
    val c = LocalOcColors.current
    val runtimeClient = remember { RuntimeCompanionClient(context) }

    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var sessionPreviews by remember { mutableStateOf<Map<String, Pair<String?, Int>>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var isCreating by remember { mutableStateOf(false) }
    var hostPort by remember { mutableStateOf("loading") }
    val endpoint by prefs.activeEndpoint.collectAsState(initial = null)
    var refreshJob by remember { mutableStateOf<Job?>(null) }

    fun Throwable?.isLocalRuntimeConnectFailure(): Boolean {
        val msg = this?.message.orEmpty()
        if (!msg.contains("127.0.0.1")) return false
        return listOf("connect timeout", "failed to connect", "connection refused", "connection timed out")
            .any { msg.contains(it, ignoreCase = true) }
    }

    suspend fun ensureBundledRuntimeReady(active: ActiveEndpoint, restart: Boolean): Boolean {
        if (active.mode != ConnectionMode.LOCAL_BUNDLED) return true
        val local = prefs.localProfile.first()
        val providerProfile = prefs.localProviderProfile.first()
        val providerApiKey = if (providerProfile.hasApiKey) prefs.getLocalProviderApiKey(providerProfile.presetId) else ""
        val serverPassword = prefs.getOrCreateLocalServerPassword()
        val result = if (restart) {
            runtimeClient.restartAndAwaitReady(
                port = local.bundledPort,
                workspaceName = local.workspacePath,
                workspaceTreeUri = local.workspaceTreeUri,
                providerProfile = providerProfile,
                providerApiKey = providerApiKey,
                serverPassword = serverPassword,
            )
        } else {
            runtimeClient.startAndAwaitReady(
                port = local.bundledPort,
                workspaceName = local.workspacePath,
                workspaceTreeUri = local.workspaceTreeUri,
                providerProfile = providerProfile,
                providerApiKey = providerApiKey,
                serverPassword = serverPassword,
            )
        }
        result.onFailure { error = it.message ?: "Local runtime failed to start" }
        return result.isSuccess
    }

    suspend fun createSessionOnce(active: ActiveEndpoint): Result<Session> {
        val api = OpenCodeApi(active)
        return try {
            api.createSession()
        } finally {
            api.close()
        }
    }

    suspend fun createNewSession() {
        val active = endpoint ?: return
        if (isCreating) return
        isCreating = true
        try {
            if (!ensureBundledRuntimeReady(active, restart = false)) return
            var result = createSessionOnce(active)
            if (result.exceptionOrNull().isLocalRuntimeConnectFailure() && ensureBundledRuntimeReady(active, restart = true)) {
                result = createSessionOnce(active)
            }
            result
                .onSuccess { session ->
                    val t = session.title.substringBefore(" - ").ifBlank { session.slug }
                    onSessionClick(session.id, t)
                }
                .onFailure { error = it.message }
        } finally {
            isCreating = false
        }
    }

    suspend fun refreshNow(active: ActiveEndpoint) {
        val refreshKey = active.identityKey
        isLoading = true
        hostPort = active.displayUrl
        if (!ensureBundledRuntimeReady(active, restart = false)) {
            if (endpoint?.identityKey == refreshKey) isLoading = false
            return
        }
        val api = OpenCodeApi(active)
        try {
            var listResult = api.listSessions()
            if (listResult.exceptionOrNull().isLocalRuntimeConnectFailure() && ensureBundledRuntimeReady(active, restart = true)) {
                listResult = api.listSessions()
            }
            listResult
                .onSuccess { list ->
                    if (endpoint?.identityKey != refreshKey) return@onSuccess
                    // Filter out subagent worker sessions (not parent tasks)
                    val subagentPattern = """(.*\(@\w+ subagent\).*)|(^Subtask worker .*$)""".toRegex()
                    sessions = list.filter { !subagentPattern.matches(it.title) }
                    error = null
                    val currentSessions = sessions
                    val previews = api.enrichSessions(currentSessions)
                    if (endpoint?.identityKey == refreshKey) {
                        sessionPreviews = previews
                    }
                }
                .onFailure {
                    if (endpoint?.identityKey == refreshKey) error = it.message
                }
        } finally {
            api.close()
            if (endpoint?.identityKey == refreshKey) isLoading = false
        }
    }

    LaunchedEffect(endpoint?.identityKey) {
        sessions = emptyList()
        sessionPreviews = emptyMap()
        error = null
        val active = endpoint
        if (active == null) {
            isLoading = true
            hostPort = "loading"
        } else {
            refreshNow(active)
        }
    }

    // Refresh session list when returning to this screen (e.g., from ChatScreen).
    // LaunchedEffect above only re-runs when identityKey changes, which won't happen
    // on a simple back navigation.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val active = endpoint ?: return@LifecycleEventObserver
                refreshJob?.cancel()
                refreshJob = scope.launch { refreshNow(active) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            // ── Top bar ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("</>", style = OcType.monoStrong.copy(fontSize = 14.sp), color = c.accent)
                    Text("opencode", style = OcType.brand.copy(fontSize = 18.sp), color = c.ink)
                }
                Spacer(Modifier.weight(1f))
                var refreshRotation by remember { mutableFloatStateOf(0f) }
                val rotation by animateFloatAsState(targetValue = refreshRotation, animationSpec = tween(600), label = "refresh")
                Box(
                    Modifier.size(44.dp).pressable {
                        refreshRotation += 360f
                        val active = endpoint ?: return@pressable
                        refreshJob?.cancel()
                        refreshJob = scope.launch { refreshNow(active) }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        modifier = Modifier.size(22.dp).rotate(rotation),
                        tint = c.ink3,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier.size(44.dp).pressable { onSettingsClick() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings",
                        modifier = Modifier.size(22.dp),
                        tint = c.ink3,
                    )
                }
            }

            // ── Connection status bar ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OnlineDot(active = error == null && !isLoading)
                Spacer(Modifier.width(8.dp))
                Text(hostPort, style = OcType.mono, color = c.ink2)
                Spacer(Modifier.width(10.dp))
                if (error == null && !isLoading) {
                    Text("online", style = OcType.mono, color = c.accent)
                }
                Spacer(Modifier.weight(1f))
                val count = sessions.size
                Text(
                    if (count > 99) "99+ sessions" else "$count sessions",
                    style = OcType.mono, color = c.ink3,
                )
            }

            Hairline()

            // ── Content ──
            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                    }
                }
                error != null -> {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("✕", style = OcType.brand, color = c.accent)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            error ?: "Error",
                            style = OcType.body,
                            color = c.ink2,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(16.dp))
                        Box(
                            Modifier
                                .pressable {
                                    val active = endpoint ?: return@pressable
                                    refreshJob?.cancel()
                                    refreshJob = scope.launch { refreshNow(active) }
                                }
                                .background(c.accent, OcButtonShape)
                                .padding(horizontal = 24.dp, vertical = 12.dp)
                        ) {
                            Text("Retry", style = OcType.body.copy(color = c.accentInk))
                        }
                    }
                }
                sessions.isEmpty() -> EmptySessionsState {
                    scope.launch { createNewSession() }
                }
                else -> {
                    val deleteWidth = 72.dp
                    val deleteWidthPx = with(LocalDensity.current) { deleteWidth.toPx() }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(
                            items = sessions,
                            key = { it.id },
                        ) { session ->
                            val isActive = session.id == sessions.firstOrNull()?.id
                            val (preview, msgCount) = sessionPreviews[session.id] ?: (null to 0)
                            val animModifier = Modifier.animateItem(
                                fadeInSpec = null,
                                fadeOutSpec = null,
                            )
                            // Per-item swipe state
                            var swipeOffset by remember(session.id) { mutableFloatStateOf(0f) }
                            val animatedOffset by animateDpAsState(
                                targetValue = with(LocalDensity.current) { swipeOffset.toDp() },
                                animationSpec = tween(200),
                                label = "swipe",
                            )
                            Box(
                                Modifier
                                    .then(animModifier)
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Min)
                            ) {
                                // Red delete square — pinned right, fills row height
                                Box(
                                    Modifier
                                        .align(Alignment.CenterEnd)
                                        .fillMaxHeight()
                                        .width(deleteWidth)
                                        .background(Color(0xFFE53935))
                                        .pressable {
                                            scope.launch {
                                                val api = OpenCodeApi(prefs.activeEndpoint.first())
                                                try {
                                                    api.deleteSession(session.id)
                                                    sessions = sessions.filter { it.id != session.id }
                                                } finally {
                                                    api.close()
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                // Session content — slides left, fully covers delete button at rest
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .offset(x = animatedOffset)
                                        .background(c.bg)
                                        .pointerInput(session.id) {
                                            detectHorizontalDragGestures(
                                                onDragEnd = {
                                                    swipeOffset = if (swipeOffset < -deleteWidthPx * 0.5f)
                                                        -deleteWidthPx else 0f
                                                },
                                                onHorizontalDrag = { _, dragAmount ->
                                                    val newOffset = swipeOffset + dragAmount
                                                    swipeOffset = newOffset.coerceIn(-deleteWidthPx, 0f)
                                                },
                                            )
                                        }
                                ) {
                                    SessionRow(
                                        session,
                                        active = isActive,
                                        preview = preview,
                                        messageCount = msgCount,
                                    ) {
                                        swipeOffset = 0f
                                        onSessionClick(session.id, session.title.substringBefore(" - ").ifBlank { session.slug })
                                    }
                                    Hairline()
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Gradient mask above FAB ──
        if (!isLoading && error == null && sessions.isNotEmpty()) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(48.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(c.bg.copy(alpha = 0f), c.bg)
                        )
                    )
            )
        }

        // ── FAB ──
        if (!isLoading && error == null) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(20.dp)
            ) {
                Box(
                    Modifier
                        .pressable { scope.launch { createNewSession() } }
                        .background(c.accent, OcButtonShape)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+ New session", style = OcType.body.copy(color = c.accentInk, fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}

/** 毫秒级时间戳 → 相对时间文案 */
@Composable
private fun relativeTime(epochMs: Long): String {
    val now = System.currentTimeMillis()
    val diff = (now - epochMs) / 1000   // 秒
    return when {
        diff < 60        -> "刚刚"
        diff < 3600       -> "${diff / 60}分钟前"
        diff < 86400      -> "${diff / 3600}小时前"
        diff < 172800     -> "昨天"
        diff < 604800     -> "${diff / 86400}天前"
        else              -> {
            val sdf = java.text.SimpleDateFormat("M月d日", LocalLocale.current.platformLocale)
            sdf.format(java.util.Date(epochMs))
        }
    }
}

@Composable
private fun SessionRow(
    session: Session,
    active: Boolean = false,
    preview: String? = null,
    messageCount: Int = 0,
    onClick: () -> Unit,
) {
    val c = LocalOcColors.current
    val timeStr = session.time?.updated?.let {
        relativeTime(it)
    } ?: ""

    Column(
        Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 16.dp),
    ) {
        // Line 1: Title + Time
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                session.title.substringBefore(" - ").ifBlank { session.slug },
                style = OcType.rowTitle,
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (timeStr.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(timeStr, style = OcType.mono.copy(fontSize = 11.5.sp), color = c.ink4)
            }
        }
        // Line 2: N msgs — active session gets static green dot
        Spacer(Modifier.height(3.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (active) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(c.accent))
                Spacer(Modifier.width(2.dp))
            }
            Text("$messageCount msgs", style = OcType.mono.copy(fontSize = 11.5.sp), color = c.ink3)
        }
        // Line 3: Preview — last assistant message
        if (!preview.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                preview,
                style = OcType.secondary.copy(fontSize = 13.5.sp),
                color = c.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptySessionsState(onNewSession: () -> Unit) {
    val c = LocalOcColors.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("opencode", style = OcType.brand, color = c.ink)
            BlinkingCursor(color = c.accent)
        }
        Spacer(Modifier.height(16.dp))
        Text("No conversations yet", style = OcType.body, color = c.ink2)
        Spacer(Modifier.height(28.dp))

        val suggestions = listOf("Explain this codebase", "Find bugs", "Write a feature", "Refactor code")
        suggestions.forEach { text ->
            Box(
                Modifier
                    .padding(vertical = 4.dp)
                    .pressable { onNewSession() }
                    .background(c.surface2, RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(text, style = OcType.secondary, color = c.ink2)
            }
        }
    }
}
