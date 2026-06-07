package com.opencode.android.runtime

import android.content.Context

object LegacyRuntimeCompanion {
    const val PACKAGE_NAME = "com.opencode.android.runtime"

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
        true
    }.getOrDefault(false)
}
