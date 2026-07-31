package com.lightningstudio.watchrss.phone.connection.ble

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal class LocalMdnsResponder(
    context: Context,
    private val report: (String) -> Unit
) : AutoCloseable {
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private val running = AtomicBoolean(false)
    private var socket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start(address: Inet4Address) {
        if (!running.compareAndSet(false, true)) return
        multicastLock = wifiManager.createMulticastLock("watchrss-mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
        val group = InetAddress.getByName(MDNS_GROUP)
        val network = NetworkInterface.getByName("wlan0")
        val mdnsSocket = MulticastSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(MDNS_PORT))
            if (network != null) joinGroup(InetSocketAddress(group, MDNS_PORT), network)
            else @Suppress("DEPRECATION") joinGroup(group)
        }
        socket = mdnsSocket
        thread(name = "watchrss-mdns", isDaemon = true) {
            val buffer = ByteArray(1500)
            while (running.get()) {
                val packet = java.net.DatagramPacket(buffer, buffer.size)
                if (runCatching { mdnsSocket.receive(packet) }.isFailure) break
                if (!containsDnsName(packet.data, packet.length, HOST_LABELS)) continue
                report("mDNS 查询命中：$HOSTNAME")
                val response = aRecordResponse(address.address)
                val reply = java.net.DatagramPacket(
                    response,
                    response.size,
                    group,
                    MDNS_PORT
                )
                runCatching { mdnsSocket.send(reply) }
            }
        }
    }

    override fun close() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    companion object {
        const val HOSTNAME = "watchrss-phone.local"
        private const val MDNS_GROUP = "224.0.0.251"
        private const val MDNS_PORT = 5353
        private val HOST_LABELS = byteArrayOf(
            14, 'w'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(),
            'c'.code.toByte(), 'h'.code.toByte(), 'r'.code.toByte(),
            's'.code.toByte(), 's'.code.toByte(), '-'.code.toByte(),
            'p'.code.toByte(), 'h'.code.toByte(), 'o'.code.toByte(),
            'n'.code.toByte(), 'e'.code.toByte(),
            5, 'l'.code.toByte(), 'o'.code.toByte(), 'c'.code.toByte(),
            'a'.code.toByte(), 'l'.code.toByte(), 0
        )

        private fun containsDnsName(data: ByteArray, length: Int, name: ByteArray): Boolean {
            if (length < name.size) return false
            for (offset in 0..length - name.size) {
                var equal = true
                for (index in name.indices) {
                    val actual = data[offset + index]
                    val expected = name[index]
                    if (actual == expected) continue
                    if (actual.toInt() in 65..90 && actual.toInt() + 32 == expected.toInt()) continue
                    equal = false
                    break
                }
                if (equal) return true
            }
            return false
        }

        private fun aRecordResponse(ipv4: ByteArray): ByteArray = buildList<Byte> {
            addAll(byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 0, 0, 1, 0, 0, 0, 0).toList())
            addAll(HOST_LABELS.toList())
            addAll(byteArrayOf(0, 1, 0x80.toByte(), 1, 0, 0, 0, 120, 0, 4).toList())
            addAll(ipv4.toList())
        }.toByteArray()
    }
}
