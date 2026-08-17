package com.lightningstudio.watchrss.phone

import android.app.Application
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.lightningstudio.watchrss.phone.cloud.PhoneCloudSyncWorker
import com.lightningstudio.watchrss.phone.connection.ble.WatchBleBandwidthServer
import com.lightningstudio.watchrss.phone.connection.ip.WatchIpSyncService
import com.lightningstudio.watchrss.phone.data.local.PhoneDeviceIdentity
import com.lightningstudio.watchrss.phone.privacy.PhonePrivacyConsentStore
import com.lightningstudio.watchrss.phone.privacy.shouldEnforceAppAccess
import com.lightningstudio.watchrss.phone.tips.TipEvents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class PhoneCompanionApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastForegroundSyncAt = 0L
    @Volatile private var resumedActivity: Activity? = null
    private var resumedAtMillis = 0L
    private val accountInitialization = CompletableDeferred<Unit>()
    private val consentServicesStarted = AtomicBoolean(false)
    private val ipSyncService: WatchIpSyncService by lazy {
        WatchIpSyncService(this, PhoneDeviceIdentity(this).deviceId)
    }

    val container: PhoneCompanionContainer by lazy {
        PhoneCompanionContainer(this)
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedActivity = activity
                resumedAtMillis = SystemClock.elapsedRealtime()
                val privacyConsent = PhonePrivacyConsentStore(this@PhoneCompanionApplication)
                val hasRequiredConsent = privacyConsent.hasRequiredConsent()
                if (!hasRequiredConsent) {
                    PhoneCloudSyncWorker.cancel(this@PhoneCompanionApplication)
                    stopWatchBaseStation()
                    if (activity !is MainActivity && activity !is PhoneOobeActivity &&
                        activity !is LegalDocumentActivity && activity !is ContactDeveloperActivity
                    ) {
                        activity.startActivity(
                            Intent(activity, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        )
                        activity.finish()
                    }
                    return
                }
                startConsentDependentServices()
                container.oppoReviewCoordinator.onAppEntry()
                if (!shouldEnforceAppAccess(
                        hasRequiredConsent = hasRequiredConsent,
                        isOobeComplete = privacyConsent.isOobeComplete()
                    )
                ) {
                    PhoneCloudSyncWorker.cancel(this@PhoneCompanionApplication)
                    stopWatchBaseStation()
                    if (activity !is MainActivity && activity !is PhoneOobeActivity &&
                        activity !is LegalDocumentActivity && activity !is AccountActivity &&
                        activity !is ContactDeveloperActivity
                    ) {
                        activity.startActivity(
                            Intent(activity, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        )
                        activity.finish()
                    }
                    return
                }
                appScope.launch {
                    container.appAccessCoordinator.reconcile()
                    if (container.appAccessCoordinator.isAuthorized) {
                        startWatchBaseStationIfPermitted()
                        if (container.accountRepository.hasUsableSession) {
                            PhoneCloudSyncWorker.schedule(this@PhoneCompanionApplication)
                            container.startCloudChangeScheduler()
                        } else {
                            PhoneCloudSyncWorker.cancel(this@PhoneCompanionApplication)
                            container.stopCloudChangeScheduler()
                        }
                    } else {
                        PhoneCloudSyncWorker.cancel(this@PhoneCompanionApplication)
                        container.stopCloudChangeScheduler()
                        stopWatchBaseStation()
                        if (activity !is MainActivity && activity !is AccountActivity &&
                            activity !is DataManagementActivity && activity !is ContactDeveloperActivity
                        ) {
                            activity.startActivity(
                                Intent(activity, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            )
                            activity.finish()
                        }
                    }
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastForegroundSyncAt < FOREGROUND_SYNC_THROTTLE_MS) return
                lastForegroundSyncAt = now
                appScope.launch {
                    if (container.accountRepository.hasUsableSession) {
                        runCatching { container.cloudSyncService.syncNow() }
                    }
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) {
                if (resumedAtMillis > 0) {
                    val elapsed = SystemClock.elapsedRealtime() - resumedAtMillis
                    if (elapsed > 0) container.oppoReviewCoordinator.recordForeground(elapsed)
                    resumedAtMillis = 0L
                }
                if (resumedActivity === activity) resumedActivity = null
            }
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        appScope.launch {
            container.accountRepository.initialize()
            accountInitialization.complete(Unit)
            if (PhonePrivacyConsentStore(this@PhoneCompanionApplication).hasRequiredConsent()) {
                startConsentDependentServices()
            }
        }
    }

    fun onPrivacyConsentGranted() {
        startConsentDependentServices()
    }

    private fun startConsentDependentServices() {
        if (!PhonePrivacyConsentStore(this).hasRequiredConsent()) return
        if (!consentServicesStarted.compareAndSet(false, true)) return
        container.oppoPushCoordinator.init()
        appScope.launch {
            accountInitialization.await()
            container.oppoPushCoordinator.ensurePushState()
            container.appAccessCoordinator.initialize()
            container.usageTelemetry.recordAppLaunch()
            container.tipManager.recordEvent(TipEvents.APP_LAUNCH)
            container.repository.recordFirstUseIfAbsent(container.firstInstalledAtMillis)
            if (container.accountRepository.hasUsableSession &&
                container.appAccessCoordinator.isAuthorized
            ) {
                runCatching { container.cloudSyncService.syncNow() }
            }
        }
    }

    fun startWatchBaseStationIfPermitted(): Boolean {
        if (!container.appAccessCoordinator.isAuthorized) return false
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
            ipSyncService.start()
            WatchBleBandwidthServer.processInstance(this).apply {
                setEndpointDescriptorProvider {
                    ipSyncService.endpointProvider.issueDescriptor().toBleJson().toString()
                        .toByteArray(Charsets.UTF_8)
                }
                start()
            }
            true
        }.onFailure { error ->
            Log.e(TAG, "Failed to start watch base station", error)
        }.getOrDefault(false)
    }

    fun currentIpEndpointDescriptorForSync(expectedWatchDeviceId: String? = null): JSONObject? =
        ipSyncService.currentEndpointDescriptorJson(expectedWatchDeviceId)

    private fun stopWatchBaseStation() {
        runCatching { ipSyncService.close() }
        runCatching { WatchBleBandwidthServer.processInstance(this).close() }
    }

    /** Only foreground phone sessions may be opened by a watch request. */
    fun requestWatchBiliLogin(): Boolean {
        val activity = resumedActivity ?: return false
        activity.startActivity(BiliWatchLoginActivity.createIntent(activity))
        return true
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
