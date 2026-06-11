package com.opencode.android.data.repository

import com.opencode.android.data.model.ServerConfig
import com.opencode.android.data.model.WorkspaceProfile
import com.opencode.android.data.model.toWorkspaceProfile
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal class WorkspaceProfileStore(
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    fun decode(raw: String?): List<WorkspaceProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(WorkspaceProfile.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    fun encode(profiles: List<WorkspaceProfile>): String {
        return json.encodeToString(ListSerializer(WorkspaceProfile.serializer()), profiles)
    }

    fun profilesOrLegacy(raw: String?, legacy: ServerConfig): List<WorkspaceProfile> {
        return decode(raw).ifEmpty { listOf(legacy.toWorkspaceProfile()) }
    }

    fun merge(
        profiles: List<WorkspaceProfile>,
        profile: WorkspaceProfile,
    ): List<WorkspaceProfile> {
        return (profiles.filterNot { it.id == profile.id } + profile)
            .sortedWith(compareByDescending<WorkspaceProfile> { it.pinned }.thenByDescending { it.lastUsedAt })
            .take(MAX_PROFILES)
    }

    fun remove(profiles: List<WorkspaceProfile>, profileId: String): List<WorkspaceProfile> {
        return profiles.filterNot { it.id == profileId }
    }

    private companion object {
        const val MAX_PROFILES = 20
    }
}
