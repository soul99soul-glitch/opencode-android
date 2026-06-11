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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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

    private val baseUrl = config.endpointBaseUrl()

    private val authHeader = if (config.password.isNotBlank()) {
        val cred = Base64.getEncoder().encodeToString("opencode:${config.password}".toByteArray())
        "Basic $cred"
    } else null

    private val directoryHeader = config.normalizedDirectory().takeIf { it.isNotBlank() }

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
            val body = try { response.bodyAsText().take(200) } catch (_: Exception) { "" }
            throw Exception("HTTP ${response.status.value}: $body")
        }
    }

    suspend fun health(): Result<HealthResponse> = safeRequest {
        client.get("$baseUrl/global/health")
    }

    suspend fun listSessions(): Result<List<Session>> = safeRequest {
        client.get("$baseUrl/session")
    }

    suspend fun getSession(sessionId: String): Result<Session> = safeRequest {
        client.get("$baseUrl/session/$sessionId")
    }

    suspend fun fetchAgents(): Result<List<AgentInfo>> = safeRequest {
        client.get("$baseUrl/agent")
    }

    /** Fetch available skills for slash-command autocomplete */
    suspend fun fetchSkills(): Result<List<SkillInfo>> = safeRequest {
        client.get("$baseUrl/skill")
    }

    suspend fun fetchConfigDiagnostics(): Result<RuntimeDiagnostics> = runCatching {
        val response = client.get("$baseUrl/config")
        if (response.status.value !in 200..299) {
            val body = try { response.bodyAsText().take(200) } catch (_: Exception) { "" }
            throw Exception("HTTP ${response.status.value}: $body")
        }
        json.parseToJsonElement(response.bodyAsText()).jsonObject.toRuntimeDiagnostics()
    }.recoverCatching { RuntimeDiagnostics(error = it.message ?: "Unable to read /config") }

    suspend fun deleteSession(sessionId: String): Result<HttpResponse> = runCatching {
        client.delete("$baseUrl/session/$sessionId")
    }

    suspend fun createSession(title: String? = null): Result<Session> = safeRequest {
        client.post("$baseUrl/session") {
            contentType(ContentType.Application.Json)
            if (title != null) setBody(CreateSessionRequest(title))
            else setBody("{}")
        }
    }

    suspend fun getMessages(sessionId: String): Result<List<Message>> = safeRequest {
        client.get("$baseUrl/session/$sessionId/message")
    }

    /**
     * Enrich sessions with preview text and message count.
     * Fetches messages for each session in parallel, takes the last assistant text.
     */
    suspend fun enrichSessions(sessions: List<Session>): Map<String, Pair<String?, Int>> {
        return coroutineScope {
            sessions.map { session ->
                async {
                    val msgs = getMessages(session.id).getOrNull()
                    val count = msgs?.size ?: 0
                    val preview = msgs
                        ?.lastOrNull { it.info.role == "assistant" }
                        ?.parts
                        ?.firstOrNull { it.type == "text" && !it.text.isNullOrBlank() }
                        ?.text
                        ?.take(120)
                    session.id to (preview to count)
                }
            }.awaitAll().toMap()
        }
    }

    /** Send prompt — API returns the assistant message synchronously in response body */
    suspend fun sendPrompt(sessionId: String, parts: List<PromptPart>, agent: String? = null, model: ModelRef? = null): Result<Message> = runCatching {
        val response = client.post("$baseUrl/session/$sessionId/message") {
            contentType(ContentType.Application.Json)
            setBody(PromptRequest(parts = parts, agent = agent, model = model))
        }
        if (response.status.value in 200..299) {
            val raw = response.bodyAsText()
            // Check if server returned HTML error page instead of JSON
            if (raw.trimStart().startsWith("<!DOCTYPE") || raw.trimStart().startsWith("<html")) {
                throw Exception("Server returned HTML (${response.status.value})")
            }
            if (raw.isBlank()) {
                throw Exception("Server returned empty response (${response.status.value})")
            }
            json.decodeFromString<Message>(raw)
        } else {
            val body = try { response.bodyAsText().take(200) } catch (_: Exception) { "" }
            throw Exception("HTTP ${response.status.value}: $body")
        }
    }

    suspend fun abort(sessionId: String): Result<HttpResponse> = runCatching {
        longPollClient.post("$baseUrl/session/$sessionId/abort")
    }

    /** Fetch available providers (only configured ones, source=config) */
    suspend fun fetchConfiguredProviders(): Result<List<Provider>> = runCatching {
        val response = client.get("$baseUrl/provider")
        if (response.status.value !in 200..299) {
            throw Exception("HTTP ${response.status.value}")
        }
        val body = response.body<ProviderResponse>()
        body.all.filter { it.source == "config" }
    }

    /**
     * SSE via raw HttpURLConnection — uses callbackFlow for safe cross-dispatcher emission.
     */
    fun sessionEvents(): Flow<String> = callbackFlow {
        val url = URL("$baseUrl/event")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Cache-Control", "no-cache")
            authHeader?.let { setRequestProperty("Authorization", it) }
            directoryHeader?.let { setRequestProperty("x-opencode-directory", it) }
            connectTimeout = 10_000
            readTimeout = 0
        }
        try {
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (l.startsWith("data: ")) {
                    val data = l.removePrefix("data: ")
                    if (data.isNotBlank()) trySend(data)
                }
            }
        } catch (_: CancellationException) {
            // expected on close
        } catch (_: Exception) {
            // connection ended or errored
        } finally {
            conn.disconnect()
            close()
        }
        awaitClose { conn.disconnect() }
    }.flowOn(Dispatchers.IO)

    fun close() { client.close(); longPollClient.close() }
}
