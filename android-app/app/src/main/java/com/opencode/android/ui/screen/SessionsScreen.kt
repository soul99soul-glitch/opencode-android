package com.opencode.android.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.android.data.api.OpenCodeApi
import com.opencode.android.data.model.Session
import com.opencode.android.data.repository.PreferencesRepository
import com.opencode.android.ui.component.*
import com.opencode.android.ui.theme.LocalOcColors
import com.opencode.android.ui.theme.OcButtonShape
import com.opencode.android.ui.theme.OcType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

import java.util.*

@Composable
fun SessionsScreen(onSessionClick: (String, String?) -> Unit, onSettingsClick: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PreferencesRepository(context) }
    val c = LocalOcColors.current

    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var sessionPreviews by remember { mutableStateOf<Map<String, Pair<String?, Int>>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showNewDialog by remember { mutableStateOf(false) }
    var isCreating by remember { mutableStateOf(false) }
    var hostPort by remember { mutableStateOf("127.0.0.1:4096") }

    fun refresh() {
        scope.launch {
            isLoading = true
            val cfg = prefs.config.first()
            hostPort = "${cfg.host}:${cfg.port}"
            val api = OpenCodeApi(cfg)
            api.listSessions()
                .onSuccess { list ->
                    sessions = list
                    error = null
                    sessionPreviews = api.enrichSessions(list)
                }
                .onFailure { error = it.message }
            api.close()
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

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
                    Modifier.size(44.dp).pressable { refreshRotation += 360f; refresh() },
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
                Text("${sessions.size} sessions", style = OcType.mono, color = c.ink3)
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
                        Text(error ?: "Error", style = OcType.body, color = c.ink2)
                        Spacer(Modifier.height(16.dp))
                        Box(
                            Modifier
                                .pressable { refresh() }
                                .background(c.accent, OcButtonShape)
                                .padding(horizontal = 24.dp, vertical = 12.dp)
                        ) {
                            Text("Retry", style = OcType.body.copy(color = c.accentInk))
                        }
                    }
                }
                sessions.isEmpty() -> EmptySessionsState {
                    showNewDialog = true
                }
                else -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(sessions, key = { it.id }) { session ->
                            val isActive = session.id == sessions.firstOrNull()?.id
                            val (preview, msgCount) = sessionPreviews[session.id] ?: (null to 0)
                            SessionRow(
                                session,
                                active = isActive,
                                preview = preview,
                                messageCount = msgCount,
                            ) { onSessionClick(session.id, session.title) }
                            Hairline()
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
                        .pressable { showNewDialog = true }
                        .background(c.accent, OcButtonShape)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+ New session", style = OcType.body.copy(color = c.accentInk, fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }

    // ── New session dialog ──
    if (showNewDialog) {
        var title by remember { mutableStateOf("") }
        Box(
            Modifier.fillMaxSize().background(c.bg.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.padding(28.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("New Session", style = OcType.titleL.copy(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold), color = c.ink)
                Spacer(Modifier.height(20.dp))
                UnderlineField(
                    value = title,
                    onValueChange = { title = it },
                    label = "Title",
                    leading = { Text("✎", color = c.ink3) },
                    placeholder = "Optional",
                )
                Spacer(Modifier.height(24.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.weight(1f).pressable { if (!isCreating) showNewDialog = false }
                            .background(c.surface2, OcButtonShape).padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Cancel", style = OcType.body, color = c.ink2)
                    }
                    Box(
                        Modifier.weight(1f).pressable {
                            if (isCreating) return@pressable
                            isCreating = true
                            scope.launch {
                                val cfg = prefs.config.first()
                                val api = OpenCodeApi(cfg)
                                api.createSession(title.ifBlank { null })
                                    .onSuccess {
                                        showNewDialog = false
                                        onSessionClick(it.id, it.title)
                                    }
                                api.close()
                                isCreating = false
                            }
                        }.background(c.ink, OcButtonShape).padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isCreating) {
                            OnlineDot()
                        } else {
                            Text("Create", style = OcType.body, color = c.bg)
                        }
                    }
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
            val sdf = java.text.SimpleDateFormat("M月d日", java.util.Locale.getDefault())
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
                session.title,
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
