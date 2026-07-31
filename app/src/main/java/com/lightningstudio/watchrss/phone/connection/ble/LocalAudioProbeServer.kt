package com.lightningstudio.watchrss.phone.connection.ble

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal class LocalAudioProbeServer(
    context: Context,
    private val report: (String) -> Unit
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val mdnsResponder = LocalMdnsResponder(appContext, report)
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    @Volatile private var audio = ByteArray(0)
    @Volatile private var video = ByteArray(0)
    @Volatile private var frameOffsets = IntArray(0)
    @Volatile private var frameSizes = IntArray(0)
    @Volatile private var sourceFps = 5

    fun start(videoId: String = "bad-apple"): String {
        loadVideo(videoId)
        if (running.compareAndSet(false, true)) {
            val socket = ServerSocket(PORT).apply { reuseAddress = true }
            serverSocket = socket
            thread(name = "watchrss-http-video", isDaemon = true) {
                while (running.get()) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    thread(name = "watchrss-http-client", isDaemon = true) {
                        client.use(::serve)
                    }
                }
            }
        }
        val address = phoneIpv4Address()
        mdnsResponder.start(InetAddress.getByName(address) as Inet4Address)
        val base = "http://${LocalMdnsResponder.HOSTNAME}:$PORT"
        return "$base/audio.mp3|$base/frame.bin?index=|65535|$sourceFps"
    }

    private fun loadVideo(videoId: String) {
        val baseId = videoId.removeSuffix("-blur")
        val audioName = if (baseId == "bad-apple") "bad-apple-full" else baseId
        val nextAudio = appContext.assets.open("bluetooth-video/$audioName.mp3").use { it.readBytes() }
        val nextVideo = appContext.assets.open("bluetooth-video/$videoId-466-4x3.wvs").use { it.readBytes() }
        require(nextVideo.copyOfRange(0, 4).decodeToString() == "WVS1")
        val count = uint16(nextVideo, 12)
        val dictionarySize = nextVideo[14].toInt() and 0xff
        val offsets = IntArray(count)
        val sizes = IntArray(count)
        var cursor = 16 + dictionarySize * 4
        for (index in 0 until count) {
            val size = uint16(nextVideo, cursor)
            cursor += 2
            offsets[index] = cursor
            sizes[index] = size
            cursor += size
        }
        audio = nextAudio
        video = nextVideo
        frameOffsets = offsets
        frameSizes = sizes
        sourceFps = nextVideo[5].toInt() and 0xff
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 10_000
        val request = readHeader(BufferedInputStream(socket.getInputStream()))
        val firstLine = request.lineSequence().firstOrNull().orEmpty()
        val target = firstLine.split(' ').getOrNull(1).orEmpty()
        if (target.startsWith("/frame.bin")) {
            serveFrame(socket, target)
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

    private fun serveFrame(socket: Socket, target: String) {
        val index = target.substringAfter("index=", "-1").substringBefore('&').toIntOrNull() ?: -1
        val offsets = frameOffsets
        val sizes = frameSizes
        val currentVideo = video
        if (index !in offsets.indices) {
            socket.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
            return
        }
        val output = BufferedOutputStream(socket.getOutputStream())
        output.write(
            "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n".plus(
                "Content-Length: ${sizes[index]}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII)
        )
        output.write(currentVideo, offsets[index], sizes[index])
        output.flush()
        if (index % 25 == 0) report("HTTP 视频帧：$index/${offsets.size}")
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
        mdnsResponder.close()
    }

    companion object {
        private const val PORT = 18765
        private fun uint16(source: ByteArray, offset: Int): Int =
            (source[offset].toInt() and 0xff) or
                ((source[offset + 1].toInt() and 0xff) shl 8)

        internal fun parseRangeStart(value: String?, size: Int): Int {
            if (value == null || !value.startsWith("bytes=")) return 0
            return value.removePrefix("bytes=").substringBefore('-').toIntOrNull()
                ?.coerceIn(0, (size - 1).coerceAtLeast(0)) ?: 0
        }
    }
}
