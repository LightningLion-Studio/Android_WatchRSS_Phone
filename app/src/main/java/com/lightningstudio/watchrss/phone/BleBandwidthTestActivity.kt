package com.lightningstudio.watchrss.phone

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lightningstudio.watchrss.phone.connection.ble.BleBandwidthTrialResult
import com.lightningstudio.watchrss.phone.connection.ble.WatchBleBandwidthServer
import kotlinx.coroutines.launch
import java.util.Locale

class BleBandwidthTestActivity : ComponentActivity() {
    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var startButton: Button
    private var server: WatchBleBandwidthServer? = null
    private var running = false
    private val results = mutableListOf<BleBandwidthTrialResult>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            finish()
            return
        }

        setContentView(buildContent())
        ensurePermissionsAndStart()
    }

    private fun buildContent(): ScrollView {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(40))
        }
        content.addView(TextView(this).apply {
            text = "WatchRSS 自有 BLE 带宽测试"
            textSize = 25f
            setTypeface(typeface, Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = "不经过运动健康或 Glyphix Debug。先在手表打开：我的 → 设置 → 手机手表带宽测试。"
            textSize = 16f
            setPadding(0, dp(12), 0, dp(12))
        })
        statusView = TextView(this).apply {
            text = "正在准备 BLE 权限…"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        content.addView(
            statusView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        startButton = Button(this).apply {
            text = "开始测试"
            isEnabled = false
            setOnClickListener { startBenchmark() }
        }
        content.addView(
            startButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56)
            )
        )
        resultView = TextView(this).apply {
            text = "将依次发送 32 / 64 / 128 / 256 KiB，每档 2 次。\n" +
                "计时口径：BEGIN 通知 → 手表完成序号、大小、checksum 校验并写回 ACK。"
            textSize = 15f
            setTextIsSelectable(true)
            gravity = Gravity.START
            setPadding(0, dp(18), 0, 0)
        }
        content.addView(resultView)
        return ScrollView(this).apply { addView(content) }
    }

    private fun ensurePermissionsAndStart() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            emptyArray()
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startServer()
        } else {
            requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startServer()
        } else {
            statusView.text = "需要“附近的设备”权限才能广播 WatchRSS BLE"
        }
    }

    private fun startServer() {
        if (server != null) return
        runCatching {
            WatchBleBandwidthServer(applicationContext) { message ->
                runOnUiThread {
                    statusView.text = message
                    startButton.isEnabled = server?.watchReady == true && !running
                }
                Log.i(TAG, message)
            }.also {
                server = it
                it.start()
            }
        }.onFailure { error ->
            statusView.text = "BLE 启动失败：${error.message}"
            Log.e(TAG, "BLE bandwidth server start failed", error)
        }
    }

    private fun startBenchmark() {
        val activeServer = server ?: return
        running = true
        startButton.isEnabled = false
        results.clear()
        resultView.text = "测试开始…"
        lifecycleScope.launch {
            runCatching {
                activeServer.runBenchmark { result ->
                    results += result
                    resultView.text = formatResults(results, complete = false)
                    Log.i(TAG, "trial=$result")
                }
            }.onSuccess {
                statusView.text = "测试完成"
                resultView.text = formatResults(results, complete = true)
            }.onFailure { error ->
                statusView.text = "测试失败：${error.message}"
                resultView.append("\n\n失败：${error.message}")
                Log.e(TAG, "BLE bandwidth benchmark failed", error)
            }
            running = false
            startButton.isEnabled = activeServer.watchReady
        }
    }

    private fun formatResults(
        values: List<BleBandwidthTrialResult>,
        complete: Boolean
    ): String = buildString {
        values.forEach { result ->
            append(
                String.format(
                    Locale.US,
                    "%4d KiB #%d  %5d ms  %7.1f KiB/s  %7.0f kbps  MTU %d  %d 包\n",
                    result.sizeBytes / 1024,
                    result.repetition,
                    result.elapsedMs,
                    result.kibPerSecond,
                    result.kilobitsPerSecond,
                    result.mtu,
                    result.packetCount
                )
            )
        }
        if (complete && values.isNotEmpty()) {
            val sorted = values.map { it.kilobitsPerSecond }.sorted()
            val median = (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
            append(
                String.format(
                    Locale.US,
                    "\n中位有效吞吐：%.0f kbps\n最低有效吞吐：%.0f kbps",
                    median,
                    sorted.first()
                )
            )
        }
    }

    override fun onDestroy() {
        server?.close()
        server = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "WatchRSS_OwnBleBandwidth"
        const val PERMISSION_REQUEST = 4817
    }
}
