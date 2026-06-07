package com.opencode.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDnsResolverTest {
    @Test
    fun formatResolvConfIncludesNameserversAndOptions() {
        val content = RuntimeDnsResolver.formatResolvConf(listOf("192.168.1.1", "8.8.8.8"))

        assertTrue(content.contains("nameserver 192.168.1.1"))
        assertTrue(content.contains("nameserver 8.8.8.8"))
        assertTrue(content.contains("options timeout:2 attempts:2"))
    }

    @Test
    fun fallbackServersAreStable() {
        assertEquals(4, RuntimeDnsResolver.FALLBACK_SERVERS.size)
    }
}
