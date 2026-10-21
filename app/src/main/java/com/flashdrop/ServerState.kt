package com.flashdrop

import java.net.Inet4Address
import java.net.NetworkInterface

object ServerState {
    @Volatile var server: FlashServer? = null
    @Volatile var urls: List<String> = emptyList()
    const val PORT = 8765

    fun refreshUrls(token: String) {
        val ips = mutableListOf<String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && a.isSiteLocalAddress) ips.add(a.hostAddress ?: continue)
                }
            }
        } catch (_: Exception) {
        }
        urls = ips.map { "http://$it:$PORT/?t=$token" }
    }
}
