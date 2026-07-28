package com.lightningstudio.watchrss.phone.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager

class CloudNetworkGate(context: Context) {
    private val appContext = context.applicationContext

    fun isConnected(): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    fun allowsPrivateBodies(policy: CloudNetworkPolicy, manual: Boolean): Boolean {
        if (manual || policy == CloudNetworkPolicy.ANY_NETWORK) return isConnected()
        val manager = appContext.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        val unmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        if (!unmetered) return false
        return policy != CloudNetworkPolicy.WIFI_AND_CHARGING || isCharging()
    }

    private fun isCharging(): Boolean {
        val battery = appContext.getSystemService(BatteryManager::class.java)
        return battery.isCharging
    }
}
