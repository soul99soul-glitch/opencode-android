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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

sealed interface PromptSendResult {
    data class Completed(val message: Message) : PromptSendResult
    data object AcceptedAsync : PromptSendResult
}

class OpenCodeApi private constructor(
    private val baseUrl: String,
    password: String,
    directory: String,
) {

    constructor(config: ServerConfig) : this(
        baseUrl = when {
            config.host.startsWith("http://") || config.host.startsWith("https://") ->
                config.host.trimEnd('/')
            else ->
                "http://${config.host}:${config.port}"
        },
        password = config.password,
        directory = config.directory,
    )

    constructor(endpoint: ActiveEndpoint) : this(
        baseUrl = endpoint.baseUrl.trimEnd('/'),
        password = endpoint.password,
        directory = endpoint.directory,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    private val authHeader = if (password.isNotBlank()) {
        val cred = Base64.getEncoder().encodeToString("opencode:$password".toByteArray())
        "Basic $cred"
    } else null

    private val blockedCleartextMessage = publicCleartextBlockMessage(baseUrl)

    private val directoryHeader = directory.takeIf { it.isNotBlank() }

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

    private fun ensureCleartextAllowed() {
        blockedCleartextMessage?.let { throw IllegalStateException(it) }
    }

    private suspend inline fun <reified T> safeRequest(request: suspend () -> HttpResponse): Result<T> = runCatching {
        ensureCleartextAllowed()
        val response = request()
        if (response.status.value in 200..299) {
            response.body<T>()
        } else {
            val body = try { response.bodyAsText() } catch (_: Exception) { "" }
            throw OpenCodeHttpException(response.status.value, body)
        }
    }

    suspend fun health(): Result<HealthResponse> = safeRequest {
        client.get("$baseUrl/global/health")
    }

    suspend fun listSessions(directory: String? = null, roots: Boolean = false): Result<List<Session>> = safeRequest {
        val requestedDirectory = directory ?: directoryHeader
        client.get("$baseUrl/experimental/session") {
            requestedDirectory?.takeIf { it.isNotBlank() }?.let {
                header("x-opencode-directory", it)
                parameter("directory", it)
            }
            parameter("limit", 200)
            if (roots) parameter("roots", true)
        }
    }

    suspend fun getSession(sessionId: String): Result<Session> = safeRequest {
        client.get("$baseUrl/session/$sessionId")
    }

    suspend fun fetchAgents(): Result<List<AgentInfo>> = safeRequest {
        client.get("$baseUrl/agent")
    }

    suspend fun fetchProjects(): Result<List<Project>> = safeRequest {
        client.get("$baseUrl/project")
    }

    suspend fun fetchProjectRootFiles(directory: String): Result<List<RemoteFileEntry>> = runCatching {
        ensureCleartextAllowed()
        val response = client.get("$baseUrl/file") {
            header("x-opencode-directory", directory)
            parameter("directory", directory)
        }
        if (response.status.value !in 200..299) {
            val body = try { response.bodyAsText() } catch (_: Exception) { "" }
            throw OpenCodeHttpException(response.status.value, body)
        }
        val raw = response.bodyAsText()
        val entries = json.parseToJsonElement(raw) as? JsonArray ?: return@runCatching emptyList()
        entries.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            RemoteFileEntry(
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                path = obj["path"]?.jsonPrimitive?.contentOrNull ?: "",
                absolute = obj["absolute"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                ignored = obj["ignored"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
            )
        }
    }

    /** Fetch available skills for slash-command autocomplete */
    suspend fun fetchSkills(): Result<List<SkillInfo>> = safeRequest {
        client.get("$baseUrl/skill")
    }

    suspend fun deleteSession(sessionId: String): Result<HttpResponse> = runCatching {
        ensureCleartextAllowed()
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

    /**
     * Send prompt to the session.
     *
     * For local async runtimes: the server accepts the prompt via POST and processes it
     * asynchronously — real-time assistant output arrives via SSE `message.part.delta`
     * events, and completion is signalled by `session.status=completed`. The POST response
     * body may contain a streaming/chunked payload that can break if the connection is
     * interrupted. When a loopback bundled runtime has clearly accepted the POST but the
     * response closes ambiguously, [PromptSendResult.AcceptedAsync] is returned so callers
     * continue SSE/polling instead of showing an immediate send failure.
     *
     * For remote servers: the full response body is expected and read synchronously.
     * Stream breaks are propagated as failures.
     */
    suspend fun sendPrompt(
        sessionId: String,
        parts: List<PromptPart>,
        agent: String? = null,
        model: ModelRef? = null,
        allowAsyncLocalClose: Boolean = false,
    ): Result<PromptSendResult> = runCatching {
        ensureCleartextAllowed()
        val response = try {
            longPollClient.post("$baseUrl/session/$sessionId/message") {
                contentType(ContentType.Application.Json)
                setBody(PromptRequest(parts = parts, agent = agent, model = model))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (allowAsyncLocalClose && isLoopbackEndpoint() && isAmbiguousPostClose(e)) {
                return@runCatching PromptSendResult.AcceptedAsync
            }
            throw e
        }
        if (response.status.value in 200..299) {
            val raw = try {
                response.bodyAsText()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Local async runtime: stream break after 2xx is not a failure —
                // prompt was accepted, SSE/polling carries the real output.
                // Catch broadly (Exception) because Ktor 3.x may throw
                // kotlinx.io.IOException instead of java.io.IOException.
                if (!allowAsyncLocalClose || !isLoopbackEndpoint() || !isAmbiguousPostClose(e)) throw e
                null
            }
            if (raw == null) {
                return@runCatching PromptSendResult.AcceptedAsync
            }
            if (raw.trimStart().startsWith("<!DOCTYPE") || raw.trimStart().startsWith("<html")) {
                throw Exception("Server returned HTML (${response.status.value})")
            }
            if (raw.isBlank()) {
                if (allowAsyncLocalClose && isLoopbackEndpoint()) {
                    return@runCatching PromptSendResult.AcceptedAsync
                }
                throw Exception("Server returned empty response (${response.status.value})")
            }
            try {
                PromptSendResult.Completed(json.decodeFromString<Message>(raw))
            } catch (e: Exception) {
                if (allowAsyncLocalClose && isLoopbackEndpoint() && isProbablyTruncatedJson(raw, e)) {
                    return@runCatching PromptSendResult.AcceptedAsync
                }
                throw e
            }
        } else {
            val body = try { response.bodyAsText() } catch (_: Exception) { "" }
            throw OpenCodeHttpException(response.status.value, body)
        }
    }

    suspend fun abort(sessionId: String): Result<HttpResponse> = runCatching {
        ensureCleartextAllowed()
        longPollClient.post("$baseUrl/session/$sessionId/abort")
    }

    /** Fetch available providers (only configured ones, source=config). */
    suspend fun fetchConfiguredProviders(): Result<List<Provider>> = runCatching {
        ensureCleartextAllowed()
        fetchConfiguredProvidersFromServer(client, baseUrl, json)
    }

    /**
     * SSE via raw HttpURLConnection — uses callbackFlow for safe cross-dispatcher emission.
     */
    fun sessionEvents(): Flow<String> {
        blockedCleartextMessage?.let { message ->
            return flow { throw IllegalStateException(message) }
        }
        return callbackFlow {
            val conn = (URL("$baseUrl/event").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "text/event-stream")
                setRequestProperty("Cache-Control", "no-cache")
                authHeader?.let { setRequestProperty("Authorization", it) }
                directoryHeader?.let { setRequestProperty("x-opencode-directory", it) }
                connectTimeout = 10_000
                readTimeout = 0
            }

            val readerJob = launch(Dispatchers.IO) {
                var reader: BufferedReader? = null
                var closeCause: Throwable? = null
                try {
                    reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                    while (isActive) {
                        val line = runInterruptible { reader.readLine() }
                        if (line == null) {
                            closeCause = IOException("SSE connection closed")
                            break
                        }
                        if (line.startsWith("data: ")) {
                            val data = line.removePrefix("data: ")
                            if (data.isNotBlank() && trySend(data).isFailure) break
                        }
                    }
                } catch (_: CancellationException) {
                    // expected on close
                } catch (e: Exception) {
                    closeCause = e
                } finally {
                    try { reader?.close() } catch (_: Exception) {}
                    conn.disconnect()
                    channel.close(closeCause)
                }
            }

            awaitClose {
                conn.disconnect()
                readerJob.cancel()
            }
        }
    }

    fun close() { client.close(); longPollClient.close() }

    companion object {
        internal suspend fun fetchConfiguredProvidersFromServer(
            client: HttpClient,
            baseUrl: String,
            json: Json,
        ): List<Provider> {
            val configProviders = runCatching {
                val response = client.get("$baseUrl/config/providers")
                if (response.status.value !in 200..299) throw Exception("HTTP ${response.status.value}")
                parseConfigProvidersResponse(response.bodyAsText(), json)
            }.getOrNull()
            if (configProviders != null) {
                return configProviders
            }

            val legacyResponse = client.get("$baseUrl/provider")
            if (legacyResponse.status.value !in 200..299) {
                throw Exception("HTTP ${legacyResponse.status.value}")
            }
            return parseLegacyProviderResponse(legacyResponse.bodyAsText(), json)
        }

        internal fun publicCleartextBlockMessage(baseUrl: String): String? =
            EndpointSecurityPolicy.publicCleartextBlockMessage(baseUrl)

        internal fun isLocalOrPrivateHost(host: String): Boolean {
            return EndpointSecurityPolicy.isLocalOrPrivateHost(host)
        }

        internal fun isAmbiguousPostClose(error: Throwable): Boolean {
            var current: Throwable? = error
            while (current != null) {
                val text = listOfNotNull(current.message, current::class.simpleName)
                    .joinToString(" ")
                    .lowercase()
                if (
                    "unexpected end of stream" in text ||
                    "stream was reset" in text ||
                    "stream reset" in text ||
                    "socket closed" in text ||
                    "eofexception" in text
                ) {
                    return true
                }
                current = current.cause
            }
            return false
        }

        internal fun isProbablyTruncatedJson(raw: String, error: Throwable): Boolean {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return false
            if (trimmed.endsWith("}") || trimmed.endsWith("]")) return false
            var current: Throwable? = error
            while (current != null) {
                val text = listOfNotNull(current.message, current::class.simpleName)
                    .joinToString(" ")
                    .lowercase()
                if (
                    "unexpected json token" in text ||
                    "unexpected end" in text ||
                    "end of input" in text ||
                    "eof" in text ||
                    "expected" in text ||
                    "serializationexception" in text
                ) {
                    return true
                }
                current = current.cause
            }
            return false
        }
    }

    private fun isLoopbackEndpoint(): Boolean =
        EndpointSecurityPolicy.isLoopbackUrl(baseUrl)
}
