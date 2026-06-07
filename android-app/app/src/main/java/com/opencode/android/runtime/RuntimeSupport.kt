package com.opencode.android.runtime

import android.content.Context
import android.system.Os
import java.io.File

object RuntimeSupport {
    private const val ASSET_ROOT = "runtime_support"

    fun currentRoot(filesDir: File): File =
        File(File(filesDir, "runtime"), "current")

    fun ensureInstalled(context: Context): Result<File> = runCatching {
        val root = currentRoot(context.filesDir)
        val marker = File(root, ".support-version")
        val expectedVersion = supportVersion(context)
        if (marker.readTextOrNull() != expectedVersion) {
            val staging = File(root.parentFile, "staging-${System.nanoTime()}")
            if (staging.exists()) staging.deleteRecursively()
            staging.mkdirs()
            copyAssetTree(context, ASSET_ROOT, staging)
            ensureLibrarySonameLinks(staging)
            File(staging, ".support-version").writeText(expectedVersion)
            if (root.exists()) root.deleteRecursively()
            if (!staging.renameTo(root)) {
                staging.copyRecursively(root, overwrite = true)
                staging.deleteRecursively()
            }
        }
        ensureLibrarySonameLinks(root)
        preflight(root).getOrThrow()
        root
    }

    fun preflight(root: File): Result<Unit> = runCatching {
        val missing = requiredPaths(root).filterNot { it.exists() }
        require(missing.isEmpty()) {
            "OpenCode runtime support is incomplete: " + missing.joinToString { it.relativeTo(root).path }
        }
    }

    fun requiredPaths(root: File): List<File> = listOf(
        File(root, "lib/glibc/ld-linux-aarch64.so.1"),
        File(root, "lib/glibc/libstdc++.so.6"),
        File(root, "lib/openssl"),
        File(root, "share/certs/ca-bundle.crt"),
        File(root, "cache/providers/@ai-sdk/openai-compatible/ready.marker"),
        File(root, "lib/probe/libopencode_probe_dummy.so"),
    )

    private fun copyAssetTree(context: Context, assetPath: String, destination: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return
        }
        destination.mkdirs()
        children.forEach { child ->
            copyAssetTree(context, "$assetPath/$child", File(destination, child))
        }
    }

    fun ensureLibrarySonameLinks(root: File) {
        val glibcDir = File(root, "lib/glibc")
        val sonamePattern = Regex("""^(.+\.so\.\d+)\..+$""")
        glibcDir.listFiles().orEmpty().forEach { versioned ->
            val soname = sonamePattern.matchEntire(versioned.name)?.groupValues?.get(1) ?: return@forEach
            val link = File(glibcDir, soname)
            if (link.exists()) return@forEach
            runCatching {
                Os.symlink(versioned.name, link.absolutePath)
            }.recoverCatching {
                versioned.copyTo(link, overwrite = true)
            }
        }
    }

    private fun supportVersion(context: Context): String {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return "${packageInfo.versionName ?: "unknown"}:${packageInfo.lastUpdateTime}"
    }

    private fun File.readTextOrNull(): String? =
        runCatching { readText() }.getOrNull()
}
