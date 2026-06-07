package com.opencode.android.runtime

import android.content.Context
import java.io.File

/** Persists which workspace directory the bundled runtime is serving across service restarts. */
object RuntimeWorkspaceState {
    private const val FILE_NAME = "runtime-active-workspace.txt"

    fun write(context: Context, absolutePath: String) {
        write(context.filesDir, absolutePath)
    }

    fun read(context: Context): String? = read(context.filesDir)

    fun clear(context: Context) {
        clear(context.filesDir)
    }

    fun write(filesDir: File, absolutePath: String) {
        stateFile(filesDir).writeText(absolutePath)
    }

    fun read(filesDir: File): String? =
        stateFile(filesDir).takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotBlank() }

    fun clear(filesDir: File) {
        stateFile(filesDir).delete()
    }

    private fun stateFile(filesDir: File): File = File(filesDir, FILE_NAME)
}
