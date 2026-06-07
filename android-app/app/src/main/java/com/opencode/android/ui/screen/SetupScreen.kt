package com.opencode.android.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.opencode.android.R
import com.opencode.android.data.api.OpenCodeApi
import com.opencode.android.data.api.WorkspaceOption
import com.opencode.android.data.api.fetchWorkspaceOptions
import com.opencode.android.data.model.ConnectionMode
import com.opencode.android.data.model.LocalProfile
import com.opencode.android.data.model.ServerConfig
import com.opencode.android.data.model.sanitizeLocalWorkspaceName
import com.opencode.android.data.repository.PreferencesRepository
import com.opencode.android.runtime.RuntimeCompanionClient
import com.opencode.android.ui.component.BlinkingCursor
import com.opencode.android.ui.component.LocalWorkspacePicker
import com.opencode.android.ui.component.rememberSafFolderPicker
import com.opencode.android.ui.component.OcButton
import com.opencode.android.ui.component.OcButtonStyle
import com.opencode.android.ui.component.UnderlineField
import com.opencode.android.ui.component.WorkspacePicker
import com.opencode.android.ui.component.pressable
import com.opencode.android.ui.theme.LocalOcColors
import com.opencode.android.ui.theme.OcColors
import com.opencode.android.ui.theme.OcType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PreferencesRepository(context) }
    val c = LocalOcColors.current

    var host by remember { mutableStateOf("127.0.0.1") }
    var port by remember { mutableStateOf("4096") }
    var password by remember { mutableStateOf("") }
    var directory by remember { mutableStateOf("") }
    var isConnecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var workspaces by remember { mutableStateOf<List<WorkspaceOption>>(emptyList()) }
    var isLoadingWorkspaces by remember { mutableStateOf(false) }
    var workspaceError by remember { mutableStateOf<String?>(null) }
    var selectedMode by remember { mutableStateOf(ConnectionMode.LAN) }
    var showWorkspacePicker by remember { mutableStateOf(false) }
    val pinnedWorkspaces by prefs.pinnedWorkspaces.collectAsState(initial = emptySet())
    val localProfile by prefs.localProfile.collectAsState(initial = LocalProfile())
    val localWorkspaceNames by prefs.localWorkspaceNames.collectAsState(initial = listOf("default"))
    var localWorkspaceDraft by remember(localProfile.workspacePath) {
        mutableStateOf(sanitizeLocalWorkspaceName(localProfile.workspacePath))
    }
    var localTreeUriDraft by remember(localProfile.workspaceTreeUri) {
        mutableStateOf(localProfile.workspaceTreeUri)
    }
    val pickExternalFolder = rememberSafFolderPicker { selection ->
        localWorkspaceDraft = sanitizeLocalWorkspaceName(selection.displayName)
        localTreeUriDraft = selection.treeUri
    }
    val localWorkspaceCandidates = remember(localWorkspaceNames, localWorkspaceDraft) {
        (localWorkspaceNames + sanitizeLocalWorkspaceName(localWorkspaceDraft))
            .filter { it.isNotBlank() }
            .distinct()
    }
    var showLocalWorkspacePicker by remember { mutableStateOf(false) }

    suspend fun loadWorkspaces() {
        if (isLoadingWorkspaces) return
        isLoadingWorkspaces = true
        workspaceError = null
        val config = ServerConfig(host, port.toIntOrNull() ?: 4096, password, directory = "")
        val api = OpenCodeApi(config)
        try {
            api.fetchWorkspaceOptions()
                .onSuccess { workspaces = it }
                .onFailure { workspaceError = it.message ?: context.getString(R.string.setup_error_load_workspaces) }
        } finally {
            api.close()
            isLoadingWorkspaces = false
            if (workspaces.isNotEmpty()) showWorkspacePicker = true
        }
    }

    fun enterMode(mode: ConnectionMode) {
        error = null
        scope.launch {
            prefs.saveConnectionMode(mode)
            prefs.setSetupDone(true)
            onComplete()
        }
    }

    fun startLocalMode() {
        error = null
        isConnecting = true
        scope.launch {
            val workspaceName = sanitizeLocalWorkspaceName(localWorkspaceDraft)
            val currentLocal = prefs.localProfile.first()
            val updatedLocal = currentLocal.copy(
                workspacePath = workspaceName,
                workspaceTreeUri = localTreeUriDraft,
            )
            prefs.saveLocalProfile(updatedLocal)
            prefs.saveConnectionMode(ConnectionMode.LOCAL_BUNDLED)
            val runtime = RuntimeCompanionClient(context)
            val providerProfile = prefs.localProviderProfile.first()
            val providerApiKey = if (providerProfile.hasApiKey) prefs.getLocalProviderApiKey(providerProfile.presetId) else ""
            val serverPassword = prefs.getOrCreateLocalServerPassword()
            val result = runtime.startAndAwaitReady(
                port = updatedLocal.bundledPort,
                workspaceName = updatedLocal.workspacePath,
                workspaceTreeUri = updatedLocal.workspaceTreeUri,
                providerProfile = providerProfile,
                providerApiKey = providerApiKey,
                serverPassword = serverPassword,
            )
            isConnecting = false
            result.onSuccess {
                prefs.setSetupDone(true)
                onComplete()
            }.onFailure {
                error = it.message ?: context.getString(R.string.setup_error_local_failed)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        // ── Brand ──
        Column(
            Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(c.ink),
                contentAlignment = Alignment.Center,
            ) {
                Text("</>", style = OcType.monoStrong.copy(fontSize = 15.sp), color = c.bg)
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("opencode", style = OcType.brand, color = c.ink)
                BlinkingCursor(color = c.accent)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (selectedMode == ConnectionMode.LAN) stringResource(R.string.setup_connect_lan) else stringResource(R.string.setup_use_local),
                style = OcType.body,
                color = c.ink2,
            )
        }

        // ── Mode ──
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.surface2, RoundedCornerShape(12.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SetupModeButton(
                label = stringResource(R.string.setup_mode_lan),
                selected = selectedMode == ConnectionMode.LAN,
                c = c,
                modifier = Modifier.weight(1f),
            ) {
                selectedMode = ConnectionMode.LAN
                error = null
            }
            SetupModeButton(
                label = stringResource(R.string.setup_mode_local),
                selected = selectedMode == ConnectionMode.LOCAL_BUNDLED,
                c = c,
                modifier = Modifier.weight(1f),
            ) {
                selectedMode = ConnectionMode.LOCAL_BUNDLED
                error = null
            }
        }

        Spacer(Modifier.height(18.dp))

        // ── Fields ──
        val isFullUrl = host.startsWith("http://") || host.startsWith("https://")
        if (selectedMode == ConnectionMode.LAN) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                UnderlineField(host, { host = it }, stringResource(R.string.setup_field_server_url), leading = { GlyphServer() })
                if (!isFullUrl) {
                    UnderlineField(port, { port = it }, stringResource(R.string.setup_field_port), leading = { GlyphPorts() }, keyboardType = KeyboardType.Number)
                }
                UnderlineField(password, { password = it }, stringResource(R.string.setup_field_password), leading = { GlyphLock() }, placeholder = stringResource(R.string.setup_placeholder_password), password = true)
                UnderlineField(
                    directory,
                    { directory = it },
                    stringResource(R.string.setup_field_directory),
                    leading = { GlyphFolder() },
                    placeholder = stringResource(R.string.setup_placeholder_directory),
                    trailing = {
                        FieldAction(if (isLoadingWorkspaces) "..." else if (workspaces.isEmpty()) stringResource(R.string.setup_action_load) else stringResource(R.string.setup_action_pick)) {
                            if (workspaces.isEmpty()) {
                                scope.launch { loadWorkspaces() }
                            } else {
                                showWorkspacePicker = !showWorkspacePicker
                            }
                        }
                    },
                )
                if (showWorkspacePicker || workspaceError != null) {
                    WorkspacePicker(
                        options = workspaces,
                        selectedPath = directory,
                        pinnedPaths = pinnedWorkspaces,
                        loading = isLoadingWorkspaces,
                        error = workspaceError,
                        onLoad = { scope.launch { loadWorkspaces() } },
                        onSelect = {
                            directory = it
                            showWorkspacePicker = false
                        },
                        onTogglePinned = { path ->
                            scope.launch { prefs.togglePinnedWorkspace(path) }
                        },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            LanEndpointPreview(isFullUrl = isFullUrl, host = host, port = port)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                UnderlineField(
                    localWorkspaceDraft,
                    { localWorkspaceDraft = sanitizeLocalWorkspaceName(it) },
                    stringResource(R.string.setup_field_workspace),
                    leading = { GlyphFolder() },
                    placeholder = stringResource(R.string.setup_placeholder_workspace),
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FieldAction(stringResource(R.string.setup_action_browse)) { pickExternalFolder() }
                            FieldAction(if (showLocalWorkspacePicker) "Hide" else "App") {
                                showLocalWorkspacePicker = !showLocalWorkspacePicker
                            }
                        }
                    },
                )
                if (localTreeUriDraft.isNotBlank()) {
                    Text(
                        stringResource(R.string.setup_external_folder_info),
                        style = OcType.mono.copy(fontSize = 11.sp),
                        color = c.ink3,
                    )
                }
                if (showLocalWorkspacePicker) {
                    LocalWorkspacePicker(
                        names = localWorkspaceCandidates,
                        selectedName = sanitizeLocalWorkspaceName(localWorkspaceDraft),
                        onSelect = {
                            localWorkspaceDraft = it
                            localTreeUriDraft = ""
                            showLocalWorkspacePicker = false
                            scope.launch {
                                prefs.saveLocalProfile(
                                    localProfile.copy(
                                        workspacePath = it,
                                        workspaceTreeUri = "",
                                    ),
                                )
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            LocalEndpointPreview(
                workspaceName = sanitizeLocalWorkspaceName(localWorkspaceDraft),
                usesExternalFolder = localTreeUriDraft.isNotBlank(),
            )
        }

        // ── Error ──
        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text(error ?: "", style = OcType.mono, color = c.accent.copy(alpha = 0.8f))
        }

        Spacer(Modifier.height(20.dp))

        if (selectedMode == ConnectionMode.LAN) {
            OcButton(
                text = stringResource(R.string.setup_action_connect_lan),
                style = OcButtonStyle.Primary,
                loading = isConnecting,
                onClick = {
                    error = null
                    isConnecting = true
                    scope.launch {
                        val config = ServerConfig(host, port.toIntOrNull() ?: 4096, password, directory)
                        val api = OpenCodeApi(config)
                        val result = try {
                            api.health()
                        } finally {
                            api.close()
                        }
                        isConnecting = false
                        result.onSuccess {
                            if (it.healthy) {
                                prefs.saveConfig(config)
                                prefs.setSetupDone(true)
                                onComplete()
                            } else {
                                error = context.getString(R.string.setup_error_server_unhealthy)
                            }
                        }.onFailure {
                            error = context.getString(R.string.setup_error_connection_failed, it.message ?: "")
                        }
                    }
                },
            )
        } else {
            OcButton(
                text = stringResource(R.string.setup_action_start_local),
                style = OcButtonStyle.Accent,
                loading = isConnecting,
                onClick = { startLocalMode() },
            )
        }

        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun SetupModeButton(
    label: String,
    selected: Boolean,
    c: OcColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(9.dp))
            .then(if (selected) Modifier.background(c.raised) else Modifier)
            .pressable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = OcType.monoStrong.copy(fontSize = 13.sp),
            color = if (selected) c.ink else c.ink3,
        )
    }
}

@Composable
private fun FieldAction(label: String, onClick: () -> Unit) {
    val c = LocalOcColors.current
    Text(
        label,
        style = OcType.monoStrong.copy(fontSize = 12.sp),
        color = c.accent,
        modifier = Modifier.pressable { onClick() },
    )
}

@Composable
private fun LanEndpointPreview(isFullUrl: Boolean, host: String, port: String) {
    val c = LocalOcColors.current
    if (isFullUrl) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text("→ ", style = OcType.mono, color = c.ink4)
            Text(host.trimEnd('/'), style = OcType.mono, color = c.accent)
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text("→ ", style = OcType.mono, color = c.ink4)
            Text(host.ifEmpty { "host" }, style = OcType.mono, color = c.ink2)
            Text(":", style = OcType.mono, color = c.ink4)
            Text(port.ifEmpty { "port" }, style = OcType.mono, color = c.accent)
        }
    }
}

