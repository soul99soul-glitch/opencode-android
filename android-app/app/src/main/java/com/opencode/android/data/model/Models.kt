package com.opencode.android.data.model

import kotlinx.serialization.*
import kotlinx.serialization.json.JsonObject

@Serializable
data class ServerConfig(
    val host: String = "127.0.0.1",
    val port: Int = 4096,
    val password: String = "",
    val directory: String = ""
) {
    fun normalizedHost(): String = host.trim().trimEnd('/')

    fun normalizedDirectory(): String = directory.trim()

    fun endpointBaseUrl(): String {
        val normalizedHost = normalizedHost()
        return if (normalizedHost.startsWith("http://") || normalizedHost.startsWith("https://")) {
            normalizedHost
        } else {
            "http://$normalizedHost:$port"
        }
    }

    fun endpointIdentity(): String {
        return "${endpointBaseUrl()}|${normalizedDirectory()}"
    }
}

@Serializable
data class WorkspaceProfile(
    val id: String,
    val name: String,
    val config: ServerConfig,
    val pinned: Boolean = false,
    val lastUsedAt: Long = 0,
)

fun ServerConfig.toWorkspaceProfile(
    name: String? = null,
    pinned: Boolean = true,
    lastUsedAt: Long = System.currentTimeMillis(),
): WorkspaceProfile {
    val label = name
        ?: normalizedDirectory().substringAfterLast('/').ifBlank {
            normalizedHost().ifBlank { "Workspace" }
        }
    return WorkspaceProfile(
        id = endpointIdentity(),
        name = label,
        config = this,
        pinned = pinned,
        lastUsedAt = lastUsedAt,
    )
}

@Serializable
enum class ConnectionMode {
    LAN,
    LOCAL_BUNDLED,
    LOCAL_EXTERNAL,
}

@Serializable
data class LanProfile(
    val host: String = "127.0.0.1",
    val port: Int = 4096,
    val password: String = "",
    val directory: String = "",
)

@Serializable
data class LocalProfile(
    val bundledPort: Int = 4097,
    val externalPort: Int = 4096,
    val workspacePath: String = "",
    val workspaceTreeUri: String = "",
    val autoStart: Boolean = true,
)

@Serializable
data class LocalWorkspaceProfile(
    val id: String,
    val name: String,
    val treeUri: String = "",
    val lastUsedAt: Long = 0,
)

fun sanitizeLocalWorkspaceName(input: String): String =
    com.opencode.android.runtime.WorkspacePaths.sanitizeName(input)

fun appLocalWorkspaceProfile(name: String, lastUsedAt: Long = System.currentTimeMillis()): LocalWorkspaceProfile {
    val safe = sanitizeLocalWorkspaceName(name)
    return LocalWorkspaceProfile(
        id = "app:$safe",
        name = safe,
        treeUri = "",
        lastUsedAt = lastUsedAt,
    )
}

fun safLocalWorkspaceProfile(
    name: String,
    treeUri: String,
    lastUsedAt: Long = System.currentTimeMillis(),
): LocalWorkspaceProfile {
    val safe = sanitizeLocalWorkspaceName(name)
    return LocalWorkspaceProfile(
        id = "saf:$treeUri",
        name = safe,
        treeUri = treeUri,
        lastUsedAt = lastUsedAt,
    )
}

@Serializable
data class ActiveEndpoint(
    val mode: ConnectionMode = ConnectionMode.LAN,
    val baseUrl: String = "http://127.0.0.1:4096",
    val password: String = "",
    val directory: String = "",
    val workspaceLabel: String = "",
) {
    val displayUrl: String
        get() = baseUrl.removePrefix("http://").removePrefix("https://")

    // Password is excluded so auth rotation does not discard current in-memory UI state.
    val identityKey: String
        get() = "${mode.name}|$baseUrl|$directory"

    companion object {
        fun fallback(): ActiveEndpoint = ActiveEndpoint()
    }
}

fun ServerConfig.toLanProfile(): LanProfile =
    LanProfile(host = host, port = port, password = password, directory = directory)

fun ServerConfig.toActiveEndpoint(mode: ConnectionMode = ConnectionMode.LAN): ActiveEndpoint =
    ActiveEndpoint(
        mode = mode,
        baseUrl = when {
            host.startsWith("http://") || host.startsWith("https://") -> host.trimEnd('/')
            else -> "http://$host:$port"
        },
        password = password,
        directory = directory,
    )

fun LanProfile.toServerConfig(): ServerConfig =
    ServerConfig(host = host, port = port, password = password, directory = directory)

fun LanProfile.toActiveEndpoint(): ActiveEndpoint =
    toServerConfig().toActiveEndpoint(ConnectionMode.LAN)

fun LocalProfile.toActiveEndpoint(mode: ConnectionMode, password: String = ""): ActiveEndpoint =
    ActiveEndpoint(
        mode = mode,
        baseUrl = "http://127.0.0.1:${if (mode == ConnectionMode.LOCAL_EXTERNAL) externalPort else bundledPort}",
        password = password,
        directory = workspacePath,
    )

