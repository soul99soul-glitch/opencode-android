package com.opencode.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimePortGuardTest {
    @Test
    fun portToProcHexUsesProcNetTcpFormat() {
        assertEquals("1001", RuntimePortGuard.portToProcHex(4097))
        assertEquals("0050", RuntimePortGuard.portToProcHex(80))
    }

    @Test
    fun parsesListeningInodeFromProcNetTcpRow() {
        val lines = listOf(
            "   0: 0100007F:1001 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10427 0 123456 1 0000000000000000 100 0 0 10 0",
            "   1: 0100007F:0050 00000000:0000 01 00000000:00000000 00:00000000 00000000 10427 0 654321 1 0000000000000000 100 0 0 10 0",
        )

        assertEquals("123456", RuntimePortGuard.parseListeningInode(lines, "0100007F", "1001"))
    }
}
