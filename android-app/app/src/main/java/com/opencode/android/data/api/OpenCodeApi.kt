package com.opencode.android.data.api

import com.opencode.android.data.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

class OpenCodeApi(config: ServerConfig) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    private val baseUrl = "http://${config.host}:${config.port}"

    private val authHeader = if (config.password.isNotBlank()) {
        val cred = Base64.getEncoder().encodeToString("opencode:${config.password}".toByteArray())
        "Basic $cred"
    } else null

    private val directoryHeader = config.directory.takeIf { it.isNotBlank() }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 120_000
        }
        defaultRequest {
            authHeader?.let { header("Authorization", it) }
            directoryHeader?.let { header("x-opencode-directory", it) }
        }
    }

    // Client with no timeout for long-running operations (send prompt, abort)
    private val longPollClient = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = Long.MAX_VALUE
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = Long.MAX_VALUE
        }
        defaultRequest {
            authHeader?.let { header("Authorization", it) }
            directoryHeader?.let { header("x-opencode-directory", it) }
        }
    }

    private suspend inline fun <reified T> safeRequest(request: suspend () -> HttpResponse): Result<T> = runCatching {
        val response = request()
        if (response.status.value in 200..299) {
            response.body<T>()
        } else {
            throw Exception("HTTP ${response.status.value}: ${response.status.description}")
        }
    }

    suspend fun health(): Result<HealthResponse> = safeRequest {
        client.get("$baseUrl/global/health")
    }

    suspend fun listSessions(): Result<List<Session>> = safeRequest {
        client.get("$baseUrl/session")
    }

    suspend fun createSession(title: String? = null): Result<Session> = safeRequest {
        client.post("$baseUrl/session") {
            contentType(ContentType.Application.Json)
            setBody(CreateSessionRequest(title))
        }
    }

    suspend fun getMessages(sessionId: String): Result<List<Message>> = safeRequest {
        client.get("$baseUrl/session/$sessionId/message")
    }

    /** Send prompt — API returns the assistant message synchronously in response body */
    suspend fun sendPrompt(sessionId: String, content: String): Result<Message> = runCatching {
        val response = longPollClient.post("$baseUrl/session/$sessionId/message") {
            contentType(ContentType.Application.Json)
            setBody(PromptRequest(parts = listOf(PromptPart(text = content))))
        }
        if (response.status.value in 200..299) {
            response.body<Message>()
        } else {
            throw Exception("HTTP ${response.status.value}")
        }
    }

    suspend fun abort(sessionId: String): Result<HttpResponse> = runCatching {
        longPollClient.post("$baseUrl/session/$sessionId/abort")
    }

    /**
     * SSE via raw HttpURLConnection - avoids Ktor SSE module dependency issues.
     * Reads the event stream line-by-line and emits "data:" payloads.
     */
    fun sessionEvents(): Flow<String> = flow {
        withContext(Dispatchers.IO) {
            val url = URL("$baseUrl/event")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "text/event-stream")
                setRequestProperty("Cache-Control", "no-cache")
                authHeader?.let { setRequestProperty("Authorization", it) }
                directoryHeader?.let { setRequestProperty("x-opencode-directory", it) }
                connectTimeout = 10_000
                readTimeout = 0 // no read timeout for SSE
            }

            try {
                val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (l.startsWith("data: ")) {
                        val data = l.removePrefix("data: ")
                        if (data.isNotBlank()) emit(data)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Connection ended or errored
            } finally {
                conn.disconnect()
            }
        }
    }

    fun close() { client.close() }
}
