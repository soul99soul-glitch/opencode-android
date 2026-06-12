package com.opencode.android.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.opencode.android.data.model.ActiveEndpoint
import com.opencode.android.data.model.ConnectionMode
import com.opencode.android.data.model.LanProfile
import com.opencode.android.data.model.LocalProfile
import com.opencode.android.data.model.LocalWorkspaceProfile
import com.opencode.android.data.model.LocalProviderDefaults
import com.opencode.android.data.model.LocalProviderProfile
import com.opencode.android.data.model.LocalProviderPresets
import com.opencode.android.data.model.McpServerConfig
import com.opencode.android.data.model.ServerConfig
import com.opencode.android.data.model.parseModelIds
import com.opencode.android.data.model.parsePluginSpecs
import com.opencode.android.data.model.sanitizeLocalWorkspaceName
import com.opencode.android.data.model.validate
import com.opencode.android.workspace.WorkspaceDisplay
import com.opencode.android.workspace.WorkspaceResolver
import com.opencode.android.service.OpenCodeNativeConfigSync
import com.opencode.android.data.model.toActiveEndpoint
import com.opencode.android.data.model.toLanProfile
import com.opencode.android.data.model.toServerConfig
import com.opencode.android.data.model.appLocalWorkspaceProfile
import com.opencode.android.data.model.safLocalWorkspaceProfile
import com.opencode.android.runtime.RuntimeContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.util.Base64

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("opencode")

class PreferencesRepository(context: Context) {

    private val appContext = context.applicationContext
    private val secure = SecureStringStore(appContext)
    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val MODE = stringPreferencesKey("connection_mode")
        val LAN_HOST = stringPreferencesKey("lan_host")
        val LAN_PORT = intPreferencesKey("lan_port")
        val LAN_DIRECTORY = stringPreferencesKey("lan_directory")
        val LOCAL_BUNDLED_PORT = intPreferencesKey("local_bundled_port")
        val LOCAL_EXTERNAL_PORT = intPreferencesKey("local_external_port")
        val LEGACY_LOCAL_PORT = intPreferencesKey("local_port")
        val LOCAL_WORKSPACE = stringPreferencesKey("local_workspace")
        val LOCAL_WORKSPACE_TREE_URI = stringPreferencesKey("local_workspace_tree_uri")
        val LOCAL_WORKSPACE_NAMES = stringSetPreferencesKey("local_workspace_names")
        val LOCAL_WORKSPACE_PROFILES = stringPreferencesKey("local_workspace_profiles")
        val LOCAL_AUTO_START = booleanPreferencesKey("local_auto_start")
        val HOST = stringPreferencesKey("host")
        val PORT = intPreferencesKey("port")
        val PASSWORD = stringPreferencesKey("password")
        val DIRECTORY = stringPreferencesKey("directory")
        val IS_SETUP_DONE = booleanPreferencesKey("is_setup_done")
        val DEFAULT_AGENT = stringPreferencesKey("default_agent")
        val DEFAULT_MODEL_PROVIDER = stringPreferencesKey("default_model_provider")
        val DEFAULT_MODEL_ID = stringPreferencesKey("default_model_id")
        val PINNED_WORKSPACES = stringSetPreferencesKey("pinned_workspace_paths")
        val LEGACY_FAVORITE_WORKSPACES = stringSetPreferencesKey("favorite_workspace_paths")
        val LOCAL_PROVIDER_ENABLED = booleanPreferencesKey("local_provider_enabled")
        val LOCAL_PROVIDER_PRESET_ID = stringPreferencesKey("local_provider_preset_id")
        val LOCAL_PROVIDER_ID = stringPreferencesKey("local_provider_id")
        val LOCAL_PROVIDER_NAME = stringPreferencesKey("local_provider_name")
        val LOCAL_PROVIDER_BASE_URL = stringPreferencesKey("local_provider_base_url")
        val LOCAL_PROVIDER_CODING_BASE_URL = stringPreferencesKey("local_provider_coding_base_url")
        val LOCAL_PROVIDER_ACTIVE_BASE_URL = stringPreferencesKey("local_provider_active_base_url")
        val LOCAL_PROVIDER_MODELS = stringPreferencesKey("local_provider_models")
        val LOCAL_PROVIDER_HAS_API_KEY = booleanPreferencesKey("local_provider_has_api_key")
        val LOCAL_MCP_SERVERS = stringPreferencesKey("local_mcp_servers")
        val LOCAL_PLUGINS = stringPreferencesKey("local_plugins")
        val LOCAL_AGENT_PLUGINS = stringPreferencesKey("local_agent_plugins")
        val LOCAL_DEFAULT_PLUGINS = booleanPreferencesKey("local_default_plugins_enabled")