@Composable
private fun LocalEndpointPreview(workspaceName: String, usesExternalFolder: Boolean) {
    val c = LocalOcColors.current
    val workspaceLabel = if (usesExternalFolder) stringResource(R.string.setup_workspace_external, workspaceName) else stringResource(R.string.setup_workspace_internal, workspaceName)
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.setup_local_runtime_info, workspaceLabel), style = OcType.body, color = c.ink)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.Center) {
            Text("→ ", style = OcType.mono, color = c.ink4)
            Text("127.0.0.1", style = OcType.mono, color = c.ink2)
            Text(":", style = OcType.mono, color = c.ink4)
            Text("4097", style = OcType.mono, color = c.accent)
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.setup_no_lan_needed), style = OcType.secondary, color = c.ink4)
    }
}

/* Placeholder glyphs — stroke style, mono, ink3 tint */
@Composable
private fun GlyphServer() { Text("☱", style = OcType.mono.copy(fontSize = 16.sp), color = LocalOcColors.current.ink3) }

@Composable
private fun GlyphPorts() { Text("◈", style = OcType.mono.copy(fontSize = 16.sp), color = LocalOcColors.current.ink3) }

@Composable
private fun GlyphLock() { Text("◻", style = OcType.mono.copy(fontSize = 16.sp), color = LocalOcColors.current.ink3) }

@Composable
private fun GlyphFolder() { Text("◇", style = OcType.mono.copy(fontSize = 16.sp), color = LocalOcColors.current.ink3) }
