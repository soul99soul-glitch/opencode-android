package com.opencode.android.runtime

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.opencode.android.data.model.LocalProviderProfile
import com.opencode.android.data.model.sanitizeLocalWorkspaceName
import com.opencode.android.service.OpenCodeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

enum class RuntimeInstallState {
    UnsupportedAbi,
    NotInstalled,
    Installed,
}

data class RuntimeCompanionStatus(
    val state: RuntimeInstallState,
    val versionName: String? = null,
    val message: String,
) {
    val canStart: Boolean get() = state == RuntimeInstallState.Installed
    val supportsBundled: Boolean get() = state != RuntimeInstallState.UnsupportedAbi
}

/**
 * Controls the in-process OpenCode runtime hosted by [OpenCodeService] (Option C).
 *
 * The native binary ships inside the main app APK and runs under the app's own UID, so its
 * outbound network access follows the foreground app rather than a background-only companion
 * package. This class keeps the previous public surface so callers (UI screens) are unchanged.
 */
class RuntimeCompanionClient(context: Context) {
    private val appContext = context.applicationContext

    fun status(): RuntimeCompanionStatus {
        if (!supportsArm64()) {
            return RuntimeCompanionStatus(
                state = RuntimeInstallState.UnsupportedAbi,
                message = "Bundled runtime supports arm64-v8a only",
            )
        }
        if (!runtimeBinary().exists()) {
            return RuntimeCompanionStatus(
                state = RuntimeInstallState.NotInstalled,
                message = "Bundled runtime binary missing from app",
            )
        }
        return RuntimeCompanionStatus(
            state = RuntimeInstallState.Installed,
            message = "Bundled runtime ready",
        )
    }

    fun start(
        port: Int,
        workspaceName: String,
        workspaceTreeUri: String = "",
        providerProfile: LocalProviderProfile,
        providerApiKey: String,
        serverPassword: String = "",
    ): Result<Unit> = send(
        OpenCodeService.ACTION_START,
        port,
        workspaceName,
        workspaceTreeUri,
        providerProfile,
        providerApiKey,
        serverPassword,
    )

    fun restart(
        port: Int,
        workspaceName: String,
        workspaceTreeUri: String = "",
        providerProfile: LocalProviderProfile,
        providerApiKey: String,
        serverPassword: String = "",
    ): Result<Unit> = send(
        OpenCodeService.ACTION_RESTART,
        port,
        workspaceName,
        workspaceTreeUri,
        providerProfile,
        providerApiKey,
        serverPassword,
    )

    suspend fun startAndAwaitReady(
        port: Int,
        workspaceName: String,
        workspaceTreeUri: String = "",
        providerProfile: LocalProviderProfile,
        providerApiKey: String,
        serverPassword: String,
    ): Result<Unit> {
        val request = start(port, workspaceName, workspaceTreeUri, providerProfile, providerApiKey, serverPassword)
        if (request.isFailure) return request
        return awaitHealthy(port, serverPassword)
    }

    suspend fun restartAndAwaitReady(
        port: Int,
        workspaceName: String,
        workspaceTreeUri: String = "",
        providerProfile: LocalProviderProfile,
        providerApiKey: String,
        serverPassword: String,
    ): Result<Unit> {
        val request = restart(port, workspaceName, workspaceTreeUri, providerProfile, providerApiKey, serverPassword)
        if (request.isFailure) return request
        return awaitHealthy(port, serverPassword)
    }

    fun stop(): Result<Unit> = runCatching {
        val intent = Intent(appContext, OpenCodeService::class.java).apply {
            action = OpenCodeService.ACTION_STOP
        }
        appContext.startService(intent)
    }

    fun requestBatteryExemption(): Result<Unit> = runCatching {
        val current = status()
        require(current.canStart) { current.message }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return@runCatching
        val packageUri = Uri.parse("package:${appContext.packageName}")
        val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = packageUri
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { appContext.startActivity(requestIntent) }
            .onFailure {
                appContext.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = packageUri
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }
    }

    private fun send(
        action: String,
        port: Int,
        workspaceName: String,
        workspaceTreeUri: String,
        providerProfile: LocalProviderProfile,
        providerApiKey: String,
        serverPassword: String,
    ): Result<Unit> = runCatching {
        val current = status()
        require(current.canStart) { current.message }
        val cleanedProviderApiKey = providerApiKey.trim()
        val intent = Intent(appContext, OpenCodeService::class.java).apply {
            this.action = action
            putExtra(RuntimeContract.EXTRA_PORT, port)
            putExtra(RuntimeContract.EXTRA_WORKSPACE, safeWorkspaceName(workspaceName))
            if (workspaceTreeUri.isNotBlank()) {
                putExtra(RuntimeContract.EXTRA_WORKSPACE_TREE_URI, workspaceTreeUri)
            }
            putExtra(RuntimeContract.EXTRA_PROVIDER_ENABLED, providerProfile.enabled)
            putExtra(RuntimeContract.EXTRA_PROVIDER_NAME, providerProfile.displayName)
            putExtra(RuntimeContract.EXTRA_PROVIDER_BASE_URL, providerProfile.baseUrl)
            putExtra(RuntimeContract.EXTRA_PROVIDER_CODING_BASE_URL, providerProfile.codingBaseUrl)
            putExtra(RuntimeContract.EXTRA_PROVIDER_ACTIVE_BASE_URL, providerProfile.activeBaseUrl)
            putStringArrayListExtra(RuntimeContract.EXTRA_PROVIDER_MODELS, ArrayList(providerProfile.modelIds))
            putExtra(RuntimeContract.EXTRA_PROVIDER_HAS_API_KEY, providerProfile.hasApiKey)
            if (providerProfile.hasApiKey && cleanedProviderApiKey.isNotBlank()) {
                putExtra(RuntimeContract.EXTRA_PROVIDER_API_KEY, cleanedProviderApiKey)
            }
            if (serverPassword.isNotBlank()) {
                putExtra(RuntimeContract.EXTRA_SERVER_PASSWORD, serverPassword)
            }
        }
        ContextCompat.startForegroundService(appContext, intent)
    }

    private suspend fun awaitHealthy(port: Int, serverPassword: String): Result<Unit> = withContext(Dispatchers.IO) {
        val authHeader = basicAuthHeader(serverPassword)
        repeat(30) {
            delay(500)
            if (isHealthy(port, authHeader)) return@withContext Result.success(Unit)
        }
        Result.failure(IllegalStateException("Runtime did not become healthy on port $port"))
    }

    private fun isHealthy(port: Int, authHeader: String?): Boolean {
        val conn = (URL("http://127.0.0.1:$port/global/health").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 1000
            readTimeout = 1000
            authHeader?.let { setRequestProperty("Authorization", it) }
        }
        return try {
            conn.responseCode == 200
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    private fun basicAuthHeader(serverPassword: String): String? {
        if (serverPassword.isBlank()) return null
        val cred = Base64.getEncoder().encodeToString("opencode:$serverPassword".toByteArray())
        return "Basic $cred"
    }

    private fun runtimeBinary(): File =
        File(appContext.applicationInfo.nativeLibraryDir, "libopencode_runtime.so")

    companion object {
        fun supportsArm64(): Boolean =
            Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

        fun safeWorkspaceName(input: String): String =
            sanitizeLocalWorkspaceName(input)
    }
}
