package com.mirror.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

object LocalNetwork {
    fun address(context: Context): String? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        // A VPN or mobile-data default network must never become the advertised camera address.
        @Suppress("DEPRECATION")
        return manager.allNetworks.firstNotNullOfOrNull { network ->
            if (manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) null
            else manager.getLinkProperties(network)?.linkAddresses
                ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }?.address?.hostAddress
        }
    }
}
