package com.opencode.android.service

import com.opencode.android.data.model.McpConfigSource
import com.opencode.android.data.model.McpServerConfig
import com.opencode.android.data.model.EndpointSecurityPolicy
import com.opencode.android.data.model.parsePluginSpecs
import com.opencode.android.runtime.RuntimeContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/** Reads and writes MCP / plugin sections in the agent-native opencode config directory. */
object OpenCodeNativeConfigSync {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val CONFIG_FILENAMES = listOf("opencode.json", "config.json", "opencode.jsonc")
    private val DEFAULT_PLUGIN_PACKAGE = "@opencode-ai/plugin"
    private val INTERNAL_PACKAGE_DEPS = setOf(DEFAULT_PLUGIN_PACKAGE, "@opencode-ai/sdk")
    private val BEARER_PREFIX = Regex("^Bearer\\s+(.+)$", RegexOption.IGNORE_CASE)

    data class NativeSnapshot(
        val mcpServers: List<ImportedMcpServer> = emptyList(),
        val plugins: List<String> = emptyList(),
        val defaultPluginPackagePresent: Boolean = false,
    )

    data class ImportedMcpServer(
        val name: String,
        val url: String,
        val token: String? = null,
        val hasToken: Boolean = false,
    )

    fun nativeConfigDir(filesDir: File): File = File(filesDir, ".config/opencode")

    fun read(filesDir: File): NativeSnapshot {
        val mcpByName = linkedMapOf<String, ImportedMcpServer>()
        val plugins = linkedSetOf<String>()

        CONFIG_FILENAMES.forEach { filename ->
            val file = File(nativeConfigDir(filesDir), filename)
            if (!file.exists()) return@forEach
            val allowComments = filename.endsWith(".jsonc")
            val root = runCatching { parseRoot(file.readText(), allowComments) }.getOrNull() ?: return@forEach
            parseMcpSection(root).forEach { entry -> mcpByName[entry.name] = entry }
            parsePluginSection(root).forEach { plugins.add(it) }
        }

        val packagePlugins = readPackageJsonPlugins(filesDir)
        packagePlugins.forEach { plugins.add(it) }

        return NativeSnapshot(
            mcpServers = mcpByName.values.toList(),
            plugins = plugins.toList(),
            defaultPluginPackagePresent = hasDefaultPluginPackage(filesDir),
        )
    }

    fun write(
        filesDir: File,
        servers: List<McpServerConfig>,
        pluginSpecs: List<String>,
        tokensByName: Map<String, String>,
    ): File {
        val configDir = nativeConfigDir(filesDir)
        configDir.mkdirs()
        val configFile = File(configDir, "opencode.json")
        val existing = if (configFile.exists()) {
            runCatching { parseRoot(configFile.readText(), allowComments = false) }.getOrElse { JsonObject(emptyMap()) }
        } else {
            JsonObject(emptyMap())
        }

        val validServers = servers.filter {
            it.name.isNotBlank() && it.url.trim().startsWith("http", ignoreCase = true)
        }
        validServers.firstNotNullOfOrNull { server ->
            EndpointSecurityPolicy.publicCleartextBlockMessage(server.url.trim())?.let { message ->
                server.name.trim() to message
            }
        }?.let { (name, message) ->
            throw IllegalArgumentException("MCP server $name uses an insecure endpoint: $message")
        }
        val collisions = RuntimeContract.mcpTokenEnvCollisions(validServers.map { it.name.trim() })
        require(collisions.isEmpty()) {
            "MCP token env collision: " + collisions.values.joinToString { it.joinToString(" / ") }
        }
        val validPlugins = pluginSpecs.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        val merged = buildJsonObject {
            existing.forEach { (key, value) ->
                if (key != "mcp" && key != "plugin") put(key, value)
            }
            if (validServers.isNotEmpty()) {
                putJsonObject("mcp") {
                    validServers.forEach { server ->
                        val token = tokensByName[server.name].orEmpty().trim()
                        val tokenEnv = RuntimeContract.mcpTokenEnvForName(server.name.trim())
                        putJsonObject(server.name.trim()) {
                            put("type", "remote")
                            put("url", server.url.trim())
                            put("enabled", true)
                            if (token.isNotBlank()) {
                                putJsonObject("headers") {
                                    put("Authorization", "Bearer {env:$tokenEnv}")
                                }
                            } else if (server.hasToken) {
                                putJsonObject("headers") {
                                    put("Authorization", "Bearer {env:$tokenEnv}")
                                }
                            }
                        }
                    }
                }
            }
            if (validPlugins.isNotEmpty()) {
                putJsonArray("plugin") {
                    validPlugins.forEach { add(it) }
                }
            }
        }

        configFile.writeText(json.encodeToString(JsonObject.serializer(), merged))
        return configFile
    }

