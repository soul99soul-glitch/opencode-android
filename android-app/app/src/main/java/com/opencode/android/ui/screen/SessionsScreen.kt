package com.opencode.android.ui.screen

import android.annotation.SuppressLint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.opencode.android.R
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.android.data.api.OpenCodeApi
import com.opencode.android.data.api.WorkspaceOption
import com.opencode.android.data.api.fetchWorkspaceOptions
import com.opencode.android.data.api.filterUserFacingSessions
import com.opencode.android.data.model.ActiveEndpoint
import com.opencode.android.data.model.ConnectionMode
import com.opencode.android.data.model.LanProfile
import com.opencode.android.data.model.LocalProfile
import com.opencode.android.data.model.LocalWorkspaceProfile
import com.opencode.android.data.model.Session
import com.opencode.android.data.model.sanitizeLocalWorkspaceName
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
import kotlin.math.roundToInt

@SuppressLint("LocalContextGetResourceValueCall")
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
    var hostPort by remember { mutableStateOf(context.getString(R.string.settings_value_loading)) }
    val endpoint by prefs.activeEndpoint.collectAsState(initial = null)
    val lanProfile by prefs.lanProfile.collectAsState(initial = LanProfile())
    val localProfile by prefs.localProfile.collectAsState(initial = LocalProfile())
    val localWorkspaceProfiles by prefs.localWorkspaceProfiles.collectAsState(initial = emptyList())
    val pinnedWorkspaces by prefs.pinnedWorkspaces.collectAsState(initial = emptySet())
    var refreshJob by remember { mutableStateOf<Job?>(null) }
    var drawerOpen by remember { mutableStateOf(false) }
    var drawerDragOffsetPx by remember { mutableFloatStateOf(Float.NaN) }
    var workspaceOptions by remember { mutableStateOf<List<WorkspaceOption>>(emptyList()) }
    var workspaceLoading by remember { mutableStateOf(false) }
    var workspaceError by remember { mutableStateOf<String?>(null) }

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
        result.onFailure { error = it.message ?: context.getString(R.string.sessions_error_local_failed) }
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
                    sessions = list.filterUserFacingSessions()
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

    suspend fun loadRemoteWorkspaces(active: ActiveEndpoint? = endpoint) {
        active ?: return
        if (active.mode != ConnectionMode.LAN || workspaceLoading) return
        workspaceLoading = true
        workspaceError = null
        val discoveryEndpoint = active.copy(directory = "")
        val api = OpenCodeApi(discoveryEndpoint)
        try {
            api.fetchWorkspaceOptions()
                .onSuccess {
                    val current = prefs.activeEndpoint.first()
                    if (current.mode == active.mode && current.baseUrl == active.baseUrl && current.password == active.password) {
                        workspaceOptions = it
                    }
                }
                .onFailure {
                    val current = prefs.activeEndpoint.first()
                    if (current.mode == active.mode && current.baseUrl == active.baseUrl && current.password == active.password) {
                        workspaceError = it.message ?: context.getString(R.string.status_failed_load_workspaces)
                    }
                }
        } finally {
            api.close()
            workspaceLoading = false
        }
    }

    suspend fun selectWorkspace(option: WorkspaceOption) {
        val active = endpoint ?: return
        when (active.mode) {
            ConnectionMode.LAN -> {
                workspaceOptions = emptyList()
                workspaceError = null
                prefs.saveLanProfile(prefs.lanProfile.first().copy(directory = option.path))
            }
            ConnectionMode.LOCAL_BUNDLED, ConnectionMode.LOCAL_EXTERNAL -> {
                prefs.saveLocalProfile(
                    localProfile.copy(
                        workspacePath = sanitizeLocalWorkspaceName(option.path),
                        workspaceTreeUri = "",
                    )
                )
            }
        }
        drawerOpen = false
    }

    suspend fun selectLocalWorkspace(profile: LocalWorkspaceProfile) {
        prefs.saveLocalProfile(
            localProfile.copy(
                workspacePath = profile.name,
                workspaceTreeUri = profile.treeUri,
            )
        )
        drawerOpen = false
    }

    LaunchedEffect(endpoint?.identityKey) {
        sessions = emptyList()
        sessionPreviews = emptyMap()
        workspaceOptions = emptyList()
        workspaceError = null
        error = null
        val active = endpoint
        if (active == null) {
            isLoading = true
            hostPort = context.getString(R.string.settings_value_loading)
        } else {
            refreshNow(active)
        }
    }

    LaunchedEffect(drawerOpen, endpoint?.identityKey) {
        val active = endpoint ?: return@LaunchedEffect
        if (drawerOpen && active.mode == ConnectionMode.LAN && workspaceOptions.isEmpty()) {
            loadRemoteWorkspaces(active)
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

    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg)) {
        val drawerWidth = (maxWidth * 0.74f).coerceAtMost(280.dp)
        val drawerWidthPx = with(LocalDensity.current) { drawerWidth.toPx() }
        val targetOffsetPx = if (drawerOpen) drawerWidthPx else 0f
        val isDraggingDrawer = !drawerDragOffsetPx.isNaN()
        val drawerOffset = remember { Animatable(0f) }
        val drawerSpring = spring<Float>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow,
        )
        LaunchedEffect(drawerOpen, drawerWidthPx) {
            if (drawerDragOffsetPx.isNaN()) {
                drawerOffset.animateTo(targetOffsetPx, drawerSpring)
            }
        }

        fun startDrawerDrag() {
            drawerDragOffsetPx = (if (drawerDragOffsetPx.isNaN()) drawerOffset.value else drawerDragOffsetPx)
                .coerceIn(0f, drawerWidthPx)
        }

        fun updateDrawerDrag(dragAmount: Float) {
            val current = if (drawerDragOffsetPx.isNaN()) drawerOffset.value else drawerDragOffsetPx
            if (drawerOpen || current > 0f || dragAmount > 0f) {
                drawerDragOffsetPx = (current + dragAmount).coerceIn(0f, drawerWidthPx)
            }
        }

        fun settleDrawerDrag() {
            val releaseOffset = (if (drawerDragOffsetPx.isNaN()) drawerOffset.value else drawerDragOffsetPx)
                .coerceIn(0f, drawerWidthPx)
            val shouldOpen = if (drawerOpen) {
                releaseOffset > drawerWidthPx * 0.78f
            } else {
                releaseOffset > drawerWidthPx * 0.22f
            }
            val destination = if (shouldOpen) drawerWidthPx else 0f
            scope.launch {
                drawerOffset.snapTo(releaseOffset)
                drawerDragOffsetPx = Float.NaN
                drawerOpen = shouldOpen
                drawerOffset.animateTo(destination, drawerSpring)
            }
        }

        val visibleDrawerOffsetPx = if (isDraggingDrawer) drawerDragOffsetPx else drawerOffset.value
        val revealProgress = if (drawerWidthPx == 0f) 0f else (visibleDrawerOffsetPx / drawerWidthPx).coerceIn(0f, 1f)

        WorkspaceRail(
            modifier = Modifier
                .width(drawerWidth)
                .fillMaxHeight()
                .offset {
                    IntOffset(
                        x = (visibleDrawerOffsetPx - drawerWidthPx).roundToInt(),
                        y = 0,
                    )
                }
                .pointerInput(drawerOpen, drawerWidthPx) {
                    detectHorizontalDragGestures(
                        onDragStart = { startDrawerDrag() },
                        onDragEnd = { settleDrawerDrag() },
                        onDragCancel = { settleDrawerDrag() },
                        onHorizontalDrag = { _, dragAmount -> updateDrawerDrag(dragAmount) },
                    )
                }
                .statusBarsPadding(),
            endpoint = endpoint,
            sessions = sessions,
            lanWorkspaces = workspaceOptions,
            lanSelectedPath = lanProfile.directory,
            localProfiles = localWorkspaceProfiles,
            localSelectedId = localWorkspaceProfiles.firstOrNull {
                it.name == localProfile.workspacePath && it.treeUri == localProfile.workspaceTreeUri
            }?.id.orEmpty(),
            loading = workspaceLoading,
            error = workspaceError,
            pinnedPaths = pinnedWorkspaces,
            onRefresh = { scope.launch { loadRemoteWorkspaces() } },
            onSelectRemote = { option -> scope.launch { selectWorkspace(option) } },
            onSelectLocal = { profile -> scope.launch { selectLocalWorkspace(profile) } },
            onTogglePinned = { path -> scope.launch { prefs.togglePinnedWorkspace(path) } },
            onNewWorkspace = onSettingsClick,
        )

        Box(
            Modifier
                .fillMaxSize()
                .offset {
                    IntOffset(
                        x = visibleDrawerOffsetPx.roundToInt(),
                        y = 0,
                    )
                }
                .background(c.bg)
                .pointerInput(drawerOpen, drawerWidthPx) {
                    detectHorizontalDragGestures(
                        onDragStart = { startDrawerDrag() },
                        onDragEnd = { settleDrawerDrag() },
                        onDragCancel = { settleDrawerDrag() },
                        onHorizontalDrag = { _, dragAmount -> updateDrawerDrag(dragAmount) },
                    )
                }
                .statusBarsPadding(),
        ) {
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
                        contentDescription = stringResource(R.string.sessions_action_refresh),
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
                        contentDescription = stringResource(R.string.sessions_action_settings),
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
                    Text(stringResource(R.string.sessions_status_online), style = OcType.mono, color = c.accent)
                }
                Spacer(Modifier.weight(1f))
                val count = sessions.size
                Text(
                    if (count > 99) stringResource(R.string.sessions_count_overflow) else stringResource(R.string.sessions_count, count),
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
                            error ?: stringResource(R.string.sessions_error_label),
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
                            Text(stringResource(R.string.sessions_action_retry), style = OcType.body.copy(color = c.accentInk))
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
                                        contentDescription = stringResource(R.string.sessions_action_delete),
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
                                        .then(
                                            if (drawerOpen) {
                                                Modifier
                                            } else {
                                                Modifier.pointerInput(session.id, deleteWidthPx) {
                                                    awaitEachGesture {
                                                        val down = awaitFirstDown(requireUnconsumed = false)
                                                        var pastSlop = 0f
                                                        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                                                            if (over < 0f || swipeOffset < 0f) {
                                                                pastSlop = over
                                                                change.consume()
                                                            }
                                                        }
                                                        if (drag != null && (pastSlop < 0f || swipeOffset < 0f)) {
                                                            swipeOffset = (swipeOffset + pastSlop).coerceIn(-deleteWidthPx, 0f)
                                                            horizontalDrag(drag.id) { change ->
                                                                val dragAmount = change.positionChange().x
                                                                swipeOffset = (swipeOffset + dragAmount).coerceIn(-deleteWidthPx, 0f)
                                                                change.consume()
                                                            }
                                                            swipeOffset = if (swipeOffset < -deleteWidthPx * 0.5f) -deleteWidthPx else 0f
                                                        }
                                                    }
                                                }
                                            }
                                        )
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
                    Text(stringResource(R.string.sessions_new_session), style = OcType.body.copy(color = c.accentInk, fontWeight = FontWeight.SemiBold))
                }
            }
        }

            if (revealProgress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(c.line.copy(alpha = 0.75f * revealProgress))
                )
            }
        }
    }
}

