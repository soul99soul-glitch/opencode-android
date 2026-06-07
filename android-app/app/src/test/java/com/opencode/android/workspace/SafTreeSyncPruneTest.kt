package com.opencode.android.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SafTreeSyncPruneTest {
    @Test
    fun pruneLocalMissingDeletesStaleFiles() {
        val dir = File(System.getProperty("java.io.tmpdir"), "saf-prune-${System.nanoTime()}").apply { mkdirs() }
        File(dir, "keep.txt").writeText("ok")
        File(dir, "stale.txt").writeText("old")
        SafTreeSync.pruneLocalMissing(remoteNames = setOf("keep.txt"), targetDir = dir)
        assertTrue(File(dir, "keep.txt").exists())
        assertFalse(File(dir, "stale.txt").exists())
        dir.deleteRecursively()
    }

    @Test
    fun pruneLocalMissingKeepsInternalMetadata() {
        val dir = File(System.getProperty("java.io.tmpdir"), "saf-prune-meta-${System.nanoTime()}").apply { mkdirs() }
        File(dir, ".bridge-sync-state.json").writeText("{}")
        SafTreeSync.pruneLocalMissing(remoteNames = emptySet(), targetDir = dir)
        assertTrue(File(dir, ".bridge-sync-state.json").exists())
        dir.deleteRecursively()
    }
}
