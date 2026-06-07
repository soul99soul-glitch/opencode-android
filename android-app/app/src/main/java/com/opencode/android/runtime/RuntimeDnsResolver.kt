package com.opencode.android.runtime

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address

/** Builds glibc resolv.conf content using system DNS when available. */
internal object RuntimeDnsResolver {
    val FALLBACK_SERVERS = listOf("8.8.8.8", "1.1.1.1", "223.5.5.5", "119.29.29.29")

    fun resolve(context: Context): List<String> {
        val system = readSystemDnsServers(context)
        return (system + FALLBACK_SERVERS).distinct().filter { it.isNotBlank() }.take(4)
    }

    fun formatResolvConf(servers: List<String>): String {
        val nameservers = servers.map { "nameserver $it" }
        return (nameservers + "options timeout:2 attempts:2").joinToString("\n") + "\n"
    }

    private fun readSystemDnsServers(context: Context): List<String> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return emptyList()
        val network = cm.activeNetwork ?: return emptyList()
        val link = cm.getLinkProperties(network) ?: return emptyList()
        return link.dnsServers.mapNotNull { address ->
            when (address) {
                is Inet4Address -> address.hostAddress
                else -> address.hostAddress?.takeIf { it.contains(':') }
            }?.takeIf { it.isNotBlank() }
        }.distinct()
    }
}