@Composable
private fun WorkspaceRail(
    endpoint: ActiveEndpoint?,
    sessions: List<Session>,
    lanWorkspaces: List<WorkspaceOption>,
    lanSelectedPath: String,
    localProfiles: List<LocalWorkspaceProfile>,
    localSelectedId: String,
    loading: Boolean,
    error: String?,
    pinnedPaths: Set<String>,
    onRefresh: () -> Unit,
    onSelectRemote: (WorkspaceOption) -> Unit,
    onSelectLocal: (LocalWorkspaceProfile) -> Unit,
    onTogglePinned: (String) -> Unit,
    onNewWorkspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalOcColors.current
    val isLan = endpoint?.mode == ConnectionMode.LAN
    val remoteRows = remember(lanWorkspaces, pinnedPaths) {
        val sorted = lanWorkspaces.sortedWith(
            compareByDescending<WorkspaceOption> { it.path in pinnedPaths }
                .thenByDescending { it.sessionCount }
                .thenBy { it.label.lowercase(Locale.ROOT) }
        )
        sorted
    }
    val totalSessions = if (isLan) {
        remoteRows.sumOf { it.sessionCount }.takeIf { it > 0 } ?: sessions.size
    } else {
        sessions.size
    }

    Column(modifier.background(c.bg)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
        ) {
            Text(
                stringResource(R.string.sessions_workspace_kicker),
                style = OcType.mono.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
                color = c.ink3,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                stringResource(R.string.sessions_workspace_title),
                style = OcType.brand.copy(fontSize = 21.sp),
                color = c.ink,
            )
        }
        Hairline()

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            if (isLan) {
                if (loading && remoteRows.isEmpty()) {
                    WorkspaceRailStatusRow(stringResource(R.string.settings_value_loading))
                }
                if (error != null && remoteRows.isEmpty()) {
                    WorkspaceRailStatusRow(error)
                }
                remoteRows.forEachIndexed { index, option ->
                    WorkspaceRailRow(
                        icon = when (index % 4) {
                            0 -> WorkspaceIcon.Folder
                            1 -> WorkspaceIcon.Code
                            2 -> WorkspaceIcon.Person
                            else -> WorkspaceIcon.Bolt
                        },
                        title = option.label,
                        subtitle = stringResource(R.string.workspace_sessions_count, option.sessionCount),
                        selected = option.path == lanSelectedPath,
                        dim = option.sessionCount == 0,
                        pinned = option.path in pinnedPaths,
                        onClick = { onSelectRemote(option) },
                        onLongClick = { onTogglePinned(option.path) },
                    )
                    Hairline()
                }
                if (!loading && remoteRows.isEmpty() && error == null) {
                    WorkspaceRailStatusRow(stringResource(R.string.sessions_workspace_empty))
                }
            } else {
                localProfiles.forEachIndexed { index, profile ->
                    val count = if (profile.id == localSelectedId) sessions.size else 0
                    WorkspaceRailRow(
                        icon = when (index % 4) {
                            0 -> WorkspaceIcon.Code
                            1 -> WorkspaceIcon.Folder
                            2 -> WorkspaceIcon.Person
                            else -> WorkspaceIcon.Bolt
                        },
                        title = profile.name.ifBlank { stringResource(R.string.sessions_workspace_default) },
                        subtitle = if (count > 0) {
                            stringResource(R.string.workspace_sessions_count, count)
                        } else if (profile.treeUri.isNotBlank()) {
                            stringResource(R.string.workspace_linked_folder)
                        } else {
                            stringResource(R.string.sessions_workspace_local)
                        },
                        selected = profile.id == localSelectedId,
                        dim = false,
                        pinned = false,
                        onClick = { onSelectLocal(profile) },
                    )
                    Hairline()
                }
            }
        }

        Hairline()
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .pressable {
                    if (isLan) onRefresh() else onNewWorkspace()
                }
                .background(Color.Transparent, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(
                    imageVector = if (isLan) Icons.Default.Refresh else Icons.Default.Add,
                    contentDescription = null,
                    tint = c.ink3,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    if (isLan) stringResource(R.string.sessions_workspace_refresh) else stringResource(R.string.sessions_workspace_new),
                    style = OcType.body.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    color = c.ink3,
                )
            }
        }
        Text(
            stringResource(R.string.sessions_workspace_total, totalSessions),
            style = OcType.mono.copy(fontSize = 10.sp),
            color = c.ink4,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 9.dp),
        )
    }
}

