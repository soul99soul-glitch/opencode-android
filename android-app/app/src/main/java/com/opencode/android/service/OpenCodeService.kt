package com.opencode.android.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.opencode.android.MainActivity
import com.opencode.android.data.model.ServerConfig
import com.opencode.android.data.repository.PreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class OpenCodeService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "opencode_service"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.opencode.android.START"
        const val ACTION_STOP = "com.opencode.android.STOP"
    }

    private lateinit var processManager: ProcessManager
    private lateinit var prefs: PreferencesRepository

    override fun onCreate() {
        super.onCreate()
        processManager = ProcessManager(this)
        prefs = PreferencesRepository(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                processManager.stop()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // Show foreground notification IMMEDIATELY (Android requirement)
        showForegroundNotification("Starting...")

        lifecycleScope.launch {
            val config = prefs.config.first()

            // Observe process state
            launch {
                processManager.state.collect { state ->
                    if (state == ProcessManager.State.ERROR || state == ProcessManager.State.STOPPED) {
                        showForegroundNotification(
                            if (state == ProcessManager.State.ERROR) "Stopped (error)" else "Stopped"
                        )
                    }
                }
            }

            if (!processManager.isRunning()) {
                val result = processManager.start(config.port, config.directory.ifBlank { null })
                val msg = if (result.isSuccess) "Running on port ${config.port}" else "Failed to start"
                showForegroundNotification(msg)
            } else {
                showForegroundNotification("Running on port ${config.port}")
            }
        }

        return START_STICKY
    }

    private fun showForegroundNotification(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
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

    override fun onDestroy() {
        processManager.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null
}
