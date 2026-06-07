package com.opencode.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorkspacePathsTest {
    @Test
    fun resolvesAbsoluteWorkspacePath() {
        val filesDir = File(System.getProperty("java.io.tmpdir"), "workspace-paths-${System.nanoTime()}")
        val workspace = WorkspacePaths.resolve(filesDir, "my-project")
        assertTrue(workspace.absolutePath.endsWith("${File.separator}workspaces${File.separator}my-project"))
    }

    @Test
    fun ensureReadyCreatesReadme() {
        val workspace = File(System.getProperty("java.io.tmpdir"), "workspace-seed-${System.nanoTime()}")
        WorkspacePaths.ensureReady(workspace)
        assertTrue(File(workspace, "README.md").exists())
    }

    @Test
    fun sanitizeNameMatchesLegacyBehavior() {
        assertEquals("default", WorkspacePaths.sanitizeName(""))
        assertEquals("my-project", WorkspacePaths.sanitizeName("/foo/my-project"))
    }
}
