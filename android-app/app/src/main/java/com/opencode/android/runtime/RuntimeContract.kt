package com.opencode.android.runtime

object RuntimeContract {
    const val PACKAGE_NAME = "com.opencode.android.runtime"
    const val BOOTSTRAP_ACTIVITY_CLASS_NAME = "com.opencode.android.runtime.RuntimeBootstrapActivity"
    const val SERVICE_CLASS_NAME = "com.opencode.android.runtime.RuntimeService"
    const val CONTROL_PERMISSION = "com.opencode.android.runtime.permission.CONTROL"

    const val ACTION_START = "com.opencode.android.runtime.START"
    const val ACTION_STOP = "com.opencode.android.runtime.STOP"
    const val ACTION_RESTART = "com.opencode.android.runtime.RESTART"
    const val ACTION_REQUEST_BATTERY_EXEMPTION = "com.opencode.android.runtime.REQUEST_BATTERY_EXEMPTION"

    const val EXTRA_PORT = "port"
    const val EXTRA_WORKSPACE = "workspace"
    const val EXTRA_WORKSPACE_TREE_URI = "workspace_tree_uri"
    const val EXTRA_PROVIDER_ENABLED = "provider_enabled"
    const val EXTRA_PROVIDER_NAME = "provider_name"
    const val EXTRA_PROVIDER_BASE_URL = "provider_base_url"
    const val EXTRA_PROVIDER_CODING_BASE_URL = "provider_coding_base_url"
    const val EXTRA_PROVIDER_ACTIVE_BASE_URL = "provider_active_base_url"
    const val EXTRA_PROVIDER_MODELS = "provider_models"
    const val EXTRA_PROVIDER_HAS_API_KEY = "provider_has_api_key"
    const val EXTRA_PROVIDER_API_KEY = "provider_api_key"
    const val EXTRA_SERVER_PASSWORD = "server_password"

    // In-process runtime (Option C): provider/env identifiers used by the bundled serve process.
    const val PROVIDER_ID = "android-local"
    const val PROVIDER_NAME = "Android Local"
    const val PROVIDER_API_KEY_ENV = "OPENCODE_ANDROID_PROVIDER_API_KEY"
    const val SERVER_USERNAME_ENV = "OPENCODE_SERVER_USERNAME"
    const val SERVER_PASSWORD_ENV = "OPENCODE_SERVER_PASSWORD"

    const val EXPECTED_RUNTIME_VERSION = "0.1.0"
    const val MANIFEST_URL = "https://github.com/soul99soul-glitch/opencode-android/releases/latest/download/runtime-manifest.json"
}
