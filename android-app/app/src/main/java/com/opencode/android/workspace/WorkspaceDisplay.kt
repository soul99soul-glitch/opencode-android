package com.opencode.android.workspace

import android.content.Context
import android.net.Uri
import com.opencode.android.data.model.ActiveEndpoint
import com.opencode.android.data.model.LocalProfile

object WorkspaceDisplay {
    fun bundledLabel(context: Context, local: LocalProfile): String {
        if (local.workspaceTreeUri.isBlank()) {
            return "内置：${local.workspacePath}"
        }
        val folderName = SafWorkspaceBridge.displayName(context, Uri.parse(local.workspaceTreeUri))
            ?: local.workspacePath
        return "外部：$folderName"
    }

    fun endpointDirectoryLabel(endpoint: ActiveEndpoint): String =
        endpoint.workspaceLabel.ifBlank { endpoint.directory.ifBlank { "—" } }
}
