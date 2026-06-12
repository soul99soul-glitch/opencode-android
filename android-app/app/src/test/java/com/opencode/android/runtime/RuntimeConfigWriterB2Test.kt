package com.opencode.android.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class RuntimeConfigWriterB2Test {

    @Test
    fun generatedConfigDoesNotContainMcpOrPlugin() {
        val filesDir = File(System.getProperty("java.io.tmpdir"), "no-mcp-${System.nanoTime()}").apply { mkdirs() }
        val config = RuntimeProviderConfig(
            enabled = true,
            displayName = "Test",
            baseUrl = "https://api.example.com/v1",
            modelIds = listOf("gpt-4"),
            hasApiKey = true,
            mcpServers = listOf(RuntimeMcpServer("ctx", "https://mcp.example.com/sse", "tok")),
            plugins = listOf("my-plugin"),
        )

        RuntimeConfigWriter.write(filesDir, config).getOrThrow()
        val text = RuntimeConfigWriter.generatedConfigFile(filesDir).readText()

        assertFalse("generated config must not contain 'mcp' section", text.contains("\"mcp\""))
        assertFalse("generated config must not contain 'plugin' section", text.contains("\"plugin\""))
        assertTrue("generated config must contain provider", text.contains("\"provider\""))
    }

    @Test
    fun rejectsPublicHttpProviderBaseUrl() {
        val filesDir = File(System.getProperty("java.io.tmpdir"), "public-http-${System.nanoTime()}").apply { mkdirs() }
        val config = RuntimeProviderConfig(
            enabled = true,
            displayName = "Test",
            baseUrl = "http://example.com/v1",
            modelIds = listOf("gpt-4"),
            hasApiKey = true,
        )

        assertNotNull(RuntimeConfigWriter.write(filesDir, config).exceptionOrNull())
    }
}
