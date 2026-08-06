package com.lightningstudio.watchrss.phone.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data class Downloading(val bytesRead: Long, val totalBytes: Long?) : AppUpdateState
    data class Ready(val apk: File) : AppUpdateState
    data class Failed(val message: String) : AppUpdateState
}

class AppUpdateDownloader(private val context: Context) {
    private val stateFlow = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state = stateFlow.asStateFlow()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    suspend fun download(version: String, url: String) = withContext(Dispatchers.IO) {
        runCatching {
            require(Uri.parse(url).scheme.equals("https", true)) { "安装包地址必须使用 HTTPS" }
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val apk = File(dir, "watchrss-phone-${version.replace(Regex("[^A-Za-z0-9._-]"), "_")}.apk")
            val part = File(dir, "${apk.name}.part")
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful) { "下载失败（HTTP ${response.code}）" }
                val body = response.body ?: error("服务器没有返回安装包")
                val total = body.contentLength().takeIf { it > 0 }
                body.byteStream().use { input ->
                    part.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var readTotal = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            readTotal += count
                            stateFlow.value = AppUpdateState.Downloading(readTotal, total)
                        }
                    }
                }
            }
            val packageInfo = context.packageManager.getPackageArchiveInfo(part.path, 0)
            check(part.length() > 0 && packageInfo?.packageName == context.packageName) {
                "下载内容不是本应用的有效 APK 安装包"
            }
            check(part.renameTo(apk)) { "无法保存安装包" }
            stateFlow.value = AppUpdateState.Ready(apk)
        }.onFailure { stateFlow.value = AppUpdateState.Failed(it.message ?: "安装包下载失败") }
    }

    fun launchInstaller(apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
            })
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return true
    }

    fun resetFailure() {
        if (stateFlow.value is AppUpdateState.Failed) stateFlow.value = AppUpdateState.Idle
    }
}
