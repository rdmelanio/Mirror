package com.mirror.app.core

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import java.net.NetworkInterface

object LocalNetwork {
    fun address(context: Context): String {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val active = manager.getLinkProperties(manager.activeNetwork)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }?.address?.hostAddress
        if (active != null) return active
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }?.hostAddress
        }.getOrNull() ?: "<phone-ip>"
    }
}
