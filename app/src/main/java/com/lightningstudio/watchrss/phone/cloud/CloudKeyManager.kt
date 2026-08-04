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
    context: Context,
    private val storageSuffix: String = ""
) {
    private val appContext = context.applicationContext
    private val preferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "watchrss_cloud_keys$storageSuffix",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun hasAccountKey(userId: String): Boolean =
        keyVersions(userId).isNotEmpty()

    fun getAccountKey(userId: String): ByteArray? =
        getAccountKey(userId, currentKeyVersion(userId))
            ?: preferences.getString(legacyAccountKeyName(userId), null)
                ?.base64Bytes()
                ?.also { saveAccountKey(userId, 1, it, makeCurrent = true) }

    fun getAccountKey(userId: String, keyVersion: Int): ByteArray? =
        preferences.getString(accountKeyName(userId, keyVersion), null)?.base64Bytes()
            ?: if (keyVersion == 1) {
                preferences.getString(legacyAccountKeyName(userId), null)
                    ?.base64Bytes()
                    ?.also { saveAccountKey(userId, 1, it, makeCurrent = true) }
            } else {
                null
            }

    fun currentKeyVersion(userId: String): Int =
        preferences.getInt(currentVersionName(userId), 0)
            .takeIf { it > 0 }
            ?: keyVersions(userId).maxOrNull()
            ?: 1

    fun keyVersions(userId: String): List<Int> {
        val prefix = accountKeyPrefix(userId)
        val versions = preferences.all.keys.mapNotNull { key ->
            key.removePrefix(prefix).takeIf { key.startsWith(prefix) }?.toIntOrNull()
        }.sorted()
        if (versions.isNotEmpty()) return versions
        return if (preferences.contains(legacyAccountKeyName(userId))) listOf(1) else emptyList()
    }

    fun getOrCreateAccountKey(userId: String): ByteArray {
        getAccountKey(userId)?.let { return it }
        return ByteArray(32).also(SecureRandom()::nextBytes)
            .also { saveAccountKey(userId, 1, it, makeCurrent = true) }
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

    fun createRecoverySetup(
        userId: String,
        accountKey: ByteArray = getOrCreateAccountKey(userId),
        keyVersion: Int = currentKeyVersion(userId)
    ): RecoveryKeySetup {
        val generated = RecoveryWords.generate()
        return RecoveryKeySetup(
            words = generated.words,
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
        envelopes: List<CloudKeyEnvelope>
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
            recovered.forEach { (version, key) ->
                saveAccountKey(userId, version, key, makeCurrent = false)
            }
            val (currentVersion, currentKey) = recovered.maxBy { it.first }
            saveAccountKey(userId, currentVersion, currentKey, makeCurrent = true)
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
        envelope: CloudKeyEnvelope
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
            saveAccountKey(
                userId,
                envelope.keyVersion,
                it,
                makeCurrent = envelope.keyVersion >= currentKeyVersion(userId)
            )
        }
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
        "account_key_${CloudSnapshotCodec.sha256(userId.toByteArray()).take(24)}_v"

    private fun accountKeyName(userId: String, keyVersion: Int): String =
        accountKeyPrefix(userId) + keyVersion

    private fun currentVersionName(userId: String): String =
        "account_key_current_${CloudSnapshotCodec.sha256(userId.toByteArray()).take(24)}"

    private fun legacyAccountKeyName(userId: String): String =
        "account_key_${CloudSnapshotCodec.sha256(userId.toByteArray()).take(24)}"

    private fun deviceAlias(userId: String, deviceId: String): String =
        "watchrss_cloud_ecdh${storageSuffix}_${
            CloudSnapshotCodec.sha256("$userId:$deviceId".toByteArray()).take(24)
        }"

    private companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
