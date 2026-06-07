package com.opencode.android.runtime

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import android.system.Os
import android.system.OsConstants

class RuntimeProcessManager(private val context: Context) {
    private var process: Process? = null
    private var processGroupId: Int? = null
    private var runningPort: Int? = null
    private var runningWorkspace: String? = null

    suspend fun start(
        port: Int,
        workspaceName: String,
        providerConfig: RuntimeProviderConfig,
        providerApiKey: String,
        serverPassword: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val executable = findExecutable()
                ?: throw IllegalStateException("OpenCode runtime binary not found in nativeLibraryDir")
            val workspace = workspaceDir(workspaceName).apply { mkdirs() }
            if (process?.isAlive == true && runningPort == port && runningWorkspace == workspace.absolutePath) {
                if (isHealthy(port, serverPassword)) {
                    return@runCatching
                }
                stopBlocking()
            }
            // ACTION_START must be idempotent across RuntimeService recreation. The service can be
            // killed while the native runtime keeps serving; in that case `process` is null, but a
            // healthy authenticated localhost server is the instance we want to keep.
            if (isHealthy(port, serverPassword)) {
                runningPort = port
                runningWorkspace = workspace.absolutePath
                return@runCatching
            }
            if (process?.isAlive == true) {
                stopBlocking()
            }
            killStaleRuntimeProcesses(OsConstants.SIGKILL)

            RuntimeSupport.ensureInstalled(context).getOrThrow()
            RuntimeConfigWriter.write(context.filesDir, providerConfig).getOrThrow()
            val nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir)
            val versionEnv = RuntimeLaunchEnv.build(
                filesDir = context.filesDir,
                cacheDir = context.cacheDir,
                nativeLibraryDir = nativeLibraryDir,
                config = providerConfig,
                providerApiKey = "",
                includeProviderApiKey = false,
                includeServerPassword = false,
            )
            runVersionPreflight(executable, workspace, versionEnv)

            val serveEnv = RuntimeLaunchEnv.build(
                filesDir = context.filesDir,
                cacheDir = context.cacheDir,
                nativeLibraryDir = nativeLibraryDir,
                config = providerConfig,
                providerApiKey = providerApiKey,
                serverPassword = serverPassword,
            )
            val command = RuntimeServeCommandBuilder.build(
                executable = executable,
                port = port,
                cacheDir = context.cacheDir,
                onSetsidUnavailable = {
                    logDebugWarning("setsid unavailable; runtime stop will only terminate the direct process")
                },
            )
            val pb = ProcessBuilder(command.args)
                .directory(workspace)
                .redirectErrorStream(true)
            serveEnv.forEach { (key, value) ->
                pb.environment()[key] = value
            }

