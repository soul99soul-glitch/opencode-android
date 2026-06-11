package com.opencode.android.data.repository

import com.opencode.android.data.model.ServerConfig
import com.opencode.android.data.model.toWorkspaceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceProfileStoreTest {
    private val store = WorkspaceProfileStore()

    @Test
    fun profilesOrLegacyFallsBackForBlankOrBadJson() {
        val legacy = ServerConfig(host = "lan.local", directory = "/repo")

        assertEquals(listOf(legacy.toWorkspaceProfile()).map { it.id }, store.profilesOrLegacy(null, legacy).map { it.id })
        assertEquals(listOf(legacy.toWorkspaceProfile()).map { it.id }, store.profilesOrLegacy("not-json", legacy).map { it.id })
    }

    @Test
    fun mergeReplacesSameProfileAndSortsPinnedThenRecent() {
        val older = ServerConfig(host = "lan.local", directory = "/old").toWorkspaceProfile(pinned = true, lastUsedAt = 1)
        val recent = ServerConfig(host = "lan.local", directory = "/recent").toWorkspaceProfile(pinned = false, lastUsedAt = 3)
        val updated = older.copy(name = "Old renamed", lastUsedAt = 4)

        val merged = store.merge(listOf(older, recent), updated)

        assertEquals(listOf(updated.id, recent.id), merged.map { it.id })
        assertEquals("Old renamed", merged.first().name)
    }

    @Test
    fun encodeDecodeRoundTripsProfiles() {
        val profiles = listOf(
            ServerConfig(host = "lan.local", directory = "/a").toWorkspaceProfile(lastUsedAt = 1),
            ServerConfig(host = "lan.local", directory = "/b").toWorkspaceProfile(lastUsedAt = 2),
        )

        val decoded = store.decode(store.encode(profiles))

        assertEquals(profiles.map { it.id }, decoded.map { it.id })
        assertTrue(decoded.all { it.name.isNotBlank() })
    }

    @Test
    fun removingOnlyProfileFallsBackToDefaultWithoutRevivingRemovedProfile() {
        val removed = ServerConfig(host = "lan.local", directory = "/repo").toWorkspaceProfile()
        val remaining = store.remove(store.profilesOrLegacy(store.encode(listOf(removed)), removed.config), removed.id)
        val fallback = ServerConfig().toWorkspaceProfile()
        val persisted = store.merge(remaining, fallback)

        assertEquals(listOf(fallback.id), persisted.map { it.id })
    }
}
