package com.opencode.android.ui.screen.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opencode.android.data.api.WorkspaceOption
import com.opencode.android.ui.component.WorkspacePicker

@Composable
internal fun SettingsWorkspaceSection(
    workspaces: List<WorkspaceOption>,
    selectedPath: String,
    pinnedPaths: Set<String>,
    loading: Boolean,
    error: String?,
    onLoad: () -> Unit,
    onSelect: (String) -> Unit,
    onTogglePinned: (String) -> Unit,
) {
    SectionHeader("WORKSPACE")
    WorkspacePicker(
        options = workspaces,
        selectedPath = selectedPath,
        pinnedPaths = pinnedPaths,
        loading = loading,
        error = error,
        onLoad = onLoad,
        onSelect = onSelect,
        onTogglePinned = onTogglePinned,
        modifier = Modifier.padding(horizontal = 22.dp),
    )
}
