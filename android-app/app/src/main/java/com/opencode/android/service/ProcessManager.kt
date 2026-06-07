package com.opencode.android.service

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.opencode.android.data.model.LocalProviderDefaults
import com.opencode.android.data.model.LocalProviderProfile
import com.opencode.android.service.LocalOpenCodeConfigWriter.generatedConfigFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.OutputStream

@Deprecated(
    message = "Use RuntimeProcessManager from the runtime package. This class uses --host (potentially wrong flag) and lacks SAF bridge, DNS, and workspace state support. Note: the replacement API is not a drop-in — it requires RuntimeProviderConfig, workspaceTreeUri, and serverPassword.",
)
class ProcessManager(private val context: Context) {

    enum class State { IDLE, STARTING, RUNNING, STOPPED, ERROR }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private var process: Process? = null
    private var processInput: OutputStream? = null
    private var runningPort: Int? = null
    private var runningWorkDir: String? = null
    private val mutex = Mutex()
    private var intentionalStop = false
    @Volatile
    private var generation = 0L

    private val _outputLines = MutableList<String>(1000) { "" }
    private var outputIndex = 0
    val recentOutput: String get() = _outputLines.filter { it.isNotBlank() }.takeLast(50).joinToString("\n")

    suspend fun start(
        port: Int = 4096,
        directory: String? = null,
        extraEnv: Map<String, String> = emptyMap(),
    ): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.lock()
        try {
            val startGeneration = synchronized(this@ProcessManager) { ++generation }
            val workDir = directory?.let { File(it) } ?: context.filesDir
            val workDirPath = workDir.absoluteFile.absolutePath
            if (_state.value == State.RUNNING && process?.isAlive == true) {
                if (runningPort == port && runningWorkDir == workDirPath) {
                    return@withContext Result.success(Unit)
                }
                stopProcess(State.IDLE)
            }

            val binary = findBundledBinary(context)
            if (binary == null) {
                _state.value = State.ERROR
                return@withContext Result.failure(Exception("Bundled OpenCode binary not found"))
            }
            if (!binary.canExecute()) binary.setExecutable(true)

            workDir.mkdirs()

            verifyBinary(binary, workDir).getOrElse {
                _state.value = State.ERROR
                return@withContext Result.failure(it)
            }
            if (startGeneration != generation) {
                return@withContext Result.failure(Exception("Start cancelled"))
            }

            _state.value = State.STARTING
            intentionalStop = false

            val env = processEnv(context, extraEnv)

            val pb = ProcessBuilder(binary.absolutePath, "serve", "--host", "127.0.0.1", "--port", port.toString())
                .directory(workDir)
                .redirectErrorStream(true)
            env.forEach { (k, v) -> pb.environment()[k] = v }
            if (startGeneration != generation) {
                _state.value = State.STOPPED
                return@withContext Result.failure(Exception("Start cancelled"))
            }

            val proc = pb.start()
            val startedAfterStop = synchronized(this@ProcessManager) {
                if (startGeneration != generation) {
                    true
                } else {
                    process = proc
                    processInput = proc.outputStream
                    runningPort = port
                    runningWorkDir = workDirPath
                    false
                }
            }
            if (startedAfterStop) {
                destroyProcess(proc)
                _state.value = State.STOPPED
                return@withContext Result.failure(Exception("Start cancelled"))
            }

            // Read output
            val debuggable = isDebuggable(context)
            Thread {
                try {
                    val reader = proc.inputStream.bufferedReader()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (debuggable) {
                            synchronized(_outputLines) {
                                _outputLines[outputIndex % 1000] = line ?: ""
                                outputIndex++
                            }
                            Log.d("OpenCode", line ?: "")
                        }
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
                    synchronized(this@ProcessManager) {
                        if (process !== proc) return@Thread
                        runningPort = null
                        runningWorkDir = null
                        if (!intentionalStop) {
                            _state.value = if (exitCode == 0) State.STOPPED else State.ERROR
                        }
                    }
                } catch (e: Exception) {
                    synchronized(this@ProcessManager) {
                        if (process === proc && !intentionalStop) _state.value = State.ERROR
                    }
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

    suspend fun stop() = withContext(Dispatchers.IO) {
        mutex.lock()
        try {
            synchronized(this@ProcessManager) { generation++ }
            stopProcess(State.STOPPED)
        } finally {
            mutex.unlock()
        }
    }

    fun stopNow() {
        synchronized(this@ProcessManager) { generation++ }
        stopProcess(State.STOPPED)
    }

    private fun stopProcess(finalState: State) {
        val input: OutputStream?
        val proc: Process?
        synchronized(this) {
            intentionalStop = true
            input = processInput
            proc = process
            process = null
            processInput = null
            runningPort = null
            runningWorkDir = null
        }
        try {
            input?.close()
            if (proc != null) {
                destroyProcess(proc)
            }
        } catch (_: Exception) {}
        _state.value = finalState
    }

    private fun destroyProcess(proc: Process) {
        proc.destroy()
        if (!proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly()
        }
    }

    fun isRunning(): Boolean = _state.value == State.RUNNING

    fun isRunningFor(port: Int, directory: String?): Boolean {
        val workDirPath = (directory?.let { File(it) } ?: context.filesDir).absoluteFile.absolutePath
        return _state.value == State.RUNNING &&
            process?.isAlive == true &&
            runningPort == port &&
            runningWorkDir == workDirPath
    }

    private fun verifyBinary(binary: File, workDir: File): Result<Unit> = runCatching {
        val fingerprint = "${binary.absolutePath}:${binary.length()}:${binary.lastModified()}"
        synchronized(verificationLock) {
            if (verifiedBinaryFingerprint == fingerprint) return@runCatching
        }

        val pb = ProcessBuilder(binary.absolutePath, "--version")
            .directory(workDir)
            .redirectErrorStream(true)
        processEnv(context).forEach { (key, value) -> pb.environment()[key] = value } // verifyBinary uses base env only
        val proc = pb.start()
        if (!proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw IllegalStateException("Bundled OpenCode binary version check timed out")
        }
        if (proc.exitValue() != 0) {
            val output = proc.inputStream.bufferedReader().readText().trim()
            throw IllegalStateException("Bundled OpenCode binary failed: ${output.ifBlank { "exit ${proc.exitValue()}" }}")
        }
        synchronized(verificationLock) {
            verifiedBinaryFingerprint = fingerprint
        }
    }

    companion object {
        private val verificationLock = Any()
        private var verifiedBinaryFingerprint: String? = null

        private fun isDebuggable(context: Context): Boolean =
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        fun findBundledBinary(context: Context): File? =
            binaryLocations(context).firstOrNull { it.exists() }

        fun bundledStatus(context: Context): String =
            findBundledBinary(context)?.absolutePath ?: "Bundled OpenCode binary not found"

        private fun binaryLocations(context: Context): List<File> = listOf(
            File(context.applicationInfo.nativeLibraryDir, "libopencode.so"),
        )

        fun bundledRuntimeEnv(
            context: Context,
            profile: LocalProviderProfile,
            apiKey: String,
        ): Map<String, String> =
            bundledRuntimeEnv(context.filesDir, profile, apiKey)

        internal fun bundledRuntimeEnv(
            filesDir: File,
            profile: LocalProviderProfile,
            apiKey: String,
        ): Map<String, String> = buildMap {
            if (profile.enabled) {
                val configFile = generatedConfigFile(filesDir)
                if (configFile.exists()) {
                    put("OPENCODE_CONFIG", configFile.absolutePath)
                }
                if (profile.hasApiKey && apiKey.isNotBlank()) {
                    put(LocalProviderDefaults.API_KEY_ENV, apiKey)
                }
            }
        }

        private fun processEnv(context: Context, extraEnv: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
            put("TERM", "xterm-256color")
            put("COLORTERM", "truecolor")
            put("HOME", context.filesDir.absolutePath)
            put("PATH", "/system/bin:/system/xbin:${context.applicationInfo.nativeLibraryDir}")
            putAll(extraEnv)
        }
    }
}
