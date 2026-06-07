package com.opencode.android.workspace

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Serializes SAF bridge sync work off the main thread. */
object SafBridgeSync {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "saf-bridge-sync").apply { isDaemon = true }
    }

    @Volatile
    private var scheduledSync: ScheduledFuture<*>? = null

    fun syncDown(context: Context, treeUri: Uri, targetDir: File) {
        executor.submit {
            SafTreeSync.syncDown(context, treeUri, targetDir)
        }.get(5, TimeUnit.MINUTES)
    }

    fun syncUp(context: Context, sourceDir: File, treeUri: Uri) {
        executor.submit {
            SafTreeSync.syncUp(context, sourceDir, treeUri)
        }.get(5, TimeUnit.MINUTES)
    }

    fun syncUpAsync(context: Context, sourceDir: File, treeUri: Uri) {
        executor.execute {
            SafTreeSync.syncUp(context, sourceDir, treeUri)
        }
    }

    fun scheduleSyncUp(
        context: Context,
        sourceDir: File,
        treeUri: Uri,
        delayMs: Long = 2_000L,
    ) {
        synchronized(this) {
            scheduledSync?.cancel(false)
            scheduledSync = executor.schedule({
                SafTreeSync.syncUp(context, sourceDir, treeUri)
            }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    fun cancelScheduledSync() {
        synchronized(this) {
            scheduledSync?.cancel(false)
            scheduledSync = null
        }
    }
}