        fun localProviderEnabled(presetId: String) =
            booleanPreferencesKey("${localProviderPrefix(presetId)}_enabled")

        fun localProviderName(presetId: String) =
            stringPreferencesKey("${localProviderPrefix(presetId)}_name")

        fun localProviderBaseUrl(presetId: String) =
            stringPreferencesKey("${localProviderPrefix(presetId)}_base_url")

        fun localProviderCodingBaseUrl(presetId: String) =
            stringPreferencesKey("${localProviderPrefix(presetId)}_coding_base_url")

        fun localProviderActiveBaseUrl(presetId: String) =
            stringPreferencesKey("${localProviderPrefix(presetId)}_active_base_url")

        fun localProviderModels(presetId: String) =
            stringPreferencesKey("${localProviderPrefix(presetId)}_models")

        fun localProviderHasApiKey(presetId: String) =
            booleanPreferencesKey("${localProviderPrefix(presetId)}_has_api_key")
    }

    private fun defaultLocalWorkspace(): String = "default"

    val connectionMode: Flow<ConnectionMode> = appContext.dataStore.data.map { prefs ->
        prefs[Keys.MODE]
            ?.let { runCatching { ConnectionMode.valueOf(it) }.getOrNull() }
            ?: ConnectionMode.LAN
    }.distinctUntilChanged()

    val lanProfile: Flow<LanProfile> = appContext.dataStore.data.map { prefs ->
        LanProfile(
            host = prefs[Keys.LAN_HOST] ?: prefs[Keys.HOST] ?: "127.0.0.1",
            port = prefs[Keys.LAN_PORT] ?: prefs[Keys.PORT] ?: 4096,
            password = secure.get(SECURE_LAN_PASSWORD).ifBlank { prefs[Keys.PASSWORD] ?: "" },
            directory = prefs[Keys.LAN_DIRECTORY] ?: prefs[Keys.DIRECTORY] ?: "",
        )
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    val localProfile: Flow<LocalProfile> = appContext.dataStore.data.map { prefs ->
        LocalProfile(
            bundledPort = prefs[Keys.LOCAL_BUNDLED_PORT] ?: prefs[Keys.LEGACY_LOCAL_PORT] ?: 4097,
            externalPort = prefs[Keys.LOCAL_EXTERNAL_PORT] ?: 4096,
            workspacePath = sanitizeLocalWorkspaceName(prefs[Keys.LOCAL_WORKSPACE] ?: defaultLocalWorkspace()),
            workspaceTreeUri = prefs[Keys.LOCAL_WORKSPACE_TREE_URI].orEmpty(),
            autoStart = prefs[Keys.LOCAL_AUTO_START] ?: true,
        )
    }.distinctUntilChanged()

    val localWorkspaceProfiles: Flow<List<LocalWorkspaceProfile>> = appContext.dataStore.data.map { prefs ->
        val current = sanitizeLocalWorkspaceName(prefs[Keys.LOCAL_WORKSPACE] ?: defaultLocalWorkspace())
        val currentTreeUri = prefs[Keys.LOCAL_WORKSPACE_TREE_URI].orEmpty()
        val currentProfile = if (currentTreeUri.isBlank()) {
            appLocalWorkspaceProfile(current, lastUsedAt = 0)
        } else {
            safLocalWorkspaceProfile(current, currentTreeUri, lastUsedAt = 0)
        }
        val profiles = decodeLocalWorkspaceProfiles(prefs[Keys.LOCAL_WORKSPACE_PROFILES])
        val saved = prefs[Keys.LOCAL_WORKSPACE_NAMES].orEmpty()
            .map(::sanitizeLocalWorkspaceName)
            .map { appLocalWorkspaceProfile(it, lastUsedAt = 0) }
        (profiles + saved + currentProfile + appLocalWorkspaceProfile(defaultLocalWorkspace(), lastUsedAt = 0))
            .distinctBy { it.id }
            .sortedWith(compareByDescending<LocalWorkspaceProfile> { it.id == currentProfile.id }.thenByDescending { it.lastUsedAt })
            .take(MAX_LOCAL_WORKSPACES)
    }.distinctUntilChanged()

    val localWorkspaceNames: Flow<List<String>> = localWorkspaceProfiles.map { profiles ->
        profiles.map { it.name }.distinct()
    }.distinctUntilChanged()

    val localProviderProfile: Flow<LocalProviderProfile> = appContext.dataStore.data.map { prefs ->
        val activePresetId = inferActiveLocalProviderPresetId(prefs)
        localProviderProfileFromPrefs(prefs, activePresetId, allowLegacyFallback = true)
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    val localMcpServers: Flow<List<McpServerConfig>> = appContext.dataStore.data.map { prefs ->
        decodeMcpServers(prefs[Keys.LOCAL_MCP_SERVERS])
    }.distinctUntilChanged()

    val localPlugins: Flow<String> = appContext.dataStore.data.map { prefs ->
        prefs[Keys.LOCAL_PLUGINS] ?: ""
    }.distinctUntilChanged()

    val defaultPluginsEnabled: Flow<Boolean> = appContext.dataStore.data.map { prefs ->
        prefs[Keys.LOCAL_DEFAULT_PLUGINS] ?: false
    }.distinctUntilChanged()

    val agentOriginatedPlugins: Flow<Set<String>> = appContext.dataStore.data.map { prefs ->
        decodeStringSet(prefs[Keys.LOCAL_AGENT_PLUGINS])
    }.distinctUntilChanged()

    private val localServerPassword: Flow<String> = flow {
        emit(getOrCreateLocalServerPassword())
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    val activeEndpoint: Flow<ActiveEndpoint> = combine(
        connectionMode,
        lanProfile,
        localProfile,
        localServerPassword,
    ) { mode, lan, local, bundledPassword ->
        when (mode) {
            ConnectionMode.LAN -> lan.toActiveEndpoint().copy(workspaceLabel = lan.directory)
            ConnectionMode.LOCAL_BUNDLED -> {
                val endpoint = local.toActiveEndpoint(mode, password = bundledPassword)
                endpoint.copy(
                    directory = WorkspaceResolver.runtimeDirectory(
                        appContext.filesDir,
                        local.workspacePath,
                        local.workspaceTreeUri,
                    ),
                    workspaceLabel = WorkspaceDisplay.bundledLabel(appContext, local),
                )
            }
            ConnectionMode.LOCAL_EXTERNAL -> local.toActiveEndpoint(mode)
        }
    }.distinctUntilChanged()

    val config: Flow<ServerConfig> = lanProfile.map { it.toServerConfig() }

    val isSetupDone: Flow<Boolean> = appContext.dataStore.data.map { prefs ->
        prefs[Keys.IS_SETUP_DONE] ?: false
    }

    suspend fun saveConfig(config: ServerConfig) {
        saveLanProfile(config.toLanProfile())
        saveConnectionMode(ConnectionMode.LAN)
    }

    suspend fun saveConnectionMode(mode: ConnectionMode) {
        appContext.dataStore.edit { prefs ->
            prefs[Keys.MODE] = mode.name
        }
    }

    suspend fun saveLanProfile(profile: LanProfile) {
        if (profile.password.isNotBlank()) {
            val saved = withContext(Dispatchers.IO) {
                secure.put(SECURE_LAN_PASSWORD, profile.password) &&
                    secure.read(SECURE_LAN_PASSWORD).getOrNull() == profile.password
            }
            if (!saved) throw IllegalStateException("Failed to save password securely")
        } else {
            withContext(Dispatchers.IO) { secure.remove(SECURE_LAN_PASSWORD) }
        }
        appContext.dataStore.edit { prefs ->
            prefs[Keys.LAN_HOST] = profile.host
            prefs[Keys.LAN_PORT] = profile.port
            prefs[Keys.LAN_DIRECTORY] = profile.directory
            prefs[Keys.HOST] = profile.host
            prefs[Keys.PORT] = profile.port
            prefs[Keys.DIRECTORY] = profile.directory
            prefs.remove(Keys.PASSWORD)
        }
    }

    suspend fun saveLocalProfile(profile: LocalProfile) {
        val workspaceName = sanitizeLocalWorkspaceName(profile.workspacePath)
        val workspaceProfile = if (profile.workspaceTreeUri.isBlank()) {
            appLocalWorkspaceProfile(workspaceName)
        } else {
            safLocalWorkspaceProfile(workspaceName, profile.workspaceTreeUri)
        }
        appContext.dataStore.edit { prefs ->
            prefs[Keys.LOCAL_BUNDLED_PORT] = profile.bundledPort
            prefs[Keys.LOCAL_EXTERNAL_PORT] = profile.externalPort
            prefs[Keys.LOCAL_WORKSPACE] = workspaceName
            prefs[Keys.LOCAL_WORKSPACE_NAMES] = (prefs[Keys.LOCAL_WORKSPACE_NAMES].orEmpty() + workspaceName)
                .map(::sanitizeLocalWorkspaceName)
                .filter { it.isNotBlank() }
                .toSet()
            val existing = decodeLocalWorkspaceProfiles(prefs[Keys.LOCAL_WORKSPACE_PROFILES])
            prefs[Keys.LOCAL_WORKSPACE_PROFILES] = encodeLocalWorkspaceProfiles(
                (existing.filterNot { it.id == workspaceProfile.id } + workspaceProfile)
                    .sortedByDescending { it.lastUsedAt }
                    .take(MAX_LOCAL_WORKSPACES),
            )
            if (profile.workspaceTreeUri.isBlank()) {
                prefs.remove(Keys.LOCAL_WORKSPACE_TREE_URI)
            } else {
                prefs[Keys.LOCAL_WORKSPACE_TREE_URI] = profile.workspaceTreeUri
            }
            prefs[Keys.LOCAL_AUTO_START] = profile.autoStart
            prefs.remove(Keys.LEGACY_LOCAL_PORT)
        }
    }

    suspend fun migrateLegacySecrets() = withContext(Dispatchers.IO) {
        migrateLegacyLocalProviderKey()
        val legacyPassword = appContext.dataStore.data.map { prefs -> prefs[Keys.PASSWORD] }.first()
        if (legacyPassword.isNullOrBlank()) return@withContext

        val migrated = runCatching {
            val existing = secure.read(SECURE_LAN_PASSWORD).getOrThrow()
            if (existing.isNullOrBlank()) {
                secure.put(SECURE_LAN_PASSWORD, legacyPassword)
            }
            secure.read(SECURE_LAN_PASSWORD).getOrThrow() == legacyPassword
        }.getOrDefault(false)
        if (!migrated) return@withContext

        appContext.dataStore.edit { prefs ->
            prefs.remove(Keys.PASSWORD)
        }
    }

    private suspend fun migrateLegacyLocalProviderKey() {
        val legacy = secure.get(SECURE_LOCAL_PROVIDER_API_KEY)
        if (legacy.isBlank()) return
        val prefs = appContext.dataStore.data.first()
        val presetId = inferActiveLocalProviderPresetId(prefs)
        val keyName = localProviderApiKeyName(presetId)
        if (secure.get(keyName).isBlank()) {
            secure.put(keyName, legacy)
        }
        secure.remove(SECURE_LOCAL_PROVIDER_API_KEY)
    }

    suspend fun setSetupDone(done: Boolean) {
        appContext.dataStore.edit { it[Keys.IS_SETUP_DONE] = done }
    }

    val defaultAgent: Flow<String> = appContext.dataStore.data.map { prefs ->
        prefs[Keys.DEFAULT_AGENT] ?: "build"
    }

    val defaultModelProvider: Flow<String> = appContext.dataStore.data.map { prefs ->
        prefs[Keys.DEFAULT_MODEL_PROVIDER] ?: ""
    }

    val defaultModelId: Flow<String> = appContext.dataStore.data.map { prefs ->
        prefs[Keys.DEFAULT_MODEL_ID] ?: ""
    }

    val pinnedWorkspaces: Flow<Set<String>> = appContext.dataStore.data.map { prefs ->
        prefs[Keys.PINNED_WORKSPACES]
            ?: prefs[Keys.LEGACY_FAVORITE_WORKSPACES]
            ?: emptySet()
    }

    suspend fun saveDefaultAgent(agent: String) {
        appContext.dataStore.edit { it[Keys.DEFAULT_AGENT] = agent }
    }

    suspend fun saveDefaultModel(provider: String, modelId: String) {
        appContext.dataStore.edit {
            it[Keys.DEFAULT_MODEL_PROVIDER] = provider
            it[Keys.DEFAULT_MODEL_ID] = modelId
        }
    }

    suspend fun getLocalProviderApiKey(presetId: String? = null): String = withContext(Dispatchers.IO) {
        val resolvedPresetId = presetId ?: localProviderProfile.first().presetId
        secure.get(localProviderApiKeyName(resolvedPresetId))
    }

    suspend fun getLocalProviderProfile(presetId: String): LocalProviderProfile = withContext(Dispatchers.IO) {
        localProviderProfileFromPrefs(
            prefs = appContext.dataStore.data.first(),
            presetId = presetId,
            allowLegacyFallback = true,
        )
    }

    suspend fun hasLocalProviderApiKey(presetId: String): Boolean = withContext(Dispatchers.IO) {
        hasLocalProviderApiKeyInternal(presetId)
    }

    suspend fun getOrCreateLocalServerPassword(): String = withContext(Dispatchers.IO) {
        val existing = secure.get(SECURE_LOCAL_SERVER_PASSWORD)
        if (existing.isNotBlank()) return@withContext existing

        val generated = generateLocalServerPassword()
        val saved = secure.put(SECURE_LOCAL_SERVER_PASSWORD, generated) &&
            secure.read(SECURE_LOCAL_SERVER_PASSWORD).getOrNull() == generated
        if (!saved) throw IllegalStateException("Failed to save local server password securely")
        generated
    }

    suspend fun saveLocalProviderProfile(
        profile: LocalProviderProfile,
        apiKey: String? = null,
    ) {
        profile.validate()?.let { throw IllegalArgumentException(it) }
        val resolvedPresetId = LocalProviderPresets.byId(profile.presetId)?.id ?: LocalProviderPresets.DEFAULT_ID
        val cleanedApiKey = apiKey?.trim()
        val keyName = localProviderApiKeyName(resolvedPresetId)

        var hasApiKey = withContext(Dispatchers.IO) {
            secure.get(keyName).isNotBlank()
        }
        if (cleanedApiKey != null) {
            if (cleanedApiKey.isBlank()) {
                withContext(Dispatchers.IO) { secure.remove(keyName) }
                hasApiKey = false
            } else {
                val saved = withContext(Dispatchers.IO) {
                    secure.put(keyName, cleanedApiKey) &&
                        secure.read(keyName).getOrNull() == cleanedApiKey
                }
                if (!saved) throw IllegalStateException("Failed to save API key securely")
                hasApiKey = true
            }
        }

        appContext.dataStore.edit { prefs ->
            prefs[Keys.LOCAL_PROVIDER_ENABLED] = profile.enabled
            prefs[Keys.LOCAL_PROVIDER_PRESET_ID] = resolvedPresetId
            prefs[Keys.LOCAL_PROVIDER_ID] = LocalProviderDefaults.PROVIDER_ID
            prefs[Keys.LOCAL_PROVIDER_NAME] = profile.displayName.trim()
            prefs[Keys.LOCAL_PROVIDER_BASE_URL] = profile.baseUrl.trim()
            prefs[Keys.LOCAL_PROVIDER_CODING_BASE_URL] = profile.codingBaseUrl.trim()
            prefs[Keys.LOCAL_PROVIDER_ACTIVE_BASE_URL] = profile.activeBaseUrl.trim().ifBlank { profile.baseUrl.trim() }
            prefs[Keys.LOCAL_PROVIDER_MODELS] = profile.modelIds.joinToString(",")
            prefs[Keys.LOCAL_PROVIDER_HAS_API_KEY] = hasApiKey

            prefs[Keys.localProviderEnabled(resolvedPresetId)] = profile.enabled
            prefs[Keys.localProviderName(resolvedPresetId)] = profile.displayName.trim()
            prefs[Keys.localProviderBaseUrl(resolvedPresetId)] = profile.baseUrl.trim()
            prefs[Keys.localProviderCodingBaseUrl(resolvedPresetId)] = profile.codingBaseUrl.trim()
            prefs[Keys.localProviderActiveBaseUrl(resolvedPresetId)] =
                profile.activeBaseUrl.trim().ifBlank { profile.baseUrl.trim() }
            prefs[Keys.localProviderModels(resolvedPresetId)] = profile.modelIds.joinToString(",")
            prefs[Keys.localProviderHasApiKey(resolvedPresetId)] = hasApiKey
        }
    }

    suspend fun clearLocalProviderApiKey(presetId: String? = null) {
        val resolvedPresetId = presetId ?: localProviderProfile.first().presetId
        withContext(Dispatchers.IO) { secure.remove(localProviderApiKeyName(resolvedPresetId)) }
        appContext.dataStore.edit { prefs ->
            if ((prefs[Keys.LOCAL_PROVIDER_PRESET_ID] ?: LocalProviderPresets.DEFAULT_ID) == resolvedPresetId) {
                prefs[Keys.LOCAL_PROVIDER_HAS_API_KEY] = false
            }
            prefs[Keys.localProviderHasApiKey(resolvedPresetId)] = false
        }
    }

    suspend fun togglePinnedWorkspace(path: String) {
        appContext.dataStore.edit { prefs ->
            val current = prefs[Keys.PINNED_WORKSPACES]
                ?: prefs[Keys.LEGACY_FAVORITE_WORKSPACES]
                ?: emptySet()
            prefs[Keys.PINNED_WORKSPACES] = if (path in current) {
                current - path
            } else {
                current + path
            }
            prefs.remove(Keys.LEGACY_FAVORITE_WORKSPACES)
        }
    }

    suspend fun saveMcpServers(servers: List<McpServerConfig>) {
        val collisions = RuntimeContract.mcpTokenEnvCollisions(servers.map { it.name.trim() }.filter { it.isNotBlank() })
        require(collisions.isEmpty()) {
            "MCP token env collision: " + collisions.values.joinToString { it.joinToString(" / ") }
        }
        val encoded = json.encodeToString(ListSerializer(McpServerConfig.serializer()), servers)
        appContext.dataStore.edit { it[Keys.LOCAL_MCP_SERVERS] = encoded }
    }

    /** Store or clear a remote MCP server bearer token; returns whether a token is now set. */
    suspend fun setMcpToken(name: String, token: String?): Boolean = withContext(Dispatchers.IO) {
        val key = mcpTokenName(name)
        val cleaned = token?.trim()
        if (cleaned.isNullOrBlank()) {
            secure.remove(key)
            false
        } else {
            val saved = secure.put(key, cleaned) && secure.read(key).getOrNull() == cleaned
            if (!saved) throw IllegalStateException("Failed to save MCP token securely")
            true
        }
    }

    suspend fun getMcpToken(name: String): String = withContext(Dispatchers.IO) {
        secure.get(mcpTokenName(name))
    }

    suspend fun savePlugins(specs: String) {
        appContext.dataStore.edit { it[Keys.LOCAL_PLUGINS] = specs }
    }

    suspend fun saveAgentOriginatedPlugins(specs: Set<String>) {
        appContext.dataStore.edit { it[Keys.LOCAL_AGENT_PLUGINS] = encodeStringSet(specs) }
    }

    suspend fun setDefaultPluginsEnabled(enabled: Boolean) {
        appContext.dataStore.edit { it[Keys.LOCAL_DEFAULT_PLUGINS] = enabled }
    }

    data class NativeMcpPluginSyncResult(
        val importedMcpNames: List<String> = emptyList(),
        val importedPluginSpecs: List<String> = emptyList(),
        val changed: Boolean = false,
    )

    /** Import agent-written native opencode config into app prefs (union merge). */
    suspend fun syncMcpAndPluginsFromNative(): NativeMcpPluginSyncResult = withContext(Dispatchers.IO) {
        val snapshot = OpenCodeNativeConfigSync.read(appContext.filesDir)
        val merge = OpenCodeNativeConfigSync.mergeIntoPrefs(
            currentServers = localMcpServers.first(),
            currentPluginText = localPlugins.first(),
            currentDefaultPlugins = defaultPluginsEnabled.first(),
            currentAgentPlugins = agentOriginatedPlugins.first(),
            snapshot = snapshot,
        )
        if (!merge.changed && merge.tokensToImport.isEmpty()) {
            return@withContext NativeMcpPluginSyncResult(
                importedMcpNames = merge.importedMcpNames,
                importedPluginSpecs = merge.importedPluginSpecs,
                changed = false,
            )
        }
        merge.tokensToImport.forEach { (name, token) -> setMcpToken(name, token) }
        saveMcpServers(merge.servers)
        savePlugins(merge.pluginText)
        saveAgentOriginatedPlugins(merge.agentPluginSpecs)
        setDefaultPluginsEnabled(merge.defaultPluginsEnabled)
        exportMcpAndPluginsToNative()
        NativeMcpPluginSyncResult(
            importedMcpNames = merge.importedMcpNames,
            importedPluginSpecs = merge.importedPluginSpecs,
            changed = true,
        )
    }

    /** Write current MCP / plugin prefs to the agent-native opencode.json. */
    suspend fun exportMcpAndPluginsToNative() = withContext(Dispatchers.IO) {
        val servers = localMcpServers.first()
        val collisions = RuntimeContract.mcpTokenEnvCollisions(servers.map { it.name.trim() }.filter { it.isNotBlank() })
        require(collisions.isEmpty()) {
            "MCP token env collision: " + collisions.values.joinToString { it.joinToString(" / ") }
        }
        val plugins = parsePluginSpecs(localPlugins.first())
        val tokens = servers.associate { server ->
            server.name to if (server.hasToken) getMcpToken(server.name) else ""
        }
        OpenCodeNativeConfigSync.write(appContext.filesDir, servers, plugins, tokens)
    }

    private fun decodeMcpServers(raw: String?): List<McpServerConfig> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(McpServerConfig.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun decodeStringSet(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), raw).toSet()
        }.getOrDefault(emptySet())
    }

    private fun encodeStringSet(values: Set<String>): String =
        json.encodeToString(ListSerializer(String.serializer()), values.sorted())

    private fun decodeLocalWorkspaceProfiles(raw: String?): List<LocalWorkspaceProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(LocalWorkspaceProfile.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun encodeLocalWorkspaceProfiles(values: List<LocalWorkspaceProfile>): String =
        json.encodeToString(ListSerializer(LocalWorkspaceProfile.serializer()), values)

    private fun mcpTokenName(name: String): String = "$SECURE_MCP_TOKEN:${name.trim()}"

    private suspend fun hasLocalProviderApiKeyInternal(presetId: String): Boolean =
        secure.get(localProviderApiKeyName(presetId)).isNotBlank()

    private fun localProviderApiKeyName(presetId: String): String =
        "$SECURE_LOCAL_PROVIDER_API_KEY:${presetId.ifBlank { LocalProviderPresets.DEFAULT_ID }}"

    private suspend fun localProviderProfileFromPrefs(
        prefs: Preferences,
        presetId: String,
        allowLegacyFallback: Boolean,
    ): LocalProviderProfile {
        val preset = LocalProviderPresets.byId(presetId)
            ?: LocalProviderPresets.ALL.first { it.id == LocalProviderPresets.DEFAULT_ID }
        val id = preset.id
        val legacyMatchesPreset = allowLegacyFallback &&
            inferActiveLocalProviderPresetId(prefs) == id
        val baseUrl = prefs[Keys.localProviderBaseUrl(id)]?.ifBlank { null }
            ?: prefs[Keys.LOCAL_PROVIDER_BASE_URL].takeIf { legacyMatchesPreset }?.ifBlank { null }
            ?: preset.apiBaseUrl
        val codingBaseUrl = prefs[Keys.localProviderCodingBaseUrl(id)]?.ifBlank { null }
            ?: prefs[Keys.LOCAL_PROVIDER_CODING_BASE_URL].takeIf { legacyMatchesPreset }?.ifBlank { null }
            ?: preset.codingBaseUrl
        val fallbackActiveBaseUrl = if (
            LocalProviderPresets.prefersCodingBase(id) &&
            codingBaseUrl.isNotBlank()
        ) {
            codingBaseUrl
        } else {
            baseUrl
        }

        return LocalProviderProfile(
            enabled = prefs[Keys.localProviderEnabled(id)]
                ?: prefs[Keys.LOCAL_PROVIDER_ENABLED].takeIf { legacyMatchesPreset }
                ?: false,
            presetId = id,
            providerId = LocalProviderDefaults.PROVIDER_ID,
            displayName = prefs[Keys.localProviderName(id)]?.ifBlank { null }
                ?: prefs[Keys.LOCAL_PROVIDER_NAME].takeIf { legacyMatchesPreset }?.ifBlank { null }
                ?: preset.displayName,
            baseUrl = baseUrl,
            codingBaseUrl = codingBaseUrl,
            activeBaseUrl = prefs[Keys.localProviderActiveBaseUrl(id)]?.ifBlank { null }
                ?: prefs[Keys.LOCAL_PROVIDER_ACTIVE_BASE_URL].takeIf { legacyMatchesPreset }?.ifBlank { null }
                ?: fallbackActiveBaseUrl,
            modelIds = parseModelIds(
                prefs[Keys.localProviderModels(id)]?.ifBlank { null }
                    ?: prefs[Keys.LOCAL_PROVIDER_MODELS].takeIf { legacyMatchesPreset }
                    ?: "",
            ).ifEmpty { preset.modelIds },
            hasApiKey = hasLocalProviderApiKeyInternal(id),
        )
    }

    private companion object {
        const val SECURE_LAN_PASSWORD = "lan_password"
        const val SECURE_LOCAL_PROVIDER_API_KEY = "local_provider_api_key"
        const val SECURE_LOCAL_SERVER_PASSWORD = "local_server_password"
        const val SECURE_MCP_TOKEN = "mcp_token"
        const val MAX_LOCAL_WORKSPACES = 20

        fun generateLocalServerPassword(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        fun localProviderPrefix(presetId: String): String {
            val safeId = presetId.ifBlank { LocalProviderPresets.DEFAULT_ID }
                .replace(Regex("[^A-Za-z0-9_.-]+"), "_")
            return "local_provider_preset_$safeId"
        }
    }

    private fun inferActiveLocalProviderPresetId(prefs: Preferences): String {
        val stored = prefs[Keys.LOCAL_PROVIDER_PRESET_ID]?.let(LocalProviderPresets::byId)
        if (stored != null && stored.id != LocalProviderPresets.DEFAULT_ID) return stored.id

        val legacyBaseUrl = prefs[Keys.LOCAL_PROVIDER_BASE_URL]?.trim().orEmpty()
        val legacyCodingBaseUrl = prefs[Keys.LOCAL_PROVIDER_CODING_BASE_URL]?.trim().orEmpty()
        val legacyActiveBaseUrl = prefs[Keys.LOCAL_PROVIDER_ACTIVE_BASE_URL]?.trim().orEmpty()
        val inferred = LocalProviderPresets.ALL.firstOrNull { preset ->
            preset.apiBaseUrl == legacyBaseUrl ||
                (preset.codingBaseUrl.isNotBlank() && preset.codingBaseUrl == legacyCodingBaseUrl) ||
                (preset.codingBaseUrl.isNotBlank() && preset.codingBaseUrl == legacyActiveBaseUrl)
        }

        return inferred?.id ?: stored?.id ?: LocalProviderPresets.DEFAULT_ID
    }
}
