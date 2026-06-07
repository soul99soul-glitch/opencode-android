package com.opencode.android.data.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeApiProvidersTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun parseConfigProvidersResponse() {
        val body = """
            {
              "providers": [
                {
                  "id": "android-local",
                  "name": "Android Local",
                  "source": "config",
                  "models": {
                    "llama3": { "id": "llama3", "name": "llama3" }
                  }
                },
                {
                  "id": "builtin-from-config-endpoint",
                  "name": "Builtin Provider",
                  "source": "builtin",
                  "models": {
                    "gpt-4o": { "id": "gpt-4o", "name": "GPT-4o" }
                  }
                }
              ],
              "default": {
                "model": "android-local/llama3"
              }
            }
        """.trimIndent()

        val providers = parseConfigProvidersResponse(body, json)

        assertEquals(2, providers.size)
        assertEquals("android-local", providers.first().id)
        assertEquals(listOf("llama3"), providers.first().models.keys.toList())
        assertEquals("builtin-from-config-endpoint", providers[1].id)
    }

    @Test
    fun parseLegacyProviderResponseFiltersConfiguredOnly() {
        val body = """
            {
              "all": [
                {
                  "id": "android-local",
                  "name": "Android Local",
                  "source": "config",
                  "models": { "m1": {} }
                },
                {
                  "id": "openai",
                  "name": "OpenAI",
                  "source": "builtin",
                  "models": { "gpt-4o": {} }
                }
              ]
            }
        """.trimIndent()

        val providers = parseLegacyProviderResponse(body, json)

        assertEquals(1, providers.size)
        assertEquals("android-local", providers.first().id)
    }

    @Test
    fun configProvidersParserFailsOnLegacyShapeSoCallerCanFallback() {
        val legacyBody = """
            {
              "all": [
                {
                  "id": "android-local",
                  "source": "config",
                  "models": { "m1": {} }
                }
              ]
            }
        """.trimIndent()

        val failure = runCatching { parseConfigProvidersResponse(legacyBody, json) }.exceptionOrNull()

        assertNotNull(failure)
    }
}
