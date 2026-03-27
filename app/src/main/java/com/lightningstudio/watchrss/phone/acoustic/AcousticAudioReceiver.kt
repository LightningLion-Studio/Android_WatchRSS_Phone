package com.lightningstudio.watchrss.phone.acoustic

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class AcousticAudioReceiver {
    suspend fun listen(timeoutMs: Long): ByteArray? = withContext(Dispatchers.IO) {
        val minBufferSize = AudioRecord.getMinBufferSize(
            AcousticCodec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val readBufferSize = maxOf(minBufferSize / 2, AcousticCodec.SAMPLE_RATE / 4)
        val readBuffer = ShortArray(readBufferSize)
        val accumulator = ShortAccumulator()
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            AcousticCodec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBufferSize, readBufferSize * 2)
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("麦克风初始化失败")
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            record.startRecording()
            while (coroutineContext.isActive && System.currentTimeMillis() < deadline) {
                val read = record.read(readBuffer, 0, readBuffer.size)
                if (read <= 0) {
                    continue
                }
                accumulator.append(readBuffer, read)
                AcousticCodec.decode(accumulator.toShortArray())?.let { return@withContext it }
            }
            null
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }
}

private class ShortAccumulator {
    private var buffer = ShortArray(16_384)
    private var size = 0

    fun append(data: ShortArray, count: Int) {
        ensureCapacity(size + count)
        System.arraycopy(data, 0, buffer, size, count)
        size += count
    }

    fun toShortArray(): ShortArray = buffer.copyOf(size)

    private fun ensureCapacity(required: Int) {
        if (required <= buffer.size) {
            return
        }
        var nextSize = buffer.size
        while (nextSize < required) {
            nextSize *= 2
        }
        buffer = buffer.copyOf(nextSize)
    }
}
