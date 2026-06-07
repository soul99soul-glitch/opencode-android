package com.opencode.android.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuntimeConfigWriterTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun writesOpenAiCompatibleProviderWithoutPlaintextApiKey() {
        val filesDir = tempDir()
        val config = RuntimeProviderConfig(
            enabled = true,
            displayName = "Android Local",
            baseUrl = "http://127.0.0.1:11434/v1",
            modelIds = listOf("qwen", "llama"),
            hasApiKey = true,
        )

        val file = RuntimeConfigWriter.write(filesDir, config).getOrThrow()
        val content = file.readText()
        val root = json.parseToJsonElement(content).jsonObject
        val provider = root["provider"]!!.jsonObject[RuntimeContract.PROVIDER_ID]!!.jsonObject
        val options = provider["options"]!!.jsonObject
        val models = provider["models"]!!

        assertEquals("@ai-sdk/openai-compatible", provider["npm"]!!.jsonPrimitive.content)
        assertEquals("http://127.0.0.1:11434/v1", options["baseURL"]!!.jsonPrimitive.content)
        assertEquals("{env:${RuntimeContract.PROVIDER_API_KEY_ENV}}", options["apiKey"]!!.jsonPrimitive.content)
        assertTrue(models is JsonObject)
        assertTrue(models.jsonObject.containsKey("qwen"))
        assertTrue(models.jsonObject.containsKey("llama"))
        assertEquals("android-local/qwen", root["model"]!!.jsonPrimitive.content)
        assertEquals("android-local/qwen", root["small_model"]!!.jsonPrimitive.content)
        assertFalse(content.contains("sk-"))
    }

    @Test
    fun omitsApiKeyWhenProfileHasNoKey() {
        val content = RuntimeConfigWriter.write(
            tempDir(),
            RuntimeProviderConfig(
                enabled = true,
                displayName = "Android Local",
                baseUrl = "http://127.0.0.1:11434/v1",
                modelIds = listOf("qwen"),
                hasApiKey = false,
            ),
        ).getOrThrow().readText()

        assertFalse(content.contains("apiKey"))
    }

    @Test
    fun usesActiveBaseUrlWhenModelWasFetchedFromCodingPlan() {
        val filesDir = tempDir()
        val config = RuntimeProviderConfig(
            enabled = true,
            displayName = "GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            codingBaseUrl = "https://open.bigmodel.cn/api/coding/paas/v4",
            activeBaseUrl = "https://open.bigmodel.cn/api/coding/paas/v4",
            modelIds = listOf("glm-5.1"),
            hasApiKey = true,
        )

        val root = json.parseToJsonElement(RuntimeConfigWriter.write(filesDir, config).getOrThrow().readText()).jsonObject
        val provider = root["provider"]!!.jsonObject[RuntimeContract.PROVIDER_ID]!!.jsonObject
        val options = provider["options"]!!.jsonObject

        assertEquals("https://open.bigmodel.cn/api/coding/paas/v4", options["baseURL"]!!.jsonPrimitive.content)
    }

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "opencode-runtime-config-${System.nanoTime()}").apply { mkdirs() }
}
