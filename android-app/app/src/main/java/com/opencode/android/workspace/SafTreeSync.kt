package com.opencode.android.workspace

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import android.util.Log
import java.io.File

/** Two-way sync between a SAF document tree and a local bridge directory. */
object SafTreeSync {
    private const val TAG = "SafTreeSync"

    /** When false (default), files missing from the bridge directory are NOT deleted from the
     *  external SAF folder. Enable only after user explicitly opts in. */
    var pruneRemoteEnabled = false

    fun syncDown(context: Context, treeUri: Uri, targetDir: File) {
        targetDir.mkdirs()
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return
        copyChildren(context, root, targetDir)
    }

    fun syncUp(context: Context, sourceDir: File, treeUri: Uri) {
        if (!sourceDir.exists()) return
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return
        uploadChildren(context, sourceDir, root)
    }

    private fun copyChildren(context: Context, parent: DocumentFile, targetDir: File) {
        val remoteChildren = parent.listFiles().filter { child ->
            SafSyncPolicy.shouldSync(child.name.orEmpty())
        }
        val remoteNames = remoteChildren.mapNotNull { it.name }.toSet()

        remoteChildren.forEach { child ->
            val name = child.name ?: return@forEach
            if (child.isDirectory) {
                val subDir = File(targetDir, name)
                subDir.mkdirs()
                copyChildren(context, child, subDir)
            } else {
                copyFile(context, child, File(targetDir, name))
            }
        }

        pruneLocalMissing(remoteNames, targetDir)
    }

    private fun copyFile(context: Context, source: DocumentFile, target: File) {
        target.parentFile?.mkdirs()
        val sourceModified = source.lastModified()
        if (target.exists() && target.lastModified() >= sourceModified && target.length() == source.length()) return
        context.contentResolver.openInputStream(source.uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        if (sourceModified > 0L) {
            target.setLastModified(sourceModified)
        }
    }

    private fun uploadChildren(context: Context, localDir: File, remoteDir: DocumentFile) {
        val localChildren = localDir.listFiles().orEmpty().filter { SafSyncPolicy.shouldSync(it.name) }
        val localNames = localChildren.map { it.name }.toSet()

        localChildren.forEach { local ->
            val name = local.name
            if (local.isDirectory) {
                val remoteChild = remoteDir.findFile(name) ?: remoteDir.createDirectory(name) ?: return@forEach
                uploadChildren(context, local, remoteChild)
            } else {
                val remoteChild = remoteDir.findFile(name)
                if (remoteChild == null || local.lastModified() > remoteChild.lastModified()) {
                    uploadFile(context, local, remoteDir)
                }
            }
        }

        pruneRemoteMissing(context, localNames, remoteDir)
    }

    private fun uploadFile(context: Context, local: File, parent: DocumentFile) {
        val name = local.name
        val existing = parent.findFile(name)
        if (existing != null && existing.isFile) {
            context.contentResolver.openOutputStream(existing.uri, "wt")?.use { output ->
                local.inputStream().use { input -> input.copyTo(output) }
            }
            return
        }
        existing?.delete()
        val remote = parent.createFile(guessMimeType(name), name) ?: return
        context.contentResolver.openOutputStream(remote.uri)?.use { output ->
            local.inputStream().use { input -> input.copyTo(output) }
        }
    }

    internal fun pruneLocalMissing(remoteNames: Set<String>, targetDir: File) {
        targetDir.listFiles()?.forEach { local ->
            val name = local.name
            if (!SafSyncPolicy.shouldSync(name)) return@forEach
            if (name !in remoteNames) {
                local.deleteRecursively()
            }
        }
    }

    private fun pruneRemoteMissing(context: Context, localNames: Set<String>, remoteDir: DocumentFile) {
        remoteDir.listFiles().forEach { remote ->
            val name = remote.name ?: return@forEach
            if (!SafSyncPolicy.shouldSync(name)) return@forEach
            if (name !in localNames) {
                if (pruneRemoteEnabled) {
                    Log.w(TAG, "Pruning remote file (not in bridge): $name")
                    remote.delete()
                } else {
                    Log.d(TAG, "Remote file not in bridge, but prune disabled — keeping: $name")
                }
            }
        }
    }

    private fun guessMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension.isNotBlank()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.let { return it }
        }
        return "application/octet-stream"
    }
}
