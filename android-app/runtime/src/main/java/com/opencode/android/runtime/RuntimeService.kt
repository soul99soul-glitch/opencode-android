package com.opencode.android.runtime

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RuntimeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commandMutex = Mutex()
    private lateinit var processManager: RuntimeProcessManager
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        processManager = RuntimeProcessManager(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            RuntimeContract.ACTION_STOP -> {
                scope.launch {
                    commandMutex.withLock {
                        processManager.stop()
                        releaseWakeLock()
                        stopForegroundCompat()
                        stopSelf()
                    }
                }
                return START_NOT_STICKY
            }
            RuntimeContract.ACTION_START,
            RuntimeContract.ACTION_RESTART,
            null -> {
                showForegroundNotification("Starting...")
                acquireWakeLock()
                scope.launch {
                    commandMutex.withLock {
                        if (intent?.action == RuntimeContract.ACTION_RESTART) {
                            processManager.stop()
                        }
                        val result = processManager.start(
                            port = intent?.getIntExtra(RuntimeContract.EXTRA_PORT, 4097) ?: 4097,
                            workspaceName = intent?.getStringExtra(RuntimeContract.EXTRA_WORKSPACE).orEmpty().ifBlank { "default" },
                            providerConfig = intent.toProviderConfig(),
                            providerApiKey = intent?.getStringExtra(RuntimeContract.EXTRA_PROVIDER_API_KEY).orEmpty(),
                            serverPassword = intent?.getStringExtra(RuntimeContract.EXTRA_SERVER_PASSWORD).orEmpty(),
                        )
                        if (result.isSuccess) {
                            showForegroundNotification("Running")
                        } else {
                            showForegroundNotification(result.exceptionOrNull()?.message ?: "Failed")
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

    override fun onDestroy() {
        processManager.stopNow()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
        val stopIntent = Intent(this, RuntimeService::class.java).apply {
            action = RuntimeContract.ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenCode Runtime")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "opencode_runtime_service"
        const val NOTIFICATION_ID = 10
    }
}
