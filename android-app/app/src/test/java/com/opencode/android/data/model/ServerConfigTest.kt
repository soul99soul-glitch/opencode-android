package com.opencode.android.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerConfigTest {
    @Test
    fun endpointIdentityIncludesConnectionAndWorkspace() {
        val config = ServerConfig(
            host = " http://192.168.1.8/ ",
            port = 4096,
            password = "secret",
            directory = " /repo ",
        )

        assertEquals("http://192.168.1.8|/repo", config.endpointIdentity())
    }

    @Test
    fun endpointIdentityMatchesFullUrlMode() {
        val base = ServerConfig(host = "https://example.test/opencode/", port = 4096, directory = "/repo")
        val sameEndpoint = base.copy(port = 9999)

        assertEquals("https://example.test/opencode|/repo", base.endpointIdentity())
        assertEquals(base.endpointIdentity(), sameEndpoint.endpointIdentity())
    }

    @Test
    fun endpointBaseUrlUsesSameNormalization() {
        val fullUrl = ServerConfig(host = " https://example.test/opencode/ ", port = 4096, directory = " /repo ")
        val legacy = ServerConfig(host = " 192.168.1.8 ", port = 4097, directory = " /repo ")

        assertEquals("https://example.test/opencode", fullUrl.endpointBaseUrl())
        assertEquals("/repo", fullUrl.normalizedDirectory())
        assertEquals("http://192.168.1.8:4097", legacy.endpointBaseUrl())
    }

    @Test
    fun endpointIdentityChangesWithWorkspaceButNotPassword() {
        val base = ServerConfig(host = "lan.local", port = 4096, password = "one", directory = "/repo-a")
        val sameEndpoint = base.copy(password = "two")
        val otherWorkspace = base.copy(directory = "/repo-b")

        assertEquals(base.endpointIdentity(), sameEndpoint.endpointIdentity())
        assertEquals("http://lan.local:4096|/repo-b", otherWorkspace.endpointIdentity())
    }

    @Test
    fun endpointIdentityMatchesLegacyAndExplicitHttpForms() {
        val legacy = ServerConfig(host = "lan.local", port = 4096, directory = "/repo")
        val explicit = ServerConfig(host = "http://lan.local:4096", port = 9999, directory = "/repo")

        assertEquals(explicit.endpointIdentity(), legacy.endpointIdentity())
    }
}
