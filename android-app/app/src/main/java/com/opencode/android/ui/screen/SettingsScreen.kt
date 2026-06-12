package com.opencode.android.ui.screen

import android.annotation.SuppressLint
import android.app.Activity
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
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.opencode.android.R
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
import com.opencode.android.data.model.sanitizeLocalWorkspaceName
import com.opencode.android.data.model.validate
import com.opencode.android.data.model.Provider
import com.opencode.android.data.model.appLocalWorkspaceProfile
import com.opencode.android.data.model.safLocalWorkspaceProfile
import com.opencode.android.data.repository.AppearanceRepository
import com.opencode.android.data.repository.PreferencesRepository
import com.opencode.android.runtime.LegacyRuntimeCompanion
import com.opencode.android.runtime.RuntimeCompanionClient
import com.opencode.android.ui.component.Hairline
import com.opencode.android.ui.component.LocalWorkspacePicker
import com.opencode.android.ui.component.rememberSafFolderPicker
import com.opencode.android.workspace.WorkspaceDisplay
import com.opencode.android.ui.component.pressable
import com.opencode.android.ui.theme.LocalOcColors
import com.opencode.android.ui.theme.OcAccent
import com.opencode.android.ui.screen.settings.*
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
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun SettingsScreen(onBack: () -> Unit, onDisconnect: () -> Unit) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
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
    var openCodeVersion by remember { mutableStateOf<String?>(null) }
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
    val localWorkspaceProfiles by prefs.localWorkspaceProfiles.collectAsState(
        initial = listOf(appLocalWorkspaceProfile("default", lastUsedAt = 0)),
    )
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
            localStatus = context.getString(R.string.status_linked_external_folder)
        }
    }
    val currentLocalWorkspaceProfile = remember(localWorkspaceDraft, localTreeUriDraft) {
        if (localTreeUriDraft.isBlank()) {
            appLocalWorkspaceProfile(localWorkspaceDraft, lastUsedAt = 0)
        } else {
            safLocalWorkspaceProfile(localWorkspaceDraft, localTreeUriDraft, lastUsedAt = 0)
        }
    }
    val localWorkspaceCandidates = remember(localWorkspaceProfiles, currentLocalWorkspaceProfile) {
        (localWorkspaceProfiles + currentLocalWorkspaceProfile)
            .distinctBy { it.id }
    }
    var showLocalWorkspacePicker by remember { mutableStateOf(false) }
    var bundledPortDraft by remember(localProfile.bundledPort) { mutableStateOf(localProfile.bundledPort.toString()) }
    var externalPortDraft by remember(localProfile.externalPort) { mutableStateOf(localProfile.externalPort.toString()) }
    val bundledPortValid = bundledPortDraft.toIntOrNull()?.let { it in 1..65535 } == true
    val externalPortValid = externalPortDraft.toIntOrNull()?.let { it in 1..65535 } == true
    val localValidationMessage = when {
        !bundledPortValid -> context.getString(R.string.status_bundled_port_invalid)
        !externalPortValid -> context.getString(R.string.status_external_port_invalid)
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
                .onFailure { workspaceError = it.message ?: context.getString(R.string.status_failed_load_workspaces) }
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
                    .onSuccess { health ->
                        openCodeVersion = health.version
                        localStatus = context.getString(R.string.status_health_version, health.version)
                    }
                    .onFailure { localStatus = it.message ?: context.getString(R.string.status_unavailable) }
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
        localStatus = if (restart) context.getString(R.string.status_runtime_restarting) else context.getString(R.string.status_runtime_starting)
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
                localStatus = context.getString(R.string.status_runtime_ready)
                runtimeStatusVersion++
            }
            .onFailure { localStatus = it.message ?: if (restart) context.getString(R.string.status_runtime_restart_failed) else context.getString(R.string.status_runtime_start_failed) }
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
            .onSuccess { localStatus = context.getString(R.string.status_runtime_stop_requested) }
            .onFailure { localStatus = it.message ?: context.getString(R.string.status_runtime_stop_failed) }
        runtimeStatusVersion++
    }

    fun requestRuntimeBatteryExemption() {
        runtimeClient.requestBatteryExemption()
            .onSuccess { localStatus = context.getString(R.string.status_battery_prompt_opened) }
            .onFailure { localStatus = it.message ?: context.getString(R.string.status_battery_prompt_failed) }
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
                val selectedModelId = nextProfile.modelIds.firstOrNull()
                if (nextProfile.enabled && !selectedModelId.isNullOrBlank()) {
                    prefs.saveDefaultModel(LocalProviderDefaults.PROVIDER_ID, selectedModelId)
                }
                val restartApplied = if (mode == ConnectionMode.LOCAL_BUNDLED && bundledAvailable) {
                    runBundledServiceCommand(restart = true)
                } else {
                    true
                }
                providerApiKeyDraft = ""
                providerHasSavedKey = nextProfile.hasApiKey
                providerStatus = if (restartApplied) {
                    context.getString(R.string.status_saved)
                } else {
                    context.getString(R.string.status_saved_runtime_not_ready)
                }
            } catch (e: Exception) {
                providerStatus = e.message ?: context.getString(R.string.status_failed_save_provider)
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
                context.getString(R.string.status_api_key_cleared)
            } else {
                context.getString(R.string.status_api_key_cleared_not_ready)
            }
        }
    }

    fun refreshMcpAndPluginsFromAgent() {
        scope.launch {
            try {
                val result = prefs.syncMcpAndPluginsFromNative()
                mcpStatus = when {
                    result.importedMcpNames.isNotEmpty() || result.importedPluginSpecs.isNotEmpty() ->
                        context.getString(R.string.status_synced_from_agent, result.importedMcpNames.joinToString(), result.importedPluginSpecs.joinToString())
                    result.changed -> context.getString(R.string.status_sync_with_native)
                    else -> context.getString(R.string.status_sync_up_to_date)
                }
            } catch (e: Exception) {
                mcpStatus = e.message ?: context.getString(R.string.status_sync_failed)
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
                mcpStatus = context.getString(R.string.status_mcp_names_must_unique)
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
                mcpStatus = if (applied) context.getString(R.string.status_saved) else context.getString(R.string.status_saved_runtime_not_ready)
            } catch (e: Exception) {
                mcpStatus = e.message ?: context.getString(R.string.status_failed_save_mcp)
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
            localStatus = context.getString(R.string.status_saved)
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
            showProviderModelPicker = false
            providerStatus = null
            showProviderPresets = false
        }
    }

    suspend fun fetchProviderModelsNow(auto: Boolean) {
        if (isFetchingProviderModels) return
        isFetchingProviderModels = true
        providerStatus = if (auto) context.getString(R.string.status_auto_fetching_models) else context.getString(R.string.status_fetching_models)
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
                showProviderModelPicker = true
                providerStatus = context.getString(R.string.status_fetched_models, result.models.size, result.sourceLabel)
            }
            .onFailure { providerStatus = context.getString(R.string.status_model_fetch_failed, it.message ?: "unknown") }
        isFetchingProviderModels = false
    }

    fun fetchProviderModels() {
        scope.launch {
            fetchProviderModelsNow(auto = false)
        }
    }

    fun fetchProviderModelsAfterInput() {
        if (providerApiKeyDraft.isBlank() && !providerHasSavedKey) return
        if (providerEnabledDraft) fetchProviderModels()
    }

    fun selectProviderModel(modelId: String) {
        providerModelsDraft = modelId
        showProviderModelPicker = false
        providerStatus = context.getString(R.string.status_model_selected)
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
            // Fetch health to populate OpenCode version in About section
            api.health().onSuccess { openCodeVersion = it.version }
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
                    mcpStatus = context.getString(R.string.status_imported_from_agent, result.importedMcpNames.joinToString(), result.importedPluginSpecs.joinToString())
                }
            }
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
            Text(stringResource(R.string.settings_title), style = OcType.titleL, color = c.ink)
        }

        Spacer(Modifier.height(8.dp))

        // ── MODE ──
        SectionHeader(stringResource(R.string.settings_section_mode))
        SettingsCard {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_label_connection), style = OcType.body, color = c.ink, modifier = Modifier.weight(1f))
                ModeCycleButton(label = mode.shortLabel(), c = c) { cycleConnectionMode() }
            }
        }

        if (mode == ConnectionMode.LAN) {
            Spacer(Modifier.height(10.dp))
            SectionHeader(stringResource(R.string.settings_section_server))
            SettingsCard {
                EditableSettingsRow(
                    label = stringResource(R.string.settings_label_host),
                    value = lanHostDraft,
                    onValueChange = { lanHostDraft = it },
                )
                Hairline()
                EditableSettingsRow(
                    label = stringResource(R.string.settings_label_port),
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
                        stringResource(R.string.settings_label_password),
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
                                        if (lanHasSavedPassword) stringResource(R.string.settings_value_saved) else stringResource(R.string.settings_placeholder_empty),
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
                            Text(stringResource(R.string.settings_action_clear), style = OcType.mono.copy(fontSize = 11.sp), color = c.accent)
                        }
                    }
                }
            }
        }

        if (mode != ConnectionMode.LAN) {
            Spacer(Modifier.height(10.dp))
            SettingsCard {
                SettingsRow(stringResource(R.string.settings_label_runtime), if (mode == ConnectionMode.LOCAL_BUNDLED) stringResource(R.string.settings_value_bundled) else stringResource(R.string.settings_value_external))
                Hairline()
                EditableSettingsRow(
                    label = stringResource(R.string.settings_label_bundled_port),
                    value = bundledPortDraft,
                    keyboardType = KeyboardType.Number,
                    onValueChange = { bundledPortDraft = it.filter(Char::isDigit).take(5) },
                )
                Hairline()
                EditableSettingsRow(
                    label = stringResource(R.string.settings_label_external_port),
                    value = externalPortDraft,
                    keyboardType = KeyboardType.Number,
                    onValueChange = { externalPortDraft = it.filter(Char::isDigit).take(5) },
                )
                Hairline()
                EditableSettingsRow(
                    label = if (mode == ConnectionMode.LOCAL_BUNDLED) stringResource(R.string.settings_label_workspace) else stringResource(R.string.settings_label_directory),
                    value = localWorkspaceDraft,
                    onValueChange = {
                        localWorkspaceDraft = if (mode == ConnectionMode.LOCAL_BUNDLED) sanitizeLocalWorkspaceName(it) else it
                    },
                    trailing = if (mode == ConnectionMode.LOCAL_BUNDLED) {
                        {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                InlineAction(stringResource(R.string.settings_action_browse)) { pickExternalFolder() }
                                InlineAction(if (showLocalWorkspacePicker) stringResource(R.string.settings_action_hide) else stringResource(R.string.settings_action_app)) {
                                    showLocalWorkspacePicker = !showLocalWorkspacePicker
                                }
                            }
                        }
                    } else null,
                )
                if (mode == ConnectionMode.LOCAL_BUNDLED && localTreeUriDraft.isNotBlank()) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                        Text(
                            stringResource(R.string.settings_external_folder_info),
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
                                profiles = localWorkspaceCandidates,
                                selectedId = currentLocalWorkspaceProfile.id,
                                onSelect = { profile ->
                                    localWorkspaceDraft = profile.name
                                    localTreeUriDraft = profile.treeUri
                                    showLocalWorkspacePicker = false
                                    scope.launch {
                                        prefs.saveLocalProfile(
                                            localProfile.copy(
                                                workspacePath = profile.name,
                                                workspaceTreeUri = profile.treeUri,
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
                SettingsRow(stringResource(R.string.settings_label_endpoint), activeEndpoint?.displayUrl ?: stringResource(R.string.settings_value_loading))
                Hairline()
                SettingsRow(stringResource(R.string.settings_label_runtime_apk), runtimeStatus.message)
                if (legacyCompanionInstalled) {
                    Hairline()
                    Text(
                        stringResource(R.string.settings_legacy_apk_warning, LegacyRuntimeCompanion.PACKAGE_NAME),
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
                        LocalAction(stringResource(R.string.settings_action_apply), enabled = localValidationMessage == null, modifier = Modifier.weight(1f)) { saveLocalDrafts() }
                        LocalAction(stringResource(R.string.settings_action_health), enabled = true, modifier = Modifier.weight(1f)) { checkActiveHealth() }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LocalAction(
                            stringResource(R.string.settings_action_start),
                            enabled = mode == ConnectionMode.LOCAL_BUNDLED && bundledAvailable,
                            modifier = Modifier.weight(1f),
                        ) { startBundledService() }
                        LocalAction(
                            stringResource(R.string.settings_action_stop),
                            enabled = mode == ConnectionMode.LOCAL_BUNDLED,
                            modifier = Modifier.weight(1f),
                        ) { stopBundledService() }
                        LocalAction(
                            stringResource(R.string.settings_action_battery),
                            enabled = mode == ConnectionMode.LOCAL_BUNDLED && bundledAvailable,
                            modifier = Modifier.weight(1f),
                        ) { requestRuntimeBatteryExemption() }
                    }
                    Text(
                        localValidationMessage ?: localStatus ?: stringResource(R.string.settings_placeholder_empty),
                        style = OcType.mono.copy(fontSize = 11.sp),
                        color = if (localValidationMessage == null) c.ink3 else c.accent,
                    )
                }
            }
        }

        if (mode == ConnectionMode.LOCAL_BUNDLED) {
            Spacer(Modifier.height(22.dp))
            SettingsLocalProviderSection(
                providerEnabledDraft = providerEnabledDraft,
                onToggleEnabled = { providerEnabledDraft = !providerEnabledDraft },
                selectedProviderPreset = selectedProviderPreset,
                selectedPresetId = providerPresetIdDraft,
                showProviderPresets = showProviderPresets,
                onTogglePresets = { showProviderPresets = !showProviderPresets },
                onSelectPreset = { selectProviderPreset(it) },
                providerBaseUrlDraft = providerBaseUrlDraft,
                onBaseUrlChange = {
                    if (providerActiveBaseUrlDraft.trim() == providerBaseUrlDraft.trim()) {
                        providerActiveBaseUrlDraft = it
                    }
                    providerBaseUrlDraft = it
                },
                providerCodingBaseUrlDraft = providerCodingBaseUrlDraft,
                onCodingBaseUrlChange = {
                    if (providerActiveBaseUrlDraft.trim() == providerCodingBaseUrlDraft.trim()) {
                        providerActiveBaseUrlDraft = it
                    }
                    providerCodingBaseUrlDraft = it
                },
                providerApiKeyDraft = providerApiKeyDraft,
                onApiKeyChange = { providerApiKeyDraft = it },
                onApiKeyEditingComplete = { fetchProviderModelsAfterInput() },
                providerHasSavedKey = providerHasSavedKey,
                onClearKey = { clearLocalProviderKey() },
                selectedProviderModel = selectedProviderModel,
                showProviderModelPicker = showProviderModelPicker,
                isFetchingProviderModels = isFetchingProviderModels,
                providerModelCandidates = providerModelCandidates,
                onModelRowClick = {
                    showProviderModelPicker = !showProviderModelPicker
                    if (providerModelCandidates.isEmpty() && !isFetchingProviderModels) fetchProviderModels()
                },
                onFetchModels = { fetchProviderModels() },
                onModelChange = { providerModelsDraft = it },
                onSelectModel = { selectProviderModel(it) },
                providerValidationMessage = providerValidationMessage,
                providerStatus = providerStatus,
                onApply = { applyLocalProvider() },
            )
        }

        if (mode == ConnectionMode.LOCAL_BUNDLED) {
            Spacer(Modifier.height(22.dp))
            SettingsMcpSection(
                mcpRows = mcpRows,
                onMcpRowChange = { index, updated -> mcpRows = mcpRows.toMutableList().also { it[index] = updated } },
                onMcpRowRemove = { index -> mcpRows = mcpRows.toMutableList().also { it.removeAt(index) } },
                onAddMcpRow = { mcpRows = mcpRows + McpRowDraft() },
                pluginsDraft = pluginsDraft,
                onPluginsChange = { pluginsDraft = it },
                savedAgentPlugins = savedAgentPlugins,
                defaultPluginsDraft = defaultPluginsDraft,
                onToggleDefaultPlugins = { defaultPluginsDraft = !defaultPluginsDraft },
                onRefreshFromAgent = { refreshMcpAndPluginsFromAgent() },
                onApply = { applyMcpAndPlugins() },
                mcpStatus = mcpStatus,
            )
        }

        if (mode == ConnectionMode.LAN || mode == ConnectionMode.LOCAL_EXTERNAL) {
            Spacer(Modifier.height(22.dp))
            SectionHeader(stringResource(R.string.settings_section_provider))
            SettingsCard {
                if (providers.isEmpty()) {
                    SettingsRow(stringResource(R.string.settings_label_status), stringResource(R.string.status_no_configured_providers))
                } else {
                    providers.forEachIndexed { index, provider ->
                        if (index > 0) Hairline()
                        SettingsRow(
                            label = provider.name.ifBlank { provider.id },
                            value = provider.models.keys.joinToString(", ").ifBlank { stringResource(R.string.settings_placeholder_empty) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        // ── CONNECTION ──
        SectionHeader(stringResource(R.string.settings_section_connection))
        SettingsCard {
            SettingsRow(stringResource(R.string.settings_label_endpoint), activeEndpoint?.displayUrl ?: stringResource(R.string.settings_value_loading))
            Hairline()
            SettingsRow(stringResource(R.string.settings_label_mode), mode.name.lowercase().replace('_', ' '))
            Hairline()
            SettingsRow(stringResource(R.string.settings_label_password), if (activeEndpoint?.password.isNullOrBlank()) stringResource(R.string.settings_placeholder_empty) else stringResource(R.string.settings_placeholder_password))
            Hairline()
            SettingsRow(
                if (mode == ConnectionMode.LAN) stringResource(R.string.settings_label_directory) else stringResource(R.string.settings_label_workspace),
                activeEndpoint?.let { WorkspaceDisplay.endpointDirectoryLabel(it) } ?: stringResource(R.string.settings_placeholder_empty),
            )
        }

        Spacer(Modifier.height(22.dp))

        // ── WORKSPACE ──
        if (mode == ConnectionMode.LAN) {
            SettingsWorkspaceSection(
                workspaces = workspaces,
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
            )

            Spacer(Modifier.height(22.dp))
        }

        // ── APPEARANCE ──
        SectionHeader(stringResource(R.string.settings_section_appearance))

        // Theme segmented control
        SettingsCard {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_label_theme), style = OcType.body, color = c.ink, modifier = Modifier.weight(1f))
                // Light / Dark / System segmented
                Row(
                    Modifier
                        .background(c.surface2, RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    ThemeOption(stringResource(R.string.settings_value_light), darkTheme == false, c) {
                        scope.launch { appearance.setDark(false) }
                    }
                    ThemeOption(stringResource(R.string.settings_value_dark), darkTheme == true, c) {
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
                Text(stringResource(R.string.settings_label_accent), style = OcType.body, color = c.ink, modifier = Modifier.weight(1f))
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

        Spacer(Modifier.height(10.dp))

        // ── LANGUAGE ──
        SectionHeader(stringResource(R.string.settings_section_language))
        SettingsCard {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.settings_label_language),
                    style = OcType.body,
                    color = c.ink,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    Modifier
                        .background(c.surface2, RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val currentTag by appearance.languageTag.collectAsState(initial = "")
                    val effectiveTag = when {
                        currentTag.isNotEmpty() -> currentTag
                        locale.language == "zh" -> "zh-CN"
                        else -> "en"
                    }
                    AppearanceRepository.LANGUAGE_OPTIONS.drop(1).forEach { (tag, label) ->
                        ThemeOption(
                            label = label,
                            selected = effectiveTag == tag,
                            c = c,
                        ) {
                            scope.launch {
                                appearance.setLanguageTag(tag)
                                // Recreate activity to apply locale change
                                (context as? Activity)?.recreate()
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        // ── AGENT ──
        SettingsAgentSection(
            defaultAgent = defaultAgent,
            availableAgents = availableAgents,
            onCycleAgent = {
                if (availableAgents.size < 2) return@SettingsAgentSection
                val currentIdx = availableAgents.indexOfFirst { it.name == defaultAgent }
                val nextIdx = if (currentIdx >= 0) (currentIdx + 1) % availableAgents.size else 0
                scope.launch { prefs.saveDefaultAgent(availableAgents[nextIdx].name) }
            },
            defaultModelProvider = defaultModelProvider,
            defaultModelId = defaultModelId,
            providers = providers,
            showModelPicker = showModelPicker,
            onToggleModelPicker = {
                if (providers.isNotEmpty()) showModelPicker = !showModelPicker
            },
            expandedProviderId = expandedProviderId,
            onToggleProvider = { expandedProviderId = it },
            onSelectModel = { providerId, modelId ->
                scope.launch { prefs.saveDefaultModel(providerId, modelId) }
                showModelPicker = false
                expandedProviderId = null
            },
        )

        Spacer(Modifier.height(22.dp))

        // ── ABOUT ──
        SectionHeader(stringResource(R.string.settings_section_about))
        SettingsCard {
            val packageInfo = remember {
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            SettingsRow(stringResource(R.string.settings_label_version), packageInfo.versionName ?: stringResource(R.string.settings_placeholder_empty))
            Hairline()
            SettingsRow(stringResource(R.string.settings_label_opencode_version), openCodeVersion ?: stringResource(R.string.settings_placeholder_empty))
            Hairline()
            SettingsRow(stringResource(R.string.settings_label_runtime_mode), when (mode) {
                ConnectionMode.LAN -> stringResource(R.string.setup_mode_lan)
                ConnectionMode.LOCAL_BUNDLED -> stringResource(R.string.settings_value_bundled)
                ConnectionMode.LOCAL_EXTERNAL -> stringResource(R.string.settings_value_external)
            })
            Hairline()
            SettingsRow(stringResource(R.string.settings_label_device), "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
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
            Text(stringResource(R.string.settings_action_disconnect), style = OcType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Color(0xFFC44D4D))
        }

        Spacer(Modifier.height(20.dp))

        // ── Bottom ──
        Row(
            Modifier.fillMaxWidth().padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text("opencode", style = OcType.mono, color = c.ink4)
            Text(" · ", style = OcType.mono, color = c.ink4)
            Text(activeEndpoint?.displayUrl ?: stringResource(R.string.settings_value_loading), style = OcType.mono, color = c.ink4)
        }
    }
}
