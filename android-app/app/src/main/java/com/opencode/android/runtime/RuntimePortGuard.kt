package com.opencode.android.runtime

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.Locale

/** Detects which process owns a localhost TCP listen port (used to reject foreign runtime serves). */
internal object RuntimePortGuard {
    private const val LOOPBACK_HEX = "0100007F"
    private const val TCP_STATE_LISTEN = "0A"

    fun findListeningPid(port: Int, hostHex: String = LOOPBACK_HEX): Int? {
        val portHex = portToProcHex(port)
        val inode = readTcpSocketInode(hostHex, portHex) ?: return null
        return findPidForSocketInode(inode)
    }

    fun processUid(pid: Int): Int? = runCatching {
        File("/proc/$pid/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("Uid:") }
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toInt()
        }
    }.getOrNull()

    fun killProcess(pid: Int, signal: Int = OsConstants.SIGKILL) {
        runCatching { android.system.Os.kill(pid, signal) }
    }

    fun portToProcHex(port: Int): String {
        val value = port and 0xFFFF
        return String.format(Locale.US, "%04X", value)
    }

    private fun readTcpSocketInode(localHostHex: String, localPortHex: String): String? = runCatching {
        parseListeningInode(File("/proc/net/tcp").readLines().drop(1), localHostHex, localPortHex)
    }.getOrNull()

    internal fun parseListeningInode(lines: List<String>, localHostHex: String, localPortHex: String): String? {
        return lines.firstNotNullOfOrNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 10) return@firstNotNullOfOrNull null
            val local = parts[1]
            val state = parts[3]
            if (!state.equals(TCP_STATE_LISTEN, ignoreCase = true)) return@firstNotNullOfOrNull null
            val address = local.substringBefore(':')
            val port = local.substringAfter(':', "")
            if (address.equals(localHostHex, ignoreCase = true) && port.equals(localPortHex, ignoreCase = true)) {
                parts[9]
            } else {
                null
            }
        }
    }

    private fun findPidForSocketInode(inode: String): Int? {
        File("/proc").listFiles()?.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            val fdDir = File(entry, "fd")
            fdDir.listFiles()?.forEach { fd ->
                val target = runCatching { Os.readlink(fd.absolutePath) }.getOrNull() ?: return@forEach
                if (target == "socket:[$inode]") return pid
            }
        }
        return null
    }
}
