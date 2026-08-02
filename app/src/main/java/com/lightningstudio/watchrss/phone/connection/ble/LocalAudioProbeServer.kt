package com.lightningstudio.watchrss.phone.connection.ble

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal class LocalAudioProbeServer(
    context: Context,
    private val report: (String) -> Unit
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    @Volatile private var audio = ByteArray(0)

    fun start(
        videoId: String = "bad-apple",
        profile: BleVideoProfile = BleVideoProfile.SMOOTH
    ): String {
        loadAudio(videoId)
        if (running.compareAndSet(false, true)) {
            val socket = ServerSocket(PORT).apply { reuseAddress = true }
            serverSocket = socket
            thread(name = "watchrss-http-audio", isDaemon = true) {
                while (running.get()) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    thread(name = "watchrss-http-client", isDaemon = true) {
                        try {
                            client.use(::serve)
                        } catch (error: SocketException) {
                            report("客户端已断开：${error.message ?: "socket closed"}")
                        } catch (error: Exception) {
                            report("本地串流连接失败：${error.message ?: error.javaClass.simpleName}")
                        }
                    }
                }
            }
        }
        val base = "http://${phoneIpv4Address()}:$PORT"
        return "$base/audio.mp3|||${profile.targetFps}|ble|${profile.wireName}"
    }

    private fun loadAudio(videoId: String) {
        val baseId = videoId.removeSuffix("-blur")
        val audioName = if (baseId == "bad-apple") "bad-apple-full" else baseId
        audio = appContext.assets.open("bluetooth-video/$audioName.mp3").use { it.readBytes() }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 10_000
        val request = readHeader(BufferedInputStream(socket.getInputStream()))
        val firstLine = request.lineSequence().firstOrNull().orEmpty()
        val target = firstLine.split(' ').getOrNull(1).orEmpty()
        if (!target.startsWith("/audio.mp3")) {
            socket.getOutputStream().write(
                "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII)
            )
            return
        }
        val range = request.lineSequence()
            .firstOrNull { it.startsWith("Range:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        val currentAudio = audio
        val start = parseRangeStart(range, currentAudio.size)
        val partial = range != null
        val output = BufferedOutputStream(socket.getOutputStream())
        val length = currentAudio.size - start
        output.write(buildString {
            append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: audio/mpeg\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: $length\r\n")
            if (partial) append("Content-Range: bytes $start-${currentAudio.lastIndex}/${currentAudio.size}\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII))
        if (!firstLine.startsWith("HEAD ")) output.write(currentAudio, start, length)
        output.flush()
        report("HTTP 命中：${socket.inetAddress.hostAddress} · ${range ?: "完整请求"}")
    }

    private fun readHeader(input: BufferedInputStream): String {
        val bytes = ArrayList<Byte>()
        while (bytes.size < 8192) {
            val value = input.read()
            if (value < 0) break
            bytes += value.toByte()
            val size = bytes.size
            if (size >= 4 && bytes[size - 4] == 13.toByte() &&
                bytes[size - 3] == 10.toByte() && bytes[size - 2] == 13.toByte() &&
                bytes[size - 1] == 10.toByte()
            ) break
        }
        return bytes.toByteArray().toString(Charsets.US_ASCII)
    }

    private fun phoneIpv4Address(): String {
        val addresses = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { network ->
                network.inetAddresses.toList().filterIsInstance<Inet4Address>()
                    .map { network.name to it.hostAddress.orEmpty() }
            }
        return addresses.firstOrNull { it.first == "wlan0" }?.second
            ?: addresses.firstOrNull()?.second ?: "127.0.0.1"
    }

    override fun close() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    companion object {
        private const val PORT = 18765
        internal fun parseRangeStart(value: String?, size: Int): Int {
            if (value == null || !value.startsWith("bytes=")) return 0
            return value.removePrefix("bytes=").substringBefore('-').toIntOrNull()
                ?.coerceIn(0, (size - 1).coerceAtLeast(0)) ?: 0
        }

    }
}

internal enum class BleVideoProfile(
    val wireName: String,
    val targetFps: Int,
    val assetSuffix: String,
    val summary: String
) {
    QUALITY("quality", 6, "-466-4x3.wvs", "约 93 × 70 · 6 FPS"),
    SMOOTH("smooth", 12, "-compact-466-4x3.wvs", "44 × 70 · 12 FPS")
}
