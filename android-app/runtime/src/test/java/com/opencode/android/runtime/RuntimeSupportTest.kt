package com.opencode.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuntimeSupportTest {
    @Test
    fun preflightFailsFastWhenRequiredSupportIsMissing() {
        val root = tempDir("support-missing")

        val result = RuntimeSupport.preflight(root)

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message.orEmpty()
        assertTrue(message.contains("lib/glibc/ld-linux-aarch64.so.1"))
        assertTrue(message.contains("share/certs/ca-bundle.crt"))
        assertTrue(message.contains("cache/providers/@ai-sdk/openai-compatible/ready.marker"))
    }

    @Test
    fun preflightPassesWhenSupportContractIsPresent() {
        val root = tempDir("support-present")
        RuntimeSupport.requiredPaths(root).forEach { path ->
            if (path.extension == "so" || path.name.contains(".")) {
                path.parentFile?.mkdirs()
                path.writeText("placeholder")
            } else {
                path.mkdirs()
            }
        }

        assertTrue(RuntimeSupport.preflight(root).isSuccess)
    }

    @Test
    fun createsSonameLinksForVersionedGlibcLibraries() {
        val root = tempDir("support-soname")
        val glibc = File(root, "lib/glibc").apply { mkdirs() }
        File(glibc, "libstdc++.so.6.0.34").writeText("stdlib")

        RuntimeSupport.ensureLibrarySonameLinks(root)

        assertTrue(File(glibc, "libstdc++.so.6").exists())
    }

    @Test
    fun currentRootUsesRuntimeCurrentUnderFilesDir() {
        val filesDir = tempDir("files")

        assertEquals(
            File(File(filesDir, "runtime"), "current").absolutePath,
            RuntimeSupport.currentRoot(filesDir).absolutePath,
        )
    }

    private fun tempDir(prefix: String): File =
        File(System.getProperty("java.io.tmpdir"), "opencode-runtime-$prefix-${System.nanoTime()}").apply { mkdirs() }
}
