package com.opencode.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuntimeServeCommandBuilderTest {
    @Test
    fun buildUsesSetsidWrapperWhenAvailable() {
        val dir = tempDir("setsid")
        val executable = executableFile(dir, "libopencode_runtime.so")
        val shell = executableFile(dir, "sh")
        val setsid = executableFile(dir, "setsid")

        val command = RuntimeServeCommandBuilder.build(
            executable = executable,
            port = 4097,
            cacheDir = dir,
            shell = shell,
            setsid = setsid,
        )

        assertEquals(shell.absolutePath, command.args[0])
        assertEquals("-c", command.args[1])
        assertTrue(command.args[2].contains("exec '${setsid.absolutePath}' \"\$@\""))
        assertEquals("opencode-runtime", command.args[3])
        assertEquals(executable.absolutePath, command.args[4])
        assertEquals(listOf("serve", "--hostname", "127.0.0.1", "--port", "4097"), command.args.drop(5))
        assertEquals(File(dir, "opencode-runtime.pid").absolutePath, command.pidFile?.absolutePath)
    }

    @Test
    fun buildFallsBackToDirectCommandWhenSetsidUnavailable() {
        val dir = tempDir("no-setsid")
        val executable = executableFile(dir, "libopencode_runtime.so")
        val shell = executableFile(dir, "sh")
        val setsid = File(dir, "setsid")
        var warned = false

        val command = RuntimeServeCommandBuilder.build(
            executable = executable,
            port = 4098,
            cacheDir = dir,
            shell = shell,
            setsid = setsid,
            onSetsidUnavailable = { warned = true },
        )

        assertTrue(warned)
        assertEquals(executable.absolutePath, command.args[0])
        assertEquals(listOf("serve", "--hostname", "127.0.0.1", "--port", "4098"), command.args.drop(1))
        assertNull(command.pidFile)
    }

    private fun executableFile(dir: File, name: String): File =
        File(dir, name).apply {
            writeText("#!/bin/sh\n")
            assertTrue(setExecutable(true, true))
            assertTrue(canExecute())
        }

    private fun tempDir(prefix: String): File =
        File(System.getProperty("java.io.tmpdir"), "opencode-runtime-command-$prefix-${System.nanoTime()}").apply {
            assertFalse(exists())
            mkdirs()
        }
}
