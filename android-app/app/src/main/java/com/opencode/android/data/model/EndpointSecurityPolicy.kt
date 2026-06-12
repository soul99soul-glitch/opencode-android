package com.opencode.android.data.model

import java.net.URL

object EndpointSecurityPolicy {
    fun publicCleartextBlockMessage(baseUrl: String): String? {
        if (!baseUrl.trim().startsWith("http://", ignoreCase = true)) return null
        val host = runCatching { URL(baseUrl).host }.getOrDefault("")
        return if (isLocalOrPrivateHost(host)) {
            null
        } else {
            "Refusing public HTTP endpoint; use HTTPS or a local/private address"
        }
    }

    fun isLocalOrPrivateHost(host: String): Boolean {
        val h = host.trim('[', ']').lowercase()
        if (h == "localhost" || h == "::1" || h.endsWith(".local") || h.endsWith(".lan")) return true
        if (h.startsWith("127.")) return true
        if (h.startsWith("10.")) return true
        if (h.startsWith("192.168.")) return true
        val parts = h.split('.').mapNotNull { it.toIntOrNull() }
        return parts.size == 4 && parts[0] == 172 && parts[1] in 16..31
    }

    fun isLoopbackUrl(baseUrl: String): Boolean {
        val host = runCatching { URL(baseUrl).host }.getOrDefault("")
        val h = host.trim('[', ']').lowercase()
        return h == "localhost" || h == "::1" || h.startsWith("127.")
    }
}
