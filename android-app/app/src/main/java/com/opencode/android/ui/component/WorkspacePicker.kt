package com.opencode.android.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.android.data.api.WorkspaceOption
import com.opencode.android.ui.theme.LocalOcColors
import com.opencode.android.ui.theme.OcType

@Composable
fun WorkspacePicker(
    options: List<WorkspaceOption>,
    selectedPath: String,
    pinnedPaths: Set<String> = emptySet(),
    loading: Boolean,
    error: String?,
    onLoad: () -> Unit,
    onSelect: (String) -> Unit,
    onTogglePinned: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val c = LocalOcColors.current
    val groupedOptions = remember(options, pinnedPaths) {
        options.partition { it.path in pinnedPaths }
    }
    val pinnedOptions = groupedOptions.first
    val otherOptions = groupedOptions.second

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MonoLabel("> WORKSPACES")
            Spacer(Modifier.weight(1f))
            Text(
                if (loading) "loading..." else "Load",
                style = OcType.mono,
                color = if (loading) c.ink4 else c.accent,
                modifier = Modifier.pressable(enabled = !loading, onClick = onLoad),
            )
        }

        if (error != null) {
            Text(error, style = OcType.mono, color = c.accent.copy(alpha = 0.8f))
        }

        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (pinnedOptions.isNotEmpty()) {
                MonoLabel("PINNED", modifier = Modifier.padding(horizontal = 2.dp))
                pinnedOptions.forEach { option ->
                    WorkspaceOptionRow(
                        option = option,
                        selected = option.path == selectedPath,
                        onSelect = onSelect,
                        onTogglePinned = onTogglePinned,
                    )
                }
            }
            if (pinnedOptions.isNotEmpty() && otherOptions.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                MonoLabel("ALL", modifier = Modifier.padding(horizontal = 2.dp))
            }
            otherOptions.forEach { option ->
                WorkspaceOptionRow(
                    option = option,
                    selected = option.path == selectedPath,
                    onSelect = onSelect,
                    onTogglePinned = onTogglePinned,
                )
            }
        }
    }
}

@Composable
private fun WorkspaceOptionRow(
    option: WorkspaceOption,
    selected: Boolean,
    onSelect: (String) -> Unit,
    onTogglePinned: ((String) -> Unit)?,
) {
    val c = LocalOcColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .pressable(
                onLongClick = onTogglePinned?.let { toggle -> { toggle(option.path) } },
            ) { onSelect(option.path) }
            .background(if (selected) c.accent.copy(alpha = 0.12f) else c.surface2, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    option.label,
                    style = OcType.body,
                    color = when {
                        selected -> c.accent
                        option.sessionCount == 0 -> c.ink3
                        else -> c.ink
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${option.sessionCount} sessions",
                    style = OcType.mono.copy(fontSize = 11.sp),
                    color = c.ink3,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                option.path,
                style = OcType.mono.copy(fontSize = 11.sp),
                color = c.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
