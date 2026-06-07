package com.opencode.android.data.api

import com.opencode.android.data.model.ConfigProvidersResponse
import com.opencode.android.data.model.Provider
import com.opencode.android.data.model.ProviderResponse
import kotlinx.serialization.json.Json

internal fun parseConfigProvidersResponse(body: String, json: Json): List<Provider> {
    val parsed = json.decodeFromString<ConfigProvidersResponse>(body)
    return parsed.providers ?: throw IllegalArgumentException("Missing providers field")
}

internal fun parseLegacyProviderResponse(body: String, json: Json): List<Provider> {
    val parsed = json.decodeFromString<ProviderResponse>(body)
    return parsed.all.filter { it.source == "config" }
}
