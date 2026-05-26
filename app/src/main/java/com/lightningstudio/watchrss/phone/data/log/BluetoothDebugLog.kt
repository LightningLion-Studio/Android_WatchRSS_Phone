package com.lightningstudio.watchrss.phone.data.log

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

class BluetoothDebugLog(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val file: File
        get() = File(appContext.filesDir, FILE_NAME)

    fun append(message: String, throwable: Throwable? = null) {
        val entry = buildString {
            append(Instant.now().toString())
            append(' ')
            append(message)
            appendLine()
            if (throwable != null) {
                append(Log.getStackTraceString(throwable))
                appendLine()
            }
        }
        synchronized(lock) {
            runCatching {
                file.appendText(entry)
                if (file.length() > MAX_LOG_BYTES) {
                    trimLocked()
                }
            }
        }
    }

    suspend fun exportTo(contentResolver: ContentResolver, uri: Uri): Long =
        withContext(Dispatchers.IO) {
            val text = snapshot()
            contentResolver.openOutputStream(uri)?.use { output ->
                val bytes = text.toByteArray(Charsets.UTF_8)
                output.write(bytes)
                bytes.size.toLong()
            } ?: error("无法创建日志文件")
        }

    private fun snapshot(): String {
        val body = synchronized(lock) {
            if (file.exists()) {
                file.readText()
            } else {
                ""
            }
        }
        return buildString {
            appendLine("WatchRSS Phone Bluetooth Debug Log")
            appendLine("exportedAt=${Instant.now()}")
            appendLine("package=${appContext.packageName}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine()
            append(body.ifBlank { "No Bluetooth debug entries recorded." })
        }
    }

    private fun trimLocked() {
        val text = file.readText()
        if (text.length <= TRIM_TO_CHARS) return
        file.writeText(text.takeLast(TRIM_TO_CHARS))
    }

    private companion object {
        private const val FILE_NAME = "bluetooth_debug.log"
        private const val MAX_LOG_BYTES = 512 * 1024
        private const val TRIM_TO_CHARS = 256 * 1024
    }
}
