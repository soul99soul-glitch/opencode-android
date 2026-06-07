package com.opencode.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeReleaseVerifierTest {
    private val valid = RuntimeReleaseManifest(
        runtimeVersion = RuntimeContract.EXPECTED_RUNTIME_VERSION,
        opencodeVersion = "1.14.20",
        abi = "arm64-v8a",
        minAppVersion = 1,
        maxAppVersion = 9,
        apkUrl = "https://github.com/soul99soul-glitch/opencode-android/releases/download/runtime/opencode-runtime.apk",
        apkSha256 = "a".repeat(64),
        signingCertSha256 = "b".repeat(64),
    )

    @Test
    fun acceptsPinnedHttpsArm64Manifest() {
        assertNull(
            RuntimeReleaseVerifier.validate(
                manifest = valid,
                appVersionCode = 1,
                supportedAbis = listOf("arm64-v8a"),
            ),
        )
    }

    @Test
    fun rejectsCleartextRuntimeUrl() {
        val error = RuntimeReleaseVerifier.validate(
            manifest = valid.copy(apkUrl = "http://example.com/runtime.apk"),
            appVersionCode = 1,
            supportedAbis = listOf("arm64-v8a"),
        )

        assertEquals("Runtime APK URL must use HTTPS", error)
    }

    @Test
    fun rejectsUnsupportedAbi() {
        val error = RuntimeReleaseVerifier.validate(
            manifest = valid.copy(abi = "x86_64"),
            appVersionCode = 1,
            supportedAbis = listOf("arm64-v8a"),
        )

        assertEquals("Runtime ABI is not supported", error)
    }

    @Test
    fun rejectsBadDigestShape() {
        val error = RuntimeReleaseVerifier.validate(
            manifest = valid.copy(apkSha256 = "not-a-sha"),
            appVersionCode = 1,
            supportedAbis = listOf("arm64-v8a"),
        )

        assertEquals("Runtime APK checksum is invalid", error)
    }
}
