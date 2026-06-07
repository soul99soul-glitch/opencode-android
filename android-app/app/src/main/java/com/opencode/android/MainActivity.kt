package com.opencode.android

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.opencode.android.data.repository.AppearanceRepository
import com.opencode.android.ui.theme.LocalOcColors
import com.opencode.android.ui.theme.OcAccent
import com.opencode.android.ui.theme.OcTheme


class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        // Read cached locale tag (synchronous, no DataStore I/O on cold start)
        val prefs = newBase.getSharedPreferences("oc_appearance_cache", Context.MODE_PRIVATE)
        val cachedTag = prefs.getString("language_tag", null)
        val locale = if (cachedTag != null) {
            AppearanceRepository.tagToLocale(cachedTag)
        } else {
            // First launch: use system default, cache will be populated after DataStore read
            java.util.Locale.getDefault()
        }
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val repo = remember { AppearanceRepository(context) }
            val dark by repo.darkTheme.collectAsState(initial = false)
            val accentIdx by repo.accentIndex.collectAsState(initial = 0)

            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    val window = (view.context as Activity).window
                    WindowCompat.getInsetsController(window, view)
                        .isAppearanceLightStatusBars = !dark
                }
            }

            OcTheme(darkTheme = dark, accent = OcAccent.entries.getOrElse(accentIdx) { OcAccent.Green }.color) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = LocalOcColors.current.bg
                ) {
                    OpenCodeNavHost()
                }
            }
        }
    }
}
