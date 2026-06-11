package com.opencode.android.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeDiagnosticsParserTest {
    @Test
    fun parsesConfigObjectsAndPluginArrays() {
        val obj = Json.parseToJsonElement(
            """
            {
              "agent": {"build": {}, "plan": {}},
              "mcp": {"websearch": {}, "context7": {}},
              "tools": {"bash": {}, "grep_app_search": {}},
              "plugin": ["file:///plugin.js", {"name": "omo"}, {}]
            }
            """.trimIndent()
        ).jsonObject

        val diagnostics = obj.toRuntimeDiagnostics()

        assertEquals(listOf("build", "plan"), diagnostics.agents)
        assertEquals(listOf("context7", "websearch"), diagnostics.mcps)
        assertEquals(listOf("bash", "grep_app_search"), diagnostics.tools)
        assertEquals(listOf("file:///plugin.js", "omo", "plugin-3"), diagnostics.plugins)
    }
}
