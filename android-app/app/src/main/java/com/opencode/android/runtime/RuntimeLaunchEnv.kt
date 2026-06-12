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
        workspaceDir: File? = null,
        includeProviderApiKey: Boolean = true,
        includeServerPassword: Boolean = true,
    ): Map<String, String> {
        val runtimeRoot = RuntimeSupport.currentRoot(filesDir)
        val tmpDir = File(cacheDir, "opencode-tmp").apply { mkdirs() }
        val runtimeBinDir = File(runtimeRoot, "bin")
        val gitExecDir = File(runtimeRoot, "libexec/git-core")
        val gitTemplateDir = File(runtimeRoot, "share/git-core/templates")
        val caBundle = File(runtimeRoot, "share/certs/ca-bundle.crt")
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
            "RUNTIME_ROOT" to runtimeRoot.absolutePath,
            "GLIBC_LD_SO" to File(nativeLibraryDir, "libglibc_loader.so").absolutePath,
            "GLIBC_LIB_PATH" to libraryPath,
            "LD_LIBRARY_PATH" to nativeLibraryDir.absolutePath,
            "SSL_CERT_FILE" to caBundle.absolutePath,
            "GIT_SSL_CAINFO" to caBundle.absolutePath,
            "GIT_EXEC_PATH" to gitExecDir.absolutePath,
            "GIT_TEMPLATE_DIR" to gitTemplateDir.absolutePath,
            "GIT_CONFIG_NOSYSTEM" to "1",
            "PATH" to listOf(
                runtimeBinDir.absolutePath,
                gitExecDir.absolutePath,
                nativeLibraryDir.absolutePath,
                "/system/bin",
                "/system/xbin",
            ).joinToString(":"),
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
        )
        workspaceDir?.let { workspace ->
            env["PWD"] = workspace.absolutePath
            env["OPENCODE_WORKSPACE"] = workspace.absolutePath
        }
        // Default opencode plugins trigger a background npm install on first run; only allow it
        // when the user opted in (it requires network and adds startup latency).
        if (!config.defaultPlugins) {
            env["OPENCODE_DISABLE_DEFAULT_PLUGINS"] = "1"
        }
        if (includeProviderApiKey && config.hasApiKey && providerApiKey.isNotBlank()) {
            env[RuntimeContract.PROVIDER_API_KEY_ENV] = providerApiKey
        }
        if (includeProviderApiKey) {
            // MCP bearer tokens — env indirection keeps them out of the generated config file.
            // Token env var is derived from the server name for stable references.
            config.mcpServers.forEach { server ->
                if (server.token.isNotBlank()) {
                    env[config.mcpTokenEnv(server)] = server.token
                }
            }
        }
        if (includeServerPassword && serverPassword.isNotBlank()) {
            env[RuntimeContract.SERVER_USERNAME_ENV] = "opencode"
            env[RuntimeContract.SERVER_PASSWORD_ENV] = serverPassword
        }
        return env
    }
}