    fun mergeIntoPrefs(
        currentServers: List<McpServerConfig>,
        currentPluginText: String,
        currentDefaultPlugins: Boolean,
        currentAgentPlugins: Set<String>,
        snapshot: NativeSnapshot,
    ): MergeResult {
        val importedMcp = mutableListOf<String>()
        val importedPlugins = mutableListOf<String>()
        val appServers = currentServers
            .filter { it.source != McpConfigSource.AGENT }
            .associateBy { it.name }
            .toMutableMap()
        val mergedServers = linkedMapOf<String, McpServerConfig>()
        mergedServers.putAll(appServers)

        snapshot.mcpServers.forEach { native ->
            if (native.name in appServers) return@forEach
            val previous = currentServers.find { it.name == native.name }
            val entry = McpServerConfig(
                name = native.name,
                url = native.url,
                hasToken = native.hasToken || !native.token.isNullOrBlank() || previous?.hasToken == true,
                source = McpConfigSource.AGENT,
            )
            if (previous == null) importedMcp += native.name
            mergedServers[native.name] = entry
        }

        val currentPlugins = parsePluginSpecs(currentPluginText)
        val mergedPlugins = (currentPlugins + snapshot.plugins).distinct()
        snapshot.plugins.filter { it !in currentPlugins }.forEach { importedPlugins += it }
        val mergedAgentPlugins = (currentAgentPlugins + importedPlugins).filter { it in mergedPlugins }.toSet()

        var defaultPlugins = currentDefaultPlugins
        var defaultPluginsChanged = false
        if (snapshot.defaultPluginPackagePresent && !currentDefaultPlugins) {
            defaultPlugins = true
            defaultPluginsChanged = true
        }

        val servers = mergedServers.values.toList()
        val serversChanged = servers != currentServers
        val pluginsChanged = mergedPlugins != currentPlugins
        val agentPluginsChanged = mergedAgentPlugins != currentAgentPlugins

        return MergeResult(
            servers = servers,
            pluginText = mergedPlugins.joinToString("\n"),
            agentPluginSpecs = mergedAgentPlugins,
            defaultPluginsEnabled = defaultPlugins,
            importedMcpNames = importedMcp,
            importedPluginSpecs = importedPlugins,
            changed = serversChanged || pluginsChanged || defaultPluginsChanged || agentPluginsChanged,
            tokensToImport = snapshot.mcpServers
                .mapNotNull { entry ->
                    val token = entry.token?.trim().orEmpty()
                    if (token.isNotBlank()) entry.name to token else null
                }
                .toMap(),
        )
    }

    data class MergeResult(
        val servers: List<McpServerConfig>,
        val pluginText: String,
        val agentPluginSpecs: Set<String>,
        val defaultPluginsEnabled: Boolean,
        val importedMcpNames: List<String>,
        val importedPluginSpecs: List<String>,
        val changed: Boolean,
        val tokensToImport: Map<String, String>,
    )

    private fun parseRoot(raw: String, allowComments: Boolean): JsonObject {
        val trimmed = if (allowComments) stripJsonComments(raw.trim()) else raw.trim()
        val element = json.parseToJsonElement(trimmed)
        return element.jsonObject
    }

    private fun stripJsonComments(raw: String): String {
        // Both block and line comment stripping must be string-aware to avoid
        // corrupting URLs like "https://example.com" or patterns like "/*keep*/".
        val withoutBlock = stripBlockComments(raw)
        return withoutBlock.lineSequence()
            .map { line -> stripLineComment(line) }
            .joinToString("\n")
    }

