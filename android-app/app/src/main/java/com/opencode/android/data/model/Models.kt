package com.opencode.android.data.model

import kotlinx.serialization.*

@Serializable
data class ServerConfig(
    val host: String = "127.0.0.1",
    val port: Int = 4096,
    val password: String = "",
    val directory: String = ""
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
    val directory: String? = null,
    val version: String? = null,
    val time: SessionTime? = null
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
    val parts: List<PromptPart>
)

@Serializable
data class PromptPart(
    val type: String = "text",
    val text: String
)

@Serializable
data class Message(
    val id: String,
    val role: String,
    val content: String? = null,
    val parts: List<MessagePart> = emptyList()
)

@Serializable
data class MessagePart(
    val type: String,
    val text: String? = null,
    val toolName: String? = null,
    val toolCallId: String? = null,
    val args: String? = null,
    val result: String? = null
)

@Serializable
data class FileInfo(
    val name: String,
    val path: String,
    val type: String,
    val size: Long? = null
)
