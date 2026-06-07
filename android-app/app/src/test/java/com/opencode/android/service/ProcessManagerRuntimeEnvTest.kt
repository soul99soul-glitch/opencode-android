package com.opencode.android.service

import com.opencode.android.data.model.LocalProviderDefaults
import com.opencode.android.data.model.LocalProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProcessManagerRuntimeEnvTest {
    @Test
    fun bundledRuntimeEnvUsesOpencodeConfigAndProviderKeyOnly() {
        val filesDir = File(System.getProperty("java.io.tmpdir"), "opencode-runtime-env-${System.nanoTime()}").apply { mkdirs() }
        val profile = LocalProviderProfile(
            enabled = true,
            baseUrl = "http://127.0.0.1:11434/v1",
            modelIds = listOf("llama3"),
            hasApiKey = true,
        )
        val configFile = LocalOpenCodeConfigWriter.write(filesDir, profile, includeApiKeyRef = true).getOrThrow()

        val env = ProcessManager.bundledRuntimeEnv(filesDir, profile, "sk-test-secret")

        assertEquals(configFile.absolutePath, env["OPENCODE_CONFIG"])
        assertEquals("sk-test-secret", env[LocalProviderDefaults.API_KEY_ENV])
        assertFalse(env.containsKey("XDG_CONFIG_HOME"))
    }

    @Test
    fun bundledRuntimeEnvIsEmptyWhenProviderDisabled() {
        val filesDir = File(System.getProperty("java.io.tmpdir"), "opencode-runtime-env-disabled-${System.nanoTime()}").apply { mkdirs() }

        val env = ProcessManager.bundledRuntimeEnv(
            filesDir,
            LocalProviderProfile(enabled = false),
            "sk-test-secret",
        )

        assertTrue(env.isEmpty())
    }
}
