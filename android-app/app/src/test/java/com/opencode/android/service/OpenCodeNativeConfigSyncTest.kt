package com.opencode.android.service

import com.opencode.android.data.model.McpConfigSource
import com.opencode.android.data.model.McpServerConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OpenCodeNativeConfigSyncTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun readsRemoteMcpAndPluginsFromNativeConfig() {
        val filesDir = tempDir("read-native")
        val configDir = OpenCodeNativeConfigSync.nativeConfigDir(filesDir)
        configDir.mkdirs()
        val configFile = File(configDir, "opencode.json")
        configFile.writeText(
            """{"mcp":{"context7":{"type":"remote","url":"https://mcp.example.com/sse","enabled":true,"headers":{"Authorization":"Bearer secret-token"}},"local-npx":{"type":"stdio","command":"npx"}},"plugin":["opencode-plugin-foo"]}""",
        )
        assertTrue(configFile.exists())
        File(configDir, "package.json").writeText(
            """{"dependencies":{"@opencode-ai/plugin":"1.0.0","@scope/bar":"2.0.0"}}""",
        )

        val snapshot = OpenCodeNativeConfigSync.read(filesDir)

        assertEquals(1, snapshot.mcpServers.size)
        assertEquals("context7", snapshot.mcpServers.first().name)
        assertEquals("secret-token", snapshot.mcpServers.first().token)
        assertTrue(snapshot.mcpServers.first().hasToken)
        assertTrue(snapshot.plugins.contains("opencode-plugin-foo"))
        assertTrue(snapshot.plugins.contains("@scope/bar@2.0.0"))
        assertFalse(snapshot.plugins.any { it.contains("@opencode-ai/plugin") })
        assertTrue(snapshot.defaultPluginPackagePresent)
    }

    @Test
    fun mergeAddsAgentEntriesWithoutDroppingAppPrefs() {
        val snapshot = OpenCodeNativeConfigSync.NativeSnapshot(
            mcpServers = listOf(
                OpenCodeNativeConfigSync.ImportedMcpServer("agent-mcp", "https://agent.example/mcp", hasToken = true),
            ),
            plugins = listOf("agent-plugin"),
        )
        val result = OpenCodeNativeConfigSync.mergeIntoPrefs(
            currentServers = listOf(McpServerConfig("app-mcp", "https://app.example/mcp")),
            currentPluginText = "app-plugin",
            currentDefaultPlugins = false,
            currentAgentPlugins = emptySet(),
            snapshot = snapshot,
        )

        assertTrue(result.changed)
        assertEquals(listOf("agent-mcp"), result.importedMcpNames)
        assertEquals(listOf("agent-plugin"), result.importedPluginSpecs)
        assertEquals(2, result.servers.size)
        assertEquals(McpConfigSource.AGENT, result.servers.first { it.name == "agent-mcp" }.source)
        assertTrue(result.pluginText.lines().contains("app-plugin"))
        assertTrue(result.pluginText.lines().contains("agent-plugin"))
        assertTrue(result.agentPluginSpecs.contains("agent-plugin"))
    }

    @Test
    fun mergeDropsAgentMcpRemovedFromNative() {
        val current = listOf(
            McpServerConfig("agent-mcp", "https://agent.example/mcp", source = McpConfigSource.AGENT),
            McpServerConfig("app-mcp", "https://app.example/mcp", source = McpConfigSource.APP),
        )
        val result = OpenCodeNativeConfigSync.mergeIntoPrefs(
            currentServers = current,
            currentPluginText = "",
            currentDefaultPlugins = false,
            currentAgentPlugins = setOf("agent-plugin"),
            snapshot = OpenCodeNativeConfigSync.NativeSnapshot(),
        )

        assertTrue(result.changed)
        assertEquals(1, result.servers.size)
        assertEquals("app-mcp", result.servers.first().name)
        assertTrue(result.agentPluginSpecs.isEmpty())
    }

    @Test
    fun readsBearerEnvRefWithoutRegexCrash() {
        val filesDir = tempDir("env-ref")
        val configDir = OpenCodeNativeConfigSync.nativeConfigDir(filesDir)
        configDir.mkdirs()
        File(configDir, "opencode.json").writeText(
            """{"mcp":{"ctx":{"type":"remote","url":"https://mcp.example/sse","headers":{"Authorization":"Bearer {env:OPENCODE_MCP_TOKEN_0}"}}}}""",
        )

        val snapshot = OpenCodeNativeConfigSync.read(filesDir)

        assertEquals(1, snapshot.mcpServers.size)
        assertTrue(snapshot.mcpServers.first().hasToken)
    }

    @Test
    fun writeClearsMcpAndPluginSectionsWhenEmpty() {
        val filesDir = tempDir("write-clear")
        val configDir = OpenCodeNativeConfigSync.nativeConfigDir(filesDir)
        configDir.mkdirs()
        File(configDir, "opencode.json").writeText(
            """{"mcp":{"old":{"type":"remote","url":"https://old.example/sse"}},"plugin":["old-plugin"]}""",
        )

        OpenCodeNativeConfigSync.write(filesDir, servers = emptyList(), pluginSpecs = emptyList(), tokensByName = emptyMap())

        val root = json.parseToJsonElement(File(configDir, "opencode.json").readText()).jsonObject
        assertFalse(root.containsKey("mcp"))
        assertFalse(root.containsKey("plugin"))
    }

    @Test
    fun parsesConfigWithHttpsUrlsWithoutStrippingSlashes() {
        val filesDir = tempDir("https-url")
        val configDir = OpenCodeNativeConfigSync.nativeConfigDir(filesDir)
        configDir.mkdirs()
        File(configDir, "opencode.json").writeText(
            """{"mcp":{"ctx":{"type":"remote","url":"https://mcp.example.com/sse"}}}""",
        )

        val snapshot = OpenCodeNativeConfigSync.read(filesDir)

        assertEquals(1, snapshot.mcpServers.size)
        assertEquals("https://mcp.example.com/sse", snapshot.mcpServers.first().url)
    }

    @Test
    fun writePreservesForeignKeysAndUsesEnvTokenRefs() {
        val filesDir = tempDir("write-native")
        val configDir = OpenCodeNativeConfigSync.nativeConfigDir(filesDir)
        configDir.mkdirs()
        File(configDir, "opencode.json").writeText("""{"agent":"keep-me"}""")

        OpenCodeNativeConfigSync.write(
            filesDir = filesDir,
            servers = listOf(McpServerConfig("ctx", "https://mcp.example/sse", hasToken = true)),
            pluginSpecs = listOf("plugin-a"),
            tokensByName = mapOf("ctx" to "tok-123"),
        )

        val root = json.parseToJsonElement(File(configDir, "opencode.json").readText()).jsonObject
        assertEquals("keep-me", root["agent"]!!.jsonPrimitive.content)
        val mcp = root["mcp"]!!.jsonObject["ctx"]!!.jsonObject
        assertEquals("remote", mcp["type"]!!.jsonPrimitive.content)
        assertEquals(
            "Bearer {env:OPENCODE_MCP_TOKEN_CTX}",
            mcp["headers"]!!.jsonObject["Authorization"]!!.jsonPrimitive.content,
        )
        assertEquals("plugin-a", root["plugin"]!!.jsonArray.first().jsonPrimitive.content)
        assertFalse(File(configDir, "opencode.json").readText().contains("tok-123"))
    }

    @Test
    fun stripJsonCommentsPreservesUrlsInJsonc() {
        val filesDir = tempDir("jsonc-url")
        val configDir = OpenCodeNativeConfigSync.nativeConfigDir(filesDir)
        configDir.mkdirs()
        // Write a .jsonc file with URLs that contain "//"
        File(configDir, "opencode.jsonc").writeText(
            """
            // This is a comment
            {
              "mcp": {
                "context7": {
                  "type": "remote",
                  "url": "https://mcp.example.com/sse",
                  "enabled": true
                }
              }
            }
            """.trimIndent(),
        )

        val snapshot = OpenCodeNativeConfigSync.read(filesDir)

        assertEquals(1, snapshot.mcpServers.size)
        assertEquals("https://mcp.example.com/sse", snapshot.mcpServers.first().url)
        assertEquals("context7", snapshot.mcpServers.first().name)
    }

    @Test
    fun stripJsonCommentsPreservesMultipleUrls() {
        val filesDir = tempDir("jsonc-multi-url")
        val configDir = OpenCodeNativeConfigSync.nativeConfigDir(filesDir)
        configDir.mkdirs()
        File(configDir, "opencode.jsonc").writeText(
            """{
              "mcp": {
                "a": {"type": "remote", "url": "https://a.example.com/path"},
                "b": {"type": "remote", "url": "http://b.local:8080/api"},
                "c": {"type": "remote", "url": "https://c.example.com/sse?q=1&r=2"}
              }
            }""",
        )

        val snapshot = OpenCodeNativeConfigSync.read(filesDir)

        assertEquals(3, snapshot.mcpServers.size)
        val urls = snapshot.mcpServers.map { it.url }.toSet()
        assertTrue(urls.contains("https://a.example.com/path"))
        assertTrue(urls.contains("http://b.local:8080/api"))
        assertTrue(urls.contains("https://c.example.com/sse?q=1&r=2"))
    }

    @Test
    fun stripBlockCommentsPreservesUrlsInStrings() {
        val filesDir = tempDir("jsonc-block-url")
        val configDir = OpenCodeNativeConfigSync.nativeConfigDir(filesDir)
        configDir.mkdirs()
        File(configDir, "opencode.jsonc").writeText(
            """{
              /* block comment about config */
              "mcp": {
                "svc": {"type": "remote", "url": "https://x.example.com/a/*keep*/b", "enabled": true}
              }
            }""",
        )

        val snapshot = OpenCodeNativeConfigSync.read(filesDir)

        assertEquals(1, snapshot.mcpServers.size)
        // The /*keep*/ inside the URL string must NOT be treated as a block comment
        assertEquals("https://x.example.com/a/*keep*/b", snapshot.mcpServers.first().url)
    }

    @Test
    fun writeUsesNameBasedTokenEnvVars() {
        val filesDir = tempDir("token-env-name")
        val configDir = OpenCodeNativeConfigSync.nativeConfigDir(filesDir)
        configDir.mkdirs()
        File(configDir, "opencode.json").writeText("{}")

        OpenCodeNativeConfigSync.write(
            filesDir = filesDir,
            servers = listOf(
                McpServerConfig("my-server", "https://mcp.example/sse", hasToken = true),
                McpServerConfig("another one!", "https://other.example/sse", hasToken = true),
            ),
            pluginSpecs = emptyList(),
            tokensByName = mapOf("my-server" to "secret-1", "another one!" to "secret-2"),
        )

        val configText = File(configDir, "opencode.json").readText()
        // Name-based env vars: non-alphanumeric chars replaced with _, then uppercased
        assertTrue("Should contain name-based token env for 'my-server'", configText.contains("OPENCODE_MCP_TOKEN_MY_SERVER"))
        assertTrue("Should contain name-based token env for 'another one!'", configText.contains("OPENCODE_MCP_TOKEN_ANOTHER_ONE"))
        // Should NOT contain index-based tokens
        assertFalse("Should not contain index-based OPENCODE_MCP_TOKEN_0", configText.contains("OPENCODE_MCP_TOKEN_0"))
        assertFalse("Should not contain index-based OPENCODE_MCP_TOKEN_1", configText.contains("OPENCODE_MCP_TOKEN_1"))
        // Should not contain raw tokens in plaintext
        assertFalse("Should not contain raw token secret-1", configText.contains("secret-1"))
        assertFalse("Should not contain raw token secret-2", configText.contains("secret-2"))
    }

    @Test
    fun writeRejectsMcpTokenEnvCollisions() {
        val filesDir = tempDir("token-env-collision")

        val failure = runCatching {
            OpenCodeNativeConfigSync.write(
                filesDir = filesDir,
                servers = listOf(
                    McpServerConfig("foo-bar", "https://mcp.example/sse", hasToken = true),
                    McpServerConfig("foo_bar", "https://other.example/sse", hasToken = true),
                ),
                pluginSpecs = emptyList(),
                tokensByName = emptyMap(),
            )
        }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("collision", ignoreCase = true))
    }

    @Test
    fun writeRejectsPublicHttpMcpServerInsteadOfDroppingIt() {
        val filesDir = tempDir("public-http-mcp")

        val failure = runCatching {
            OpenCodeNativeConfigSync.write(
                filesDir = filesDir,
                servers = listOf(McpServerConfig("docs", "http://example.com/sse")),
                pluginSpecs = emptyList(),
                tokensByName = emptyMap(),
            )
        }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("insecure endpoint", ignoreCase = true))
    }

    private fun tempDir(prefix: String): File =
        File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}").apply { mkdirs() }
}
