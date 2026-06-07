package com.opencode.android.workspace

import android.content.Context
import android.net.Uri
import com.opencode.android.runtime.WorkspacePaths
import java.io.File

data class ResolvedWorkspace(
    val runtimeDir: File,
    val treeUri: Uri?,
    val displayLabel: String,
    val usesSafBridge: Boolean,
)

object WorkspaceResolver {
    fun resolve(
        context: Context,
        workspaceName: String,
        workspaceTreeUri: String,
    ): ResolvedWorkspace {
        if (workspaceTreeUri.isNotBlank()) {
            SafWorkspaceBridge.requireAccess(context, workspaceTreeUri)
            val uri = Uri.parse(workspaceTreeUri)
            val bridge = SafWorkspaceBridge.bridgeDir(context.filesDir, workspaceTreeUri)
            val label = SafWorkspaceBridge.displayName(context, uri)
                ?: WorkspacePaths.sanitizeName(workspaceName)
            return ResolvedWorkspace(
                runtimeDir = bridge,
                treeUri = uri,
                displayLabel = label,
                usesSafBridge = true,
            )
        }
        val dir = WorkspacePaths.resolve(context.filesDir, workspaceName)
        WorkspacePaths.ensureReady(dir)
        val label = WorkspacePaths.sanitizeName(workspaceName)
        return ResolvedWorkspace(
            runtimeDir = dir,
            treeUri = null,
            displayLabel = label,
            usesSafBridge = false,
        )
    }

    fun runtimeDirectory(
        filesDir: File,
        workspaceName: String,
        workspaceTreeUri: String,
    ): String {
        if (workspaceTreeUri.isNotBlank()) {
            return SafWorkspaceBridge.bridgeDir(filesDir, workspaceTreeUri).absolutePath
        }
        return WorkspacePaths.absolutePath(filesDir, workspaceName)
    }
}
