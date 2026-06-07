package com.opencode.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OpenCodeApiErrorTest {
    @Test
    fun jsonServerErrorExtractsDataMessageAndDropsStack() {
        val body = """
            {
              "name": "UnknownError",
              "data": {
                "message": "SQLiteError: FOREIGN KEY constraint failed\n    at run (unknown)\n    at #run (bun:sqlite:185:20)"
              }
            }
        """.trimIndent()

        val message = formatHttpError(500, body)

        assertEquals(
            "OpenCode server error (HTTP 500): SQLiteError: FOREIGN KEY constraint failed",
            message,
        )
        assertFalse(message.contains("bun:sqlite"))
    }

    @Test
    fun htmlServerErrorDoesNotLeakMarkup() {
        assertEquals(
            "OpenCode server error (HTTP 500): Server returned HTML",
            formatHttpError(500, "<!DOCTYPE html><html><body>boom</body></html>"),
        )
    }

    @Test
    fun blankUnauthorizedHasReadableDefault() {
        assertEquals(
            "Authentication failed (HTTP 401)",
            formatHttpError(401, ""),
        )
    }

    @Test
    fun nestedProviderErrorExtractsMessage() {
        val body = """
            {
              "error": {
                "message": "Invalid API Key",
                "param": "Please provide valid API Key",
                "code": "401",
                "type": "invalid_key"
              }
            }
        """.trimIndent()

        assertEquals(
            "Authentication failed (HTTP 401): Invalid API Key",
            formatHttpError(401, body),
        )
    }
}
