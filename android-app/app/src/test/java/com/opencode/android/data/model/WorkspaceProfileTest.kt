package com.opencode.android.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceProfileTest {
    @Test
    fun profileIdUsesEndpointIdentity() {
        val config = ServerConfig(host = "lan.local", port = 4096, directory = "/repo")
        val profile = config.toWorkspaceProfile(lastUsedAt = 1L)

        assertEquals(config.endpointIdentity(), profile.id)
        assertEquals("repo", profile.name)
        assertTrue(profile.pinned)
        assertEquals(1L, profile.lastUsedAt)
    }

    @Test
    fun blankDirectoryUsesHostAsProfileName() {
        val profile = ServerConfig(host = "lan.local", directory = "").toWorkspaceProfile()

        assertEquals("lan.local", profile.name)
    }
}