@Serializable
data class HealthResponse(
    val healthy: Boolean = false,
    val version: String = ""
)

@Serializable
data class Session(
    val id: String,
    val title: String = "Untitled",
    val slug: String = "new session",
    val directory: String? = null,
    val version: String? = null,
    val time: SessionTime? = null,
    val agent: String? = null,
    val messageCount: Int? = null,
    val preview: String? = null,
)

@Serializable
data class Project(
    val id: String,
    val worktree: String,
    val vcs: String = "",
    val sandboxes: List<String> = emptyList(),
)

@Serializable
data class RemoteFileEntry(
    val name: String,
    val path: String,
    val absolute: String,
    val type: String,
    val ignored: Boolean = false,
)

@Serializable
data class SessionTime(
    val created: Long = 0,
    val updated: Long = 0
)

@Serializable
data class CreateSessionRequest(
    val title: String? = null
)

@Serializable
data class PromptRequest(
    val parts: List<PromptPart>,
    val agent: String? = null,
    val model: ModelRef? = null
)

@Serializable
data class ModelRef(
    val providerID: String,
    val modelID: String
)

@Serializable
data class PromptPart(
    val type: String = "text",
    val text: String? = null,
    val mime: String? = null,
    val url: String? = null,
    val filename: String? = null,
)

@Serializable
data class Message(
    val info: MessageInfo,
    val parts: List<MessagePart> = emptyList()
)

@Serializable
data class MessageInfo(
    val id: String,
    val role: String,
    val sessionID: String? = null,
    val providerID: String? = null,
    val modelID: String? = null,
    val agent: String? = null
)

@Serializable
data class MessageTime(
    val created: Long = 0,
    val completed: Long? = null
)

@Serializable
data class MessagePart(
    val type: String,
    val text: String? = null,
    val mime: String? = null,
    val url: String? = null,
    val filename: String? = null,
    val id: String? = null,
    val sessionID: String? = null,
    val messageID: String? = null,
    val tool: String? = null,
    val callID: String? = null,
    val state: ToolState? = null
)

@Serializable
data class ToolState(
    val status: String = "",
    val input: JsonObject? = null,
    val output: String? = null,
    val metadata: JsonObject? = null,
    val title: String? = null
)

@Serializable
data class FileInfo(
    val name: String,
    val path: String,
    val type: String,
    val size: Long? = null
)

/* Local bundled provider profile */

@Serializable
data class LocalProviderProfile(
    val enabled: Boolean = false,
    val presetId: String = LocalProviderPresets.DEFAULT_ID,
    val providerId: String = LocalProviderDefaults.PROVIDER_ID,
    val displayName: String = LocalProviderDefaults.DISPLAY_NAME,
    val baseUrl: String = "",
    val codingBaseUrl: String = "",
    val activeBaseUrl: String = "",
    val modelIds: List<String> = emptyList(),
    val hasApiKey: Boolean = false,
)

object LocalProviderDefaults {
    const val PROVIDER_ID = "android-local"
    const val DISPLAY_NAME = "Android Local"
    const val API_KEY_ENV = "OPENCODE_ANDROID_PROVIDER_API_KEY"
}

data class LocalProviderPreset(
    val id: String,
    val displayName: String,
    val defaultEnabled: Boolean,
    val apiBaseUrl: String,
    val codingBaseUrl: String = "",
    val modelIds: List<String> = emptyList(),
)

object LocalProviderPresets {
    const val DEFAULT_ID = "openai"

    val ALL: List<LocalProviderPreset> = listOf(
        LocalProviderPreset(
            id = "openai",
            displayName = "OpenAI",
            defaultEnabled = true,
            apiBaseUrl = "https://api.openai.com/v1",
            codingBaseUrl = "https://chatgpt.com/backend-api/codex",
        ),
        LocalProviderPreset(
            id = "gemini",
            displayName = "Gemini",
            defaultEnabled = true,
            apiBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
            codingBaseUrl = "https://cloudcode-pa.googleapis.com",
        ),
        LocalProviderPreset(
            id = "deepseek",
            displayName = "DeepSeek",
            defaultEnabled = true,
            apiBaseUrl = "https://api.deepseek.com/v1",
        ),
        LocalProviderPreset(
            id = "openrouter",
            displayName = "OpenRouter",
            defaultEnabled = true,
            apiBaseUrl = "https://openrouter.ai/api/v1",
        ),
        LocalProviderPreset(
            id = "kimi",
            displayName = "月之暗面 (Kimi)",
            defaultEnabled = false,
            apiBaseUrl = "https://api.moonshot.cn/v1",
            codingBaseUrl = "https://api.kimi.com/coding/v1",
        ),
        LocalProviderPreset(
            id = "glm",
            displayName = "智谱 GLM",
            defaultEnabled = false,
            apiBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
            codingBaseUrl = "https://open.bigmodel.cn/api/coding/paas/v4",
        ),
        LocalProviderPreset(
            id = "mimo",
            displayName = "小米 MiMo",
            defaultEnabled = false,
            apiBaseUrl = "https://api.xiaomi.com/v1",
            codingBaseUrl = "https://token-plan-cn.xiaomimimo.com/v1",
        ),
        LocalProviderPreset(
            id = "minimax",
            displayName = "MiniMax",
            defaultEnabled = false,
            apiBaseUrl = "https://api.minimaxi.com/v1",
            codingBaseUrl = "https://api.minimaxi.com/v1",
            modelIds = listOf("MiniMax-M3"),
        ),
        LocalProviderPreset(
            id = "xai",
            displayName = "xAI",
            defaultEnabled = false,
            apiBaseUrl = "https://api.x.ai/v1",
        ),
    )

