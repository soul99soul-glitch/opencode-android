package com.opencode.android.data.api

import com.opencode.android.data.model.ActiveEndpoint
import com.opencode.android.data.model.ConnectionMode
import com.opencode.android.data.model.PromptPart
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class OpenCodeApiSendPromptTest {
    @Test
    fun sendPromptReturnsCompletedForJsonBody() = runBlocking {
        withOneShotHttpServer(
            status = 200,
            body = """{"info":{"id":"m1","role":"assistant"},"parts":[]}""",
        ) { port ->
            val api = api(port)
            try {
                val result = api.sendPrompt(
                    sessionId = "s1",
                    parts = listOf(PromptPart(text = "hi")),
                    allowAsyncLocalClose = true,
                ).getOrThrow()

                assertTrue(result is PromptSendResult.Completed)
            } finally {
                api.close()
            }
        }
    }

    @Test
    fun sendPromptAcceptsEmptyLocalResponseOnlyWhenAllowed() = runBlocking {
        withOneShotHttpServer(status = 204, body = "") { port ->
            val api = api(port)
            try {
                val result = api.sendPrompt(
                    sessionId = "s1",
                    parts = listOf(PromptPart(text = "hi")),
                    allowAsyncLocalClose = true,
                ).getOrThrow()

                assertTrue(result is PromptSendResult.AcceptedAsync)
            } finally {
                api.close()
            }
        }
    }

    @Test
    fun sendPromptRejectsEmptyResponseWhenAsyncLocalCloseDisabled() = runBlocking {
        withOneShotHttpServer(status = 204, body = "") { port ->
            val api = api(port)
            try {
                val result = api.sendPrompt(
                    sessionId = "s1",
                    parts = listOf(PromptPart(text = "hi")),
                    allowAsyncLocalClose = false,
                )

                assertTrue(result.isFailure)
            } finally {
                api.close()
            }
        }
    }

    @Test
    fun sendPromptAcceptsTruncatedLocalJsonWhenAsyncCloseAllowed() = runBlocking {
        withOneShotHttpServer(status = 200, body = "{\"info\":{\"id\":\"m1\"") { port ->
            val api = api(port)
            try {
                val result = api.sendPrompt(
                    sessionId = "s1",
                    parts = listOf(PromptPart(text = "hi")),
                    allowAsyncLocalClose = true,
                ).getOrThrow()

                assertTrue(result is PromptSendResult.AcceptedAsync)
            } finally {
                api.close()
            }
        }
    }

    @Test
    fun sendPromptRejectsTruncatedJsonWhenAsyncCloseDisabled() = runBlocking {
        withOneShotHttpServer(status = 200, body = "{\"info\":{\"id\":\"m1\"") { port ->
            val api = api(port)
            try {
                val result = api.sendPrompt(
                    sessionId = "s1",
                    parts = listOf(PromptPart(text = "hi")),
                    allowAsyncLocalClose = false,
                )

                assertTrue(result.isFailure)
            } finally {
                api.close()
            }
        }
    }

    @Test
    fun sendPromptDoesNotSwallowHttpErrors() = runBlocking {
        withOneShotHttpServer(
            status = 401,
            body = """{"error":{"message":"Invalid API Key"}}""",
        ) { port ->
            val api = api(port)
            try {
                val result = api.sendPrompt(
                    sessionId = "s1",
                    parts = listOf(PromptPart(text = "hi")),
                    allowAsyncLocalClose = true,
                )

                assertTrue(result.exceptionOrNull() is OpenCodeHttpException)
            } finally {
                api.close()
            }
        }
    }

    private fun api(port: Int): OpenCodeApi =
        OpenCodeApi(
            ActiveEndpoint(
                mode = ConnectionMode.LOCAL_BUNDLED,
                baseUrl = "http://127.0.0.1:$port",
            ),
        )

    private suspend fun <T> withOneShotHttpServer(status: Int, body: String, block: suspend (Int) -> T): T {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        var thrown: Throwable? = null
        val worker = thread(start = true, name = "opencode-api-test-server") {
            try {
                server.use {
                    it.accept().use { socket ->
                        socket.readRequestHeaders()
                        socket.writeResponse(status, body)
                    }
                }
            } catch (e: Throwable) {
                thrown = e
            }
        }

        return try {
            block(server.localPort)
        } finally {
            runCatching { server.close() }
            worker.join(1_000)
            thrown?.let { throw it }
        }
    }

    private fun Socket.readRequestHeaders() {
        val reader = getInputStream().bufferedReader(Charsets.ISO_8859_1)
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) return
        }
    }

    private fun Socket.writeResponse(status: Int, body: String) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            204 -> "No Content"
            401 -> "Unauthorized"
            else -> "OK"
        }
        val headers = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(Charsets.ISO_8859_1)
        getOutputStream().apply {
            write(headers)
            write(bodyBytes)
            flush()
        }
    }
}
