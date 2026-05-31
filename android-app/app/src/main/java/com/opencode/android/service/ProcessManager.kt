package com.opencode.android.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.OutputStream

class ProcessManager(private val context: Context) {

    enum class State { IDLE, STARTING, RUNNING, STOPPED, ERROR }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private var process: Process? = null
    private var processInput: OutputStream? = null
    private val mutex = Mutex()
    private var intentionalStop = false

    private val _outputLines = MutableList<String>(1000) { "" }
    private var outputIndex = 0
    val recentOutput: String get() = _outputLines.filter { it.isNotBlank() }.takeLast(50).joinToString("\n")

    suspend fun start(port: Int = 4096, directory: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.lock()
        try {
            if (_state.value == State.RUNNING) return@withContext Result.success(Unit)

            val binary = getBinaryPath()
            if (!binary.exists()) {
                _state.value = State.ERROR
                return@withContext Result.failure(Exception("Binary not found: ${binary.absolutePath}"))
            }
            if (!binary.canExecute()) binary.setExecutable(true)

            val workDir = directory?.let { File(it) } ?: context.filesDir
            workDir.mkdirs()

            _state.value = State.STARTING
            intentionalStop = false

            val env = buildMap {
                put("TERM", "xterm-256color")
                put("COLORTERM", "truecolor")
                put("HOME", context.filesDir.absolutePath)
                put("PATH", "/system/bin:/system/xbin:${context.applicationInfo.nativeLibraryDir}")
            }

            val pb = ProcessBuilder(binary.absolutePath, "serve", "--host", "127.0.0.1", "--port", port.toString())
                .directory(workDir)
                .redirectErrorStream(true)
            env.forEach { (k, v) -> pb.environment()[k] = v }

            val proc = pb.start()
            process = proc
            processInput = proc.outputStream

            // Read output
            Thread {
                try {
                    val reader = proc.inputStream.bufferedReader()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        synchronized(_outputLines) {
                            _outputLines[outputIndex % 1000] = line ?: ""
                            outputIndex++
                        }
                        Log.d("OpenCode", line ?: "")
                    }
                } catch (e: Exception) {
                    Log.e("OpenCode", "Output read ended", e)
                }
            }.apply { name = "opencode-output"; isDaemon = true; start() }

            // Monitor lifecycle
            Thread {
                try {
                    val exitCode = proc.waitFor()
                    Log.i("OpenCode", "Exited with code $exitCode")
                    synchronized(this) {
                        if (!intentionalStop) {
                            _state.value = if (exitCode == 0) State.STOPPED else State.ERROR
                        }
                    }
                } catch (e: Exception) {
                    if (!intentionalStop) _state.value = State.ERROR
                }
            }.apply { name = "opencode-monitor"; isDaemon = true; start() }

            // Wait for readiness (poll health)
            var retries = 0
            while (retries < 30 && proc.isAlive) {
                Thread.sleep(500)
                try {
                    val url = java.net.URL("http://127.0.0.1:$port/global/health")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 1000
                    conn.readTimeout = 1000
                    val code = conn.responseCode
                    conn.disconnect()
                    if (code == 200) {
                        _state.value = State.RUNNING
                        return@withContext Result.success(Unit)
                    }
                } catch (_: Exception) {}
                retries++
            }

            if (!proc.isAlive) {
                _state.value = State.ERROR
                return@withContext Result.failure(Exception("Process exited during startup"))
            }

            _state.value = State.RUNNING
            Result.success(Unit)
        } catch (e: Exception) {
            _state.value = State.ERROR
            Result.failure(e)
        } finally {
            mutex.unlock()
        }
    }

    fun stop() {
        synchronized(this) { intentionalStop = true }
        try {
            processInput?.close()
            val proc = process
            if (proc != null) {
                proc.destroy()
                if (!proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                }
            }
        } catch (_: Exception) {}
        process = null
        processInput = null
        _state.value = State.STOPPED
    }

    fun isRunning(): Boolean = _state.value == State.RUNNING

    private fun getBinaryPath(): File {
        val locations = listOf(
            File(context.applicationInfo.nativeLibraryDir, "libopencode.so"),
            File(context.filesDir, "bin/opencode"),
            File(context.filesDir, "opencode")
        )
        return locations.firstOrNull { it.exists() } ?: locations.first()
    }
}