private enum class WorkspaceIcon { Code, Folder, Person, Bolt }

@Composable
private fun WorkspaceRailRow(
    icon: WorkspaceIcon,
    title: String,
    subtitle: String,
    selected: Boolean,
    dim: Boolean,
    pinned: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val c = LocalOcColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .background(if (selected) c.accent else c.surface2, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val tint = if (selected) c.accentInk else c.ink3
            when (icon) {
                WorkspaceIcon.Code -> Text("</>", style = OcType.monoStrong.copy(fontSize = 12.sp), color = tint)
                WorkspaceIcon.Folder -> Icon(Icons.Default.Folder, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
                WorkspaceIcon.Person -> Icon(Icons.Default.Person, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                WorkspaceIcon.Bolt -> Icon(Icons.Default.Bolt, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = OcType.rowTitle.copy(fontSize = 15.sp),
                color = when {
                    selected -> c.ink
                    dim -> c.ink3
                    else -> c.ink
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                style = OcType.body.copy(fontSize = 11.5.sp),
                color = c.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected || pinned) {
            Box(
                Modifier
                    .size(if (selected) 10.dp else 7.dp)
                    .clip(CircleShape)
                    .background(if (selected) c.accent else c.ink4)
            )
        }
    }
}

@Composable
private fun WorkspaceRailStatusRow(text: String) {
    val c = LocalOcColors.current
    Text(
        text,
        style = OcType.body,
        color = c.ink3,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 18.dp),
    )
}

/** 毫秒级时间戳 → 相对时间文案 */
@Composable
private fun relativeTime(epochMs: Long): String {
    val now = System.currentTimeMillis()
    val diff = (now - epochMs) / 1000   // 秒
    return when {
        diff < 60        -> stringResource(R.string.time_just_now)
        diff < 3600       -> stringResource(R.string.time_minutes_ago, diff / 60)
        diff < 86400      -> stringResource(R.string.time_hours_ago, diff / 3600)
        diff < 172800     -> stringResource(R.string.time_yesterday)
        diff < 604800     -> stringResource(R.string.time_days_ago, diff / 86400)
        else              -> {
            val sdf = java.text.SimpleDateFormat(stringResource(R.string.time_date_format), LocalLocale.current.platformLocale)
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
            Text(stringResource(R.string.sessions_msg_count, messageCount), style = OcType.mono.copy(fontSize = 11.5.sp), color = c.ink3)
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
        Text(stringResource(R.string.sessions_empty_title), style = OcType.body, color = c.ink2)
        Spacer(Modifier.height(28.dp))

        val suggestions = listOf(
            stringResource(R.string.sessions_suggestion_explain),
            stringResource(R.string.sessions_suggestion_bugs),
            stringResource(R.string.sessions_suggestion_feature),
            stringResource(R.string.sessions_suggestion_refactor),
        )
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
