package com.lightningstudio.watchrss.phone.account

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val mutex = Mutex()
    private var prefsRef: SharedPreferences? = null
    private val _session = MutableStateFlow<PhoneAccountSession?>(null)
    val session: StateFlow<PhoneAccountSession?> = _session

    suspend fun load(): PhoneAccountSession? = withContext(Dispatchers.IO) {
        withPrefs { prefs ->
            prefs.getString(KEY_SESSION, null)
                ?.let(::decodeSession)
        }.also { _session.value = it }
    }

    suspend fun save(session: PhoneAccountSession) = withContext(Dispatchers.IO) {
        withPrefs { prefs ->
            prefs.edit().putString(KEY_SESSION, encodeSession(session)).apply()
        }
        _session.value = session
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        withPrefs { prefs ->
            prefs.edit().remove(KEY_SESSION).apply()
        }
        _session.value = null
    }

    private suspend fun <T> withPrefs(block: (SharedPreferences) -> T): T {
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
                updatedAtMillis = json.optLong("updatedAtMillis")
            ).takeIf { it.userId.isNotBlank() && it.accessToken.isNotBlank() }
        }.getOrNull()

    private companion object {
        private const val TAG = "WatchRSSAccountStore"
        private const val KEY_SESSION = "session_json"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    }
}

