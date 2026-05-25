package com.lightningstudio.watchrss.phone

import android.os.Bundle
import android.content.pm.ApplicationInfo
import android.util.Base64
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.lightningstudio.watchrss.phone.acoustic.AcousticAudioPlayer
import com.lightningstudio.watchrss.phone.connection.PhoneConnectionAbility
import kotlinx.coroutines.launch

class DebugGuidedSessionActivity : ComponentActivity() {
    private val acousticPlayer = AcousticAudioPlayer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            finish()
            return
        }

        val statusView = TextView(this).apply {
            textSize = 14f
            setPadding(24, 24, 24, 24)
            text = "Starting debug guided session..."
        }
        setContentView(statusView)

        val ability = intent.getStringExtra(EXTRA_ABILITY)
            ?.let { PhoneConnectionAbility.fromPayloadValue(it) }
            ?: PhoneConnectionAbility.REMOTE_INPUT
        val remoteUrl = intent.getStringExtra(EXTRA_URL)
            ?: "https://example.com/feed.xml"
        val shouldPlay = intent.getBooleanExtra(EXTRA_PLAY, false)

        lifecycleScope.launch {
            runCatching {
                val session = (application as PhoneCompanionApplication)
                    .container
                    .guidedSessionManager
                    .startSession(
                        ability = ability,
                        remoteUrl = if (ability == PhoneConnectionAbility.REMOTE_INPUT) remoteUrl else null
                    )
                val payloadJson = session.payload.toString(Charsets.UTF_8)
                val payloadBase64 = Base64.encodeToString(session.payload, Base64.NO_WRAP)
                val message = buildString {
                    appendLine("Debug guided session started")
                    appendLine("ability=${session.ability.name}")
                    appendLine("usesHotspot=${session.usesHotspot}")
                    appendLine("ssid=${session.ssid}")
                    appendLine("passphrase=${session.passphrase}")
                    appendLine("host=${session.host}")
                    appendLine("port=${session.port}")
                    appendLine("token=${session.token}")
                    appendLine("durationMs=${session.packet.durationMs}")
                    appendLine("payload=$payloadJson")
                    appendLine("payloadBase64=$payloadBase64")
                    appendLine("play=$shouldPlay")
                }
                Log.i(TAG, message)
                statusView.text = message
                if (shouldPlay) {
                    Log.i(TAG, "Playing debug guided payload")
                    acousticPlayer.play(session.packet)
                    Log.i(TAG, "Finished playing debug guided payload")
                }
            }.onFailure { throwable ->
                val message = "Debug guided session failed: ${throwable.message}"
                Log.e(TAG, message, throwable)
                statusView.text = message
            }
        }
    }

    companion object {
        private const val TAG = "WatchRSS_DebugGuided"
        private const val EXTRA_ABILITY = "ability"
        private const val EXTRA_URL = "url"
        private const val EXTRA_PLAY = "play"
    }
}
