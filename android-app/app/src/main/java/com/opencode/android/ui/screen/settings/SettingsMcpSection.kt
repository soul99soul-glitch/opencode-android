package com.opencode.android.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.android.R
import com.opencode.android.ui.component.Hairline
import com.opencode.android.ui.component.pressable
import com.opencode.android.ui.theme.LocalOcColors
import com.opencode.android.ui.theme.OcType

@Composable
internal fun SettingsMcpSection(
    mcpRows: List<McpRowDraft>,
    onMcpRowChange: (Int, McpRowDraft) -> Unit,
    onMcpRowRemove: (Int) -> Unit,
    onAddMcpRow: () -> Unit,
    pluginsDraft: String,
    onPluginsChange: (String) -> Unit,
    savedAgentPlugins: Set<String>,
    defaultPluginsDraft: Boolean,
    onToggleDefaultPlugins: () -> Unit,
    onRefreshFromAgent: () -> Unit,
    onApply: () -> Unit,
    mcpStatus: String?,
) {
    val c = LocalOcColors.current

    SectionHeader(stringResource(R.string.mcp_section_title))
    SettingsCard {
        mcpRows.forEachIndexed { index, row ->
            if (index > 0) Hairline()
            McpServerEditor(
                row = row,
                fromAgent = row.source == com.opencode.android.data.model.McpConfigSource.AGENT,
                onChange = { updated -> onMcpRowChange(index, updated) },
                onRemove = { onMcpRowRemove(index) },
            )
        }
        Hairline()
        Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
            LocalAction(stringResource(R.string.mcp_add_remote), enabled = true, modifier = Modifier.fillMaxWidth()) {
                onAddMcpRow()
            }
        }
        Hairline()
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.mcp_plugins_label), style = OcType.body, color = c.ink)
            BasicTextField(
                value = pluginsDraft,
                onValueChange = onPluginsChange,
                cursorBrush = SolidColor(c.accent),
                textStyle = OcType.mono.copy(color = c.ink2, fontSize = 12.sp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                decorationBox = { inner ->
                    if (pluginsDraft.isBlank()) {
                        Text(
                            stringResource(R.string.mcp_plugins_placeholder),
                            style = OcType.mono.copy(fontSize = 12.sp),
                            color = c.ink4,
                        )
                    }
                    inner()
                },
            )
            if (savedAgentPlugins.isNotEmpty()) {
                Text(
                    "From agent: ${savedAgentPlugins.joinToString(", ")}",
                    style = OcType.mono.copy(fontSize = 11.sp),
                    color = c.ink3,
                )
            }
        }
        Hairline()
        Row(
            Modifier
                .fillMaxWidth()
                .pressable { onToggleDefaultPlugins() }
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.mcp_default_plugins), style = OcType.body, color = c.ink, modifier = Modifier.weight(1f))
            Text(
                if (defaultPluginsDraft) stringResource(R.string.settings_value_on) else stringResource(R.string.settings_value_off),
                style = OcType.mono,
                color = if (defaultPluginsDraft) c.accent else c.ink3,
            )
        }
        Hairline()
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                LocalAction(
                    stringResource(R.string.mcp_refresh_from_agent),
                    enabled = true,
                    modifier = Modifier.weight(1f),
                ) { onRefreshFromAgent() }
                LocalAction(stringResource(R.string.settings_action_apply), enabled = true, modifier = Modifier.weight(1f)) { onApply() }
            }
            Text(
                mcpStatus ?: stringResource(R.string.mcp_sync_info),
                style = OcType.mono.copy(fontSize = 11.sp),
                color = c.ink3,
            )
        }
    }
}
