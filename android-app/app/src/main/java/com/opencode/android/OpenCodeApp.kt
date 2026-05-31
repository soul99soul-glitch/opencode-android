package com.opencode.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class OpenCodeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "opencode_service",
                "OpenCode Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "OpenCode is running in the background"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
