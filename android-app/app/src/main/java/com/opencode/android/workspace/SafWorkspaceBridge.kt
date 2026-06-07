package com.opencode.android.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.security.MessageDigest

/** Maps a SAF tree URI to a POSIX directory the bundled runtime can use. */
object SafWorkspaceBridge {
    fun bridgeId(treeUri: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(treeUri.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    fun bridgeDir(filesDir: File, treeUri: String): File =
        File(File(File(filesDir, "workspaces"), ".saf-bridge"), bridgeId(treeUri))

    fun takePersistablePermission(context: Context, treeUri: Uri): Result<Unit> = runCatching {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(treeUri, flags)
    }

    fun requireAccess(context: Context, workspaceTreeUri: String) {
        val msg = context.getString(com.opencode.android.R.string.error_external_folder_permission_lost)
        if (!hasPersistedPermission(context, workspaceTreeUri)) {
            throw IllegalStateException(msg)
        }
        val uri = Uri.parse(workspaceTreeUri)
        val root = DocumentFile.fromTreeUri(context, uri)
            ?: throw IllegalStateException(msg)
        if (!root.canRead() || !root.canWrite()) {
            throw IllegalStateException(msg)
        }
    }

    const val EXTERNAL_FOLDER_PERMISSION_LOST =
        "External folder access permission has expired. Please re-select the folder in Settings > Browse."

    fun displayName(context: Context, treeUri: Uri): String? {
        DocumentFile.fromTreeUri(context, treeUri)?.name?.takeIf { it.isNotBlank() }?.let { return it }
        return runCatching {
            context.contentResolver.query(
                treeUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun hasPersistedPermission(context: Context, treeUri: String): Boolean {
        if (treeUri.isBlank()) return false
        val uri = Uri.parse(treeUri)
        return context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri &&
                permission.isReadPermission &&
                permission.isWritePermission
        }
    }
}
