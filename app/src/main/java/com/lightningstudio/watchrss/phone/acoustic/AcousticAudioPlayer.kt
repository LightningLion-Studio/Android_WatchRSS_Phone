package com.lightningstudio.watchrss.phone.acoustic

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class AcousticAudioPlayer {
    suspend fun play(packet: AcousticPacket) = withContext(Dispatchers.IO) {
        val minBufferSize = AudioTrack.getMinBufferSize(
            AcousticCodec.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(AcousticCodec.SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            maxOf(minBufferSize, packet.waveform.size * 2),
            AudioTrack.MODE_STATIC,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        try {
            Log.i(TAG, "play durationMs=${packet.durationMs} samples=${packet.waveform.size} compressed=${packet.compressedBytes} encodedChars=${packet.encodedChars}")
            audioTrack.write(packet.waveform, 0, packet.waveform.size)
            audioTrack.setVolume(AudioTrack.getMaxVolume())
            audioTrack.play()
            delay(packet.durationMs.toLong() + 300L)
        } finally {
            runCatching { audioTrack.stop() }
            audioTrack.release()
        }
    }

    companion object {
        private const val TAG = "WatchRSS_AcousticPlayer"
    }
}
