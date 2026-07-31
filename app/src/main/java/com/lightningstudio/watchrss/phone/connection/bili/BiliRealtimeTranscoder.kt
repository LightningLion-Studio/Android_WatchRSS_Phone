package com.lightningstudio.watchrss.phone.connection.bili

import android.graphics.Bitmap
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal data class BiliPlaybackRequest(
    val url: String,
    val referer: String,
    val durationMs: Long,
    val cookieHeader: String
)

internal fun interface VideoFrameSink {
    suspend fun send(frameIndex: Int, payload: ByteArray)
}

internal interface VideoFrameSource : AutoCloseable {
    val durationMs: Long
    fun frameAt(positionMs: Long): Bitmap?
}

internal fun interface GrayFrameEncoder {
    fun encode(bitmap: Bitmap): ByteArray
}

/** Phone-side decode/encode pipeline. Transport, decoder and glyph mapping stay replaceable. */
internal class BiliRealtimeTranscoder(
    context: Context,
    private val frameSourceFactory: (BiliPlaybackRequest) -> VideoFrameSource = ::RetrieverFrameSource,
    private val encoder: GrayFrameEncoder = FiveBitTextFrameEncoder()
) {
    private val appContext = context.applicationContext

    suspend fun stream(request: BiliPlaybackRequest, sink: VideoFrameSink) {
        val headers = linkedMapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to request.referer
        ).apply {
            if (request.cookieHeader.isNotBlank()) put("Cookie", request.cookieHeader)
        }
        val player = withContext(Dispatchers.IO) {
            MediaPlayer().also { media ->
                media.setDataSource(appContext, Uri.parse(request.url), headers)
                media.prepare()
            }
        }
        val source = withContext(Dispatchers.IO) { frameSourceFactory(request) }
        try {
            player.start() // Relative gain remains untouched: playback follows system media volume.
            val startedAt = SystemClock.elapsedRealtime()
            val duration = minOf(
                source.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE,
                request.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
            )
            var frameIndex = 0
            while (true) {
                coroutineContext.ensureActive()
                val positionMs = SystemClock.elapsedRealtime() - startedAt
                if (positionMs >= duration) break
                val bitmap = withContext(Dispatchers.IO) { source.frameAt(positionMs) }
                if (bitmap != null) {
                    val payload = encoder.encode(bitmap)
                    bitmap.recycle()
                    sink.send(frameIndex, payload)
                }
                frameIndex += 1
                val nextAt = startedAt + frameIndex * 1000L / FPS
                delay((nextAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            }
        } finally {
            runCatching { player.stop() }
            player.release()
            source.close()
        }
    }

    private class RetrieverFrameSource(request: BiliPlaybackRequest) : VideoFrameSource {
        private val retriever = MediaMetadataRetriever().apply {
            val headers = linkedMapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to request.referer
            ).apply {
                if (request.cookieHeader.isNotBlank()) put("Cookie", request.cookieHeader)
            }
            setDataSource(request.url, headers)
        }

        override val durationMs: Long =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: request.durationMs

        override fun frameAt(positionMs: Long): Bitmap? = retriever.getScaledFrameAtTime(
            positionMs * 1000L,
            MediaMetadataRetriever.OPTION_CLOSEST,
            FRAME_COLUMNS,
            FRAME_ROWS
        )

        override fun close() {
            retriever.release()
        }
    }

    private class FiveBitTextFrameEncoder : GrayFrameEncoder {
        override fun encode(bitmap: Bitmap): ByteArray {
            val output = ArrayList<Byte>(FRAME_ROWS * 96)
            output += FRAME_ROWS.toByte()
            for (row in 0 until FRAME_ROWS) {
                val runs = ArrayList<Byte>()
                var previous = -1
                var length = 0
                for (column in 0 until FRAME_COLUMNS) {
                    val pixel = bitmap.getPixel(column, row)
                    val red = pixel ushr 16 and 0xff
                    val green = pixel ushr 8 and 0xff
                    val blue = pixel and 0xff
                    val luminance = (red * 54 + green * 183 + blue * 19) ushr 8
                    val level = (luminance * 31 + 127) / 255
                    if (level == previous && length < 255) {
                        length += 1
                    } else {
                        if (length > 0) {
                            runs += length.toByte()
                            runs += previous.toByte()
                        }
                        previous = level
                        length = 1
                    }
                }
                runs += length.toByte()
                runs += previous.toByte()
                output += (runs.size / 2).toByte()
                output.addAll(runs)
            }
            return ByteArray(output.size) { output[it] }
        }
    }

    private companion object {
        private const val FPS = 6
        private const val FRAME_COLUMNS = 93
        private const val FRAME_ROWS = 70
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}
