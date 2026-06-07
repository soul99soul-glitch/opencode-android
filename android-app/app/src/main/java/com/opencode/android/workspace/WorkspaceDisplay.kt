package com.opencode.android.workspace

import android.content.Context
import android.net.Uri
import com.opencode.android.data.model.ActiveEndpoint
import com.opencode.android.data.model.LocalProfile
import com.opencode.android.R

object WorkspaceDisplay {
    fun bundledLabel(context: Context, local: LocalProfile): String {
        if (local.workspaceTreeUri.isBlank()) {
            return context.getString(R.string.setup_workspace_internal, local.workspacePath)
        }
        val folderName = SafWorkspaceBridge.displayName(context, Uri.parse(local.workspaceTreeUri))
            ?: local.workspacePath
        return context.getString(R.string.setup_workspace_external, folderName)
    }

    fun endpointDirectoryLabel(endpoint: ActiveEndpoint): String =
        endpoint.workspaceLabel.ifBlank { endpoint.directory.ifBlank { "—" } }
}
