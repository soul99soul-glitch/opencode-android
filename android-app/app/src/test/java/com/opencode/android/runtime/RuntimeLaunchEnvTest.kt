package com.opencode.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuntimeLaunchEnvTest {

    @Test
    fun gitRuntimePathsAreInjectedAheadOfSystemPath() {
        val root = File(System.getProperty("java.io.tmpdir"), "runtime-env-${System.nanoTime()}")
        val filesDir = File(root, "files").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }
        val nativeLibraryDir = File(root, "native").apply { mkdirs() }

        val env = RuntimeLaunchEnv.build(
            filesDir = filesDir,
            cacheDir = cacheDir,
            nativeLibraryDir = nativeLibraryDir,
            config = RuntimeProviderConfig(
                enabled = true,
                displayName = "Test",
                baseUrl = "https://api.example.com/v1",
                modelIds = listOf("model"),
                hasApiKey = false,
            ),
            providerApiKey = "",
        )

        val runtimeRoot = RuntimeSupport.currentRoot(filesDir)
        assertEquals(File(runtimeRoot, "libexec/git-core").absolutePath, env["GIT_EXEC_PATH"])
        assertEquals(File(runtimeRoot, "share/git-core/templates").absolutePath, env["GIT_TEMPLATE_DIR"])
        assertEquals(File(runtimeRoot, "share/certs/ca-bundle.crt").absolutePath, env["GIT_SSL_CAINFO"])
        assertEquals(File(nativeLibraryDir, "libglibc_loader.so").absolutePath, env["GLIBC_LD_SO"])
        assertEquals("1", env["GIT_CONFIG_NOSYSTEM"])

        val pathEntries = env.getValue("PATH").split(":")
        assertEquals(File(runtimeRoot, "bin").absolutePath, pathEntries[0])
        assertEquals(File(runtimeRoot, "libexec/git-core").absolutePath, pathEntries[1])
        assertEquals(nativeLibraryDir.absolutePath, pathEntries[2])
        assertTrue(pathEntries.contains("/system/bin"))
    }
}
