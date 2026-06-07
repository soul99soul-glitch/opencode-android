package com.opencode.android.workspace

import com.opencode.android.data.model.ActiveEndpoint
import com.opencode.android.data.model.ConnectionMode
import com.opencode.android.data.model.LocalProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceDisplayTest {
    @Test
    fun endpointLabelPrefersWorkspaceLabel() {
        val endpoint = ActiveEndpoint(
            mode = ConnectionMode.LOCAL_BUNDLED,
            directory = "/data/data/app/files/workspaces/.saf-bridge/abc",
            workspaceLabel = "外部：Documents",
        )
        assertEquals("外部：Documents", WorkspaceDisplay.endpointDirectoryLabel(endpoint))
    }

    @Test
    fun endpointLabelFallsBackToDirectory() {
        val endpoint = ActiveEndpoint(
            mode = ConnectionMode.LOCAL_BUNDLED,
            directory = "/data/data/app/files/workspaces/default",
        )
        assertEquals("/data/data/app/files/workspaces/default", WorkspaceDisplay.endpointDirectoryLabel(endpoint))
    }

    @Test
    fun internalWorkspaceLabelDoesNotNeedContext() {
        val local = LocalProfile(workspacePath = "default")
        assertEquals("内置：default", WorkspaceDisplay.bundledLabel(context = nullContext(), local))
    }

    private fun nullContext(): android.content.Context =
        object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
        }
}
