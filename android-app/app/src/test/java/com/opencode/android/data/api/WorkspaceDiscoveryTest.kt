package com.opencode.android.data.api

import com.opencode.android.data.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceDiscoveryTest {
    @Test
    fun userFacingSessionsExcludeSubagentWorkers() {
        val sessions = listOf(
            Session(id = "parent", title = "Fix workspace drawer"),
            Session(id = "old-worker", title = "Subtask worker from parent"),
            Session(id = "new-worker", title = "Review changes (@reviewer subagent)"),
        )

        assertEquals(listOf("parent"), sessions.filterUserFacingSessions().map { it.id })
    }

    @Test
    fun userFacingSessionKeepsNormalMentions() {
        assertTrue(Session(id = "normal", title = "Discuss @reviewer flow").isUserFacingSession())
        assertFalse(Session(id = "worker", title = "Review (@code subagent)").isUserFacingSession())
    }

    @Test
    fun transientWorkspaceLabelsExcludeConnectionTestsAndUuids() {
        assertTrue("od-conn-test-fqhylX".isTransientWorkspaceLabel())
        assertTrue("20efcfe0-58a8-4fd8-902b-123456789abc".isTransientWorkspaceLabel())
        assertFalse("opencode-android".isTransientWorkspaceLabel())
        assertFalse("AI".isTransientWorkspaceLabel())
        assertFalse("2026".isTransientWorkspaceLabel())
    }
}
