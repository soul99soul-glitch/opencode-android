package com.opencode.android.runtime

import java.io.File

object RuntimeLaunchEnv {
    fun build(
        filesDir: File,
        cacheDir: File,
        nativeLibraryDir: File,
        config: RuntimeProviderConfig,
        providerApiKey: String,
        serverPassword: String = "",
        includeProviderApiKey: Boolean = true,
        includeServerPassword: Boolean = true,
    ): Map<String, String> {
        val runtimeRoot = RuntimeSupport.currentRoot(filesDir)
        val tmpDir = File(cacheDir, "opencode-tmp").apply { mkdirs() }
        val glibcLibDir = File(runtimeRoot, "lib/glibc")
        val opensslLibDir = File(runtimeRoot, "lib/openssl")
        val libraryPath = listOf(
            glibcLibDir.absolutePath,
            opensslLibDir.absolutePath,
            nativeLibraryDir.absolutePath,
        ).joinToString(":")
        val env = linkedMapOf(
            "HOME" to filesDir.absolutePath,
            "TMPDIR" to tmpDir.absolutePath,
            "XDG_CACHE_HOME" to File(filesDir, "cache").absolutePath,
            "XDG_DATA_HOME" to File(filesDir, "data").absolutePath,
            "XDG_STATE_HOME" to File(filesDir, "state").absolutePath,
            "OPENCODE_CONFIG" to RuntimeConfigWriter.generatedConfigFile(filesDir).absolutePath,
            "OPENCODE_DISABLE_DEFAULT_PLUGINS" to "1",
            "RUNTIME_ROOT" to runtimeRoot.absolutePath,
            "GLIBC_LD_SO" to File(glibcLibDir, "ld-linux-aarch64.so.1").absolutePath,
            "GLIBC_LIB_PATH" to libraryPath,
            "LD_LIBRARY_PATH" to nativeLibraryDir.absolutePath,
            "SSL_CERT_FILE" to File(runtimeRoot, "share/certs/ca-bundle.crt").absolutePath,
            "PATH" to "/system/bin:/system/xbin:${nativeLibraryDir.absolutePath}",
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
        )
        if (includeProviderApiKey && config.hasApiKey && providerApiKey.isNotBlank()) {
            env[RuntimeContract.PROVIDER_API_KEY_ENV] = providerApiKey
        }
        if (includeServerPassword && serverPassword.isNotBlank()) {
            env[RuntimeContract.SERVER_USERNAME_ENV] = "opencode"
            env[RuntimeContract.SERVER_PASSWORD_ENV] = serverPassword
        }
        return env
    }
}