    /**
     * Strip `/* ... */` block comments only when they appear outside double-quoted strings.
     * Uses the same quote-tracking approach as [stripLineComment].
     */
    private fun stripBlockComments(text: String): String {
        val result = StringBuilder(text.length)
        var inString = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\\' && inString) {
                result.append(text, i, minOf(i + 2, text.length))
                i += 2
                continue
            }
            if (ch == '"') {
                inString = !inString
                result.append(ch)
                i++
            } else if (!inString && i + 1 < text.length && text[i] == '/' && text[i + 1] == '*') {
                // Skip until closing */
                val end = text.indexOf("*/", i + 2)
                if (end >= 0) {
                    i = end + 2
                } else {
                    // Unclosed block comment — skip rest
                    break
                }
            } else {
                result.append(ch)
                i++
            }
        }
        return result.toString()
    }

    /**
     * Strip `//` line comments only when they appear outside double-quoted strings.
     * A naive `indexOf("//")` would break URLs like `"https://example.com"`.
     */
    private fun stripLineComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (ch == '\\' && inString) {
                i += 2 // skip escaped character
                continue
            }
            if (ch == '"') {
                inString = !inString
            } else if (!inString && i + 1 < line.length && line[i] == '/' && line[i + 1] == '/') {
                return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private fun parseMcpSection(root: JsonObject): List<ImportedMcpServer> {
        val mcp = root["mcp"]?.jsonObject ?: return emptyList()
        val results = mutableListOf<ImportedMcpServer>()
        for ((name, value) in mcp) {
            val obj = runCatching { value.jsonObject }.getOrNull() ?: continue
            val type = obj["type"].asJsonString().orEmpty()
            if (type.isNotBlank() && !type.equals("remote", ignoreCase = true)) continue
            val enabled = obj["enabled"].asJsonBoolean() ?: true
            if (!enabled) continue
            val url = obj["url"].asJsonString()?.trim().orEmpty()
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue
            val auth = parseAuthorization(obj["headers"]?.jsonObject?.get("Authorization"))
            results += ImportedMcpServer(
                name = name.trim(),
                url = url,
                token = auth.plainToken,
                hasToken = auth.hasToken,
            )
        }
        return results
    }

    private fun JsonElement?.asJsonString(): String? = when (this) {
        null -> null
        is JsonPrimitive -> content
        else -> null
    }

    private fun JsonElement?.asJsonBoolean(): Boolean? = when (this) {
        null -> null
        is JsonPrimitive -> content.toBooleanStrictOrNull()
        else -> null
    }

    private data class ParsedAuth(val plainToken: String?, val hasToken: Boolean)

    private fun parseAuthorization(element: JsonElement?): ParsedAuth {
        val raw = element?.jsonPrimitive?.content?.trim().orEmpty()
        if (raw.isBlank()) return ParsedAuth(null, false)
        val bearer = BEARER_PREFIX.matchEntire(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val value = bearer.ifBlank { raw }
        if (isEnvRef(value)) return ParsedAuth(null, true)
        return ParsedAuth(value, true)
    }

    private fun isEnvRef(value: String): Boolean =
        value.startsWith("{env:") && value.endsWith("}")

    private fun parsePluginSection(root: JsonObject): List<String> {
        val plugin = root["plugin"] ?: return emptyList()
        return when (plugin) {
            is JsonArray -> plugin.mapNotNull { element -> element.asJsonString()?.trim()?.takeIf { it.isNotEmpty() } }
            is JsonPrimitive -> plugin.content.trim().takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList()
            else -> emptyList()
        }
    }

    private fun readPackageJsonPlugins(filesDir: File): List<String> {
        val pkgFile = File(nativeConfigDir(filesDir), "package.json")
        if (!pkgFile.exists()) return emptyList()
        val root = runCatching { json.parseToJsonElement(pkgFile.readText()).jsonObject }.getOrNull() ?: return emptyList()
        val deps = root["dependencies"]?.jsonObject ?: return emptyList()
        return deps.mapNotNull { (name, versionElement) ->
            if (name in INTERNAL_PACKAGE_DEPS) return@mapNotNull null
            val version = versionElement.jsonPrimitive.content.trim()
            when {
                version.isBlank() || version == "*" -> name
                else -> "$name@$version"
            }
        }
    }

    private fun hasDefaultPluginPackage(filesDir: File): Boolean {
        val pkgFile = File(nativeConfigDir(filesDir), "package.json")
        if (!pkgFile.exists()) return false
        val root = runCatching { json.parseToJsonElement(pkgFile.readText()).jsonObject }.getOrNull() ?: return false
        return root["dependencies"]?.jsonObject?.containsKey(DEFAULT_PLUGIN_PACKAGE) == true
    }
}
