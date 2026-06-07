package com.opencode.android.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

data class RuntimeMcpServer(
    val name: String,
    val url: String,
    val token: String = "",
)

data class RuntimeProviderConfig(
    val enabled: Boolean,
    val displayName: String,
    val baseUrl: String,
    val codingBaseUrl: String = "",
    val activeBaseUrl: String = "",
    val modelIds: List<String>,
    val hasApiKey: Boolean,
    val mcpServers: List<RuntimeMcpServer> = emptyList(),
    val plugins: List<String> = emptyList(),
    val defaultPlugins: Boolean = false,
) {
    /** Env var name holding the bearer token for the MCP server at [index]. */
    fun mcpTokenEnv(index: Int): String = "OPENCODE_MCP_TOKEN_$index"
}

object RuntimeConfigWriter {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = false
    }

    fun generatedConfigFile(filesDir: File): File =
        File(File(filesDir, "opencode-android"), "generated-opencode.json")

    fun write(filesDir: File, config: RuntimeProviderConfig): Result<File> = runCatching {
        val output = generatedConfigFile(filesDir)
        output.parentFile?.mkdirs()
        if (!config.enabled) {
            if (output.exists()) output.delete()
            return@runCatching output
        }
        val effectiveBaseUrl = config.activeBaseUrl.trim().ifBlank { config.baseUrl.trim() }
        require(effectiveBaseUrl.startsWith("http://") || effectiveBaseUrl.startsWith("https://")) {
            "Provider base URL must start with http:// or https://"
        }
        require(config.modelIds.isNotEmpty()) { "At least one model is required" }

        val providerId = RuntimeContract.PROVIDER_ID
        val firstModel = config.modelIds.first()
        val modelRef = "$providerId/$firstModel"

        val options = buildJsonObject {
            put("baseURL", effectiveBaseUrl)
            if (config.hasApiKey) {
                put("apiKey", "{env:${RuntimeContract.PROVIDER_API_KEY_ENV}}")
            }
        }
        val models = buildJsonObject {
            config.modelIds.forEach { modelId ->
                putJsonObject(modelId) {
                    put("name", modelId)
                }
            }
        }

        fun RuntimeMcpServer.isValid(): Boolean =
            name.isNotBlank() && (url.startsWith("http://") || url.startsWith("https://"))
        val hasMcp = config.mcpServers.any { it.isValid() }
        val validPlugins = config.plugins.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        val generated = buildJsonObject {
            put("\$schema", "https://opencode.ai/config.json")
            put("model", modelRef)
            put("small_model", modelRef)
            putJsonObject("provider") {
                putJsonObject(providerId) {
                    put("npm", "@ai-sdk/openai-compatible")
                    put("name", config.displayName.ifBlank { RuntimeContract.PROVIDER_NAME })
                    put("options", options)
                    put("models", models)
                }
            }
            if (hasMcp) {
                putJsonObject("mcp") {
                    // Index must match RuntimeLaunchEnv token injection: iterate the full list.
                    config.mcpServers.forEachIndexed { index, server ->
                        if (!server.isValid()) return@forEachIndexed
                        putJsonObject(server.name) {
                            put("type", "remote")
                            put("url", server.url.trim())
                            put("enabled", true)
                            if (server.token.isNotBlank()) {
                                putJsonObject("headers") {
                                    // Token kept out of the plaintext config via env indirection.
                                    put("Authorization", "Bearer {env:${config.mcpTokenEnv(index)}}")
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
        output.writeText(json.encodeToString(JsonObject.serializer(), generated))
        output
    }
}
