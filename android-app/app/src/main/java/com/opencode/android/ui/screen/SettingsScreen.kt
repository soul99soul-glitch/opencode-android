package com.opencode.android.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.opencode.android.data.api.LocalProviderModelFetcher
import com.opencode.android.data.api.OpenCodeApi
import com.opencode.android.data.api.WorkspaceOption
import com.opencode.android.data.api.fetchWorkspaceOptions
import com.opencode.android.data.model.AgentInfo
import com.opencode.android.data.model.ConnectionMode
import com.opencode.android.data.model.LocalProviderDefaults
import com.opencode.android.data.model.LocalProviderPreset
import com.opencode.android.data.model.LocalProviderProfile
import com.opencode.android.data.model.LocalProviderPresets
import com.opencode.android.data.model.McpConfigSource
import com.opencode.android.data.model.McpServerConfig
import com.opencode.android.data.model.parseModelIds
import com.opencode.android.data.model.parsePluginSpecs
import com.opencode.android.data.model.sanitizeLocalWorkspaceName
import com.opencode.android.data.model.validate
import com.opencode.android.data.model.Provider
import com.opencode.android.data.repository.AppearanceRepository
import com.opencode.android.data.repository.PreferencesRepository
import com.opencode.android.runtime.LegacyRuntimeCompanion
import com.opencode.android.runtime.RuntimeCompanionClient
import com.opencode.android.ui.component.Hairline
import com.opencode.android.ui.component.LocalWorkspacePicker
import com.opencode.android.ui.component.rememberSafFolderPicker
import com.opencode.android.workspace.WorkspaceDisplay
import com.opencode.android.ui.component.MonoLabel
import com.opencode.android.ui.component.WorkspacePicker
import com.opencode.android.ui.component.pressable
import com.opencode.android.ui.theme.LocalOcColors
import com.opencode.android.ui.theme.OcAccent
import com.opencode.android.ui.theme.OcType

