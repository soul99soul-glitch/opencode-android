package com.opencode.android.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.opencode.android.data.model.ServerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("opencode")

class PreferencesRepository(context: Context) {

    private val appContext = context.applicationContext

    private object Keys {
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
    }

    val config: Flow<ServerConfig> = appContext.dataStore.data.map { prefs ->
        ServerConfig(
            host = prefs[Keys.HOST] ?: "127.0.0.1",
            port = prefs[Keys.PORT] ?: 4096,
            password = prefs[Keys.PASSWORD] ?: "",
            directory = prefs[Keys.DIRECTORY] ?: ""
        )
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
}
