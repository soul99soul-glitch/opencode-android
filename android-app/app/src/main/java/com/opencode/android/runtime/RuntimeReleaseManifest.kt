package com.opencode.android.runtime

import kotlinx.serialization.Serializable

@Serializable
data class RuntimeReleaseManifest(
    val runtimeVersion: String,
    val opencodeVersion: String,
    val abi: String,
    val minAppVersion: Int,
    val maxAppVersion: Int? = null,
    val apkUrl: String,
    val apkSha256: String,
    val signingCertSha256: String,
)

object RuntimeReleaseVerifier {
    fun validate(
        manifest: RuntimeReleaseManifest,
        appVersionCode: Int,
        supportedAbis: List<String>,
        expectedRuntimeVersion: String = RuntimeContract.EXPECTED_RUNTIME_VERSION,
    ): String? {
        if (manifest.runtimeVersion != expectedRuntimeVersion) {
            return "Runtime version mismatch"
        }
        if (manifest.abi !in supportedAbis) {
            return "Runtime ABI is not supported"
        }
        if (!manifest.apkUrl.startsWith("https://")) {
            return "Runtime APK URL must use HTTPS"
        }
        if (appVersionCode < manifest.minAppVersion) {
            return "App is too old for this runtime"
        }
        if (manifest.maxAppVersion != null && appVersionCode > manifest.maxAppVersion) {
            return "Runtime is too old for this app"
        }
        if (!SHA256_PATTERN.matches(manifest.apkSha256)) {
            return "Runtime APK checksum is invalid"
        }
        if (!SHA256_PATTERN.matches(manifest.signingCertSha256)) {
            return "Runtime signing digest is invalid"
        }
        return null
    }

    private val SHA256_PATTERN = Regex("^[A-Fa-f0-9]{64}$")
}
