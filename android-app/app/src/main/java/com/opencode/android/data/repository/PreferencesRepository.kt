package com.opencode.android.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.opencode.android.data.model.ServerConfig
import com.opencode.android.data.model.WorkspaceProfile
import com.opencode.android.data.model.toWorkspaceProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("opencode")

class PreferencesRepository(context: Context) {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val workspaceStore = WorkspaceProfileStore(json)

    private object Keys {
        val HOST = stringPreferencesKey("host")
        val PORT = intPreferencesKey("port")
        val PASSWORD = stringPreferencesKey("password")
        val DIRECTORY = stringPreferencesKey("directory")
        val IS_SETUP_DONE = booleanPreferencesKey("is_setup_done")
        val DEFAULT_AGENT = stringPreferencesKey("default_agent")
        val DEFAULT_MODEL_PROVIDER = stringPreferencesKey("default_model_provider")
        val DEFAULT_MODEL_ID = stringPreferencesKey("default_model_id")
        val WORKSPACE_PROFILES = stringPreferencesKey("workspace_profiles")
        val ACTIVE_WORKSPACE_ID = stringPreferencesKey("active_workspace_id")
    }

    private fun Preferences.legacyConfig(): ServerConfig {
        return ServerConfig(
            host = this[Keys.HOST] ?: "127.0.0.1",
            port = this[Keys.PORT] ?: 4096,
            password = this[Keys.PASSWORD] ?: "",
            directory = this[Keys.DIRECTORY] ?: ""
        )
    }

    val config: Flow<ServerConfig> = appContext.dataStore.data.map { prefs ->
        prefs.legacyConfig()
    }

    val workspaceProfiles: Flow<List<WorkspaceProfile>> = appContext.dataStore.data.map { prefs ->
        workspaceStore.profilesOrLegacy(prefs[Keys.WORKSPACE_PROFILES], prefs.legacyConfig())
    }

    val activeWorkspaceId: Flow<String> = appContext.dataStore.data.map { prefs ->
        prefs[Keys.ACTIVE_WORKSPACE_ID] ?: prefs.legacyConfig().endpointIdentity()
    }

    val isSetupDone: Flow<Boolean> = appContext.dataStore.data.map { prefs ->
        prefs[Keys.IS_SETUP_DONE] ?: false
    }

    suspend fun saveConfig(config: ServerConfig) {
        appContext.dataStore.edit { prefs ->
            prefs[Keys.HOST] = config.host
            prefs[Keys.PORT] = config.port
            prefs[Keys.PASSWORD] = config.password
            prefs[Keys.DIRECTORY] = config.directory
            val profile = config.toWorkspaceProfile()
            val profiles = workspaceStore.merge(workspaceStore.decode(prefs[Keys.WORKSPACE_PROFILES]), profile)
            prefs[Keys.WORKSPACE_PROFILES] = workspaceStore.encode(profiles)
            prefs[Keys.ACTIVE_WORKSPACE_ID] = profile.id
        }
    }

    suspend fun saveWorkspaceProfile(profile: WorkspaceProfile, makeActive: Boolean = true) {
        appContext.dataStore.edit { prefs ->
            val updated = profile.copy(lastUsedAt = System.currentTimeMillis())
            val profiles = workspaceStore.merge(workspaceStore.decode(prefs[Keys.WORKSPACE_PROFILES]), updated)
            prefs[Keys.WORKSPACE_PROFILES] = workspaceStore.encode(profiles)
            if (makeActive) {
                prefs[Keys.HOST] = updated.config.host
                prefs[Keys.PORT] = updated.config.port
                prefs[Keys.PASSWORD] = updated.config.password
                prefs[Keys.DIRECTORY] = updated.config.directory
                prefs[Keys.ACTIVE_WORKSPACE_ID] = updated.id
            }
        }
    }

    suspend fun activateWorkspace(profileId: String) {
        appContext.dataStore.edit { prefs ->
            val profiles = workspaceStore.profilesOrLegacy(prefs[Keys.WORKSPACE_PROFILES], prefs.legacyConfig())
            val profile = profiles.firstOrNull { it.id == profileId } ?: return@edit
            val updated = profile.copy(lastUsedAt = System.currentTimeMillis())
            prefs[Keys.HOST] = updated.config.host
            prefs[Keys.PORT] = updated.config.port
            prefs[Keys.PASSWORD] = updated.config.password
            prefs[Keys.DIRECTORY] = updated.config.directory
            prefs[Keys.ACTIVE_WORKSPACE_ID] = updated.id
            prefs[Keys.WORKSPACE_PROFILES] = workspaceStore.encode(workspaceStore.merge(profiles, updated))
        }
    }

    suspend fun removeWorkspaceProfile(profileId: String) {
        appContext.dataStore.edit { prefs ->
            val profiles = workspaceStore.remove(
                workspaceStore.profilesOrLegacy(prefs[Keys.WORKSPACE_PROFILES], prefs.legacyConfig()),
                profileId,
            )
            if (prefs[Keys.ACTIVE_WORKSPACE_ID] == profileId) {
                val fallback = profiles.firstOrNull() ?: ServerConfig().toWorkspaceProfile()
                    prefs[Keys.HOST] = fallback.config.host
                    prefs[Keys.PORT] = fallback.config.port
                    prefs[Keys.PASSWORD] = fallback.config.password
                    prefs[Keys.DIRECTORY] = fallback.config.directory
                    prefs[Keys.ACTIVE_WORKSPACE_ID] = fallback.id
                prefs[Keys.WORKSPACE_PROFILES] = workspaceStore.encode(workspaceStore.merge(profiles, fallback))
            } else {
                prefs[Keys.WORKSPACE_PROFILES] = workspaceStore.encode(profiles)
            }
        }
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

    suspend fun saveDefaultAgent(agent: String) {
        appContext.dataStore.edit { it[Keys.DEFAULT_AGENT] = agent }
    }

    suspend fun saveDefaultModel(provider: String, modelId: String) {
        appContext.dataStore.edit {
            it[Keys.DEFAULT_MODEL_PROVIDER] = provider
            it[Keys.DEFAULT_MODEL_ID] = modelId
        }
    }
}
