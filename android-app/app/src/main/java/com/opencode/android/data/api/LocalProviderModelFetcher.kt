package com.opencode.android.data.api

import com.opencode.android.data.model.LocalProviderPreset
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object LocalProviderModelFetcher {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    data class FetchResult(
        val models: List<String>,
        val sourceLabel: String,
        val baseUrl: String,
    )

    data class ModelListEndpoint(
        val sourceLabel: String,
        val baseUrl: String,
    )

    suspend fun fetchModels(
        preset: LocalProviderPreset,
        apiBaseUrl: String,
        codingBaseUrl: String,
        apiKey: String,
    ): Result<FetchResult> = runCatching {
        val endpoints = modelListEndpoints(preset, apiBaseUrl, codingBaseUrl)
        val client = HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 30_000
            }
        }
        try {
            val failures = mutableListOf<String>()
            endpoints.forEach { endpoint ->
                runCatching {
                    fetchFromEndpoint(client, preset, endpoint, apiKey.trim())
                }.onSuccess { models ->
                    return@runCatching FetchResult(models, endpoint.sourceLabel, endpoint.baseUrl)
                }.onFailure { failure ->
                    failures += "${endpoint.sourceLabel}: ${failure.message ?: "Failed to fetch models"}"
                }
            }

            throw IllegalStateException(
                failures.joinToString("; ").ifBlank { "No model endpoints available" },
            )
        } finally {
            client.close()
        }
    }

    private suspend fun fetchFromEndpoint(
        client: HttpClient,
        preset: LocalProviderPreset,
        endpoint: ModelListEndpoint,
        apiKey: String,
    ): List<String> {
        val modelBaseUrl = endpoint.baseUrl.trim()
        require(modelBaseUrl.startsWith("http://") || modelBaseUrl.startsWith("https://")) {
            "${endpoint.sourceLabel} must start with http:// or https://"
        }
        OpenCodeApi.publicCleartextBlockMessage(modelBaseUrl)?.let {
            throw IllegalStateException(it)
        }

        val url = "${modelBaseUrl.trimEnd('/')}/models"
        val response = client.get(url) {
            header(HttpHeaders.Accept, "application/json")
            if (preset.id == "gemini") {
                parameter("pageSize", 100)
                if (apiKey.isNotBlank()) header("x-goog-api-key", apiKey)
            } else if (apiKey.isNotBlank()) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
        }
        if (response.status.value !in 200..299) {
            throw OpenCodeHttpException(response.status.value, response.bodyAsText())
        }
        return parseModelIds(response.bodyAsText()).ifEmpty {
            throw IllegalStateException("No models returned")
        }
    }

    fun parseModelIds(body: String): List<String> {
        val root = json.parseToJsonElement(body).jsonObject
        val openAiIds = root["data"]
            ?.jsonArrayOrNull()
            ?.modelIdsFromObjects()
            .orEmpty()
        if (openAiIds.isNotEmpty()) return openAiIds

        return root["models"]
            ?.jsonArrayOrNull()
            ?.modelIdsFromObjects()
            .orEmpty()
    }

    private fun JsonArray.modelIdsFromObjects(): List<String> =
        mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val raw = obj["id"]?.jsonPrimitive?.contentOrNull
                ?: obj["name"]?.jsonPrimitive?.contentOrNull
            raw?.removePrefix("models/")?.trim()?.takeIf { it.isNotBlank() }
        }.distinct()

    private fun kotlinx.serialization.json.JsonElement.jsonArrayOrNull(): JsonArray? =
        this as? JsonArray

    internal fun modelListBaseUrl(
        preset: LocalProviderPreset,
        apiBaseUrl: String,
        codingBaseUrl: String,
    ): String {
        val coding = codingBaseUrl.trim()
        return if (preset.id in planModelProviders && coding.isNotBlank()) {
            coding
        } else {
            apiBaseUrl.trim()
        }
    }

    internal fun modelListEndpoints(
        preset: LocalProviderPreset,
        apiBaseUrl: String,
        codingBaseUrl: String,
    ): List<ModelListEndpoint> {
        val api = apiBaseUrl.trim()
        val coding = codingBaseUrl.trim()
        val endpoints = mutableListOf<ModelListEndpoint>()

        if (preset.id in planModelProviders && coding.isNotBlank()) {
            endpoints += ModelListEndpoint("Coding Base", coding)
        }
        if (api.isNotBlank()) {
            endpoints += ModelListEndpoint("API Base", api)
        }

        return endpoints.distinctBy { it.baseUrl.trimEnd('/') }
    }

    private val planModelProviders = setOf("kimi", "glm", "mimo", "minimax")
}
