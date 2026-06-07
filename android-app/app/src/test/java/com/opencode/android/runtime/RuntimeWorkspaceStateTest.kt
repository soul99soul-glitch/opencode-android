package com.opencode.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class RuntimeWorkspaceStateTest {
    @Test
    fun roundTripActiveWorkspacePath() {
        val filesDir = File(System.getProperty("java.io.tmpdir"), "runtime-workspace-state-${System.nanoTime()}")
        filesDir.mkdirs()
        RuntimeWorkspaceState.write(filesDir, "/data/data/app/files/workspaces/default")
        assertEquals("/data/data/app/files/workspaces/default", RuntimeWorkspaceState.read(filesDir))
        RuntimeWorkspaceState.clear(filesDir)
        assertNull(RuntimeWorkspaceState.read(filesDir))
    }
}
