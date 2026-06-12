package com.opencode.android.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.opencode.android.MainActivity
import com.opencode.android.data.model.parsePluginSpecs
import com.opencode.android.data.repository.PreferencesRepository
import com.opencode.android.runtime.RuntimeContract
import com.opencode.android.runtime.RuntimeMcpServer
import com.opencode.android.runtime.RuntimeProcessManager
import com.opencode.android.runtime.RuntimeProviderConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.thread

/**
 * Hosts the bundled OpenCode runtime process inside the main app's process/UID (Option C).
 *
 * The runtime makes outbound LLM calls; running it under the foreground app's UID lets those
 * calls use the app's network access (OEM ROMs such as ColorOS block background-only packages
 * from the network). The service is driven entirely by intent extras supplied by
 * [com.opencode.android.runtime.RuntimeCompanionClient].
 */
class OpenCodeService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "opencode_service"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.opencode.android.START"
        const val ACTION_STOP = "com.opencode.android.STOP"
        const val ACTION_RESTART = "com.opencode.android.RESTART"
    }

    private val commandMutex = Mutex()
    private lateinit var processManager: RuntimeProcessManager
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        processManager = RuntimeProcessManager(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                lifecycleScope.launch {
                    commandMutex.withLock {
                        processManager.stop()
                        releaseWakeLock()
                        stopForegroundCompat()
                        stopSelf()
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_START, ACTION_RESTART, null -> {
                showForegroundNotification(if (intent?.action == ACTION_RESTART) "Restarting..." else "Starting...")
                acquireWakeLock()
                val restart = intent?.action == ACTION_RESTART
                val port = intent?.getIntExtra(RuntimeContract.EXTRA_PORT, 4097) ?: 4097
                val workspace = intent?.getStringExtra(RuntimeContract.EXTRA_WORKSPACE).orEmpty().ifBlank { "default" }
                val workspaceTreeUri = intent?.getStringExtra(RuntimeContract.EXTRA_WORKSPACE_TREE_URI).orEmpty()
                val baseConfig = intent.toProviderConfig()
                val providerApiKey = intent?.getStringExtra(RuntimeContract.EXTRA_PROVIDER_API_KEY).orEmpty()
                val serverPassword = intent?.getStringExtra(RuntimeContract.EXTRA_SERVER_PASSWORD).orEmpty()
                lifecycleScope.launch {
                    commandMutex.withLock {
                        if (restart) processManager.stop()
                        val providerConfig = baseConfig.withMcpAndPlugins()
                        val result = processManager.start(
                            port = port,
                            workspaceName = workspace,
                            workspaceTreeUri = workspaceTreeUri,
                            providerConfig = providerConfig,
                            providerApiKey = providerApiKey,
                            serverPassword = serverPassword,
                        )
                        if (result.isSuccess) {
                            showForegroundNotification("Runtime ready on port $port")
                        } else {
                            showForegroundNotification(result.exceptionOrNull()?.message ?: "Runtime unavailable")
                            processManager.stop()
                            releaseWakeLock()
                            stopForegroundCompat()
                            stopSelf()
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (::processManager.isInitialized) {
            runLifecycleCleanup("task-removed") { processManager.flushSafBridgeBestEffortNow() }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (::processManager.isInitialized) {
            runLifecycleCleanup("destroy") { processManager.stopBestEffortNow() }
        }
        releaseWakeLock()
        super.onDestroy()
    }

    private fun runLifecycleCleanup(name: String, cleanup: () -> Unit) {
        thread(start = true, isDaemon = true, name = "opencode-service-cleanup-$name") {
            runCatching { cleanup() }
        }
    }

    // MCP servers + plugins are managed as lists in app preferences (tokens in the secure store),
    // so they are read here at start time and merged onto the intent-derived provider config.
    // Always export to native config so the runtime finds MCP/plugin sections in opencode.json
    // even when syncMcpAndPluginsFromNative() detected no changes from the agent side.
    private suspend fun RuntimeProviderConfig.withMcpAndPlugins(): RuntimeProviderConfig {
        val prefs = PreferencesRepository(this@OpenCodeService)
        prefs.syncMcpAndPluginsFromNative()
        prefs.exportMcpAndPluginsToNative()
        val servers = prefs.localMcpServers.first().map { s ->
            RuntimeMcpServer(
                name = s.name,
                url = s.url,
                token = if (s.hasToken) prefs.getMcpToken(s.name) else "",
            )
        }
        val plugins = parsePluginSpecs(prefs.localPlugins.first())
        val defaultPlugins = prefs.defaultPluginsEnabled.first()
        return copy(mcpServers = servers, plugins = plugins, defaultPlugins = defaultPlugins)
    }

    private fun Intent?.toProviderConfig(): RuntimeProviderConfig {
        val models = this?.providerModelIds().orEmpty()
        return RuntimeProviderConfig(
            enabled = this?.getBooleanExtra(RuntimeContract.EXTRA_PROVIDER_ENABLED, false) == true,
            displayName = this?.getStringExtra(RuntimeContract.EXTRA_PROVIDER_NAME).orEmpty().ifBlank { RuntimeContract.PROVIDER_NAME },
            baseUrl = this?.getStringExtra(RuntimeContract.EXTRA_PROVIDER_BASE_URL).orEmpty(),
            codingBaseUrl = this?.getStringExtra(RuntimeContract.EXTRA_PROVIDER_CODING_BASE_URL).orEmpty(),
            activeBaseUrl = this?.getStringExtra(RuntimeContract.EXTRA_PROVIDER_ACTIVE_BASE_URL).orEmpty(),
            modelIds = models,
            hasApiKey = this?.getBooleanExtra(RuntimeContract.EXTRA_PROVIDER_HAS_API_KEY, false) == true,
        )
    }

    @Suppress("DEPRECATION")
    private fun Intent.providerModelIds(): List<String> {
        val raw = extras?.get(RuntimeContract.EXTRA_PROVIDER_MODELS)
        return when (raw) {
            is ArrayList<*> -> raw.mapNotNull { it as? String }
            is Array<*> -> raw.mapNotNull { it as? String }
            is String -> raw.split(',')
            else -> emptyList()
        }.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "OpenCode Runtime", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun showForegroundNotification(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val existing = wakeLock
        if (existing?.isHeld == true) return
        val manager = getSystemService(PowerManager::class.java)
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenCodeRuntime:serve").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, OpenCodeService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenCode")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .setOngoing(true)
            .build()
    }
}
