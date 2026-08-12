package com.lightningstudio.watchrss.phone.account

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class LicenseDeviceIdentity(context: Context) {
    private val appContext = context.applicationContext

    val deviceId: String by lazy {
        val androidId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            .orEmpty()
        val packageInfo = appContext.packageManager.getPackageInfo(
            appContext.packageName,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else 0
        )
        val certificate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray() ?: byteArrayOf()
        } else {
            @Suppress("DEPRECATION") packageInfo.signatures?.firstOrNull()?.toByteArray() ?: byteArrayOf()
        }
        sha256("$androidId|${appContext.packageName}|${sha256(certificate)}".toByteArray())
    }

    val publicKeyPem: String
        get() {
            val encoded = keyPair().public.encoded
            return "-----BEGIN PUBLIC KEY-----\n" +
                Base64.encodeToString(encoded, Base64.NO_WRAP).chunked(64).joinToString("\n") +
                "\n-----END PUBLIC KEY-----"
        }

    fun sign(payload: ByteArray): String {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(keyPair().private)
        signature.update(payload)
        return Base64.encodeToString(signature.sign(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    internal fun possessionProof(
        purpose: String,
        challenge: DevicePossessionChallenge,
        requestHash: String
    ): DevicePossessionProof {
        val message = devicePossessionMessage(
            purpose = purpose,
            challengeId = challenge.challengeId,
            nonce = challenge.nonce,
            licenseDeviceId = deviceId,
            requestHash = requestHash
        )
        return DevicePossessionProof(
            challengeId = challenge.challengeId,
            nonce = challenge.nonce,
            signature = sign(message.toByteArray(StandardCharsets.UTF_8))
        )
    }

    private fun keyPair(): java.security.KeyPair {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val certificate = keyStore.getCertificate(KEY_ALIAS)
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as? java.security.PrivateKey
        if (certificate != null && privateKey != null) return java.security.KeyPair(certificate.publicKey, privateKey)
        return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").run {
            initialize(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            generateKeyPair()
        }
    }

    private fun sha256(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

    private companion object { const val KEY_ALIAS = "watchrss_phone_license_device_v1" }
}

internal data class DevicePossessionChallenge(
    val challengeId: String,
    val nonce: String,
    val expiresAtMillis: Long
)

internal data class DevicePossessionProof(
    val challengeId: String,
    val nonce: String,
    val signature: String
)

internal fun devicePossessionMessage(
    purpose: String,
    challengeId: String,
    nonce: String,
    licenseDeviceId: String,
    requestHash: String
): String = "watchrss-device-possession-v1\n$purpose\n$challengeId\n$nonce\n$licenseDeviceId\n$requestHash"

internal fun devicePossessionRequestHash(vararg fields: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fields.forEach { field ->
        val bytes = field.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(bytes.size.toLong()).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
