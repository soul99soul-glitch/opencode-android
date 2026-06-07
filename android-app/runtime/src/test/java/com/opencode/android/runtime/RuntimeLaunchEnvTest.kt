package com.opencode.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuntimeLaunchEnvTest {
    @Test
    fun launchEnvContainsPhase0RequiredValues() {
        val filesDir = tempDir("files")
        val cacheDir = tempDir("cache")
        val nativeLibraryDir = tempDir("native")
        val config = RuntimeProviderConfig(
            enabled = true,
            displayName = "Android Local",
            baseUrl = "http://127.0.0.1:11434/v1",
            modelIds = listOf("phase0"),
            hasApiKey = true,
        )

        val env = RuntimeLaunchEnv.build(
            filesDir = filesDir,
            cacheDir = cacheDir,
            nativeLibraryDir = nativeLibraryDir,
            config = config,
            providerApiKey = "sk-test-secret",
            serverPassword = "runtime-secret",
        )

        assertEquals(filesDir.absolutePath, env["HOME"])
        assertEquals(RuntimeConfigWriter.generatedConfigFile(filesDir).absolutePath, env["OPENCODE_CONFIG"])
        assertEquals("1", env["OPENCODE_DISABLE_DEFAULT_PLUGINS"])
        val runtimeRoot = RuntimeSupport.currentRoot(filesDir)
        assertEquals(runtimeRoot.absolutePath, env["RUNTIME_ROOT"])
        assertEquals(File(runtimeRoot, "lib/glibc/ld-linux-aarch64.so.1").absolutePath, env["GLIBC_LD_SO"])
        assertTrue(env["GLIBC_LIB_PATH"]!!.contains(File(runtimeRoot, "lib/glibc").absolutePath))
        assertTrue(env["GLIBC_LIB_PATH"]!!.contains(File(runtimeRoot, "lib/openssl").absolutePath))
        assertTrue(env["GLIBC_LIB_PATH"]!!.contains(nativeLibraryDir.absolutePath))
        assertEquals(nativeLibraryDir.absolutePath, env["LD_LIBRARY_PATH"])
        assertEquals(File(runtimeRoot, "share/certs/ca-bundle.crt").absolutePath, env["SSL_CERT_FILE"])
        assertEquals("sk-test-secret", env[RuntimeContract.PROVIDER_API_KEY_ENV])
        assertEquals("opencode", env[RuntimeContract.SERVER_USERNAME_ENV])
        assertEquals("runtime-secret", env[RuntimeContract.SERVER_PASSWORD_ENV])
        assertFalse(env.containsKey("XDG_CONFIG_HOME"))
    }

    @Test
    fun versionPreflightEnvCanOmitProviderApiKey() {
        val filesDir = tempDir("files")
        val config = RuntimeProviderConfig(
            enabled = true,
            displayName = "Android Local",
            baseUrl = "http://127.0.0.1:11434/v1",
            modelIds = listOf("phase0"),
            hasApiKey = true,
        )

        val env = RuntimeLaunchEnv.build(
            filesDir = filesDir,
            cacheDir = tempDir("cache"),
            nativeLibraryDir = tempDir("native"),
            config = config,
            providerApiKey = "sk-test-secret",
            includeProviderApiKey = false,
            includeServerPassword = false,
        )

        assertFalse(env.containsKey(RuntimeContract.PROVIDER_API_KEY_ENV))
        assertFalse(env.containsKey(RuntimeContract.SERVER_PASSWORD_ENV))
        assertFalse(env.containsKey(RuntimeContract.SERVER_USERNAME_ENV))
    }

    private fun tempDir(prefix: String): File =
        File(System.getProperty("java.io.tmpdir"), "opencode-runtime-env-$prefix-${System.nanoTime()}").apply { mkdirs() }
}
