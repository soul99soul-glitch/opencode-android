package com.opencode.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimePortGuardTest {
    @Test
    fun portToProcHexUsesLittleEndian() {
        assertEquals("0110", RuntimePortGuard.portToProcHex(4097))
        assertEquals("5000", RuntimePortGuard.portToProcHex(80))
    }
}
