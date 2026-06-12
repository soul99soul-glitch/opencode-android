import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val releaseStoreFile = providers.gradleProperty("OPENCODE_RELEASE_STORE_FILE")
    .orElse(providers.environmentVariable("OPENCODE_RELEASE_STORE_FILE"))
    .orNull
val releaseStorePassword = providers.gradleProperty("OPENCODE_RELEASE_STORE_PASSWORD")
    .orElse(providers.environmentVariable("OPENCODE_RELEASE_STORE_PASSWORD"))
    .orNull
val releaseKeyAlias = providers.gradleProperty("OPENCODE_RELEASE_KEY_ALIAS")
    .orElse(providers.environmentVariable("OPENCODE_RELEASE_KEY_ALIAS"))
    .orNull
val releaseKeyPassword = providers.gradleProperty("OPENCODE_RELEASE_KEY_PASSWORD")
    .orElse(providers.environmentVariable("OPENCODE_RELEASE_KEY_PASSWORD"))
    .orNull
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.opencode.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.opencode.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
    }

    // Local runtime (Option C): the native opencode binary + glibc support run inside the
    // main app process so that outbound LLM traffic uses the foreground app's UID, which has
    // network access. useLegacyPackaging extracts the binary to nativeLibraryDir so it can be
    // exec'd. The payload is referenced from the :runtime module's source tree to avoid
    // duplicating ~246MB on disk.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(rootProject.file("runtime/src/main/jniLibs"))
            assets.srcDir(rootProject.file("runtime/src/main/assets"))
        }
    }
}

val verifyRuntimePayload by tasks.registering {
    val runtimeDir = rootProject.file("runtime/src/main")
    val required = listOf(
        runtimeDir.resolve("jniLibs/arm64-v8a/libopencode_runtime.so"),
        runtimeDir.resolve("assets/runtime_support/lib/glibc/ld-linux-aarch64.so.1"),
        runtimeDir.resolve("assets/runtime_support/lib/openssl/libcrypto.so.3"),
        runtimeDir.resolve("assets/runtime_support/lib/openssl/libssl.so.3"),
        runtimeDir.resolve("assets/runtime_support/share/certs/ca-bundle.crt"),
        runtimeDir.resolve("assets/runtime_support/cache/providers/@ai-sdk/openai-compatible/ready.marker"),
        runtimeDir.resolve("assets/runtime_support/share/opencode-runtime/git-tools.tsv"),
        runtimeDir.resolve("assets/runtime_support/share/git-core/templates"),
        runtimeDir.resolve("assets/runtime_support/tool_payload/bin/git"),
        runtimeDir.resolve("assets/runtime_support/tool_payload/libexec/git-core/git-remote-http"),
        runtimeDir.resolve("assets/runtime_support/tool_payload/libexec/git-core/git-remote-https"),
        runtimeDir.resolve("jniLibs/arm64-v8a/libglibc_loader.so"),
        runtimeDir.resolve("jniLibs/arm64-v8a/libgit.so"),
    )
    inputs.files(required)
    doLast {
        val missing = required.filterNot { it.exists() }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "OpenCode runtime payload is missing:\n" +
                    missing.joinToString("\n") { " - ${it.relativeTo(rootProject.projectDir)}" } +
                    "\nRun android-app/runtime/tools/import-opencode-runtime.sh before building the app.",
            )
        }
    }
}

val verifyDebugApkRuntimePayload by tasks.registering {
    dependsOn("assembleDebug")
    val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
    inputs.file(apk)
    doLast {
        val apkFile = apk.get().asFile
        if (!apkFile.isFile) {
            throw GradleException("Debug APK is missing: ${apkFile.relativeTo(projectDir)}")
        }
        val requiredEntries = listOf(
            "lib/arm64-v8a/libopencode_runtime.so",
            "assets/runtime_support/lib/glibc/ld-linux-aarch64.so.1",
            "assets/runtime_support/lib/openssl/libcrypto.so.3",
            "assets/runtime_support/lib/openssl/libssl.so.3",
            "assets/runtime_support/share/certs/ca-bundle.crt",
            "assets/runtime_support/cache/providers/@ai-sdk/openai-compatible/ready.marker",
            "assets/runtime_support/share/opencode-runtime/git-tools.tsv",
            "assets/runtime_support/share/git-core/templates/description",
            "assets/runtime_support/tool_payload/bin/git",
            "assets/runtime_support/tool_payload/libexec/git-core/git-remote-http",
            "assets/runtime_support/tool_payload/libexec/git-core/git-remote-https",
            "lib/arm64-v8a/libglibc_loader.so",
            "lib/arm64-v8a/libgit.so",
        )
        ZipFile(apkFile).use { zip ->
            val missing = requiredEntries.filter { zip.getEntry(it) == null }
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "Debug APK is missing runtime payload entries:\n" +
                        missing.joinToString("\n") { " - $it" },
                )
            }
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyRuntimePayload)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Markdown rendering (pure Compose)
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.41.0")

    testImplementation("junit:junit:4.13.2")
}
