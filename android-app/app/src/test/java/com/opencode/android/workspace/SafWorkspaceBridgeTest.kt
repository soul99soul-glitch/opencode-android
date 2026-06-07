package com.opencode.android.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SafWorkspaceBridgeTest {
    @Test
    fun bridgeIdIsStableForSameUri() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments"
        assertEquals(SafWorkspaceBridge.bridgeId(uri), SafWorkspaceBridge.bridgeId(uri))
    }

    @Test
    fun bridgeIdDiffersForDifferentUris() {
        val a = SafWorkspaceBridge.bridgeId("content://a")
        val b = SafWorkspaceBridge.bridgeId("content://b")
        assertNotEquals(a, b)
    }

    @Test
    fun bridgeDirLivesUnderSafBridgeFolder() {
        val filesDir = File(System.getProperty("java.io.tmpdir"), "saf-bridge-${System.nanoTime()}")
        val treeUri = "content://example/tree"
        val bridge = SafWorkspaceBridge.bridgeDir(filesDir, treeUri)
        assertTrue(bridge.absolutePath.contains("${File.separator}.saf-bridge${File.separator}"))
    }
}