    fun byId(id: String): LocalProviderPreset? =
        ALL.firstOrNull { it.id == id }

    fun bestMatch(profile: LocalProviderProfile): LocalProviderPreset {
        val saved = byId(profile.presetId)
        if (saved != null && saved.id != DEFAULT_ID) return saved
        val urlMatch = ALL.firstOrNull { preset ->
            preset.apiBaseUrl == profile.baseUrl ||
                (preset.codingBaseUrl.isNotBlank() && preset.codingBaseUrl == profile.codingBaseUrl) ||
                (preset.codingBaseUrl.isNotBlank() && preset.codingBaseUrl == profile.activeBaseUrl)
        }
        return urlMatch ?: saved ?: ALL.first { it.id == DEFAULT_ID }
    }

    fun prefersCodingBase(presetId: String): Boolean =
        presetId in setOf("kimi", "glm", "mimo", "minimax")
}

fun parseModelIds(input: String): List<String> =
    input.split(',', '\n', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

fun LocalProviderProfile.validate(): String? {
    if (!enabled) return null
    val url = baseUrl.trim()
    if (url.isBlank()) return "Base URL is required"
    if (!BASE_URL_PATTERN.matches(url)) return "Base URL must start with http:// or https://"
    EndpointSecurityPolicy.publicCleartextBlockMessage(url)?.let { return it }
    val codingUrl = codingBaseUrl.trim()
    if (codingUrl.isNotBlank() && !BASE_URL_PATTERN.matches(codingUrl)) {
        return "Coding Base URL must start with http:// or https://"
    }
    if (codingUrl.isNotBlank()) EndpointSecurityPolicy.publicCleartextBlockMessage(codingUrl)?.let { return it }
    val activeUrl = activeBaseUrl.trim()
    if (activeUrl.isNotBlank() && !BASE_URL_PATTERN.matches(activeUrl)) {
        return "Active Base URL must start with http:// or https://"
    }
    if (activeUrl.isNotBlank()) EndpointSecurityPolicy.publicCleartextBlockMessage(activeUrl)?.let { return it }
    if (modelIds.isEmpty()) return "At least one model is required"
    if (modelIds.any { !MODEL_ID_PATTERN.matches(it) }) {
        return "Model IDs may use letters, numbers, slash, dot, underscore, colon, or hyphen"
    }
    return null
}

private val BASE_URL_PATTERN = Regex("^https?://\\S+$")
private val MODEL_ID_PATTERN = Regex("^[A-Za-z0-9._:/-]{1,180}$")

/* MCP servers + plugins (opencode-compatible local-mode extensions) */

object McpConfigSource {
    const val APP = "app"
    const val AGENT = "agent"
}

@Serializable
data class McpServerConfig(
    val name: String,
    val url: String,
    val hasToken: Boolean = false,
    val source: String = McpConfigSource.APP,
)

private val MCP_NAME_PATTERN = Regex("^[A-Za-z0-9._-]{1,60}$")

fun McpServerConfig.validate(): String? {
    if (!MCP_NAME_PATTERN.matches(name)) return "MCP name may use letters, numbers, dot, underscore, hyphen"
    if (!BASE_URL_PATTERN.matches(url.trim())) return "MCP URL must start with http:// or https://"
    EndpointSecurityPolicy.publicCleartextBlockMessage(url.trim())?.let { return it }
    return null
}

/** Parse a newline/comma/semicolon separated list of npm plugin specs. */
fun parsePluginSpecs(input: String): List<String> =
    input.split(',', '\n', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

/* Provider / Model discovery */

@Serializable
data class ConfigProvidersResponse(
    val providers: List<Provider>? = null,
)

@Serializable
data class ProviderResponse(
    val all: List<Provider> = emptyList(),
)

@Serializable
data class Provider(
    val id: String,
    val name: String = "",
    val source: String = "",
    val models: Map<String, ModelInfo> = emptyMap(),
)

@Serializable
data class ModelInfo(
    val id: String = "",
    val providerID: String = "",
    val name: String = "",
)

/* Agent discovery */

@Serializable
data class AgentInfo(
    val name: String,
    val mode: String = "primary",
    val hidden: Boolean = false,
)

/* Skill discovery */

@Serializable
data class SkillInfo(
    val name: String,
    val description: String = "",
)

data class RuntimeDiagnostics(
    val agents: List<String> = emptyList(),
    val mcps: List<String> = emptyList(),
    val tools: List<String> = emptyList(),
    val plugins: List<String> = emptyList(),
    val error: String? = null,
) {
    val hasRuntimeConfig: Boolean = error == null
}
