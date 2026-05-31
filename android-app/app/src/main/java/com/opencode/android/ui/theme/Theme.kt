package com.opencode.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable

/**
 * Legacy theme bridge — no longer used for styling.
 * All visual tokens live in OcColors, accessed via LocalOcColors.current.
 * This file only exists to satisfy any leftover imports.
 */
@Composable
fun OpenCodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Delegate to OcTheme which sets both LocalOcColors and MaterialTheme
    OcTheme(darkTheme = darkTheme, content = content)
}
