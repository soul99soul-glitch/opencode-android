package com.opencode.android.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.opencode.android.data.api.OpenCodeApi
import com.opencode.android.data.model.AgentInfo
import com.opencode.android.data.model.ModelRef
import com.opencode.android.data.model.Provider
import com.opencode.android.data.repository.AppearanceRepository
import com.opencode.android.data.repository.PreferencesRepository
import com.opencode.android.ui.component.Hairline
import com.opencode.android.ui.component.MonoLabel
import com.opencode.android.ui.component.pressable
import com.opencode.android.ui.theme.LocalOcColors
import com.opencode.android.ui.theme.OcAccent
import com.opencode.android.ui.theme.OcType

/**
 * Settings 页（spec §7.4）
 *
 * 分组结构: 大写等宽小标签 + 成组卡(圆角14dp, 无边框无阴影, 内部发丝线分行)
 *  1. CONNECTION  — Host / Port / Password / Directory
 *  2. APPEARANCE  — 亮/暗/系统 分段控件 + 强调色色块
 *  3. AGENT       — 默认 Agent / 模型
 *  4. ABOUT       — 版本号 / 检查更新
 *  末尾: DISCONNECT（红色）
 *  底部: opencode · host:port
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onDisconnect: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PreferencesRepository(context) }
    val appearance = remember { AppearanceRepository(context) }
    val c = LocalOcColors.current

    val config by prefs.config.collectAsState(
        initial = com.opencode.android.data.model.ServerConfig()
    )
    val darkTheme by appearance.darkTheme.collectAsState(initial = false)
    val accentIndex by appearance.accentIndex.collectAsState(initial = 0)
    val scope = rememberCoroutineScope()

    // Agent / model state
    val defaultAgent by prefs.defaultAgent.collectAsState(initial = "build")
    val defaultModelProvider by prefs.defaultModelProvider.collectAsState(initial = "")
    val defaultModelId by prefs.defaultModelId.collectAsState(initial = "")
    var providers by remember { mutableStateOf<List<Provider>>(emptyList()) }
    var availableAgents by remember { mutableStateOf<List<AgentInfo>>(emptyList()) }
    var showModelPicker by remember { mutableStateOf(false) }
    var expandedProviderId by remember { mutableStateOf<String?>(null) }

    // Load providers + agents from API
    LaunchedEffect(Unit) {
        val cfg = prefs.config.first()
        val api = OpenCodeApi(cfg)
        api.fetchConfiguredProviders()
            .onSuccess { providers = it }
        api.fetchAgents()
            .onSuccess { all -> availableAgents = all.filter { it.mode == "primary" && !it.hidden } }
        api.close()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        // ── Top bar ──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).pressable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Text("←", style = OcType.title, color = c.ink)
            }
            Spacer(Modifier.width(4.dp))
            Text("Settings", style = OcType.titleL, color = c.ink)
        }

        Spacer(Modifier.height(8.dp))

        // ── CONNECTION ──
        SectionHeader("CONNECTION")
        SettingsCard {
            SettingsRow("Server URL", config.host)
            Hairline()
            if (!config.host.startsWith("http://") && !config.host.startsWith("https://")) {
                SettingsRow("Port", config.port.toString())
                Hairline()
            }
            SettingsRow("Password", if (config.password.isBlank()) "—" else "••••••••")
            Hairline()
            SettingsRow("Directory", config.directory.ifBlank { "—" })
        }

        Spacer(Modifier.height(22.dp))

        // ── APPEARANCE ──
        SectionHeader("APPEARANCE")

        // Theme segmented control
        SettingsCard {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Theme", style = OcType.body, color = c.ink, modifier = Modifier.weight(1f))
                // Light / Dark / System segmented
                Row(
                    Modifier
                        .background(c.surface2, RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    ThemeOption("Light", darkTheme == false, c) {
                        scope.launch { appearance.setDark(false) }
                    }
                    ThemeOption("Dark", darkTheme == true, c) {
                        scope.launch { appearance.setDark(true) }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Accent color swatches
        SettingsCard {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Accent", style = OcType.body, color = c.ink, modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OcAccent.entries.forEachIndexed { index, accent ->
                        val selected = index == accentIndex
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(accent.color)
                                .pressable {
                                    scope.launch { appearance.setAccentIndex(index) }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                androidx.compose.foundation.Canvas(
                                    Modifier.size(18.dp)
                                ) {
                                    val stroke = 2.5.dp.toPx()
                                    val path = androidx.compose.ui.graphics.Path().apply {
                                        moveTo(size.width * 0.2f, size.height * 0.5f)
                                        lineTo(size.width * 0.42f, size.height * 0.72f)
                                        lineTo(size.width * 0.8f, size.height * 0.28f)
                                    }
                                    drawPath(
                                        path,
                                        color = c.accentInk,
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                                            width = stroke,
                                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                            join = androidx.compose.ui.graphics.StrokeJoin.Round,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        // ── AGENT ──
        SectionHeader("AGENT")
        SettingsCard {
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable {
                        if (availableAgents.size < 2) return@pressable
                        val currentIdx = availableAgents.indexOfFirst { it.name == defaultAgent }
                        val nextIdx = if (currentIdx >= 0) (currentIdx + 1) % availableAgents.size else 0
                        scope.launch { prefs.saveDefaultAgent(availableAgents[nextIdx].name) }
                    }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Default Agent", style = OcType.body, color = c.ink, modifier = Modifier.weight(1f))
                Text(
                    defaultAgent.replaceFirstChar { it.uppercase() },
                    style = OcType.mono,
                    color = c.ink2,
                )
            }
            Hairline()
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable {
                        if (providers.isNotEmpty()) showModelPicker = !showModelPicker
                    }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Model", style = OcType.body, color = c.ink, modifier = Modifier.weight(1f))
                val modelLabel = if (defaultModelProvider.isNotBlank() && defaultModelId.isNotBlank()) {
                    "$defaultModelProvider/$defaultModelId"
                } else {
                    if (providers.isEmpty()) "Loading…" else "Select…"
                }
                Text(
                    modelLabel,
                    style = OcType.mono,
                    color = if (providers.isNotEmpty()) c.ink2 else c.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Model picker panel — outside the card
        AnimatedVisibility(visible = showModelPicker) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .background(c.surface2, RoundedCornerShape(14.dp))
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp)
            ) {
                providers.forEach { provider ->
                    val isExpanded = expandedProviderId == provider.id
                    val isProviderSelected = defaultModelProvider == provider.id
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .pressable {
                                expandedProviderId = if (isExpanded) null else provider.id
                            }
                            .background(if (isProviderSelected && !isExpanded) c.bg else Color.Transparent)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            provider.name.ifBlank { provider.id },
                            style = OcType.mono.copy(fontSize = 12.sp),
                            color = if (isProviderSelected) c.accent else c.ink2,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (isExpanded) "−" else "+",
                            style = OcType.mono.copy(fontSize = 12.sp),
                            color = c.ink4,
                        )
                    }
                    AnimatedVisibility(visible = isExpanded) {
                        Column {
                            provider.models.keys.forEach { modelId ->
                                val isSelected = defaultModelProvider == provider.id && defaultModelId == modelId
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .pressable {
                                            scope.launch { prefs.saveDefaultModel(provider.id, modelId) }
                                            showModelPicker = false
                                            expandedProviderId = null
                                        }
                                        .background(if (isSelected) c.bg else Color.Transparent)
                                        .padding(horizontal = 24.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        modelId,
                                        style = OcType.mono.copy(fontSize = 11.sp),
                                        color = if (isSelected) c.accent else c.ink3,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        // ── ABOUT ──
        SectionHeader("ABOUT")
        SettingsCard {
            SettingsRow("Version", "0.6.0")
        }

        Spacer(Modifier.height(28.dp))

        // ── DISCONNECT ──
        Box(
            Modifier
                .fillMaxWidth()
                .pressable {
                    scope.launch { prefs.setSetupDone(false) }
                    onDisconnect()
                }
                .padding(horizontal = 22.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Disconnect", style = OcType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Color(0xFFC44D4D))
        }

        Spacer(Modifier.height(20.dp))

        // ── Bottom ──
        Row(
            Modifier.fillMaxWidth().padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text("opencode", style = OcType.mono, color = c.ink4)
            Text(" · ", style = OcType.mono, color = c.ink4)
            val url = when {
                config.host.startsWith("http://") || config.host.startsWith("https://") ->
                    config.host.trimEnd('/')
                else ->
                    "${config.host}:${config.port}"
            }
            Text(url, style = OcType.mono, color = c.ink4)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    val c = LocalOcColors.current
    MonoLabel(text, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    val c = LocalOcColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp)
            .background(c.surface, RoundedCornerShape(14.dp))
            .padding(vertical = 2.dp),
    ) {
        content()
    }
}

@Composable
private fun SettingsRow(label: String, value: String) {
    val c = LocalOcColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = OcType.body, color = c.ink, modifier = Modifier.weight(1f))
        Text(value, style = OcType.mono, color = c.ink2)
    }
}

@Composable
private fun ThemeOption(label: String, selected: Boolean, c: com.opencode.android.ui.theme.OcColors, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .then(if (selected) Modifier.background(c.raised) else Modifier)
            .pressable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = OcType.monoStrong.copy(fontSize = 12.sp), color = if (selected) c.ink else c.ink3)
    }
}
