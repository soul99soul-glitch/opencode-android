package com.opencode.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeCompanionClientTest {
    @Test
    fun safeWorkspaceNameUsesLastPathSegmentAndSanitizes() {
        assertEquals(
            "my-workspace",
            RuntimeCompanionClient.safeWorkspaceName("/Users/me/my workspace"),
        )
    }

    @Test
    fun safeWorkspaceNameFallsBackToDefault() {
        assertEquals("default", RuntimeCompanionClient.safeWorkspaceName("///"))
    }

    @Test
    fun safeWorkspaceNameLimitsLength() {
        assertEquals(64, RuntimeCompanionClient.safeWorkspaceName("a".repeat(100)).length)
    }
}
