package com.opencode.android.workspace

import android.content.Context
import android.net.Uri
import com.opencode.android.runtime.WorkspacePaths
import java.io.File
import java.io.IOException

data class ResolvedWorkspace(
    val runtimeDir: File,
    val treeUri: Uri?,
    val displayLabel: String,
    val usesSafBridge: Boolean,
)

object WorkspaceResolver {
    /**
     * Resolve symlinks so the path matches the server's own CWD resolution.
     * On Android `/data/user/0/<pkg>` is a bind mount of `/data/data/<pkg>`;
     * the native runtime resolves to the latter via getcwd(), so we must
     * send the canonical form to avoid directory-filter mismatches.
     */
    private fun File.canonicalOrAbsolute(): File = try {
        canonicalFile
    } catch (_: IOException) {
        absoluteFile
    }

    fun resolve(
        context: Context,
        workspaceName: String,
        workspaceTreeUri: String,
    ): ResolvedWorkspace {
        if (workspaceTreeUri.isNotBlank()) {
            SafWorkspaceBridge.requireAccess(context, workspaceTreeUri)
            val uri = Uri.parse(workspaceTreeUri)
            val bridge = SafWorkspaceBridge.bridgeDir(context.filesDir, workspaceTreeUri).canonicalOrAbsolute()
            val label = SafWorkspaceBridge.displayName(context, uri)
                ?: WorkspacePaths.sanitizeName(workspaceName)
            return ResolvedWorkspace(
                runtimeDir = bridge,
                treeUri = uri,
                displayLabel = label,
                usesSafBridge = true,
            )
        }
        val dir = WorkspacePaths.resolve(context.filesDir, workspaceName).canonicalOrAbsolute()
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
        val file = if (workspaceTreeUri.isNotBlank()) {
            SafWorkspaceBridge.bridgeDir(filesDir, workspaceTreeUri)
        } else {
            WorkspacePaths.resolve(filesDir, workspaceName)
        }
        return try { file.canonicalPath } catch (_: IOException) { file.absolutePath }
    }
}
