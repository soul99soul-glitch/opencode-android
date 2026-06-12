package com.opencode.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

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

    @Test
    fun ambiguousPostCloseDetectionIsNarrow() {
        assertTrue(OpenCodeApi.isAmbiguousPostClose(IOException("unexpected end of stream on http://127.0.0.1:4097/...")))
        assertTrue(OpenCodeApi.isAmbiguousPostClose(IOException("stream was reset: CANCEL")))
        assertFalse(OpenCodeApi.isAmbiguousPostClose(IOException("failed to connect to /127.0.0.1")))
        assertFalse(OpenCodeApi.isAmbiguousPostClose(OpenCodeHttpException(401, """{"error":{"message":"Invalid API Key"}}""")))
    }

    @Test
    fun directoryHeaderSafetyRejectsNonAsciiPaths() {
        assertTrue("/Users/mi/Downloads/work".isHttpHeaderValueSafe())
        assertFalse("/Users/mi/Downloads/工作".isHttpHeaderValueSafe())
    }

    @Test
    fun sseParserFlushesStandardMultiLineDataFrame() {
        val lines = mutableListOf<String>()

        assertEquals(null, OpenCodeApi.consumeSseLine(": keepalive", lines))
        assertEquals(null, OpenCodeApi.consumeSseLine("event: message", lines))
        assertEquals(null, OpenCodeApi.consumeSseLine("data: {\"type\":", lines))
        assertEquals(null, OpenCodeApi.consumeSseLine("data: \"message.updated\"}", lines))

        assertEquals("{\"type\":\n\"message.updated\"}", OpenCodeApi.consumeSseLine("", lines))
        assertTrue(lines.isEmpty())
    }

    @Test
    fun sseParserAcceptsDataWithoutSpaceAfterColon() {
        val lines = mutableListOf<String>()

        assertEquals(null, OpenCodeApi.consumeSseLine("data:{\"type\":\"session.status\"}", lines))

        assertEquals("{\"type\":\"session.status\"}", OpenCodeApi.consumeSseLine("", lines))
    }
}
