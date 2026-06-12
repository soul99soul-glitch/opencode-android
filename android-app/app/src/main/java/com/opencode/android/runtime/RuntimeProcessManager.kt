package com.opencode.android.runtime

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.FileObserver
import android.util.Log
import com.opencode.android.workspace.ResolvedWorkspace
import com.opencode.android.workspace.SafBridgeSync
import com.opencode.android.workspace.WorkspaceResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import android.system.Os
import android.system.OsConstants

class RuntimeProcessManager(private val context: Context) {
    private var process: Process? = null
    private var processGroupId: Int? = null
    private var runningPort: Int? = null
    private var runningWorkspace: String? = null
    private var activeTreeUri: Uri? = null
    private var activeBridgeDir: File? = null
    private var bridgeObserver: FileObserver? = null

    suspend fun start(
        port: Int,
        workspaceName: String,
        workspaceTreeUri: String = "",
        providerConfig: RuntimeProviderConfig,
        providerApiKey: String,
        serverPassword: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // Ensure DNS config exists before any serve (including an already-healthy one we
            // adopt) is used. /global/health is a local endpoint that returns 200 even when DNS
            // is broken, so this must run unconditionally, not only on the cold-start path.
            writeResolvConf()
            evictForeignRuntimeOnPort(port)
            val executable = findExecutable()
                ?: throw IllegalStateException("OpenCode runtime binary not found in nativeLibraryDir")
            val resolved = WorkspaceResolver.resolve(context, workspaceName, workspaceTreeUri)
            val workspace = resolved.runtimeDir
            if (resolved.usesSafBridge) {
                SafBridgeSync.syncDown(context, resolved.treeUri!!, workspace)
            }
            if (RuntimeSupport.needsInstall(context)) {
                stopBlocking()
            }
            RuntimeSupport.ensureInstalled(context).getOrThrow()
            if (process?.isAlive == true && runningPort == port && runningWorkspace == workspace.absolutePath) {
                if (tryAdoptHealthyRuntime(port, serverPassword, workspace, resolved)) {
                    return@runCatching
                }
                stopBlocking()
            }
            // ACTION_START must be idempotent across service recreation. The service can be
            // killed while the native runtime keeps serving; in that case `process` is null, but a
            // healthy authenticated localhost server is the instance we want to keep.
            if (tryAdoptHealthyRuntime(port, serverPassword, workspace, resolved)) {
                return@runCatching
            }
            evictMismatchedRuntimeOnPort(port, serverPassword, workspace.absolutePath)
            evictForeignRuntimeOnPort(port)
            if (process?.isAlive == true) {
                stopBlocking()
            }
            killStaleRuntimeProcesses(OsConstants.SIGKILL)

            RuntimeConfigWriter.write(context.filesDir, providerConfig).getOrThrow()
            val nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir)
            val versionEnv = RuntimeLaunchEnv.build(
                filesDir = context.filesDir,
                cacheDir = context.cacheDir,
                nativeLibraryDir = nativeLibraryDir,
                config = providerConfig,
                providerApiKey = "",
                workspaceDir = workspace,
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
                workspaceDir = workspace,
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
            RuntimeWorkspaceState.write(context, workspace.absolutePath)
            bindSafBridgeObserver(resolved)
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

    fun stopBestEffortNow() {
        flushSafBridgeBestEffortNow()
        clearSafBridgeObserver()
        stopProcessBlocking()
    }

    fun flushSafBridgeNow() {
        flushSafBridgeSyncBlocking()
    }

    fun flushSafBridgeBestEffortNow() {
        runBlockingStepWithTimeout("SAF workspace sync-up", 5_000) {
            flushSafBridgeSyncBlocking()
        }
    }

    private fun stopBlocking() {
        flushSafBridgeSyncBlocking()
        clearSafBridgeObserver()
        stopProcessBlocking()
    }

    private fun stopProcessBlocking() {
        val proc = process
        val groupId = processGroupId
        process = null
        processGroupId = null
        runningPort = null
        runningWorkspace = null
        RuntimeWorkspaceState.clear(context)
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

    // The bundled Termux glibc reads its resolver config from a path baked into libc.so.6,
    // which has been patched to this app's files dir. Android has no /etc/resolv.conf and does
    // not expose net.dns* props, so without this file glibc falls back to 127.0.0.1:53 and every
    // outbound DNS lookup is refused. Provide reliable public resolvers here.
    private fun writeResolvConf() {
        runCatching {
            val servers = RuntimeDnsResolver.resolve(context)
            File(context.filesDir, "resolv.conf").writeText(RuntimeDnsResolver.formatResolvConf(servers))
        }
    }

    private fun isRuntimeOwnedByUs(port: Int): Boolean {
        if (process?.isAlive == true) return true
        val pid = RuntimePortGuard.findListeningPid(port) ?: return false
        val uid = RuntimePortGuard.processUid(pid) ?: return false
        return uid == android.os.Process.myUid()
    }

    private fun evictForeignRuntimeOnPort(port: Int) {
        val pid = RuntimePortGuard.findListeningPid(port) ?: return
        val uid = RuntimePortGuard.processUid(pid) ?: return
        if (uid != android.os.Process.myUid()) {
            RuntimePortGuard.killProcess(pid, OsConstants.SIGKILL)
        }
    }

    private fun findExecutable(): File? =
        listOf(
            File(context.applicationInfo.nativeLibraryDir, "libopencode_runtime.so"),
            File(context.applicationInfo.nativeLibraryDir, "libopencode.so"),
        ).firstOrNull { it.exists() && it.canExecute() }

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

    private fun tryAdoptHealthyRuntime(
        port: Int,
        serverPassword: String,
        workspace: File,
        resolved: ResolvedWorkspace,
    ): Boolean {
        if (!isHealthy(port, serverPassword) || !isRuntimeOwnedByUs(port)) return false
        if (RuntimeWorkspaceState.read(context) != workspace.absolutePath) return false
        runningPort = port
        runningWorkspace = workspace.absolutePath
        bindSafBridgeObserver(resolved)
        return true
    }

    private fun evictMismatchedRuntimeOnPort(port: Int, serverPassword: String, workspacePath: String) {
        if (!isHealthy(port, serverPassword) || !isRuntimeOwnedByUs(port)) return
        if (RuntimeWorkspaceState.read(context) == workspacePath) return
        stopBlocking()
        RuntimePortGuard.findListeningPid(port)?.let { pid ->
            RuntimePortGuard.killProcess(pid, OsConstants.SIGKILL)
        }
    }

    private fun bindSafBridgeObserver(resolved: ResolvedWorkspace) {
        clearSafBridgeObserver()
        val treeUri = resolved.treeUri ?: return
        activeTreeUri = treeUri
        activeBridgeDir = resolved.runtimeDir
        val events = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MODIFY or
            FileObserver.MOVED_FROM or FileObserver.MOVED_TO
        bridgeObserver = object : FileObserver(resolved.runtimeDir.absolutePath, events) {
            override fun onEvent(event: Int, path: String?) {
                val dir = activeBridgeDir ?: return
                val uri = activeTreeUri ?: return
                SafBridgeSync.scheduleSyncUp(context, dir, uri)
            }
        }.also { it.startWatching() }
    }

    private fun flushSafBridgeSyncBlocking() {
        SafBridgeSync.cancelScheduledSync()
        val treeUri = activeTreeUri ?: return
        val bridgeDir = activeBridgeDir ?: return
        runCatching { SafBridgeSync.syncUp(context, bridgeDir, treeUri) }
            .onFailure { Log.w("OpenCodeRuntime", "SAF workspace sync-up failed", it) }
    }

    private fun runBlockingStepWithTimeout(label: String, timeoutMillis: Long, block: () -> Unit) {
        val worker = thread(start = true, isDaemon = true, name = "opencode-runtime-${label.replace(' ', '-')}") {
            block()
        }
        worker.join(timeoutMillis)
        if (worker.isAlive) {
            Log.w("OpenCodeRuntime", "$label did not finish within ${timeoutMillis}ms")
            worker.interrupt()
        }
    }

    private fun clearSafBridgeObserver() {
        SafBridgeSync.cancelScheduledSync()
        bridgeObserver?.stopWatching()
        bridgeObserver = null
        activeTreeUri = null
        activeBridgeDir = null
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
