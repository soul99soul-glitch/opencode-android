package com.opencode.android.ui.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.android.data.model.AgentInfo
import com.opencode.android.data.model.Provider
import com.opencode.android.ui.component.Hairline
import com.opencode.android.ui.component.pressable
import com.opencode.android.ui.theme.LocalOcColors
import com.opencode.android.ui.theme.OcType

@Composable
internal fun SettingsAgentSection(
    defaultAgent: String,
    availableAgents: List<AgentInfo>,
    onCycleAgent: () -> Unit,
    defaultModelProvider: String,
    defaultModelId: String,
    providers: List<Provider>,
    showModelPicker: Boolean,
    onToggleModelPicker: () -> Unit,
    expandedProviderId: String?,
    onToggleProvider: (String?) -> Unit,
    onSelectModel: (providerId: String, modelId: String) -> Unit,
) {
    val c = LocalOcColors.current

    SectionHeader("AGENT")
    SettingsCard {
        Row(
            Modifier
                .fillMaxWidth()
                .pressable { onCycleAgent() }
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
                .pressable { onToggleModelPicker() }
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
                            onToggleProvider(if (isExpanded) null else provider.id)
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
                                        onSelectModel(provider.id, modelId)
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
}