/**
 * Settings 页（spec §7.4）
 *
 * 分组结构: 大写等宽小标签 + 成组卡(圆角14dp, 无边框无阴影, 内部发丝线分行)
 *  1. CONNECTION  — Host / Port / Password / Directory
 *  2. APPEARANCE  — 亮/暗/系统 分段控件 + 强调色色块
 *  3. AGENT       — 默认 Agent / 模型
 *  4. ABOUT       — 版本号 / 检查更新
 *  末尾: DISCONNECT（红色）
 *  底部: opencode · host:port
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onDisconnect: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PreferencesRepository(context) }
    val appearance = remember { AppearanceRepository(context) }
    val c = LocalOcColors.current

    val mode by prefs.connectionMode.collectAsState(initial = ConnectionMode.LAN)
    val lanProfile by prefs.lanProfile.collectAsState(initial = com.opencode.android.data.model.LanProfile())
    val localProfile by prefs.localProfile.collectAsState(initial = com.opencode.android.data.model.LocalProfile())
    val activeEndpoint by prefs.activeEndpoint.collectAsState(initial = null)
    val darkTheme by appearance.darkTheme.collectAsState(initial = false)
    val accentIndex by appearance.accentIndex.collectAsState(initial = 0)
    val scope = rememberCoroutineScope()
    val runtimeClient = remember { RuntimeCompanionClient(context) }
    var runtimeStatusVersion by remember { mutableStateOf(0) }
    val runtimeStatus = remember(runtimeStatusVersion) { runtimeClient.status() }
    val bundledAvailable = runtimeStatus.canStart
    val bundledSupported = runtimeStatus.supportsBundled
    val legacyCompanionInstalled = remember { LegacyRuntimeCompanion.isInstalled(context) }
    var lanHostDraft by remember(lanProfile.host) { mutableStateOf(lanProfile.host) }
    var lanPortDraft by remember(lanProfile.port) { mutableStateOf(lanProfile.port.toString()) }
    var lanPasswordDraft by remember { mutableStateOf("") }
    var lanHasSavedPassword by remember(lanProfile.password.isNotBlank()) { mutableStateOf(lanProfile.password.isNotBlank()) }
    // Debounced save for LAN profile drafts
    LaunchedEffect(lanHostDraft, lanPortDraft, lanPasswordDraft) {
        delay(600)
        val port = lanPortDraft.toIntOrNull() ?: return@LaunchedEffect
        if (port in 1..65535 && (lanHostDraft != lanProfile.host || port != lanProfile.port)) {
            prefs.saveLanProfile(lanProfile.copy(host = lanHostDraft, port = port))
        }
        if (lanPasswordDraft.isNotBlank() && lanPasswordDraft.length >= 4) {
            prefs.saveLanProfile(
                prefs.lanProfile.first().copy(password = lanPasswordDraft)
            )
            lanPasswordDraft = ""
            lanHasSavedPassword = true
        }
    }
    var localStatus by remember { mutableStateOf<String?>(null) }
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
        scope.launch {
            prefs.saveLocalProfile(
                localProfile.copy(
                    workspacePath = sanitizeLocalWorkspaceName(selection.displayName),
                    workspaceTreeUri = selection.treeUri,
                ),
            )
            localStatus = "已关联外部文件夹"
        }
    }
    val localWorkspaceCandidates = remember(localWorkspaceNames, localWorkspaceDraft) {
        (localWorkspaceNames + sanitizeLocalWorkspaceName(localWorkspaceDraft))
            .filter { it.isNotBlank() }
            .distinct()
    }
    var showLocalWorkspacePicker by remember { mutableStateOf(false) }
    var bundledPortDraft by remember(localProfile.bundledPort) { mutableStateOf(localProfile.bundledPort.toString()) }
    var externalPortDraft by remember(localProfile.externalPort) { mutableStateOf(localProfile.externalPort.toString()) }
    val bundledPortValid = bundledPortDraft.toIntOrNull()?.let { it in 1..65535 } == true
    val externalPortValid = externalPortDraft.toIntOrNull()?.let { it in 1..65535 } == true
    val localValidationMessage = when {
        !bundledPortValid -> "Bundled port must be 1-65535"
        !externalPortValid -> "External port must be 1-65535"
        else -> null
    }

    // Agent / model state
    val defaultAgent by prefs.defaultAgent.collectAsState(initial = "build")
    val defaultModelProvider by prefs.defaultModelProvider.collectAsState(initial = "")
    val defaultModelId by prefs.defaultModelId.collectAsState(initial = "")
    var providers by remember { mutableStateOf<List<Provider>>(emptyList()) }
    var availableAgents by remember { mutableStateOf<List<AgentInfo>>(emptyList()) }
    var showModelPicker by remember { mutableStateOf(false) }
    var expandedProviderId by remember { mutableStateOf<String?>(null) }
    var workspaces by remember { mutableStateOf<List<WorkspaceOption>>(emptyList()) }
    var isLoadingWorkspaces by remember { mutableStateOf(false) }
    var workspaceError by remember { mutableStateOf<String?>(null) }
    val pinnedWorkspaces by prefs.pinnedWorkspaces.collectAsState(initial = emptySet())
    val localProviderProfile by prefs.localProviderProfile.collectAsState(initial = LocalProviderProfile())
    val initialProviderPreset = remember(localProviderProfile) { LocalProviderPresets.bestMatch(localProviderProfile) }
    var providerEnabledDraft by remember(localProviderProfile.enabled) { mutableStateOf(localProviderProfile.enabled) }
    var providerPresetIdDraft by remember(localProviderProfile.presetId, initialProviderPreset.id) {
        mutableStateOf(initialProviderPreset.id)
    }
    var providerNameDraft by remember(localProviderProfile.displayName, initialProviderPreset.displayName) {
        mutableStateOf(localProviderProfile.displayName.ifBlank { initialProviderPreset.displayName })
    }
    var providerBaseUrlDraft by remember(localProviderProfile.baseUrl, initialProviderPreset.apiBaseUrl) {
        mutableStateOf(localProviderProfile.baseUrl.ifBlank { initialProviderPreset.apiBaseUrl })
    }
    var providerCodingBaseUrlDraft by remember(localProviderProfile.codingBaseUrl, initialProviderPreset.codingBaseUrl) {
        mutableStateOf(localProviderProfile.codingBaseUrl.ifBlank { initialProviderPreset.codingBaseUrl })
    }
    var providerActiveBaseUrlDraft by remember(localProviderProfile.activeBaseUrl, localProviderProfile.baseUrl) {
        mutableStateOf(localProviderProfile.activeBaseUrl.ifBlank { localProviderProfile.baseUrl })
    }
    var providerModelsDraft by remember(localProviderProfile.modelIds, initialProviderPreset.modelIds) {
        mutableStateOf(localProviderProfile.modelIds.ifEmpty { initialProviderPreset.modelIds }.joinToString("\n"))
    }
    var providerApiKeyDraft by remember { mutableStateOf("") }
    var providerHasSavedKey by remember(localProviderProfile.presetId, localProviderProfile.hasApiKey) {
        mutableStateOf(localProviderProfile.hasApiKey)
    }
    var providerStatus by remember { mutableStateOf<String?>(null) }
    var showProviderPresets by remember { mutableStateOf(false) }
    var showProviderModelPicker by remember { mutableStateOf(false) }
    var isFetchingProviderModels by remember { mutableStateOf(false) }
    var fetchedProviderModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var lastProviderModelFetchKey by remember { mutableStateOf("") }
    val selectedProviderPreset = LocalProviderPresets.byId(providerPresetIdDraft) ?: initialProviderPreset
    val selectedProviderModel = parseModelIds(providerModelsDraft).firstOrNull().orEmpty()
    val providerModelCandidates = (fetchedProviderModels.ifEmpty { selectedProviderPreset.modelIds } + selectedProviderModel)
        .filter { it.isNotBlank() }
        .distinct()
    val providerDraft = LocalProviderProfile(
        enabled = providerEnabledDraft,
        presetId = providerPresetIdDraft,
        providerId = LocalProviderDefaults.PROVIDER_ID,
        displayName = providerNameDraft,
        baseUrl = providerBaseUrlDraft,
        codingBaseUrl = providerCodingBaseUrlDraft,
        activeBaseUrl = providerActiveBaseUrlDraft.ifBlank { providerBaseUrlDraft },
        modelIds = parseModelIds(providerModelsDraft),
        hasApiKey = providerApiKeyDraft.isNotBlank() || providerHasSavedKey,
    )
    val providerValidationMessage = providerDraft.validate()

    // ── MCP & plugins state ──
    val savedMcpServers by prefs.localMcpServers.collectAsState(initial = emptyList())
    val savedPlugins by prefs.localPlugins.collectAsState(initial = "")
    val savedAgentPlugins by prefs.agentOriginatedPlugins.collectAsState(initial = emptySet())
    val savedDefaultPlugins by prefs.defaultPluginsEnabled.collectAsState(initial = false)
    var mcpRows by remember(savedMcpServers) {
        mutableStateOf(savedMcpServers.map { McpRowDraft(it.name, it.url, "", it.hasToken, it.source) })
    }
    var pluginsDraft by remember(savedPlugins) { mutableStateOf(savedPlugins) }
    var defaultPluginsDraft by remember(savedDefaultPlugins) { mutableStateOf(savedDefaultPlugins) }
    var mcpStatus by remember { mutableStateOf<String?>(null) }

    suspend fun loadWorkspaces() {
        if (isLoadingWorkspaces) return
        if (mode != ConnectionMode.LAN) {
            workspaces = emptyList()
            workspaceError = null
            return
        }
        val endpoint = activeEndpoint ?: return
        isLoadingWorkspaces = true
        workspaceError = null
        val api = OpenCodeApi(endpoint.copy(directory = ""))
        try {
            api.fetchWorkspaceOptions()
                .onSuccess { workspaces = it }
                .onFailure { workspaceError = it.message ?: "Failed to load workspaces" }
        } finally {
            api.close()
            isLoadingWorkspaces = false
        }
    }

    fun checkActiveHealth() {
        val endpoint = activeEndpoint ?: return
        scope.launch {
            val api = OpenCodeApi(endpoint)
            try {
                api.health()
                    .onSuccess { localStatus = "Healthy ${it.version}".trim() }
                    .onFailure { localStatus = it.message ?: "Unavailable" }
            } finally {
                api.close()
            }
        }
    }

    suspend fun runBundledServiceCommand(restart: Boolean): Boolean {
        if (!bundledAvailable) {
            localStatus = runtimeStatus.message
            return false
        }
        localStatus = if (restart) "Runtime restarting" else "Runtime starting"
        val providerProfile = prefs.localProviderProfile.first()
        val providerApiKey = if (providerProfile.hasApiKey) prefs.getLocalProviderApiKey(providerProfile.presetId) else ""
        val serverPassword = prefs.getOrCreateLocalServerPassword()
        val result = if (restart) {
            runtimeClient.restartAndAwaitReady(
                port = bundledPortDraft.toIntOrNull() ?: localProfile.bundledPort,
                workspaceName = localWorkspaceDraft,
                workspaceTreeUri = localTreeUriDraft,
                providerProfile = providerProfile,
                providerApiKey = providerApiKey,
                serverPassword = serverPassword,
            )
        } else {
            runtimeClient.startAndAwaitReady(
                port = bundledPortDraft.toIntOrNull() ?: localProfile.bundledPort,
                workspaceName = localWorkspaceDraft,
                workspaceTreeUri = localTreeUriDraft,
                providerProfile = providerProfile,
                providerApiKey = providerApiKey,
                serverPassword = serverPassword,
            )
        }
        result
            .onSuccess {
                localStatus = "Runtime ready"
                runtimeStatusVersion++
            }
            .onFailure { localStatus = it.message ?: if (restart) "Runtime restart failed" else "Runtime start failed" }
        return result.isSuccess
    }

    fun startBundledService() {
        scope.launch {
            runBundledServiceCommand(restart = false)
        }
    }

    fun restartBundledService(): Boolean {
        if (!bundledAvailable) {
            localStatus = runtimeStatus.message
            return false
        }
        scope.launch {
            runBundledServiceCommand(restart = true)
        }
        return true
    }

    fun stopBundledService() {
        runtimeClient.stop()
            .onSuccess { localStatus = "Runtime stop requested" }
            .onFailure { localStatus = it.message ?: "Runtime stop failed" }
        runtimeStatusVersion++
    }

    fun requestRuntimeBatteryExemption() {
        runtimeClient.requestBatteryExemption()
            .onSuccess { localStatus = "Battery prompt opened" }
            .onFailure { localStatus = it.message ?: "Battery prompt failed" }
    }

    fun applyLocalProvider() {
        if (providerEnabledDraft && providerValidationMessage != null) {
            providerStatus = providerValidationMessage
            return
        }
        scope.launch {
            try {
                val nextProfile = providerDraft
                prefs.saveLocalProviderProfile(
                    profile = nextProfile,
                    apiKey = providerApiKeyDraft.takeIf { it.isNotBlank() },
                )
                val restartApplied = if (mode == ConnectionMode.LOCAL_BUNDLED && bundledAvailable) {
                    runBundledServiceCommand(restart = true)
                } else {
                    true
                }
                providerApiKeyDraft = ""
                providerHasSavedKey = nextProfile.hasApiKey
                providerStatus = if (restartApplied) {
                    "Saved"
                } else {
                    "Saved; runtime is not ready"
                }
            } catch (e: Exception) {
                providerStatus = e.message ?: "Failed to save provider"
            }
        }
    }

    fun clearLocalProviderKey() {
        scope.launch {
            prefs.clearLocalProviderApiKey(providerPresetIdDraft)
            providerApiKeyDraft = ""
            providerHasSavedKey = false
            val restartApplied = if (mode == ConnectionMode.LOCAL_BUNDLED && bundledAvailable) {
                runBundledServiceCommand(restart = true)
            } else {
                true
            }
            providerStatus = if (restartApplied) {
                "API key cleared"
            } else {
                "API key cleared; runtime is not ready"
            }
        }
    }

    fun refreshMcpAndPluginsFromAgent() {
        scope.launch {
            try {
                val result = prefs.syncMcpAndPluginsFromNative()
                mcpStatus = when {
                    result.importedMcpNames.isNotEmpty() || result.importedPluginSpecs.isNotEmpty() ->
                        "Synced from agent: MCP ${result.importedMcpNames.joinToString()} · plugins ${result.importedPluginSpecs.joinToString()}"
                    result.changed -> "Synced with native config"
                    else -> "Already up to date"
                }
            } catch (e: Exception) {
                mcpStatus = e.message ?: "Sync failed"
            }
        }
    }

    fun applyMcpAndPlugins() {
        scope.launch {
            val rows = mcpRows.filter { it.name.isNotBlank() || it.url.isNotBlank() }
            rows.firstNotNullOfOrNull { McpServerConfig(it.name.trim(), it.url.trim()).validate() }?.let {
                mcpStatus = it
                return@launch
            }
            val names = rows.map { it.name.trim() }
            if (names.size != names.toSet().size) {
                mcpStatus = "MCP names must be unique"
                return@launch
            }
            try {
                val configs = rows.map { row ->
                    val name = row.name.trim()
                    val hasToken = if (row.token.isNotBlank()) {
                        prefs.setMcpToken(name, row.token)
                    } else {
                        row.hasSavedToken
                    }
                    McpServerConfig(
                        name = name,
                        url = row.url.trim(),
                        hasToken = hasToken,
                        source = McpConfigSource.APP,
                    )
                }
                savedMcpServers.map { it.name }.filterNot { it in names }.forEach { prefs.setMcpToken(it, null) }
                prefs.saveMcpServers(configs)
                prefs.savePlugins(pluginsDraft)
                prefs.saveAgentOriginatedPlugins(emptySet())
                prefs.setDefaultPluginsEnabled(defaultPluginsDraft)
                prefs.exportMcpAndPluginsToNative()
                val applied = if (mode == ConnectionMode.LOCAL_BUNDLED && bundledAvailable) {
                    runBundledServiceCommand(restart = true)
                } else {
                    true
                }
                mcpStatus = if (applied) "Saved" else "Saved; runtime is not ready"
            } catch (e: Exception) {
                mcpStatus = e.message ?: "Failed to save MCP/plugins"
            }
        }
    }

    fun saveLocalDrafts() {
        if (localValidationMessage != null) {
            localStatus = localValidationMessage
            return
        }
        scope.launch {
            val updated = localProfile.copy(
                bundledPort = bundledPortDraft.toInt(),
                externalPort = externalPortDraft.toInt(),
                workspacePath = sanitizeLocalWorkspaceName(localWorkspaceDraft),
                workspaceTreeUri = localTreeUriDraft,
            )
            prefs.saveLocalProfile(updated)
            localStatus = "Saved"
            if (mode == ConnectionMode.LOCAL_BUNDLED && bundledAvailable) {
                startBundledService()
            }
        }
    }

    fun switchMode(nextMode: ConnectionMode) {
        if (nextMode == mode) return
        scope.launch {
            if (mode == ConnectionMode.LOCAL_BUNDLED && nextMode != ConnectionMode.LOCAL_BUNDLED) {
                stopBundledService()
            }
            prefs.saveConnectionMode(nextMode)
            localStatus = null
            if (nextMode == ConnectionMode.LOCAL_BUNDLED && localProfile.autoStart && bundledAvailable) {
                startBundledService()
            }
        }
    }

    fun cycleConnectionMode() {
        val nextMode = when (mode) {
            ConnectionMode.LAN -> if (bundledSupported) ConnectionMode.LOCAL_BUNDLED else ConnectionMode.LOCAL_EXTERNAL
            ConnectionMode.LOCAL_BUNDLED -> ConnectionMode.LOCAL_EXTERNAL
            ConnectionMode.LOCAL_EXTERNAL -> ConnectionMode.LAN
        }
        switchMode(nextMode)
    }

    fun selectProviderPreset(preset: LocalProviderPreset) {
        scope.launch {
            val saved = prefs.getLocalProviderProfile(preset.id)
            providerPresetIdDraft = preset.id
            providerEnabledDraft = true
            providerNameDraft = saved.displayName
            providerBaseUrlDraft = saved.baseUrl
            providerCodingBaseUrlDraft = saved.codingBaseUrl
            providerActiveBaseUrlDraft = saved.activeBaseUrl
            providerModelsDraft = saved.modelIds.joinToString("\n")
            providerHasSavedKey = saved.hasApiKey
            providerApiKeyDraft = ""
            fetchedProviderModels = emptyList()
            lastProviderModelFetchKey = ""
            showProviderModelPicker = false
            providerStatus = null
            showProviderPresets = false
        }
    }

    suspend fun fetchProviderModelsNow(auto: Boolean) {
        if (isFetchingProviderModels) return
        isFetchingProviderModels = true
        providerStatus = if (auto) "Auto fetching models" else "Fetching models"
        val key = providerApiKeyDraft.trim().ifBlank {
            if (providerHasSavedKey) prefs.getLocalProviderApiKey(providerPresetIdDraft) else ""
        }.trim()
        LocalProviderModelFetcher.fetchModels(
            preset = selectedProviderPreset,
            apiBaseUrl = providerBaseUrlDraft,
            codingBaseUrl = providerCodingBaseUrlDraft,
            apiKey = key,
        )
            .onSuccess { result ->
                fetchedProviderModels = result.models
                providerActiveBaseUrlDraft = result.baseUrl
                providerStatus = "Fetched ${result.models.size} models from ${result.sourceLabel}; choose one"
            }
            .onFailure { providerStatus = "Model fetch failed: ${it.message ?: "unknown error"}" }
        isFetchingProviderModels = false
    }

    fun fetchProviderModels() {
        scope.launch {
            fetchProviderModelsNow(auto = false)
        }
    }

    fun selectProviderModel(modelId: String) {
        providerModelsDraft = modelId
        showProviderModelPicker = false
        providerStatus = "Model selected"
    }

    // Load providers + agents from the active endpoint.
    LaunchedEffect(activeEndpoint?.identityKey) {
        val endpoint = activeEndpoint
        if (endpoint == null) {
            providers = emptyList()
            availableAgents = emptyList()
            return@LaunchedEffect
        }
        providers = emptyList()
        availableAgents = emptyList()
        val api = OpenCodeApi(endpoint)
        try {
            api.fetchConfiguredProviders()
                .onSuccess { providers = it }
            api.fetchAgents()
                .onSuccess { all -> availableAgents = all.filter { it.mode == "primary" && !it.hidden } }
        } finally {
            api.close()
        }
    }

    LaunchedEffect(mode, activeEndpoint?.identityKey) {
        loadWorkspaces()
    }

    LaunchedEffect(mode) {
        if (mode != ConnectionMode.LOCAL_BUNDLED) return@LaunchedEffect
        runCatching { prefs.syncMcpAndPluginsFromNative() }
            .onSuccess { result ->
                if (result.importedMcpNames.isNotEmpty() || result.importedPluginSpecs.isNotEmpty()) {
                    mcpStatus = "Imported from agent: ${result.importedMcpNames.joinToString()} ${result.importedPluginSpecs.joinToString()}"
                }
            }
    }

    LaunchedEffect(
        mode,
        providerPresetIdDraft,
        providerBaseUrlDraft,
        providerCodingBaseUrlDraft,
        providerApiKeyDraft,
    ) {
        if (mode != ConnectionMode.LOCAL_BUNDLED) return@LaunchedEffect
        if (!providerEnabledDraft) return@LaunchedEffect
        if (providerApiKeyDraft.trim().length < 8 && !providerHasSavedKey) return@LaunchedEffect
        val key = listOf(
            providerPresetIdDraft,
            providerBaseUrlDraft.trim(),
            providerCodingBaseUrlDraft.trim(),
            providerApiKeyDraft.trim().takeIf { it.isNotBlank() }?.hashCode()?.toString()
                ?: "saved-$providerHasSavedKey",
        ).joinToString("|")
        if (key == lastProviderModelFetchKey) return@LaunchedEffect
        delay(800)
        lastProviderModelFetchKey = key
        fetchProviderModelsNow(auto = true)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        // ── Top bar ──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).pressable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Text("←", style = OcType.title, color = c.ink)
            }
            Spacer(Modifier.width(4.dp))
            Text("Settings", style = OcType.titleL, color = c.ink)
        }

        Spacer(Modifier.height(8.dp))

        // ── MODE ──
        SectionHeader("MODE")
        SettingsCard {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Connection", style = OcType.body, color = c.ink, modifier = Modifier.weight(1f))
                ModeCycleButton(label = mode.shortLabel(), c = c) { cycleConnectionMode() }
            }
        }

        if (mode == ConnectionMode.LAN) {
            Spacer(Modifier.height(10.dp))
            SectionHeader("SERVER")
            SettingsCard {
                EditableSettingsRow(
                    label = "Host",
                    value = lanHostDraft,
                    onValueChange = { lanHostDraft = it },
                )
                Hairline()
                EditableSettingsRow(
                    label = "Port",
                    value = lanPortDraft,
                    keyboardType = KeyboardType.Number,
                    onValueChange = { lanPortDraft = it.filter(Char::isDigit).take(5) },
                )
                Hairline()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Password",
                        style = OcType.body,
                        color = c.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(min = 104.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    BasicTextField(
                        value = lanPasswordDraft,
                        onValueChange = { lanPasswordDraft = it },
                        singleLine = true,
                        cursorBrush = SolidColor(c.accent),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        textStyle = OcType.mono.copy(color = c.ink2, textAlign = TextAlign.End),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                                if (lanPasswordDraft.isBlank()) {
                                    Text(
                                        if (lanHasSavedPassword) "Saved" else "—",
                                        style = OcType.mono,
                                        color = if (lanHasSavedPassword) c.ink2 else c.ink4,
                                        textAlign = TextAlign.End,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    if (lanHasSavedPassword) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.pressable {
                                scope.launch { prefs.saveLanProfile(lanProfile.copy(password = "")) }
                                lanHasSavedPassword = false
                            }.padding(horizontal = 4.dp, vertical = 2.dp),
                        ) {
                            Text("Clear", style = OcType.mono.copy(fontSize = 11.sp), color = c.accent)
                        }
                    }
                }
            }
        }

        if (mode != ConnectionMode.LAN) {
            Spacer(Modifier.height(10.dp))
            SettingsCard {
                SettingsRow("Runtime", if (mode == ConnectionMode.LOCAL_BUNDLED) "Bundled" else "External")
                Hairline()
                EditableSettingsRow(
                    label = "Bundled Port",
                    value = bundledPortDraft,
                    keyboardType = KeyboardType.Number,
                    onValueChange = { bundledPortDraft = it.filter(Char::isDigit).take(5) },
                )
                Hairline()
                EditableSettingsRow(
                    label = "External Port",
                    value = externalPortDraft,
                    keyboardType = KeyboardType.Number,
                    onValueChange = { externalPortDraft = it.filter(Char::isDigit).take(5) },
                )
                Hairline()
                EditableSettingsRow(
                    label = if (mode == ConnectionMode.LOCAL_BUNDLED) "Workspace" else "Directory",
                    value = localWorkspaceDraft,
                    onValueChange = {
                        localWorkspaceDraft = if (mode == ConnectionMode.LOCAL_BUNDLED) sanitizeLocalWorkspaceName(it) else it
                    },
                    trailing = if (mode == ConnectionMode.LOCAL_BUNDLED) {
                        {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                InlineAction("Browse") { pickExternalFolder() }
                                InlineAction(if (showLocalWorkspacePicker) "Hide" else "App") {
                                    showLocalWorkspacePicker = !showLocalWorkspacePicker
                                }
                            }
                        }
                    } else null,
                )
                if (mode == ConnectionMode.LOCAL_BUNDLED && localTreeUriDraft.isNotBlank()) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                        Text(
                            "外部文件夹 · 含 .git/.env 等隐藏文件，增删改会自动同步",
                            style = OcType.mono.copy(fontSize = 11.sp),
                            color = c.ink3,
                        )
                        activeEndpoint?.directory?.takeIf { it.isNotBlank() }?.let { bridgePath ->
                            Text(
                                bridgePath,
                                style = OcType.mono.copy(fontSize = 10.sp),
                                color = c.ink4,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
                if (mode == ConnectionMode.LOCAL_BUNDLED) {
                    AnimatedVisibility(visible = showLocalWorkspacePicker) {
                        Column(Modifier.fillMaxWidth()) {
                            Hairline()
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
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
                Hairline()
                SettingsRow("Endpoint", activeEndpoint?.displayUrl ?: "loading")
                Hairline()
                SettingsRow("Runtime APK", runtimeStatus.message)
                if (legacyCompanionInstalled) {
                    Hairline()
                    Text(
                        "Legacy companion APK detected (${LegacyRuntimeCompanion.PACKAGE_NAME}). Uninstall it to avoid port conflicts.",
                        style = OcType.mono.copy(fontSize = 11.sp),
                        color = c.accent,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
                Hairline()
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LocalAction("Apply", enabled = localValidationMessage == null, modifier = Modifier.weight(1f)) { saveLocalDrafts() }
                        LocalAction("Health", enabled = true, modifier = Modifier.weight(1f)) { checkActiveHealth() }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LocalAction(
                            "Start",
                            enabled = mode == ConnectionMode.LOCAL_BUNDLED && bundledAvailable,
                            modifier = Modifier.weight(1f),
                        ) { startBundledService() }
                        LocalAction(
                            "Stop",
                            enabled = mode == ConnectionMode.LOCAL_BUNDLED,
                            modifier = Modifier.weight(1f),
                        ) { stopBundledService() }
                        LocalAction(
                            "Battery",
                            enabled = mode == ConnectionMode.LOCAL_BUNDLED && bundledAvailable,
                            modifier = Modifier.weight(1f),
                        ) { requestRuntimeBatteryExemption() }
                    }
                    Text(
                        localValidationMessage ?: localStatus ?: "—",
                        style = OcType.mono.copy(fontSize = 11.sp),
                        color = if (localValidationMessage == null) c.ink3 else c.accent,
                    )
                }
            }
        }

        if (mode == ConnectionMode.LOCAL_BUNDLED) {
            Spacer(Modifier.height(22.dp))
            SectionHeader("LOCAL PROVIDER")
            SettingsCard {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pressable { providerEnabledDraft = !providerEnabledDraft }
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Enable", style = OcType.body, color = c.ink, modifier = Modifier.weight(1f))
                    Text(
                        if (providerEnabledDraft) "On" else "Off",
                        style = OcType.mono,
                        color = if (providerEnabledDraft) c.accent else c.ink3,
                    )
                }
                Hairline()
                ProviderPresetRow(
                    preset = selectedProviderPreset,
                    expanded = showProviderPresets,
                    onClick = { showProviderPresets = !showProviderPresets },
                )
                AnimatedVisibility(visible = showProviderPresets) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        LocalProviderPresets.ALL.forEach { preset ->
                            ProviderPresetOption(
                                preset = preset,
                                selected = preset.id == providerPresetIdDraft,
                                onClick = { selectProviderPreset(preset) },
                            )
                        }
                    }
                }
                Hairline()
                EditableSettingsRow(
                    label = "API Base",
                    value = providerBaseUrlDraft,
                    onValueChange = {
                        if (providerActiveBaseUrlDraft.trim() == providerBaseUrlDraft.trim()) {
                            providerActiveBaseUrlDraft = it
                        }
                        providerBaseUrlDraft = it
                    },
                )
                Hairline()
                EditableSettingsRow(
                    label = "Coding Base",
                    value = providerCodingBaseUrlDraft,
                    onValueChange = {
                        if (providerActiveBaseUrlDraft.trim() == providerCodingBaseUrlDraft.trim()) {
                            providerActiveBaseUrlDraft = it
                        }
                        providerCodingBaseUrlDraft = it
                    },
                )
                Hairline()
                ProviderApiKeyRow(
                    hasSavedKey = providerHasSavedKey,
                    value = providerApiKeyDraft,
                    onValueChange = { providerApiKeyDraft = it },
                    onClear = { clearLocalProviderKey() },
                )
                Hairline()
                ProviderModelRow(
                    model = selectedProviderModel,
                    expanded = showProviderModelPicker,
                    loading = isFetchingProviderModels,
                    hasCandidates = providerModelCandidates.isNotEmpty(),
                    onClick = {
                        showProviderModelPicker = !showProviderModelPicker
                        if (providerModelCandidates.isEmpty() && !isFetchingProviderModels) fetchProviderModels()
                    },
                )
                AnimatedVisibility(visible = showProviderModelPicker) {
                    Column(Modifier.fillMaxWidth()) {
                        Hairline()
                        ProviderModelCandidatePicker(
                            models = providerModelCandidates,
                            selectedModel = selectedProviderModel,
                            loading = isFetchingProviderModels,
                            onRetry = { fetchProviderModels() },
                            onSelect = { selectProviderModel(it) },
                        )
                    }
                }
                Hairline()
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LocalAction(
                        "Apply",
                        enabled = !providerEnabledDraft || providerValidationMessage == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { applyLocalProvider() }
                    Text(
                        providerValidationMessage ?: providerStatus ?: "—",
                        style = OcType.mono.copy(fontSize = 11.sp),
                        color = if (providerValidationMessage == null) c.ink3 else c.accent,
                    )
                }
            }
        }

        if (mode == ConnectionMode.LOCAL_BUNDLED) {
            Spacer(Modifier.height(22.dp))
            SectionHeader("MCP & PLUGINS")
            SettingsCard {
                mcpRows.forEachIndexed { index, row ->
                    if (index > 0) Hairline()
                    McpServerEditor(
                        row = row,
                        fromAgent = row.source == McpConfigSource.AGENT,
                        onChange = { updated -> mcpRows = mcpRows.toMutableList().also { it[index] = updated } },
                        onRemove = { mcpRows = mcpRows.toMutableList().also { it.removeAt(index) } },
                    )
                }
                Hairline()
                Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
                    LocalAction("+ Add remote MCP", enabled = true, modifier = Modifier.fillMaxWidth()) {
                        mcpRows = mcpRows + McpRowDraft()
                    }
                }
                Hairline()
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Plugins (npm specs, one per line)", style = OcType.body, color = c.ink)
                    BasicTextField(
                        value = pluginsDraft,
                        onValueChange = { pluginsDraft = it },
                        cursorBrush = SolidColor(c.accent),
                        textStyle = OcType.mono.copy(color = c.ink2, fontSize = 12.sp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                        decorationBox = { inner ->
                            if (pluginsDraft.isBlank()) {
                                Text(
                                    "opencode-plugin-foo\n@scope/bar@1.2.3",
                                    style = OcType.mono.copy(fontSize = 12.sp),
                                    color = c.ink4,
                                )
                            }
                            inner()
                        },
                    )
                    if (savedAgentPlugins.isNotEmpty()) {
                        Text(
                            "From agent: ${savedAgentPlugins.joinToString(", ")}",
                            style = OcType.mono.copy(fontSize = 11.sp),
                            color = c.ink3,
                        )
                    }
                }
                Hairline()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pressable { defaultPluginsDraft = !defaultPluginsDraft }
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Default plugins", style = OcType.body, color = c.ink, modifier = Modifier.weight(1f))
                    Text(
                        if (defaultPluginsDraft) "On" else "Off",
                        style = OcType.mono,
                        color = if (defaultPluginsDraft) c.accent else c.ink3,
                    )
                }
                Hairline()
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        LocalAction(
                            "Refresh from agent",
                            enabled = true,
                            modifier = Modifier.weight(1f),
                        ) { refreshMcpAndPluginsFromAgent() }
                        LocalAction("Apply", enabled = true, modifier = Modifier.weight(1f)) { applyMcpAndPlugins() }
                    }
                    Text(
                        mcpStatus ?: "Bidirectional sync with files/.config/opencode · remote MCP (HTTP/SSE) only",
                        style = OcType.mono.copy(fontSize = 11.sp),
                        color = c.ink3,
                    )
                }
            }
        }

        if (mode == ConnectionMode.LAN || mode == ConnectionMode.LOCAL_EXTERNAL) {
            Spacer(Modifier.height(22.dp))
            SectionHeader("PROVIDER")
            SettingsCard {
                if (providers.isEmpty()) {
                    SettingsRow("Status", "No configured providers")
                } else {
                    providers.forEachIndexed { index, provider ->
                        if (index > 0) Hairline()
                        SettingsRow(
                            label = provider.name.ifBlank { provider.id },
                            value = provider.models.keys.joinToString(", ").ifBlank { "—" },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        // ── CONNECTION ──
        SectionHeader("CONNECTION")
        SettingsCard {
            SettingsRow("Endpoint", activeEndpoint?.displayUrl ?: "loading")
            Hairline()
            SettingsRow("Mode", mode.name.lowercase().replace('_', ' '))
            Hairline()
            SettingsRow("Password", if (activeEndpoint?.password.isNullOrBlank()) "—" else "••••••••")
            Hairline()
            SettingsRow(
                if (mode == ConnectionMode.LAN) "Directory" else "Workspace",
                activeEndpoint?.let { WorkspaceDisplay.endpointDirectoryLabel(it) } ?: "—",
            )
        }

        Spacer(Modifier.height(22.dp))

        // ── WORKSPACE ──
        if (mode == ConnectionMode.LAN) {
            SectionHeader("WORKSPACE")
            WorkspacePicker(
                options = workspaces,
                selectedPath = lanProfile.directory,
                pinnedPaths = pinnedWorkspaces,
                loading = isLoadingWorkspaces,
                error = workspaceError,
                onLoad = { scope.launch { loadWorkspaces() } },
                onSelect = { path ->
                    scope.launch {
                        prefs.saveLanProfile(lanProfile.copy(directory = path))
                    }
                },
                onTogglePinned = { path ->
                    scope.launch { prefs.togglePinnedWorkspace(path) }
                },
                modifier = Modifier.padding(horizontal = 22.dp),
            )

            Spacer(Modifier.height(22.dp))
        }

        // ── APPEARANCE ──
        SectionHeader("APPEARANCE")

        // Theme segmented control
        SettingsCard {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Theme", style = OcType.body, color = c.ink, modifier = Modifier.weight(1f))
                // Light / Dark / System segmented
                Row(
                    Modifier
                        .background(c.surface2, RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    ThemeOption("Light", darkTheme == false, c) {
                        scope.launch { appearance.setDark(false) }
                    }
                    ThemeOption("Dark", darkTheme == true, c) {
                        scope.launch { appearance.setDark(true) }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Accent color swatches
        SettingsCard {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Accent", style = OcType.body, color = c.ink, modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OcAccent.entries.forEachIndexed { index, accent ->
                        val selected = index == accentIndex
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(accent.color)
                                .pressable {
                                    scope.launch { appearance.setAccentIndex(index) }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                androidx.compose.foundation.Canvas(
                                    Modifier.size(18.dp)
                                ) {
                                    val stroke = 2.5.dp.toPx()
                                    val path = androidx.compose.ui.graphics.Path().apply {
                                        moveTo(size.width * 0.2f, size.height * 0.5f)
                                        lineTo(size.width * 0.42f, size.height * 0.72f)
                                        lineTo(size.width * 0.8f, size.height * 0.28f)
                                    }
                                    drawPath(
                                        path,
                                        color = c.accentInk,
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                                            width = stroke,
                                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                            join = androidx.compose.ui.graphics.StrokeJoin.Round,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        // ── AGENT ──
        SectionHeader("AGENT")
        SettingsCard {
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable {
                        if (availableAgents.size < 2) return@pressable
                        val currentIdx = availableAgents.indexOfFirst { it.name == defaultAgent }
                        val nextIdx = if (currentIdx >= 0) (currentIdx + 1) % availableAgents.size else 0
                        scope.launch { prefs.saveDefaultAgent(availableAgents[nextIdx].name) }
                    }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Default Agent", style = OcType.body, color = c.ink, modifier = Modifier.weight(1f))
                Text(
                    defaultAgent.replaceFirstChar { it.uppercase() },
                    style = OcType.mono,
                    color = c.ink2,
                )
            }
            Hairline()
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable {
                        if (providers.isNotEmpty()) showModelPicker = !showModelPicker
                    }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Model", style = OcType.body, color = c.ink, modifier = Modifier.weight(1f))
                val modelLabel = if (defaultModelProvider.isNotBlank() && defaultModelId.isNotBlank()) {
                    "$defaultModelProvider/$defaultModelId"
                } else {
                    if (providers.isEmpty()) "Loading…" else "Select…"
                }
                Text(
                    modelLabel,
                    style = OcType.mono,
                    color = if (providers.isNotEmpty()) c.ink2 else c.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Model picker panel — outside the card
        AnimatedVisibility(visible = showModelPicker) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .background(c.surface2, RoundedCornerShape(14.dp))
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp)
            ) {
                providers.forEach { provider ->
                    val isExpanded = expandedProviderId == provider.id
                    val isProviderSelected = defaultModelProvider == provider.id
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .pressable {
                                expandedProviderId = if (isExpanded) null else provider.id
                            }
                            .background(if (isProviderSelected && !isExpanded) c.bg else Color.Transparent)
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
                                val isSelected = defaultModelProvider == provider.id && defaultModelId == modelId
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .pressable {
                                            scope.launch { prefs.saveDefaultModel(provider.id, modelId) }
                                            showModelPicker = false
                                            expandedProviderId = null
                                        }
                                        .background(if (isSelected) c.bg else Color.Transparent)
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

        Spacer(Modifier.height(22.dp))

        // ── ABOUT ──
        SectionHeader("ABOUT")
        SettingsCard {
            SettingsRow("Version", "0.6.0")
        }

        Spacer(Modifier.height(28.dp))

        // ── DISCONNECT ──
        Box(
            Modifier
                .fillMaxWidth()
                .pressable {
                    if (mode == ConnectionMode.LOCAL_BUNDLED) stopBundledService()
                    scope.launch {
                        prefs.saveConnectionMode(ConnectionMode.LAN)
                        prefs.setSetupDone(false)
                    }
                    onDisconnect()
                }
                .padding(horizontal = 22.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Disconnect", style = OcType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Color(0xFFC44D4D))
        }

        Spacer(Modifier.height(20.dp))

        // ── Bottom ──
        Row(
            Modifier.fillMaxWidth().padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text("opencode", style = OcType.mono, color = c.ink4)
            Text(" · ", style = OcType.mono, color = c.ink4)
            Text(activeEndpoint?.displayUrl ?: "loading", style = OcType.mono, color = c.ink4)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    val c = LocalOcColors.current
    MonoLabel(text, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    val c = LocalOcColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp)
            .background(c.surface, RoundedCornerShape(14.dp))
            .padding(vertical = 2.dp),
    ) {
        content()
    }
}

@Composable
private fun SettingsRow(label: String, value: String) {
    val c = LocalOcColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = OcType.body,
            color = c.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(min = 104.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            value,
            style = OcType.mono,
            color = c.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun EditableSettingsRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = LocalOcColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = OcType.body,
            color = c.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(min = 104.dp),
        )
        Spacer(Modifier.width(14.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            textStyle = OcType.mono.copy(color = c.ink2, textAlign = TextAlign.End),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    if (value.isBlank()) {
                        Text("—", style = OcType.mono, color = c.ink4, textAlign = TextAlign.End)
                    }
                    inner()
                }
            },
        )
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
private fun InlineAction(label: String, onClick: () -> Unit) {
    val c = LocalOcColors.current
    Text(
        label,
        style = OcType.monoStrong.copy(fontSize = 11.sp),
        color = c.accent,
        modifier = Modifier.pressable { onClick() },
    )
}

@Composable
private fun ThemeOption(label: String, selected: Boolean, c: com.opencode.android.ui.theme.OcColors, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .then(if (selected) Modifier.background(c.raised) else Modifier)
            .pressable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = OcType.monoStrong.copy(fontSize = 12.sp), color = if (selected) c.ink else c.ink3)
    }
}

private fun ConnectionMode.shortLabel(): String =
    when (this) {
        ConnectionMode.LAN -> "LAN"
        ConnectionMode.LOCAL_BUNDLED -> "Local"
        ConnectionMode.LOCAL_EXTERNAL -> "External"
    }

@Composable
private fun ModeCycleButton(
    label: String,
    c: com.opencode.android.ui.theme.OcColors,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .widthIn(min = 76.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(c.surface2)
            .pressable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = OcType.monoStrong.copy(fontSize = 12.sp),
            color = c.accent,
        )
    }
}

@Composable
private fun ProviderPresetRow(
    preset: LocalProviderPreset,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalOcColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .pressable { onClick() }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Provider", style = OcType.body, color = c.ink, modifier = Modifier.widthIn(min = 104.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(
                preset.displayName,
                style = OcType.mono,
                color = c.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
            Text(
                preset.apiBaseUrl.shortEndpoint(),
                style = OcType.mono.copy(fontSize = 10.sp),
                color = c.ink4,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(if (expanded) "−" else "+", style = OcType.mono, color = c.ink4)
    }
}

@Composable
private fun ProviderPresetOption(
    preset: LocalProviderPreset,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalOcColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) c.bg else Color.Transparent)
            .pressable { onClick() }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    preset.displayName,
                    style = OcType.monoStrong.copy(fontSize = 12.sp),
                    color = if (selected) c.accent else c.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (preset.defaultEnabled) {
                    Spacer(Modifier.width(8.dp))
                    Text("Default", style = OcType.mono.copy(fontSize = 10.sp), color = c.ink4)
                }
            }
            Text(
                preset.apiBaseUrl.shortEndpoint(),
                style = OcType.mono.copy(fontSize = 10.sp),
                color = c.ink4,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            if (selected) "Selected" else "Use",
            style = OcType.mono.copy(fontSize = 11.sp),
            color = if (selected) c.accent else c.ink4,
        )
    }
}

@Composable
private fun ProviderModelRow(
    model: String,
    expanded: Boolean,
    loading: Boolean,
    hasCandidates: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalOcColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .pressable { onClick() }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Model", style = OcType.body, color = c.ink, modifier = Modifier.widthIn(min = 104.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            when {
                model.isNotBlank() -> model
                loading -> "Fetching..."
                hasCandidates -> "Choose..."
                else -> "Choose..."
            },
            style = OcType.mono,
            color = if (model.isNotBlank()) c.ink2 else c.ink4,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(if (expanded) "−" else "+", style = OcType.mono, color = c.ink4)
    }
}

@Composable
private fun ProviderModelCandidatePicker(
    models: List<String>,
    selectedModel: String,
    loading: Boolean,
    onRetry: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val c = LocalOcColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Models", style = OcType.mono.copy(fontSize = 11.sp), color = c.ink4, modifier = Modifier.weight(1f))
            Text(
                if (loading) "fetching..." else "Retry",
                style = OcType.mono.copy(fontSize = 11.sp),
                color = if (loading) c.ink4 else c.accent,
                modifier = Modifier.pressable(enabled = !loading) { onRetry() },
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (models.isEmpty()) {
                Text(
                    if (loading) "Loading models..." else "No models yet",
                    style = OcType.mono.copy(fontSize = 11.sp),
                    color = c.ink4,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            models.forEach { model ->
                val selected = model == selectedModel
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pressable { onSelect(model) }
                        .background(if (selected) c.accent.copy(alpha = 0.12f) else c.surface2, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        model,
                        style = OcType.mono.copy(fontSize = 11.sp),
                        color = if (selected) c.accent else c.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (selected) "selected" else "select",
                        style = OcType.mono.copy(fontSize = 10.sp),
                        color = if (selected) c.accent else c.ink4,
                    )
                }
            }
        }
    }
}

private fun String.shortEndpoint(): String =
    removePrefix("https://")
        .removePrefix("http://")
        .trimEnd('/')

@Composable
private fun ProviderApiKeyRow(
    hasSavedKey: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    val c = LocalOcColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "API Key",
            style = OcType.body,
            color = c.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(min = 104.dp),
        )
        Spacer(Modifier.width(14.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            cursorBrush = SolidColor(c.accent),
            visualTransformation = PasswordVisualTransformation(),
            textStyle = OcType.mono.copy(color = c.ink2, textAlign = TextAlign.End),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    if (value.isBlank()) {
                        Text(
                            if (hasSavedKey) "Saved" else "—",
                            style = OcType.mono,
                            color = if (hasSavedKey) c.ink2 else c.ink4,
                            textAlign = TextAlign.End,
                        )
                    }
                    inner()
                }
            },
        )
        if (hasSavedKey) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.pressable { onClear() }.padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text("Clear", style = OcType.mono.copy(fontSize = 11.sp), color = c.accent)
            }
        }
    }
}

@Composable
private fun LocalAction(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = LocalOcColors.current
    Box(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (enabled) c.surface2 else c.bg)
            .pressable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = OcType.monoStrong.copy(fontSize = 11.sp), color = if (enabled) c.accent else c.ink4)
    }
}

private data class McpRowDraft(
    val name: String = "",
    val url: String = "",
    val token: String = "",
    val hasSavedToken: Boolean = false,
    val source: String = McpConfigSource.APP,
)

@Composable
private fun McpServerEditor(
    row: McpRowDraft,
    fromAgent: Boolean,
    onChange: (McpRowDraft) -> Unit,
    onRemove: () -> Unit,
) {
    val c = LocalOcColors.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            McpField(row.name, "name", Modifier.weight(1f)) { onChange(row.copy(name = it)) }
            if (fromAgent) {
                Spacer(Modifier.width(6.dp))
                Text("agent", style = OcType.mono.copy(fontSize = 10.sp), color = c.accent)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "✕",
                style = OcType.monoStrong.copy(fontSize = 13.sp),
                color = c.ink4,
                modifier = Modifier.pressable { onRemove() }.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        McpField(row.url, "https://mcp.example.com/sse", Modifier.fillMaxWidth()) { onChange(row.copy(url = it)) }
        BasicTextField(
            value = row.token,
            onValueChange = { onChange(row.copy(token = it)) },
            singleLine = true,
            cursorBrush = SolidColor(c.accent),
            visualTransformation = PasswordVisualTransformation(),
            textStyle = OcType.mono.copy(color = c.ink2, fontSize = 12.sp),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (row.token.isBlank()) {
                    Text(
                        if (row.hasSavedToken) "token: saved (leave blank to keep)" else "bearer token (optional)",
                        style = OcType.mono.copy(fontSize = 12.sp),
                        color = c.ink4,
                    )
                }
                inner()
            },
        )
    }
}

@Composable
private fun McpField(
    value: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    val c = LocalOcColors.current
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        cursorBrush = SolidColor(c.accent),
        textStyle = OcType.mono.copy(color = c.ink2, fontSize = 12.sp),
        modifier = modifier,
        decorationBox = { inner ->
            if (value.isBlank()) {
                Text(placeholder, style = OcType.mono.copy(fontSize = 12.sp), color = c.ink4)
            }
            inner()
        },
    )
}
