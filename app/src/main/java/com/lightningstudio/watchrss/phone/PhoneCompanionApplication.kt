package com.lightningstudio.watchrss.phone

import android.app.Application
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.lightningstudio.watchrss.phone.cloud.PhoneCloudSyncWorker
import com.lightningstudio.watchrss.phone.connection.ble.WatchBleBandwidthServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PhoneCompanionApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastForegroundSyncAt = 0L

    val container: PhoneCompanionContainer by lazy {
        PhoneCompanionContainer(this)
    }

    override fun onCreate() {
        super.onCreate()
        PhoneCloudSyncWorker.schedule(this)
        container.startCloudChangeScheduler()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                startWatchBaseStationIfPermitted()
                val now = SystemClock.elapsedRealtime()
                if (now - lastForegroundSyncAt < FOREGROUND_SYNC_THROTTLE_MS) return
                lastForegroundSyncAt = now
                appScope.launch {
                    if (container.accountRepository.session.value != null) {
                        runCatching { container.cloudSyncService.syncNow() }
                    }
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        appScope.launch {
            container.accountRepository.initialize()
            container.usageTelemetry.recordAppLaunch()
            container.repository.recordFirstUseIfAbsent(container.firstInstalledAtMillis)
            if (container.accountRepository.session.value != null) {
                runCatching { container.cloudSyncService.syncNow() }
            }
        }
    }

    fun startWatchBaseStationIfPermitted(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            emptyList()
        }
        if (permissions.any {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
        ) return false
        return runCatching {
            WatchBleBandwidthServer.processInstance(this).start()
            true
        }.onFailure { error ->
            Log.e(TAG, "Failed to start watch base station", error)
        }.getOrDefault(false)
    }

    fun restartAfterRemoteEnvironmentChange() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
            ?: error("无法获取应用启动入口")
        val restartIntent = PendingIntent.getActivity(
            this,
            REMOTE_ENVIRONMENT_RESTART_REQUEST_CODE,
            launchIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        getSystemService(AlarmManager::class.java).set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + REMOTE_ENVIRONMENT_RESTART_DELAY_MS,
            restartIntent
        )
        Process.killProcess(Process.myPid())
    }

    private companion object {
        private const val TAG = "WatchRSS_BaseStation"
        private const val FOREGROUND_SYNC_THROTTLE_MS = 5 * 60 * 1000L
        private const val REMOTE_ENVIRONMENT_RESTART_REQUEST_CODE = 4821
        private const val REMOTE_ENVIRONMENT_RESTART_DELAY_MS = 300L
    }
}
