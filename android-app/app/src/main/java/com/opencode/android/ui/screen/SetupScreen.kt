package com.opencode.android.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.android.data.api.OpenCodeApi
import com.opencode.android.data.model.ServerConfig
import com.opencode.android.data.repository.PreferencesRepository
import com.opencode.android.ui.component.BlinkingCursor
import com.opencode.android.ui.component.MonoLabel
import com.opencode.android.ui.component.OcButton
import com.opencode.android.ui.component.OcButtonStyle
import com.opencode.android.ui.component.UnderlineField
import com.opencode.android.ui.theme.LocalOcColors
import com.opencode.android.ui.theme.OcType
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PreferencesRepository(context) }
    val c = LocalOcColors.current

    var host by remember { mutableStateOf("127.0.0.1") }
    var port by remember { mutableStateOf("4096") }
    var password by remember { mutableStateOf("") }
    var directory by remember { mutableStateOf("") }
    var isConnecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
    ) {
        // ── Brand ──
        Column(
            Modifier.fillMaxWidth().padding(top = 46.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(60.dp).clip(RoundedCornerShape(17.dp)).background(c.ink),
                contentAlignment = Alignment.Center,
            ) {
                Text("</>", style = OcType.monoStrong.copy(fontSize = 15.sp), color = c.bg)
            }
            Spacer(Modifier.height(26.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("opencode", style = OcType.brand, color = c.ink)
                BlinkingCursor(color = c.accent)
            }
            Spacer(Modifier.height(12.dp))
            Text("连接到你的 OpenCode 服务器", style = OcType.body, color = c.ink2)
        }

        // ── Fields ──
        val isFullUrl = host.startsWith("http://") || host.startsWith("https://")
        Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
            UnderlineField(host, { host = it }, "> SERVER URL", leading = { GlyphServer() })
            if (!isFullUrl) {
                UnderlineField(port, { port = it }, "> PORT", leading = { GlyphPorts() }, keyboardType = KeyboardType.Number)
            }
            UnderlineField(password, { password = it }, "> PASSWORD", leading = { GlyphLock() }, placeholder = "Optional", password = true)
            UnderlineField(directory, { directory = it }, "> DIRECTORY", leading = { GlyphFolder() }, placeholder = "~/projects/opencode")
        }

        // ── Endpoint preview ──
        Spacer(Modifier.height(22.dp))
        if (isFullUrl) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Text("→ ", style = OcType.mono, color = c.ink4)
                Text(host.trimEnd('/'), style = OcType.mono, color = c.accent)
            }
        } else {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text("→ ", style = OcType.mono, color = c.ink4)
            Text(host.ifEmpty { "host" }, style = OcType.mono, color = c.ink2)
            Text(":", style = OcType.mono, color = c.ink4)
            Text(port.ifEmpty { "port" }, style = OcType.mono, color = c.accent)
        }
        }

        // ── Error ──
        if (error != null) {
            Spacer(Modifier.height(14.dp))
            Text(error ?: "", style = OcType.mono, color = c.accent.copy(alpha = 0.8f))
        }

        Spacer(Modifier.height(28.dp))

        // ── Connect button ──
        OcButton(
            text = "Connect",
            style = OcButtonStyle.Primary,
            loading = isConnecting,
            onClick = {
                error = null
                isConnecting = true
                scope.launch {
                    val config = ServerConfig(host, port.toIntOrNull() ?: 4096, password, directory)
                    val api = OpenCodeApi(config)
                    val result = api.health()
                    api.close()
                    isConnecting = false
                    result.onSuccess {
                        if (it.healthy) {
                            prefs.saveConfig(config)
                            prefs.setSetupDone(true)
                            onComplete()
                        } else {
                            error = "Server unhealthy"
                        }
                    }.onFailure {
                        error = "Connection failed: ${it.message}"
                    }
                }
            },
        )

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text("opencode serve", style = OcType.mono, color = c.ink3)
            Text(" — or use built-in local service", style = OcType.secondary, color = c.ink4)
        }

        Spacer(Modifier.height(28.dp))
    }
}

/* Placeholder glyphs — stroke style, mono, ink3 tint */
@Composable
private fun GlyphServer() { Text("☱", style = OcType.mono.copy(fontSize = 16.sp), color = LocalOcColors.current.ink3) }

@Composable
private fun GlyphPorts() { Text("◈", style = OcType.mono.copy(fontSize = 16.sp), color = LocalOcColors.current.ink3) }

@Composable
private fun GlyphLock() { Text("◻", style = OcType.mono.copy(fontSize = 16.sp), color = LocalOcColors.current.ink3) }

@Composable
private fun GlyphFolder() { Text("◇", style = OcType.mono.copy(fontSize = 16.sp), color = LocalOcColors.current.ink3) }
