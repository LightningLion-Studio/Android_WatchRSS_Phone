package com.lightningstudio.watchrss.phone.account

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore

class EncryptedAccountSessionStore(
    context: Context,
    private val prefsName: String = "watchrss_account_session"
) {
    private val appContext = context.applicationContext
    // Preference transactions do not suspend. The same lock also serves synchronous
    // HTTP response observers, so a refusal is durable before a response escapes.
    private val mutex = preferenceLocks.getOrPut("${appContext.filesDir}/$prefsName") { ReentrantLock() }
    private var prefsRef: SharedPreferences? = null
    private val _session = MutableStateFlow<PhoneAccountSession?>(null)
    val session: StateFlow<PhoneAccountSession?> = _session
    private val _reauthenticationOwner = MutableStateFlow<String?>(null)
    val reauthenticationOwner: StateFlow<String?> = _reauthenticationOwner

    // Session publication, B2 revocation, logout entry and purchase dispatch share this lock.
    // Preference transactions and handoff HTTP calls run outside it.
    private val sessionLock = Any()
    @Volatile internal var b2AccountFence = B2AccountFence()
        private set

    internal fun revokeB2Authorization(owner: String? = null): Unit = synchronized(sessionLock) {
        if (owner == null || _session.value?.userId == owner) {
            b2AccountFence.revoke()
            b2AccountFence = B2AccountFence()
        }
    }

    suspend fun beginSameAccountReauthentication() = withContext(Dispatchers.IO) {
        withPrefs { prefs ->
            val current = _session.value ?: return@withPrefs
            // Pause is separate from the server's expiry; logout still revokes a server-valid token.
            check(prefs.edit().putString("reauthentication_owner", current.userId).commit())
            publishSession(current.copy(authenticationPaused = true), current.userId)
        }
    }

    suspend fun finishReauthentication() = withContext(Dispatchers.IO) {
        withPrefs {
            check(it.edit().remove("reauthentication_owner").commit())
            publishSession(_session.value?.copy(authenticationPaused = false), null)
        }
    }

    private val purchaseGeneration = MutableStateFlow(0L)
    private var logoutsInProgress = 0

    internal class PurchaseSession(
        val session: PhoneAccountSession?,
        val generation: Long,
        val authenticated: Boolean
    )

    internal fun beginLogout(): PhoneAccountSession? = synchronized(sessionLock) {
        logoutsInProgress++
        purchaseGeneration.value++
        _session.value
    }

    internal fun finishLogout() {
        synchronized(sessionLock) { logoutsInProgress-- }
    }

    internal fun capturePurchaseSession(
        expected: PhoneAccountSession?,
        authenticated: Boolean
    ): PurchaseSession? = synchronized(sessionLock) {
        val current = _session.value
        if (logoutsInProgress > 0 || !current.hasSamePurchaseCredentials(expected) ||
            current.isUsableForPurchase() != authenticated
        ) return@synchronized null
        PurchaseSession(current, purchaseGeneration.value, authenticated)
    }

    internal fun <T> withCurrentPurchaseSession(snapshot: PurchaseSession, action: () -> T): T? =
        synchronized(sessionLock) {
            val current = _session.value
            if (logoutsInProgress > 0 || purchaseGeneration.value != snapshot.generation ||
                !current.hasSamePurchaseCredentials(snapshot.session) ||
                current.isUsableForPurchase() != snapshot.authenticated
            ) return@synchronized null
            action()
        }

    internal suspend fun awaitPurchaseSessionChange(snapshot: PurchaseSession) {
        purchaseGeneration.first { it != snapshot.generation }
    }

    private fun publishSession(
        next: PhoneAccountSession?,
        reauthenticationOwner: String? = _reauthenticationOwner.value
    ) = synchronized(sessionLock) {
        val previous = _session.value
        val changed = !previous.hasSamePurchaseCredentials(next) || previous?.expiresAtMillis != next?.expiresAtMillis ||
            previous?.authenticationPaused != next?.authenticationPaused
        if (changed) {
            b2AccountFence.revoke()
            purchaseGeneration.value++
        }
        _session.value = next
        if (changed) b2AccountFence = B2AccountFence()
        _reauthenticationOwner.value = reauthenticationOwner
    }

    private fun PhoneAccountSession?.hasSamePurchaseCredentials(other: PhoneAccountSession?) =
        this?.userId == other?.userId && this?.accessToken == other?.accessToken

    private fun PhoneAccountSession?.isUsableForPurchase() =
        this != null && isUsableForAuthentication && userId.isNotBlank() && accessToken.isNotBlank()

    suspend fun load(): PhoneAccountSession? = withContext(Dispatchers.IO) {
        withPrefs { prefs ->
            val owner = prefs.getString("reauthentication_owner", null)
            prefs.getString(KEY_SESSION, null)
                ?.let(::decodeSession)
                ?.copy(authenticationPaused = owner != null)
                .also { publishSession(it, owner) }
        }
    }

    suspend fun save(session: PhoneAccountSession) = withContext(Dispatchers.IO) {
        withPrefs { prefs ->
            prefs.edit().putString(KEY_SESSION, encodeSession(session)).apply()
            publishSession(session.copy(authenticationPaused = _reauthenticationOwner.value != null))
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        withPrefs { prefs ->
            prefs.edit().apply {remove(KEY_SESSION);remove("reauthentication_owner");prefs.all.keys.filter{it.startsWith("ble_grant:")}.forEach{remove(it)}}.apply()
            publishSession(null, null)
        }
    }

    internal suspend fun bluetoothGrant(key:String):JSONObject?=withContext(Dispatchers.IO) {
        withPrefs {it.getString("ble_grant:$key",null)?.let {raw->runCatching{JSONObject(raw)}.getOrNull()}}
    }
    internal suspend fun removeBluetoothGrant(key:String)=withContext(Dispatchers.IO) {
        withPrefs { prefs -> check(prefs.edit().remove("ble_grant:$key")
            .putLong("ble_grant_version:$key", grantVersion(prefs, key) + 1).commit()) }
    }
    internal suspend fun saveBluetoothGrant(key:String,value:JSONObject)=withContext(Dispatchers.IO) {
        withPrefs { prefs ->
            val raw = value.toString()
            if (prefs.getString("ble_grant:$key", null) != raw) check(prefs.edit()
                .putString("ble_grant:$key", raw)
                .putLong("ble_grant_version:$key", grantVersion(prefs, key) + 1).commit())
        }
    }
    internal suspend fun clearBluetoothGrants()=withContext(Dispatchers.IO) {
        withPrefs { prefs -> check(prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith("ble_grant:") }.forEach {
                remove(it)
                val key = it.removePrefix("ble_grant:")
                putLong("ble_grant_version:$key", grantVersion(prefs, key) + 1)
            }
        }.commit()) }
    }

    /** The refusal generation and grant revision are captured in the same transaction as the bytes. */
    internal data class BluetoothGrantSnapshot(
        val key: String, val scope: String, val authorizationVersion: Long, val grantVersion: Long, val raw: String
    )

    internal suspend fun bluetoothGrantSnapshot(key: String, scope: String): BluetoothGrantSnapshot? = withContext(Dispatchers.IO) {
        withPrefs { prefs ->
            if (prefs.getBoolean("ble_auth_rejected:$scope", false)) return@withPrefs null
            val version = prefs.getLong("ble_auth_version:$scope", 0L)
            val revision = grantVersion(prefs, key)
            val raw = prefs.getString("ble_grant:$key", null) ?: return@withPrefs null
            BluetoothGrantSnapshot(key, scope, version, revision, raw)
        }
    }

    internal fun isCurrentBluetoothGrant(snapshot: BluetoothGrantSnapshot): Boolean = withPrefs { prefs ->
        !prefs.getBoolean("ble_auth_rejected:${snapshot.scope}", false) &&
            prefs.getLong("ble_auth_version:${snapshot.scope}", 0L) == snapshot.authorizationVersion &&
            grantVersion(prefs, snapshot.key) == snapshot.grantVersion &&
            prefs.getString("ble_grant:${snapshot.key}", null) == snapshot.raw
    }

    /** Serialize the final check AND frame write against refusal/deletion/replacement.
     * No suspension or response read is allowed inside write. Transport IO must stay outside
     * withPrefs' crypto recovery catch, which must never retry a partially written frame.
     */
    internal fun withCurrentBluetoothGrant(snapshot: BluetoothGrantSnapshot, write: () -> Unit) = mutex.withLock {
        if (!isCurrentBluetoothGrant(snapshot)) throw PhoneAccountNetworkRequired(
            IllegalStateException("离线账号授权缓存已失效")
        )
        write()
    }

    private fun grantVersion(prefs: SharedPreferences, key: String) = prefs.getLong("ble_grant_version:$key", 0L)

    internal fun offlineAuthorizationVersion(scope: String): Long = withPrefs {
        it.getLong("ble_auth_version:$scope", 0L)
    }

    internal fun offlineAuthorizationBlocked(scope: String): Boolean = withPrefs {
        it.getBoolean("ble_auth_rejected:$scope", false)
    }

    internal fun rejectOfflineAuthorization(scope: String, owner: String, origin: String) {
        // Closing the dedicated socket must not wait for preference I/O.
        revokeB2Authorization(owner)
        withPrefs { prefs ->
            val edit = prefs.edit().putBoolean("ble_auth_rejected:$scope", true)
                .putLong("ble_auth_version:$scope", prefs.getLong("ble_auth_version:$scope", 0L) + 1)
            for ((key, raw) in prefs.all) {
                if (!key.startsWith("ble_grant:") || raw !is String) continue
                val account = runCatching { JSONObject(raw).optJSONObject("account") }.getOrNull() ?: continue
                if (account.optString("userId") == owner && account.optString("backendBaseUrl") == origin) {
                    val grantKey = key.removePrefix("ble_grant:")
                    edit.remove(key).remove("$key:peer")
                        .putLong("ble_grant_version:$grantKey", grantVersion(prefs, grantKey) + 1)
                }
            }
            check(edit.commit()) { "离线授权失效状态保存失败" }
        }
    }

    internal fun confirmOnlineAuthorization(scope: String, version: Long) = withPrefs { prefs ->
        if (prefs.getLong("ble_auth_version:$scope", 0L) == version) {
            check(prefs.edit().remove("ble_auth_rejected:$scope").commit()) { "在线验证状态保存失败" }
        }
    }

    internal suspend fun saveAuthorizedBluetoothGrant(key: String, scope: String, version: Long, value: JSONObject) = withContext(Dispatchers.IO) {
        withPrefs { prefs ->
            if (prefs.getBoolean("ble_auth_rejected:$scope", false) || prefs.getLong("ble_auth_version:$scope", 0L) != version) {
                throw PhoneDeviceVerificationRequired()
            }
            val raw = value.toString()
            val revision = grantVersion(prefs, key) + 1
            check(prefs.edit().putString("ble_grant:$key", raw)
                .putLong("ble_grant_version:$key", revision).commit())
            BluetoothGrantSnapshot(key, scope, version, revision, raw)
        }
    }

    suspend fun queueRevocation(session: PhoneAccountSession) = withContext(Dispatchers.IO) {
        withPrefs { prefs ->
            val existing = prefs.getString(KEY_PENDING_REVOCATION, null)?.let(::decodeSession)
            check(existing == null || existing.accessToken == session.accessToken) {
                "已有待撤销会话"
            }
            prefs.edit().putString(KEY_PENDING_REVOCATION, encodeSession(session)).commit()
        }.also { saved -> check(saved) { "退出登录撤销任务保存失败" } }
    }

    suspend fun pendingRevocation(): PhoneAccountSession? = withContext(Dispatchers.IO) {
        withPrefs { prefs ->
            prefs.getString(KEY_PENDING_REVOCATION, null)?.let(::decodeSession)
        }
    }

    suspend fun clearPendingRevocation() = withContext(Dispatchers.IO) {
        withPrefs { prefs -> prefs.edit().remove(KEY_PENDING_REVOCATION).apply() }
    }

    fun schedulePendingRevocation() {
        SessionRevocationWorker.schedule(appContext)
    }

    private fun <T> withPrefs(block: (SharedPreferences) -> T): T {
        return mutex.withLock {
            try {
                block(ensurePrefsLocked())
            } catch (error: Exception) {
                if (!isRecoverableCryptoFailure(error)) throw error
                Log.w(TAG, "Encrypted account prefs failed, resetting secure storage", error)
                resetSecureStorageLocked()
                block(ensurePrefsLocked())
            }
        }
    }

    private fun ensurePrefsLocked(): SharedPreferences {
        prefsRef?.let { return it }
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            prefsName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ).also { prefsRef = it }
    }

    private fun resetSecureStorageLocked() {
        revokeB2Authorization()
        prefsRef = null
        runCatching { appContext.deleteSharedPreferences(prefsName) }
        runCatching {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (keyStore.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            }
        }
    }

    private fun isRecoverableCryptoFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is GeneralSecurityException || current is IOException) return true
            val name = current.javaClass.name
            val message = current.message.orEmpty()
            if (
                name == "android.security.KeyStoreException" ||
                message.contains("Signature/MAC verification failed", ignoreCase = true) ||
                message.contains("keystore", ignoreCase = true) ||
                message.contains("aeadbadtagexception", ignoreCase = true) ||
                message.contains("decryption failed", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun encodeSession(session: PhoneAccountSession): String =
        JSONObject().apply {
            put("userId", session.userId)
            put("phoneMasked", session.phoneMasked)
            put("accessToken", session.accessToken)
            put("refreshToken", session.refreshToken)
            put("expiresAtMillis", session.expiresAtMillis)
            put("activationProof", session.activationProof)
            put("updatedAtMillis", session.updatedAtMillis)
        }.toString()

    private fun decodeSession(raw: String): PhoneAccountSession? =
        runCatching {
            val json = JSONObject(raw)
            PhoneAccountSession(
                userId = json.optString("userId").trim(),
                phoneMasked = json.optString("phoneMasked").trim(),
                accessToken = json.optString("accessToken").trim(),
                refreshToken = json.optString("refreshToken").trim(),
                expiresAtMillis = json.optLong("expiresAtMillis"),
                activationProof = json.optString("activationProof"),
                updatedAtMillis = json.optLong("updatedAtMillis")
            ).takeIf { it.userId.isNotBlank() && it.accessToken.isNotBlank() }
        }.getOrNull()

    private companion object {
        private val preferenceLocks = ConcurrentHashMap<String, ReentrantLock>()
        private const val TAG = "WatchRSSAccountStore"
        private const val KEY_SESSION = "session_json"
        private const val KEY_PENDING_REVOCATION = "pending_revocation_json"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    }
}
