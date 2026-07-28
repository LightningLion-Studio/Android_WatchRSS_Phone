package com.lightningstudio.watchrss.phone

import android.app.Application
import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import com.lightningstudio.watchrss.phone.cloud.PhoneCloudSyncWorker
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
            if (container.accountRepository.session.value != null) {
                runCatching { container.cloudSyncService.syncNow() }
            }
        }
    }

    private companion object {
        private const val FOREGROUND_SYNC_THROTTLE_MS = 5 * 60 * 1000L
    }
}
