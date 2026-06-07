package com.opencode.android.runtime

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

class RuntimeBootstrapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action
        when (action) {
            RuntimeContract.ACTION_START,
            RuntimeContract.ACTION_RESTART,
            RuntimeContract.ACTION_STOP
            -> {
                val serviceIntent = Intent(this, RuntimeService::class.java).apply {
                    this.action = action
                    intent?.extras?.let { putExtras(it) }
                }
                if (action == RuntimeContract.ACTION_STOP) {
                    startService(serviceIntent)
                } else {
                    ContextCompat.startForegroundService(this, serviceIntent)
                }
            }
            RuntimeContract.ACTION_REQUEST_BATTERY_EXEMPTION -> {
                requestBatteryExemption()
            }
        }
        finish()
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return

        val packageUri = Uri.parse("package:$packageName")
        val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = packageUri
        }
        val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = packageUri
        }

        runCatching { startActivity(requestIntent) }
            .onFailure { startActivity(fallbackIntent) }
    }
}
