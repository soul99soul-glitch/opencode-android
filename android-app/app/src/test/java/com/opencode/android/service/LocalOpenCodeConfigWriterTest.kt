package com.opencode.android.service

import com.opencode.android.data.model.LocalProviderDefaults
import com.opencode.android.data.model.LocalProviderProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalOpenCodeConfigWriterTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun writesOpenAiCompatibleProviderWithoutPlaintextApiKey() {
        val filesDir = File(System.getProperty("java.io.tmpdir"), "opencode-config-writer-${System.nanoTime()}").apply { mkdirs() }
        val profile = LocalProviderProfile(
            enabled = true,
            providerId = "android-local",
            displayName = "Local LLM",
            baseUrl = "http://127.0.0.1:11434/v1",
            modelIds = listOf("llama3", "qwen"),
            hasApiKey = true,
        )

        val file = LocalOpenCodeConfigWriter.write(filesDir, profile, includeApiKeyRef = true).getOrThrow()
        val content = file.readText()
        val root = json.parseToJsonElement(content).jsonObject
        val provider = root["provider"]!!.jsonObject[LocalProviderDefaults.PROVIDER_ID]!!.jsonObject
        val options = provider["options"]!!.jsonObject
        val models = provider["models"]!!

        assertEquals("@ai-sdk/openai-compatible", provider["npm"]!!.jsonPrimitive.content)
        assertEquals("http://127.0.0.1:11434/v1", options["baseURL"]!!.jsonPrimitive.content)
        assertEquals("{env:${LocalProviderDefaults.API_KEY_ENV}}", options["apiKey"]!!.jsonPrimitive.content)
        assertTrue(models is JsonObject)
        assertTrue(models.jsonObject.containsKey("llama3"))
        assertTrue(models.jsonObject.containsKey("qwen"))
        assertEquals("android-local/llama3", root["model"]!!.jsonPrimitive.content)
        assertEquals("android-local/llama3", root["small_model"]!!.jsonPrimitive.content)
        assertFalse(content.contains("sk-"))
    }

    @Test
    fun ignoresCallerProvidedProviderId() {
        val filesDir = File(System.getProperty("java.io.tmpdir"), "opencode-config-writer-fixed-id-${System.nanoTime()}").apply { mkdirs() }
        val profile = LocalProviderProfile(
            enabled = true,
            providerId = "openai",
            displayName = "Local LLM",
            baseUrl = "http://127.0.0.1:11434/v1",
            modelIds = listOf("llama3"),
        )

        val content = LocalOpenCodeConfigWriter.write(filesDir, profile, includeApiKeyRef = false).getOrThrow().readText()
        val root = json.parseToJsonElement(content).jsonObject
        val providers = root["provider"]!!.jsonObject

        assertTrue(providers.containsKey(LocalProviderDefaults.PROVIDER_ID))
        assertFalse(providers.containsKey("openai"))
        assertEquals("android-local/llama3", root["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun omitsApiKeyWhenNotConfigured() {
        val filesDir = File(System.getProperty("java.io.tmpdir"), "opencode-config-writer-no-key-${System.nanoTime()}").apply { mkdirs() }
        val profile = LocalProviderProfile(
            enabled = true,
            baseUrl = "http://127.0.0.1:1234/v1",
            modelIds = listOf("local-model"),
            hasApiKey = false,
        )

        val content = LocalOpenCodeConfigWriter.write(filesDir, profile, includeApiKeyRef = false).getOrThrow().readText()

        assertFalse(content.contains("apiKey"))
    }

    @Test
    fun usesActiveBaseUrlForCodingPlanModels() {
        val filesDir = File(System.getProperty("java.io.tmpdir"), "opencode-config-writer-active-base-${System.nanoTime()}").apply { mkdirs() }
        val profile = LocalProviderProfile(
            enabled = true,
            displayName = "GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            codingBaseUrl = "https://open.bigmodel.cn/api/coding/paas/v4",
            activeBaseUrl = "https://open.bigmodel.cn/api/coding/paas/v4",
            modelIds = listOf("glm-5.1"),
            hasApiKey = true,
        )

        val root = json.parseToJsonElement(
            LocalOpenCodeConfigWriter.write(filesDir, profile, includeApiKeyRef = true).getOrThrow().readText(),
        ).jsonObject
        val provider = root["provider"]!!.jsonObject[LocalProviderDefaults.PROVIDER_ID]!!.jsonObject
        val options = provider["options"]!!.jsonObject

        assertEquals("https://open.bigmodel.cn/api/coding/paas/v4", options["baseURL"]!!.jsonPrimitive.content)
    }

    @Test
    fun disabledProfileDeletesGeneratedFile() {
        val filesDir = File(System.getProperty("java.io.tmpdir"), "opencode-config-writer-disabled-${System.nanoTime()}").apply { mkdirs() }
        val output = LocalOpenCodeConfigWriter.generatedConfigFile(filesDir)
        output.parentFile?.mkdirs()
        output.writeText("{}")

        LocalOpenCodeConfigWriter.write(
            filesDir,
            LocalProviderProfile(enabled = false),
            includeApiKeyRef = false,
        ).getOrThrow()

        assertFalse(output.exists())
    }
}
