package com.opencode.android.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafSyncPolicyTest {
    @Test
    fun syncsDotfilesUsedByCodingAgents() {
        assertTrue(SafSyncPolicy.shouldSync(".git"))
        assertTrue(SafSyncPolicy.shouldSync(".env"))
        assertTrue(SafSyncPolicy.shouldSync(".opencode"))
    }

    @Test
    fun skipsBridgeMetadataOnly() {
        assertFalse(SafSyncPolicy.shouldSync(".bridge-sync-state.json"))
    }

    @Test
    fun skipsBlankAndNavigationEntries() {
        assertFalse(SafSyncPolicy.shouldSync(""))
        assertFalse(SafSyncPolicy.shouldSync("."))
        assertFalse(SafSyncPolicy.shouldSync(".."))
    }
}
