package com.opencode.android.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

private val errorJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

internal class OpenCodeHttpException(
    val status: Int,
    body: String,
) : Exception(formatHttpError(status, body))

internal fun formatHttpError(status: Int, body: String): String {
    val prefix = when (status) {
        401 -> "Authentication failed"
        403 -> "Access denied"
        404 -> "Endpoint not found"
        in 500..599 -> "OpenCode server error"
        else -> "Request failed"
    }
    val detail = extractErrorDetail(body)
    return if (detail.isBlank()) {
        "$prefix (HTTP $status)"
    } else {
        "$prefix (HTTP $status): $detail"
    }
}

internal fun extractErrorDetail(body: String): String {
    val trimmed = body.trim()
    if (trimmed.isBlank()) return ""
    if (trimmed.startsWith("<!DOCTYPE", ignoreCase = true) || trimmed.startsWith("<html", ignoreCase = true)) {
        return "Server returned HTML"
    }

    val parsed = runCatching { errorJson.parseToJsonElement(trimmed).jsonObject }.getOrNull()
    val parsedMessage = parsed?.serverMessage()
    return compactServerMessage(parsedMessage ?: trimmed)
}

private fun JsonObject.serverMessage(): String? {
    val dataMessage = (this["data"] as? JsonObject)
        ?.let { it["message"] as? JsonPrimitive }
        ?.contentOrNull
    if (!dataMessage.isNullOrBlank()) return dataMessage

    val message = (this["message"] as? JsonPrimitive)?.contentOrNull
    if (!message.isNullOrBlank()) return message

    val name = (this["name"] as? JsonPrimitive)?.contentOrNull
    return name?.takeIf { it.isNotBlank() }
}

private fun compactServerMessage(message: String): String {
    val stackStart = listOf("\n    at ", "\nat ", "\n\tat ")
        .mapNotNull { marker -> message.indexOf(marker).takeIf { it >= 0 } }
        .minOrNull()
    val withoutStack = if (stackStart != null) message.substring(0, stackStart) else message
    return withoutStack
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(180)
}
