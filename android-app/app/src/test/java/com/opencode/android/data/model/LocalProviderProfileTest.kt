package com.opencode.android.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class LocalProviderProfileTest {
    @Test
    fun parseModelIdsSupportsCommaAndNewline() {
        assertEquals(
            listOf("gpt-4o-mini", "deepseek-chat"),
            parseModelIds("gpt-4o-mini, deepseek-chat\ngpt-4o-mini"),
        )
    }

    @Test
    fun disabledProfileSkipsValidation() {
        assertNull(
            LocalProviderProfile(enabled = false, baseUrl = "", modelIds = emptyList()).validate(),
        )
    }

    @Test
    fun rejectsInvalidBaseUrl() {
        assertNotNull(
            LocalProviderProfile(
                enabled = true,
                baseUrl = "ftp://example.com",
                modelIds = listOf("m1"),
            ).validate(),
        )
    }

    @Test
    fun rejectsEmptyModelsWhenEnabled() {
        assertEquals(
            "At least one model is required",
            LocalProviderProfile(
                enabled = true,
                baseUrl = "http://127.0.0.1:11434/v1",
                modelIds = emptyList(),
            ).validate(),
        )
    }

    @Test
    fun rejectsModelIdsThatBreakProviderModelRef() {
        assertEquals(
            "Model IDs may use letters, numbers, slash, dot, underscore, colon, or hyphen",
            LocalProviderProfile(
                enabled = true,
                baseUrl = "http://127.0.0.1:11434/v1",
                modelIds = listOf("bad model"),
            ).validate(),
        )
    }

    @Test
    fun acceptsSafeLocalModelIds() {
        assertNull(
            LocalProviderProfile(
                enabled = true,
                baseUrl = "http://127.0.0.1:11434/v1",
                modelIds = listOf("llama3.1:8b-instruct_q4", "openai/gpt-4o"),
            ).validate(),
        )
    }

    @Test
    fun rejectsInvalidCodingBaseUrl() {
        assertEquals(
            "Coding Base URL must start with http:// or https://",
            LocalProviderProfile(
                enabled = true,
                baseUrl = "http://127.0.0.1:11434/v1",
                codingBaseUrl = "ftp://coding",
                modelIds = listOf("llama"),
            ).validate(),
        )
    }

    @Test
    fun includesRequestedProviderPresets() {
        val presets = LocalProviderPresets.ALL.associateBy { it.id }

        assertEquals("https://api.openai.com/v1", presets["openai"]!!.apiBaseUrl)
        assertEquals("https://chatgpt.com/backend-api/codex", presets["openai"]!!.codingBaseUrl)
        assertEquals(emptyList<String>(), presets["openai"]!!.modelIds)
        assertEquals("https://generativelanguage.googleapis.com/v1beta", presets["gemini"]!!.apiBaseUrl)
        assertEquals("https://cloudcode-pa.googleapis.com", presets["gemini"]!!.codingBaseUrl)
        assertEquals(emptyList<String>(), presets["gemini"]!!.modelIds)
        assertEquals("https://api.deepseek.com/v1", presets["deepseek"]!!.apiBaseUrl)
        assertEquals("https://openrouter.ai/api/v1", presets["openrouter"]!!.apiBaseUrl)
        assertEquals("https://api.moonshot.cn/v1", presets["kimi"]!!.apiBaseUrl)
        assertEquals("https://api.kimi.com/coding/v1", presets["kimi"]!!.codingBaseUrl)
        assertEquals("https://open.bigmodel.cn/api/paas/v4", presets["glm"]!!.apiBaseUrl)
        assertEquals("https://open.bigmodel.cn/api/coding/paas/v4", presets["glm"]!!.codingBaseUrl)
        assertEquals("https://api.xiaomi.com/v1", presets["mimo"]!!.apiBaseUrl)
        assertEquals("https://token-plan-cn.xiaomimimo.com/v1", presets["mimo"]!!.codingBaseUrl)
        assertEquals("https://api.minimaxi.com/v1", presets["minimax"]!!.apiBaseUrl)
        assertEquals("https://api.x.ai/v1", presets["xai"]!!.apiBaseUrl)
    }

    @Test
    fun bestMatchInfersLegacyPlanProviderFromUrls() {
        val preset = LocalProviderPresets.bestMatch(
            LocalProviderProfile(
                presetId = LocalProviderPresets.DEFAULT_ID,
                baseUrl = "https://open.bigmodel.cn/api/paas/v4",
                codingBaseUrl = "https://open.bigmodel.cn/api/coding/paas/v4",
                activeBaseUrl = "https://open.bigmodel.cn/api/coding/paas/v4",
            ),
        )

        assertEquals("glm", preset.id)
    }

    @Test
    fun bestMatchKeepsSavedNonDefaultPresetAuthoritative() {
        val preset = LocalProviderPresets.bestMatch(
            LocalProviderProfile(
                presetId = "kimi",
                baseUrl = "https://api.openai.com/v1",
                codingBaseUrl = "https://api.kimi.com/coding/v1",
            ),
        )

        assertEquals("kimi", preset.id)
    }

    @Test
    fun providerIdIsNotUserValidated() {
        assertNull(
            LocalProviderProfile(
                enabled = true,
                providerId = "bad id",
                baseUrl = "http://127.0.0.1:11434/v1",
                modelIds = listOf("llama"),
            ).validate(),
        )
    }

    @Test
    fun localBundledEndpointCarriesGeneratedPassword() {
        val endpoint = LocalProfile(bundledPort = 4097, workspacePath = "/work")
            .toActiveEndpoint(ConnectionMode.LOCAL_BUNDLED, password = "local-secret")

        assertEquals("http://127.0.0.1:4097", endpoint.baseUrl)
        assertEquals("local-secret", endpoint.password)
        assertEquals("/work", endpoint.directory)
    }

    @Test
    fun localExternalEndpointCanRemainUnauthenticated() {
        val endpoint = LocalProfile(externalPort = 4096, workspacePath = "/work")
            .toActiveEndpoint(ConnectionMode.LOCAL_EXTERNAL)

        assertEquals("http://127.0.0.1:4096", endpoint.baseUrl)
        assertEquals("", endpoint.password)
    }

    @Test
    fun sanitizesLocalWorkspaceNames() {
        assertEquals("default", sanitizeLocalWorkspaceName(""))
        assertEquals("default", sanitizeLocalWorkspaceName("///"))
        assertEquals("my-workspace", sanitizeLocalWorkspaceName("/Users/me/my workspace"))
        assertEquals(64, sanitizeLocalWorkspaceName("a".repeat(100)).length)
    }

    @Test
    fun localWorkspaceProfilesKeepSafUriDistinctFromAppName() {
        val app = appLocalWorkspaceProfile("repo")
        val saf = safLocalWorkspaceProfile("repo", "content://tree/repo")

        assertEquals("app:repo", app.id)
        assertEquals("", app.treeUri)
        assertEquals("saf:content://tree/repo", saf.id)
        assertEquals("content://tree/repo", saf.treeUri)
    }
}
