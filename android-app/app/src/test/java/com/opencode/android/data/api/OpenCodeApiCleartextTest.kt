package com.opencode.android.data.api

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OpenCodeApiCleartextTest {
    @Test
    fun allowsLocalAndPrivateHttpEndpoints() {
        assertNull(OpenCodeApi.publicCleartextBlockMessage("http://127.0.0.1:4097"))
        assertNull(OpenCodeApi.publicCleartextBlockMessage("http://localhost:4097"))
        assertNull(OpenCodeApi.publicCleartextBlockMessage("http://192.168.100.226:4096"))
        assertNull(OpenCodeApi.publicCleartextBlockMessage("http://10.0.0.2:4096"))
        assertNull(OpenCodeApi.publicCleartextBlockMessage("http://172.16.0.2:4096"))
        assertNull(OpenCodeApi.publicCleartextBlockMessage("http://172.31.255.255:4096"))
    }

    @Test
    fun rejectsPublicHttpEndpointsEvenWithoutAuth() {
        assertNotNull(OpenCodeApi.publicCleartextBlockMessage("http://example.com:4096"))
        assertNotNull(OpenCodeApi.publicCleartextBlockMessage("http://8.8.8.8:4096"))
    }

    @Test
    fun allowsHttpsEndpoints() {
        assertNull(OpenCodeApi.publicCleartextBlockMessage("https://example.com"))
    }
}