            val proc = pb.start()
            process = proc
            processGroupId = command.pidFile?.let { readPidFile(it) }
            runningPort = port
            runningWorkspace = workspace.absolutePath
            readOutput(proc)
            waitUntilHealthy(proc, port, serverPassword)
        }
    }

    suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        stopBlocking()
    }

    fun stopNow() {
        stopBlocking()
    }

    private fun stopBlocking() {
        val proc = process
        val groupId = processGroupId
        process = null
        processGroupId = null
        runningPort = null
        runningWorkspace = null
        killProcessGroup(groupId, OsConstants.SIGTERM)
        proc?.destroy()
        if (proc != null && !proc.waitFor(3, TimeUnit.SECONDS)) {
            killProcessGroup(groupId, OsConstants.SIGKILL)
            proc.destroyForcibly()
        }
        killStaleRuntimeProcesses(OsConstants.SIGKILL)
    }

    private fun killProcessGroup(groupId: Int?, signal: Int) {
        if (groupId == null) return
        runCatching { Os.kill(-groupId, signal) }
    }

    private fun killStaleRuntimeProcesses(signal: Int) {
        val selfPid = android.os.Process.myPid()
        File("/proc").listFiles().orEmpty().forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            if (pid == selfPid) return@forEach
            val cmdline = runCatching {
                File(entry, "cmdline").readText().replace('\u0000', ' ')
            }.getOrNull() ?: return@forEach
            if (cmdline.contains("libopencode_runtime.so") && cmdline.contains("serve")) {
                runCatching { Os.kill(pid, signal) }
            }
        }
    }

    private fun readPidFile(pidFile: File): Int? {
        repeat(10) {
            val pid = runCatching { pidFile.readText().trim().toIntOrNull() }.getOrNull()
            if (pid != null) return pid
            Thread.sleep(20)
        }
        return null
    }

    private fun logDebugWarning(message: String) {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) Log.w("OpenCodeRuntime", message)
    }

    private fun findExecutable(): File? =
        listOf(
            File(context.applicationInfo.nativeLibraryDir, "libopencode_runtime.so"),
            File(context.applicationInfo.nativeLibraryDir, "libopencode.so"),
        ).firstOrNull { it.exists() && it.canExecute() }

    private fun workspaceDir(name: String): File {
        val safeName = name.trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-', '.', '_')
            .ifBlank { "default" }
            .take(64)
        return File(File(context.filesDir, "workspaces"), safeName)
    }

    private fun waitUntilHealthy(proc: Process, port: Int, serverPassword: String) {
        repeat(30) {
            if (!proc.isAlive) throw IllegalStateException("OpenCode runtime exited during startup")
            Thread.sleep(500)
            if (isHealthy(port, serverPassword)) return
        }
        throw IllegalStateException("OpenCode runtime did not become healthy")
    }

    private fun isHealthy(port: Int, serverPassword: String): Boolean =
        runCatching {
            val url = URL("http://127.0.0.1:$port/global/health")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            basicAuthHeader(serverPassword)?.let { conn.setRequestProperty("Authorization", it) }
            conn.connectTimeout = 1000
            conn.readTimeout = 1000
            val code = conn.responseCode
            conn.disconnect()
            code == 200
        }.getOrDefault(false)

    private fun basicAuthHeader(serverPassword: String): String? {
        if (serverPassword.isBlank()) return null
        val cred = Base64.getEncoder().encodeToString("opencode:$serverPassword".toByteArray())
        return "Basic $cred"
    }

    private fun runVersionPreflight(executable: File, workspace: File, env: Map<String, String>) {
        val pb = ProcessBuilder(executable.absolutePath, "--version")
            .directory(workspace)
            .redirectErrorStream(true)
        env.forEach { (key, value) -> pb.environment()[key] = value }
        val proc = pb.start()
        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    if (output.length < MAX_PREFLIGHT_OUTPUT) {
                        output.appendLine(line)
                    }
                    val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
                    if (debuggable) Log.d("OpenCodeRuntime", "version: $line")
                }
            }
        }.apply {
            name = "opencode-runtime-version"
            isDaemon = true
            start()
        }
        if (!proc.waitFor(8, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw IllegalStateException("OpenCode runtime --version timed out")
        }
        reader.join(1000)
        if (proc.exitValue() != 0) {
            throw IllegalStateException(
                "OpenCode runtime --version failed (${describeExit(proc.exitValue())}): ${output.toString().trim()}",
            )
        }
    }

    private fun describeExit(exitCode: Int): String =
        when {
            exitCode == 159 -> "SIGSYS/159"
            exitCode == 139 -> "SIGSEGV/139"
            exitCode == 134 -> "SIGABRT/134"
            exitCode >= 128 -> "signal ${exitCode - 128}/$exitCode"
            else -> "exit $exitCode"
        }

    private fun readOutput(proc: Process) {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        Thread {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    if (debuggable) Log.d("OpenCodeRuntime", line)
                }
            }
        }.apply {
            name = "opencode-runtime-output"
            isDaemon = true
            start()
        }
    }

    private companion object {
        const val MAX_PREFLIGHT_OUTPUT = 4096
    }
}

internal object RuntimeServeCommandBuilder {
    fun build(
        executable: File,
        port: Int,
        cacheDir: File,
        shell: File = File(SH_PATH),
        setsid: File = File(SETSID_PATH),
        onSetsidUnavailable: () -> Unit = {},
    ): ServeCommand {
        val command = listOf(
            executable.absolutePath,
            "serve",
            "--hostname",
            "127.0.0.1",
            "--port",
            port.toString(),
        )
        if (!shell.canExecute() || !setsid.canExecute()) {
            onSetsidUnavailable()
            return ServeCommand(command, pidFile = null)
        }

        val pidFile = File(cacheDir, "opencode-runtime.pid").apply {
            parentFile?.mkdirs()
            delete()
        }
        val script = "echo $$ > ${shellQuote(pidFile.absolutePath)}; exec ${shellQuote(setsid.absolutePath)} \"\$@\""
        return ServeCommand(
            args = listOf(shell.absolutePath, "-c", script, "opencode-runtime") + command,
            pidFile = pidFile,
        )
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private const val SETSID_PATH = "/system/bin/setsid"
    private const val SH_PATH = "/system/bin/sh"

    internal data class ServeCommand(
        val args: List<String>,
        val pidFile: File?,
    )
}
