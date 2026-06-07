package com.opencode.android.data.api

import com.opencode.android.data.model.LocalProviderPresets
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalProviderModelFetcherTest {
    @Test
    fun parsesOpenAiCompatibleModels() {
        val body = """
            {
              "object": "list",
              "data": [
                { "id": "gpt-4o-mini" },
                { "id": "openai/gpt-4o" }
              ]
            }
        """.trimIndent()

        assertEquals(
            listOf("gpt-4o-mini", "openai/gpt-4o"),
            LocalProviderModelFetcher.parseModelIds(body),
        )
    }

    @Test
    fun parsesGeminiModels() {
        val body = """
            {
              "models": [
                { "name": "models/gemini-3.1-flash-image-preview" },
                { "name": "models/gemini-pro" }
              ]
            }
        """.trimIndent()

        assertEquals(
            listOf("gemini-3.1-flash-image-preview", "gemini-pro"),
            LocalProviderModelFetcher.parseModelIds(body),
        )
    }

    @Test
    fun planProvidersFetchModelsFromCodingBaseUrl() {
        val mimo = LocalProviderPresets.byId("mimo")!!
        val kimi = LocalProviderPresets.byId("kimi")!!

        assertEquals(
            "https://token-plan-cn.xiaomimimo.com/v1",
            LocalProviderModelFetcher.modelListBaseUrl(
                preset = mimo,
                apiBaseUrl = mimo.apiBaseUrl,
                codingBaseUrl = mimo.codingBaseUrl,
            ),
        )
        assertEquals(
            "https://api.kimi.com/coding/v1",
            LocalProviderModelFetcher.modelListBaseUrl(
                preset = kimi,
                apiBaseUrl = kimi.apiBaseUrl,
                codingBaseUrl = kimi.codingBaseUrl,
            ),
        )
    }

    @Test
    fun planProvidersFallBackToApiBaseUrl() {
        val mimo = LocalProviderPresets.byId("mimo")!!

        assertEquals(
            listOf(
                "Coding Base" to "https://token-plan-cn.xiaomimimo.com/v1",
                "API Base" to "https://api.xiaomi.com/v1",
            ),
            LocalProviderModelFetcher.modelListEndpoints(
                preset = mimo,
                apiBaseUrl = mimo.apiBaseUrl,
                codingBaseUrl = mimo.codingBaseUrl,
            ).map { it.sourceLabel to it.baseUrl },
        )
    }

    @Test
    fun apiProvidersFetchModelsFromApiBaseUrl() {
        val openAi = LocalProviderPresets.byId("openai")!!
        val gemini = LocalProviderPresets.byId("gemini")!!

        assertEquals(
            "https://api.openai.com/v1",
            LocalProviderModelFetcher.modelListBaseUrl(
                preset = openAi,
                apiBaseUrl = openAi.apiBaseUrl,
                codingBaseUrl = openAi.codingBaseUrl,
            ),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta",
            LocalProviderModelFetcher.modelListBaseUrl(
                preset = gemini,
                apiBaseUrl = gemini.apiBaseUrl,
                codingBaseUrl = gemini.codingBaseUrl,
            ),
        )
    }
}
