package com.opencode.android.runtime

import java.io.File

/** Maps UI workspace names to on-disk directories under app private storage. */
object WorkspacePaths {
    fun sanitizeName(input: String): String =
        input.substringAfterLast('/')
            .trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-', '.', '_')
            .ifBlank { "default" }
            .take(64)

    fun resolve(filesDir: File, workspaceName: String): File =
        File(File(filesDir, "workspaces"), sanitizeName(workspaceName))

    fun absolutePath(filesDir: File, workspaceName: String): String =
        resolve(filesDir, workspaceName).absolutePath

    /** Create the workspace folder and a small README so `ls` is not empty on first run. */
    fun ensureReady(workspace: File) {
        workspace.mkdirs()
        val readme = File(workspace, "README.md")
        if (!readme.exists()) {
            readme.writeText(
                """
                # OpenCode Workspace

                This directory is the agent working folder. Save project files here.

                """.trimIndent() + "\n",
            )
        }
    }
}
