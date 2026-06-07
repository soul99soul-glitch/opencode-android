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

    // bundledLabel now requires a real Android Context for string resources.
    // Tested via instrumented tests or manual verification.
}
