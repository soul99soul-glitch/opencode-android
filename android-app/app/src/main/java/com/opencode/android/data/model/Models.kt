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

/* Provider / Model discovery */

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
