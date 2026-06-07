package com.opencode.android.runtime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

object RuntimeApkInstaller {
    fun runtimeDownloadsDir(context: Context): File =
        File(context.filesDir, "runtime-downloads").apply { mkdirs() }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verifyDownloadedApk(context: Context, apkFile: File, manifest: RuntimeReleaseManifest): String? {
        if (!apkFile.isFile) return "Runtime APK file is missing"
        val actualSha256 = sha256(apkFile)
        if (!actualSha256.equals(manifest.apkSha256, ignoreCase = true)) {
            return "Runtime APK checksum does not match"
        }
        val signingDigests = signingCertSha256Digests(context, apkFile)
        if (signingDigests.isEmpty()) return "Runtime APK signing certificate is missing"
        if (signingDigests.none { it.equals(manifest.signingCertSha256, ignoreCase = true) }) {
            return "Runtime APK signing certificate does not match"
        }
        return null
    }

    fun buildInstallIntent(context: Context, apkFile: File, manifest: RuntimeReleaseManifest): Intent {
        verifyDownloadedApk(context, apkFile, manifest)?.let { throw IllegalArgumentException(it) }
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.runtimefiles",
            apkFile,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    @Suppress("DEPRECATION")
    private fun signingCertSha256Digests(context: Context, apkFile: File): List<String> {
        val packageManager = context.packageManager
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES,
            ) ?: return emptyList()
        } else {
            packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.GET_SIGNATURES,
            ) ?: return emptyList()
        }

        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = packageInfo.signingInfo ?: return emptyList()
            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        } else {
            packageInfo.signatures ?: return emptyList()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }
}
