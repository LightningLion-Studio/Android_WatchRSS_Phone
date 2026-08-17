package com.lightningstudio.watchrss.phone.push

import android.content.Context
import android.os.Build
import android.util.Log
import com.heytap.msp.push.HeytapPushManager
import com.heytap.msp.push.callback.ICallBackResultService
import com.lightningstudio.watchrss.phone.BuildConfig
import com.lightningstudio.watchrss.phone.privacy.PhonePrivacyConsentStore

/**
 * OPPO push registration. Every SDK call and regId upload is gated on the required
 * privacy consent. register() runs only when the device supports OPPO push and the user
 * has not disabled "接收推送通知". A successful regId is uploaded to the backend (account
 * session + device possession required); failures retry on the next cold start.
 *
 * API surface verified against com.heytap.msp_V3.7.1.aar (javap): HeytapPushManager
 * init/isSupportPush/register/pausePush/resumePush/requestNotificationPermission all
 * match the calls below; ICallBackResultService carries extra string params in 3.7.x.
 */
class OppoPushCoordinator(
    private val context: Context,
    private val store: PushRegistrationStore = PushRegistrationStore(context),
    private val uploader: PhonePushRegistrationUploader? = null
) {
    private val consentGate = OppoPushConsentGate {
        PhonePrivacyConsentStore(context).hasRequiredConsent()
    }
    private val pendingUploadRetry = PendingPushUploadRetry(
        hasRequiredConsent = consentGate::isGranted,
        upload = { regId -> uploader?.upload(regId) == true },
        markUploaded = { regId -> store.uploadedRegId = regId }
    )

    /** No-op-safe SDK init; must run after consent and before register()/pause()/resume(). */
    fun init() {
        consentGate.runIfGranted {
            runCatching {
                HeytapPushManager.init(context.applicationContext, BuildConfig.DEBUG)
            }.onFailure { Log.e(TAG, "HeytapPushManager.init failed", it) }
        }
    }

    /** Idempotent reconciliation; call on cold start. */
    fun ensurePushState() {
        if (!consentGate.isGranted()) return
        if (BuildConfig.WATCHRSS_OPPO_PUSH_APP_KEY.isBlank() ||
            BuildConfig.WATCHRSS_OPPO_PUSH_APP_SECRET.isBlank()
        ) {
            Log.w(TAG, "OPPO push credentials missing from BuildConfig; skipping registration")
            return
        }
        val supported = runCatching { HeytapPushManager.isSupportPush(context.applicationContext) }
            .getOrDefault(false)
        if (!supported) {
            Log.i(TAG, "isSupportPush=false (not an OPPO/OnePlus/realme device); push disabled")
            return
        }
        if (!store.isEnabled) {
            runCatching { HeytapPushManager.pausePush() }
            Log.i(TAG, "push disabled by settings; paused")
            return
        }
        if (store.regId != null && store.lastRegisterCode == 0) {
            runCatching { HeytapPushManager.resumePush() }
            store.regId?.let { uploadIfNeeded(it) } // retries failed uploads on every cold start
            return // already registered; no re-register on every launch
        }
        register()
    }

    private fun register() {
        runCatching {
            HeytapPushManager.register(
                context.applicationContext,
                BuildConfig.WATCHRSS_OPPO_PUSH_APP_KEY,
                BuildConfig.WATCHRSS_OPPO_PUSH_APP_SECRET,
                callback
            )
        }.onFailure { Log.e(TAG, "register() threw", it) }
        requestNotificationPermission()
    }

    private val callback = object : ICallBackResultService {
        override fun onRegister(
            responseCode: Int,
            registerID: String,
            arg2: String,
            arg3: String
        ) {
            val previous = store.regId
            store.lastRegisterCode = responseCode
            store.regId = registerID.takeIf { responseCode == 0 }
            if (responseCode == 0 && store.regId != previous) store.uploadedRegId = null
            Log.i(TAG, "onRegister code=$responseCode regId=${store.regId ?: "none"} " +
                "(extra: $arg2 / $arg3)")
            if (responseCode != 0) {
                Log.w(TAG, "register failed; 16 = signature mismatch")
            } else {
                store.regId?.let { uploadIfNeeded(it) }
            }
        }

        override fun onUnRegister(responseCode: Int, arg2: String, arg3: String) = Unit
        override fun onSetPushTime(responseCode: Int, pushTime: String) = Unit
        override fun onGetPushStatus(responseCode: Int, status: Int) = Unit
        override fun onGetNotificationStatus(responseCode: Int, status: Int) = Unit
        override fun onError(code: Int, msg: String, arg3: String, arg4: String) {
            Log.w(TAG, "push callback error code=$code msg=$msg ($arg3 / $arg4)")
        }
    }

    /** Settings toggle handler. */
    fun setEnabled(enabled: Boolean) {
        store.isEnabled = enabled
        if (!consentGate.isGranted()) return
        if (enabled) {
            ensurePushState()
            requestNotificationPermission()
        } else {
            runCatching { HeytapPushManager.pausePush() }
        }
    }

    /** POST_NOTIFICATIONS on Android 13+; the system dialog is suppressed after a permanent denial. */
    fun requestNotificationPermission() {
        consentGate.runIfGranted {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching { HeytapPushManager.requestNotificationPermission() }
            }
        }
    }

    /**
     * Retries only the persisted, pending regId. App-access authorization invokes this
     * after a usable session and device token are available; successful uploads remain
     * idempotent through [PushRegistrationStore.uploadedRegId].
     */
    fun retryPendingUpload() {
        uploadIfNeeded(store.regId)
    }

    private fun uploadIfNeeded(regId: String?) {
        when (pendingUploadRetry.retry(regId, store.uploadedRegId, store.isEnabled)) {
            PendingPushUploadResult.DEFERRED -> Log.i(
                TAG,
                "regId upload deferred (no session / no device access / network); " +
                    "retry after authorization or next cold start"
            )
            else -> Unit
        }
    }

    fun regId(): String? = store.regId

    fun lastRegisterCode(): Int = store.lastRegisterCode

    companion object {
        const val TAG = "WatchRSS_OppoPush"
    }
}

internal enum class PendingPushUploadResult {
    SKIPPED,
    DEFERRED,
    UPLOADED
}

internal class PendingPushUploadRetry(
    private val hasRequiredConsent: () -> Boolean,
    private val upload: (String) -> Boolean,
    private val markUploaded: (String) -> Unit
) {
    @Synchronized
    fun retry(
        regId: String?,
        uploadedRegId: String?,
        enabled: Boolean
    ): PendingPushUploadResult {
        if (!hasRequiredConsent() || !enabled || regId == null || uploadedRegId == regId) {
            return PendingPushUploadResult.SKIPPED
        }
        if (!runCatching { upload(regId) }.getOrDefault(false)) {
            return PendingPushUploadResult.DEFERRED
        }
        markUploaded(regId)
        return PendingPushUploadResult.UPLOADED
    }
}

internal class OppoPushConsentGate(
    private val hasRequiredConsent: () -> Boolean
) {
    fun isGranted(): Boolean = hasRequiredConsent()

    fun runIfGranted(action: () -> Unit): Boolean {
        if (!isGranted()) return false
        action()
        return true
    }
}
