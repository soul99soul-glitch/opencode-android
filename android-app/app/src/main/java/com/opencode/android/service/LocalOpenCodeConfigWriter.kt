package com.opencode.android.service

import com.opencode.android.data.model.LocalProviderDefaults
import com.opencode.android.data.model.LocalProviderProfile
import com.opencode.android.data.model.validate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

object LocalOpenCodeConfigWriter {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = false
    }

    fun generatedConfigFile(filesDir: File): File =
        File(File(filesDir, "opencode-android"), "generated-opencode.json")

    fun write(filesDir: File, profile: LocalProviderProfile, includeApiKeyRef: Boolean): Result<File> = runCatching {
        val output = generatedConfigFile(filesDir)
        output.parentFile?.mkdirs()
        if (!profile.enabled) {
            if (output.exists()) output.delete()
            return@runCatching output
        }
        val validationError = profile.validate()
        if (validationError != null) throw IllegalArgumentException(validationError)

        val providerId = LocalProviderDefaults.PROVIDER_ID
        val firstModel = profile.modelIds.first()
        val modelRef = "$providerId/$firstModel"
        val effectiveBaseUrl = profile.activeBaseUrl.trim().ifBlank { profile.baseUrl.trim() }

        val options = buildJsonObject {
            put("baseURL", effectiveBaseUrl)
            if (includeApiKeyRef) {
                put("apiKey", "{env:${LocalProviderDefaults.API_KEY_ENV}}")
            }
        }

        val models = buildJsonObject {
            profile.modelIds.forEach { modelId ->
                putJsonObject(modelId) {
                    put("name", modelId)
                }
            }
        }

        val config = buildJsonObject {
            put("\$schema", "https://opencode.ai/config.json")
            put("model", modelRef)
            put("small_model", modelRef)
            putJsonObject("provider") {
                putJsonObject(providerId) {
                    put("npm", "@ai-sdk/openai-compatible")
                    put("name", profile.displayName.trim().ifBlank { LocalProviderDefaults.DISPLAY_NAME })
                    put("options", options)
                    put("models", models)
                }
            }
        }

        output.writeText(json.encodeToString(JsonObject.serializer(), config))
        output
    }
}
