package com.opencode.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.opencode.android.data.repository.AppearanceRepository
import com.opencode.android.ui.theme.LocalOcColors
import com.opencode.android.ui.theme.OcAccent
import com.opencode.android.ui.theme.OcTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val repo = remember { AppearanceRepository(context) }
            val dark by repo.darkTheme.collectAsState(initial = false)
            val accentIdx by repo.accentIndex.collectAsState(initial = 0)

            OcTheme(darkTheme = dark, accent = OcAccent.entries[accentIdx].color) {
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
