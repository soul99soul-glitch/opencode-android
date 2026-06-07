import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.opencode.android.runtime"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories.add(layout.buildDirectory.dir("generated/runtimeProbe/jniLibs").get().asFile.absolutePath)
            assets.directories.add(layout.buildDirectory.dir("generated/runtimeProbe/assets").get().asFile.absolutePath)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation("junit:junit:4.13.2")
}

val runtimeProbeSource = layout.projectDirectory.file("src/main/native/opencode_runtime_probe.c")
val runtimeProbeDummySource = layout.projectDirectory.file("src/main/native/opencode_probe_dummy.c")
val runtimeProbeOutput = layout.buildDirectory.dir("generated/runtimeProbe")
val realRuntimeExecutable = layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libopencode_runtime.so")
val realRuntimeSupportRoot = layout.projectDirectory.dir("src/main/assets/runtime_support")

val compileRuntimeProbe by tasks.registering {
    inputs.file(runtimeProbeSource)
    inputs.file(runtimeProbeDummySource)
    outputs.dir(runtimeProbeOutput)
    outputs.upToDateWhen { false }

    doLast {
        fun runCommand(args: List<String>) {
            val proc = ProcessBuilder(args)
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(60, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                throw org.gradle.api.GradleException("Command timed out: ${args.joinToString(" ")}")
            }
            if (proc.exitValue() != 0) {
                throw org.gradle.api.GradleException(
                    "Command failed (${proc.exitValue()}): ${args.joinToString(" ")}\n$output",
                )
            }
        }

        runtimeProbeOutput.get().asFile.deleteRecursively()

        val sdkDir = providers.environmentVariable("ANDROID_HOME").orNull
            ?: providers.environmentVariable("ANDROID_SDK_ROOT").orNull
            ?: error("ANDROID_HOME or ANDROID_SDK_ROOT must point to the Android SDK")
        val ndkRoot = file("$sdkDir/ndk")
        val ndkDir = ndkRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.maxByOrNull { it.name }
            ?: error("No Android NDK found under $ndkRoot")
        val hostTag = when {
            org.gradle.internal.os.OperatingSystem.current().isMacOsX -> "darwin-x86_64"
            org.gradle.internal.os.OperatingSystem.current().isLinux -> "linux-x86_64"
            else -> error("Unsupported host for runtime probe build")
        }
        val clang = file("${ndkDir.absolutePath}/toolchains/llvm/prebuilt/$hostTag/bin/aarch64-linux-android26-clang")
        check(clang.exists()) { "Missing NDK clang: $clang" }

        val jniOut = runtimeProbeOutput.get().dir("jniLibs/arm64-v8a").asFile
        val assetsOut = runtimeProbeOutput.get().dir("assets/runtime_support").asFile
        val supportLibOut = File(assetsOut, "lib/probe")
        jniOut.mkdirs()

        val generatedExecutable = File(jniOut, "libopencode_runtime.so")
        if (realRuntimeExecutable.asFile.exists()) {
            generatedExecutable.delete()
        } else {
            runCommand(
                listOf(
                    clang.absolutePath,
                    "-fPIE",
                    "-pie",
                    "-O2",
                    "-Wall",
                    "-Wextra",
                    "-o",
                    generatedExecutable.absolutePath,
                    runtimeProbeSource.asFile.absolutePath,
                    "-ldl",
                ),
            )
        }
        val realSupport = realRuntimeSupportRoot.asFile
        val realDummy = File(realSupport, "lib/probe/libopencode_probe_dummy.so")
        val shouldGenerateDummy = !realDummy.exists()
        if (shouldGenerateDummy) {
            supportLibOut.mkdirs()
            runCommand(
                listOf(
                    clang.absolutePath,
                    "-shared",
                    "-fPIC",
                    "-O2",
                    "-Wall",
                    "-Wextra",
                    "-Wl,-soname,libopencode_probe_dummy.so",
                    "-o",
                    File(supportLibOut, "libopencode_probe_dummy.so").absolutePath,
                    runtimeProbeDummySource.asFile.absolutePath,
                ),
            )
        }

        if (!realSupport.exists()) {
            val opensslOut = File(assetsOut, "lib/openssl")
            val glibcOut = File(assetsOut, "lib/glibc")
            val certOut = File(assetsOut, "share/certs")
            val providerCacheOut = File(assetsOut, "cache/providers/@ai-sdk/openai-compatible")
            listOf(opensslOut, glibcOut, certOut, providerCacheOut).forEach { it.mkdirs() }
            File(glibcOut, "ld-linux-aarch64.so.1").writeText("phase0 probe placeholder\n")
            File(opensslOut, "placeholder.txt").writeText("phase0 probe placeholder\n")
            File(certOut, "ca-bundle.crt").writeText("phase0 probe placeholder\n")
            File(providerCacheOut, "ready.marker").writeText("phase0 probe placeholder\n")
        }
    }
}

tasks.configureEach {
    if (name.matches(Regex("merge.*(Assets|JniLibFolders)")) ||
        name.contains("lint", ignoreCase = true)
    ) {
        dependsOn(compileRuntimeProbe)
    }
}
