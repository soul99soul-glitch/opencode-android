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
        if (!hasPersistedPermission(context, workspaceTreeUri)) {
            throw IllegalStateException(EXTERNAL_FOLDER_PERMISSION_LOST)
        }
        val uri = Uri.parse(workspaceTreeUri)
        val root = DocumentFile.fromTreeUri(context, uri)
            ?: throw IllegalStateException(EXTERNAL_FOLDER_PERMISSION_LOST)
        if (!root.canRead() || !root.canWrite()) {
            throw IllegalStateException(EXTERNAL_FOLDER_PERMISSION_LOST)
        }
    }

    const val EXTERNAL_FOLDER_PERMISSION_LOST =
        "外部文件夹访问权限已失效，请在设置中重新 Browse 选择文件夹。"

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
