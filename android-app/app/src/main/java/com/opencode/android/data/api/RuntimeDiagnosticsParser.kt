package com.opencode.android.data.api

import com.opencode.android.data.model.RuntimeDiagnostics
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal fun JsonObject.toRuntimeDiagnostics(): RuntimeDiagnostics {
    return RuntimeDiagnostics(
        agents = namesFrom("agent"),
        mcps = namesFrom("mcp"),
        tools = namesFrom("tools"),
        plugins = namesFrom("plugin"),
    )
}

private fun JsonObject.namesFrom(key: String): List<String> {
    return when (val value = this[key]) {
        is JsonObject -> value.keys.sorted()
        is JsonArray -> value.mapIndexedNotNull { index, item -> item.displayName(index) }
        else -> emptyList()
    }
}

private fun JsonElement.displayName(index: Int): String? {
    return when (this) {
        is JsonObject -> this["name"]?.toString()?.trim('"')
            ?: this["id"]?.toString()?.trim('"')
            ?: "plugin-${index + 1}"
        else -> toString().trim('"').takeIf { it.isNotBlank() }
    }
}
