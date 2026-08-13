package com.lightningstudio.watchrss.phone.connection.ip

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

internal class IpSyncNsdAdvertiser(
    context: Context,
    private val descriptorProvider: IpEndpointProvider
) : AutoCloseable {
    private val nsdManager = context.applicationContext.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    @Synchronized
    fun start(port: Int) {
        if (listener != null || port <= 0) return
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "mDNS registered name=${serviceInfo.serviceName} port=${serviceInfo.port}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed code=$errorCode")
                synchronized(this@IpSyncNsdAdvertiser) { listener = null }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS unregistration failed code=$errorCode")
            }
        }
        val descriptor = descriptorProvider.issueDescriptor()
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "WatchRSS-${descriptorProvider.deviceIdHash()}"
            serviceType = IpSyncProtocol.SERVICE_TYPE
            setPort(port)
            setAttribute("v", IpSyncProtocol.VERSION.toString())
            setAttribute("id", descriptorProvider.deviceIdHash())
            setAttribute("epoch", descriptor.epoch.toString())
        }
        listener = registration
        runCatching {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registration)
        }.onFailure { error ->
            listener = null
            Log.w(TAG, "mDNS registration unavailable; BLE discovery remains active", error)
        }
    }

    @Synchronized
    override fun close() {
        val active = listener ?: return
        listener = null
        runCatching { nsdManager.unregisterService(active) }
            .onFailure { Log.w(TAG, "mDNS unregister failed", it) }
    }

    companion object {
        private const val TAG = "WatchRSS_IpNsd"
    }
}
