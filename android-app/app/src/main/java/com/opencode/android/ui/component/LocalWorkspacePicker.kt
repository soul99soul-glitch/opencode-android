package com.opencode.android.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.android.ui.theme.LocalOcColors
import com.opencode.android.ui.theme.OcType
import androidx.compose.ui.res.stringResource
import com.opencode.android.R
import com.opencode.android.data.model.LocalWorkspaceProfile

@Composable
fun LocalWorkspacePicker(
    profiles: List<LocalWorkspaceProfile>,
    selectedId: String,
    onSelect: (LocalWorkspaceProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalOcColors.current
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        profiles.forEach { profile ->
            val selected = profile.id == selectedId
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable { onSelect(profile) }
                    .background(if (selected) c.accent.copy(alpha = 0.12f) else c.surface2, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        profile.name,
                        style = OcType.body,
                        color = if (selected) c.accent else c.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (profile.treeUri.isNotBlank()) {
                        Text(
                            stringResource(R.string.workspace_linked_folder),
                            style = OcType.mono.copy(fontSize = 11.sp),
                            color = c.ink3,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    if (selected) stringResource(R.string.workspace_current) else stringResource(R.string.workspace_open),
                    style = OcType.mono.copy(fontSize = 11.sp),
                    color = if (selected) c.accent else c.ink3,
                )
            }
        }
    }
}
