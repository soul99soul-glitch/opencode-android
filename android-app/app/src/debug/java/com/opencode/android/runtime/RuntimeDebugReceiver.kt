package com.opencode.android.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import com.opencode.android.data.model.LocalProviderDefaults
import com.opencode.android.data.model.LocalProviderProfile

class RuntimeDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (!isAllowedDebugCaller(context)) {
            Log.w(TAG, "Rejected debug runtime action from uid ${sentFromUidCompat()}")
            return
        }
        val client = RuntimeCompanionClient(context)
        val result = when (action) {
            ACTION_START -> client.start(
                port = intent.getIntExtra(EXTRA_PORT, 4097),
                workspaceName = intent.getStringExtra(EXTRA_WORKSPACE).orEmpty().ifBlank { "phase0-probe" },
                providerProfile = providerProfile(intent),
                providerApiKey = intent.getStringExtra(EXTRA_PROVIDER_API_KEY).orEmpty(),
                serverPassword = intent.getStringExtra(EXTRA_SERVER_PASSWORD).orEmpty(),
            )
            ACTION_RESTART -> client.restart(
                port = intent.getIntExtra(EXTRA_PORT, 4097),
                workspaceName = intent.getStringExtra(EXTRA_WORKSPACE).orEmpty().ifBlank { "phase0-probe" },
                providerProfile = providerProfile(intent),
                providerApiKey = intent.getStringExtra(EXTRA_PROVIDER_API_KEY).orEmpty(),
                serverPassword = intent.getStringExtra(EXTRA_SERVER_PASSWORD).orEmpty(),
            )
            ACTION_STOP -> client.stop()
            else -> Result.failure(IllegalArgumentException("Unknown debug runtime action: $action"))
        }
        Log.d(TAG, "$action -> ${result.fold({ "ok" }, { it.message ?: it.javaClass.simpleName })}")
    }

    private fun isAllowedDebugCaller(context: Context): Boolean {
        val sentFromUid = sentFromUidCompat() ?: return false
        return sentFromUid == UNKNOWN_SENDER_UID ||
            sentFromUid == Process.SHELL_UID ||
            sentFromUid == Process.myUid() ||
            sentFromUid == context.applicationInfo.uid
    }

    private fun sentFromUidCompat(): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            sentFromUid
        } else {
            null
        }

    private fun providerProfile(intent: Intent): LocalProviderProfile =
        LocalProviderProfile(
            enabled = intent.getBooleanExtra(EXTRA_PROVIDER_ENABLED, true),
            providerId = LocalProviderDefaults.PROVIDER_ID,
            displayName = intent.getStringExtra(EXTRA_PROVIDER_NAME).orEmpty().ifBlank { LocalProviderDefaults.DISPLAY_NAME },
            baseUrl = intent.getStringExtra(EXTRA_PROVIDER_BASE_URL).orEmpty().ifBlank { "http://127.0.0.1:11434/v1" },
            modelIds = providerModels(intent).ifEmpty { listOf("phase0-probe") },
            hasApiKey = intent.getBooleanExtra(EXTRA_PROVIDER_HAS_API_KEY, false),
        )

    @Suppress("DEPRECATION")
    private fun providerModels(intent: Intent): List<String> {
        val raw = intent.extras?.get(EXTRA_PROVIDER_MODELS)
        return when (raw) {
            is ArrayList<*> -> raw.mapNotNull { it as? String }
            is Array<*> -> raw.mapNotNull { it as? String }
            is String -> raw.split(',')
            else -> emptyList()
        }.map { it.trim() }.filter { it.isNotEmpty() }
    }

    companion object {
        const val ACTION_START = "com.opencode.android.DEBUG_RUNTIME_START"
        const val ACTION_RESTART = "com.opencode.android.DEBUG_RUNTIME_RESTART"
        const val ACTION_STOP = "com.opencode.android.DEBUG_RUNTIME_STOP"

        private const val EXTRA_PORT = "port"
        private const val EXTRA_WORKSPACE = "workspace"
        private const val EXTRA_PROVIDER_ENABLED = "provider_enabled"
        private const val EXTRA_PROVIDER_NAME = "provider_name"
        private const val EXTRA_PROVIDER_BASE_URL = "provider_base_url"
        private const val EXTRA_PROVIDER_MODELS = "provider_models"
        private const val EXTRA_PROVIDER_HAS_API_KEY = "provider_has_api_key"
        private const val EXTRA_PROVIDER_API_KEY = "provider_api_key"
        private const val EXTRA_SERVER_PASSWORD = "server_password"
        private const val UNKNOWN_SENDER_UID = -1
        private const val TAG = "OpenCodeRuntimeDebug"
    }
}
