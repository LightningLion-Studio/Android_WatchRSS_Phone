package com.lightningstudio.watchrss.phone.cloud

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec

class CloudKeyManager(
    context: Context?,
    private val storageSuffix: String = "",
    private val preferencesOverride: android.content.SharedPreferences? = null
) {
    private val appContext = context?.applicationContext
    private var backendScope: String = ""
    fun bindBackend(baseUrl: String) { backendScope = CloudSnapshotCodec.sha256(baseUrl.trimEnd('/').toByteArray()).take(24) }
    private val loadedPreferences by lazy {
        preferencesOverride ?: run {
        val appContext = requireNotNull(appContext)
        fun create() = EncryptedSharedPreferences.create(appContext, "watchrss_cloud_keys$storageSuffix",
            MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        // Reset only this XML; never remove the shared AndroidX master key.
        recoverCloudKeyPreferences(::create) { appContext.deleteSharedPreferences("watchrss_cloud_keys$storageSuffix") }
        }
    }
    private val preferences get() = requireNotNull(loadedPreferences) { "云密钥存储不可用，请恢复或重新批准" }
    fun requireStorageReady() { requireNotNull(loadedPreferences) { "云密钥存储不可用，请修复系统密钥库后重试" } }

    @Synchronized
    private fun migrateLegacy(userId: String) {
        if (loadedPreferences == null || backendScope.isBlank()) return
        val claimed = preferences.getString("legacy_backend_scope", null)
        if (claimed != null && claimed != backendScope) return
        val oldHash = CloudSnapshotCodec.sha256(userId.toByteArray()).take(24)
        val newHash = CloudSnapshotCodec.sha256("$backendScope:$userId".toByteArray()).take(24)
        val oldPrefix = "account_key_${oldHash}"
        val editor = preferences.edit().putString("legacy_backend_scope", backendScope)
        preferences.all.forEach { (name, value) ->
            if (name == oldPrefix || name.startsWith("${oldPrefix}_v") || name == "account_key_current_$oldHash") {
                val newName = name.replace(oldHash, newHash)
                if (!preferences.contains(newName)) when (value) {
                    is String -> editor.putString(newName, value)
                    is Int -> editor.putInt(newName, value)
                }
                editor.remove(name)
            }
        }
        check(editor.commit())
    }
    fun hasAccountKey(userId: String): Boolean =
        keyVersions(userId).isNotEmpty()

    fun getAccountKey(userId: String): ByteArray? = getAccountKey(userId, currentKeyVersion(userId))

    fun getAccountKey(userId: String, keyVersion: Int): ByteArray? {
        if (loadedPreferences == null) return null
        migrateLegacy(userId)
        return preferences.getString(accountKeyName(userId, keyVersion), null)?.base64Bytes()
            ?: if (keyVersion == 1) {
                preferences.getString(legacyAccountKeyName(userId), null)
                    ?.base64Bytes()
                    ?.also { saveAccountKey(userId, 1, it, makeCurrent = true) }
            } else {
                null
            }
    }

    fun currentKeyVersion(userId: String): Int {
        if (loadedPreferences == null) return 1
        migrateLegacy(userId)
        return preferences.getInt(currentVersionName(userId), 0).takeIf { it > 0 } ?: keyVersions(userId).maxOrNull() ?: 1
    }

    fun keyVersions(userId: String): List<Int> {
        if (loadedPreferences == null) return emptyList()
        migrateLegacy(userId)
        val prefix = accountKeyPrefix(userId)
        val versions = preferences.all.keys.mapNotNull { key ->
            key.removePrefix(prefix).takeIf { key.startsWith(prefix) }?.toIntOrNull()
        }.sorted()
        if (versions.isNotEmpty()) return versions
        return if (preferences.contains(legacyAccountKeyName(userId))) listOf(1) else emptyList()
    }

    fun saveAccountKey(
        userId: String,
        keyVersion: Int,
        accountKey: ByteArray,
        makeCurrent: Boolean
    ) {
        require(accountKey.size == 32) { "账号主密钥必须为256位" }
        require(keyVersion > 0) { "账号密钥版本无效" }
        val editor = preferences.edit()
            .putString(accountKeyName(userId, keyVersion), accountKey.base64())
        if (makeCurrent) editor.putInt(currentVersionName(userId), keyVersion)
        check(editor.commit()) { "账号密钥保存失败" }
    }

    @Synchronized
    internal fun saveVerifiedKeys(userId: String, keys: Map<Int, ByteArray>) {
        require(keys.isNotEmpty() && keys.all { it.key > 0 && it.value.size == 32 })
        replaceVerifiedKeys(userId, keys)
    }

    @Synchronized
    internal fun replaceVerifiedKeys(userId: String, keys: Map<Int, ByteArray>) {
        require(keys.all { it.key > 0 && it.value.size == 32 })
        migrateLegacy(userId)
        val editor = preferences.edit().remove(legacyAccountKeyName(userId)).remove(currentVersionName(userId))
        preferences.all.keys.filter { it.startsWith(accountKeyPrefix(userId)) }.forEach(editor::remove)
        keys.forEach { (v, key) -> editor.putString(accountKeyName(userId, v), key.base64()) }
        if (keys.isNotEmpty()) editor.putInt(currentVersionName(userId), keys.keys.max())
        check(editor.commit()) { "账号密钥保存失败" }
    }

    @Synchronized
    fun clearAccountKeys(userId: String) {
        if (loadedPreferences == null) return
        migrateLegacy(userId)
        val prefix = accountKeyPrefix(userId)
        val editor = preferences.edit()
            .remove(currentVersionName(userId))
            .remove(legacyAccountKeyName(userId))
        preferences.all.keys
            .filter { it.startsWith(prefix) }
            .forEach(editor::remove)
        check(editor.commit()) { "本机云端账号密钥清理失败" }
    }

    fun createRecoverySetup(
        userId: String,
        accountKey: ByteArray = ByteArray(32).also(SecureRandom()::nextBytes),
        keyVersion: Int = currentKeyVersion(userId)
    ): RecoveryKeySetup {
        requireStorageReady()
        val generated = RecoveryWords.generate()
        return RecoveryKeySetup(
            words = generated.words,
            pendingKey = accountKey.copyOf(),
            envelope = CloudKeyEnvelopeCodec.createRecoveryEnvelope(
                accountKey = accountKey,
                userId = userId,
                keyVersion = keyVersion,
                recoveryEntropy = generated.entropy
            )
        ).also { generated.entropy.fill(0) }
    }

    fun recoverWithWords(
        userId: String,
        words: List<String>,
        envelopes: List<CloudKeyEnvelope>,
        validate: (Map<Int, ByteArray>) -> Unit
    ): ByteArray {
        require(envelopes.isNotEmpty()) { "账号没有恢复密钥信封" }
        val entropy = RecoveryWords.decode(words)
        return try {
            val recovered = envelopes.sortedBy(CloudKeyEnvelope::keyVersion).map { envelope ->
                envelope.keyVersion to CloudKeyEnvelopeCodec.unwrapRecoveryEnvelope(
                    envelope,
                    userId,
                    entropy
                )
            }
            validate(recovered.toMap())
            saveVerifiedKeys(userId, recovered.toMap())
            val currentKey = recovered.maxBy { it.first }.second
            currentKey
        } finally {
            entropy.fill(0)
        }
    }

    fun devicePublicKeySpki(userId: String, deviceId: String): String =
        getOrCreateDeviceKey(userId, deviceId).public.encoded.base64()

    fun unwrapDeviceEnvelope(
        userId: String,
        deviceId: String,
        envelope: CloudKeyEnvelope,
        persist: Boolean = false
    ): ByteArray {
        val privateKey = requireNotNull(
            keyStore().getKey(deviceAlias(userId, deviceId), null) as? PrivateKey
        ) { "当前设备没有云端设备私钥" }
        return CloudKeyEnvelopeCodec.unwrapDeviceEnvelope(
            envelope = envelope,
            userId = userId,
            recipientDeviceId = deviceId,
            recipientPrivateKey = privateKey
        ).also {
            if (persist) saveAccountKey(
                userId,
                envelope.keyVersion,
                it,
                makeCurrent = envelope.keyVersion >= currentKeyVersion(userId)
            )
        }
    }

    fun clearDeviceKey(userId: String, deviceId: String) {
        // Only the cloud device alias belonging to this installation and account.
        val alias = deviceAlias(userId, deviceId)
        keyStore().deleteEntry(alias)
    }

    private fun getOrCreateDeviceKey(userId: String, deviceId: String): java.security.KeyPair {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "Android 12及以上系统才支持硬件保护的云端设备密钥"
        }
        val alias = deviceAlias(userId, deviceId)
        val store = keyStore()
        val existingPrivate = store.getKey(alias, null) as? PrivateKey
        val existingPublic = store.getCertificate(alias)?.publicKey
        if (existingPrivate != null && existingPublic != null) {
            return java.security.KeyPair(existingPublic, existingPrivate)
        }
        return KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE
        ).run {
            initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            generateKeyPair()
        }
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun accountKeyPrefix(userId: String): String =
        "account_key_${CloudSnapshotCodec.sha256("$backendScope:$userId".toByteArray()).take(24)}_v"

    private fun accountKeyName(userId: String, keyVersion: Int): String =
        accountKeyPrefix(userId) + keyVersion

    private fun currentVersionName(userId: String): String =
        "account_key_current_${CloudSnapshotCodec.sha256("$backendScope:$userId".toByteArray()).take(24)}"

    private fun legacyAccountKeyName(userId: String): String =
        "account_key_${CloudSnapshotCodec.sha256("$backendScope:$userId".toByteArray()).take(24)}"

    private fun deviceAlias(userId: String, deviceId: String): String {
        val old = "watchrss_cloud_ecdh${storageSuffix}_${CloudSnapshotCodec.sha256("$userId:$deviceId".toByteArray()).take(24)}"
        if (loadedPreferences?.getString("legacy_backend_scope", null) == backendScope && keyStore().containsAlias(old)) return old
        return "watchrss_cloud_ecdh${storageSuffix}_${CloudSnapshotCodec.sha256("$backendScope:$userId:$deviceId".toByteArray()).take(24)}"
    }

    private companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

/** Restored encrypted XML may outlive its Keystore. Failure never selects plaintext storage. */
internal fun recoverCloudKeyPreferences(create: () -> android.content.SharedPreferences, resetOwnXml: () -> Unit): android.content.SharedPreferences? {
    return try { create() } catch (_: Exception) {
        runCatching { resetOwnXml() }.getOrElse { return null }
        runCatching { create() }.getOrNull()
    }
}
