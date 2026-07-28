package com.lightningstudio.watchrss.phone.cloud

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement

data class CloudKeyEnvelope(
    val algorithm: String,
    val keyVersion: Int,
    val wrappedKeyBase64: String,
    val nonceBase64: String,
    val ephemeralPublicKeySpki: String? = null
)

data class RecoveryKeySetup(
    val words: List<String>,
    val envelope: CloudKeyEnvelope
)

object CloudKeyEnvelopeCodec {
    const val RECOVERY_ALGORITHM = "HKDF-SHA256+A256GCM"
    const val DEVICE_ALGORITHM = "P-256+HKDF-SHA256+A256GCM"

    fun createRecoveryEnvelope(
        accountKey: ByteArray,
        userId: String,
        keyVersion: Int,
        recoveryEntropy: ByteArray,
        random: SecureRandom = SecureRandom()
    ): CloudKeyEnvelope {
        validateAccountKey(accountKey)
        val nonce = ByteArray(12).also(random::nextBytes)
        val wrappingKey = deriveRecoveryKey(recoveryEntropy, userId, keyVersion)
        val wrapped = CloudSnapshotCodec.encryptAesGcm(
            key = wrappingKey,
            nonce = nonce,
            plaintext = accountKey,
            aad = envelopeAad(userId, keyVersion, "recovery")
        )
        return CloudKeyEnvelope(
            algorithm = RECOVERY_ALGORITHM,
            keyVersion = keyVersion,
            wrappedKeyBase64 = wrapped.base64(),
            nonceBase64 = nonce.base64()
        )
    }

    fun unwrapRecoveryEnvelope(
        envelope: CloudKeyEnvelope,
        userId: String,
        recoveryEntropy: ByteArray
    ): ByteArray {
        require(envelope.algorithm == RECOVERY_ALGORITHM) { "恢复密钥信封算法不受支持" }
        return CloudSnapshotCodec.decryptAesGcm(
            key = deriveRecoveryKey(recoveryEntropy, userId, envelope.keyVersion),
            nonce = envelope.nonceBase64.base64Bytes(),
            ciphertext = envelope.wrappedKeyBase64.base64Bytes(),
            aad = envelopeAad(userId, envelope.keyVersion, "recovery")
        ).also(::validateAccountKey)
    }

    fun createDeviceEnvelope(
        accountKey: ByteArray,
        userId: String,
        recipientDeviceId: String,
        recipientPublicKey: PublicKey,
        keyVersion: Int,
        random: SecureRandom = SecureRandom()
    ): CloudKeyEnvelope {
        validateAccountKey(accountKey)
        val ephemeral = generateP256KeyPair()
        val sharedSecret = agree(ephemeral.private, recipientPublicKey)
        val nonce = ByteArray(12).also(random::nextBytes)
        val wrapped = CloudSnapshotCodec.encryptAesGcm(
            key = deriveDeviceKey(sharedSecret, userId, recipientDeviceId, keyVersion),
            nonce = nonce,
            plaintext = accountKey,
            aad = envelopeAad(userId, keyVersion, recipientDeviceId)
        )
        sharedSecret.fill(0)
        return CloudKeyEnvelope(
            algorithm = DEVICE_ALGORITHM,
            keyVersion = keyVersion,
            wrappedKeyBase64 = wrapped.base64(),
            nonceBase64 = nonce.base64(),
            ephemeralPublicKeySpki = ephemeral.public.encoded.base64()
        )
    }

    fun unwrapDeviceEnvelope(
        envelope: CloudKeyEnvelope,
        userId: String,
        recipientDeviceId: String,
        recipientPrivateKey: PrivateKey
    ): ByteArray {
        require(envelope.algorithm == DEVICE_ALGORITHM) { "设备密钥信封算法不受支持" }
        val ephemeralPublicKey = decodeP256PublicKey(
            requireNotNull(envelope.ephemeralPublicKeySpki) { "设备密钥信封缺少临时公钥" }
        )
        val sharedSecret = agree(recipientPrivateKey, ephemeralPublicKey)
        return try {
            CloudSnapshotCodec.decryptAesGcm(
                key = deriveDeviceKey(
                    sharedSecret,
                    userId,
                    recipientDeviceId,
                    envelope.keyVersion
                ),
                nonce = envelope.nonceBase64.base64Bytes(),
                ciphertext = envelope.wrappedKeyBase64.base64Bytes(),
                aad = envelopeAad(userId, envelope.keyVersion, recipientDeviceId)
            ).also(::validateAccountKey)
        } finally {
            sharedSecret.fill(0)
        }
    }

    fun decodeP256PublicKey(spkiBase64: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(spkiBase64.base64Bytes())
        )

    fun generateP256KeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    private fun agree(privateKey: PrivateKey, publicKey: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(publicKey, true)
            generateSecret()
        }

    private fun deriveRecoveryKey(entropy: ByteArray, userId: String, keyVersion: Int): ByteArray {
        require(entropy.size == 32) { "恢复密钥熵必须为256位" }
        return CloudSnapshotCodec.hkdfSha256(
            inputKey = entropy,
            salt = CloudSnapshotCodec.sha256(userId.toByteArray()).hexBytes(),
            info = "watchrss/recovery-envelope/v$keyVersion".toByteArray(),
            length = 32
        )
    }

    private fun deriveDeviceKey(
        sharedSecret: ByteArray,
        userId: String,
        deviceId: String,
        keyVersion: Int
    ): ByteArray = CloudSnapshotCodec.hkdfSha256(
        inputKey = sharedSecret,
        salt = CloudSnapshotCodec.sha256(userId.toByteArray()).hexBytes(),
        info = "watchrss/device-envelope/v$keyVersion/$deviceId".toByteArray(),
        length = 32
    )

    private fun envelopeAad(userId: String, keyVersion: Int, recipient: String): ByteArray =
        "watchrss-account-key:$userId:$keyVersion:$recipient".toByteArray()

    private fun validateAccountKey(key: ByteArray) {
        require(key.size == 32) { "账号主密钥必须为256位" }
    }
}

internal fun ByteArray.base64(): String =
    Base64.getEncoder().withoutPadding().encodeToString(this)

internal fun String.base64Bytes(): ByteArray = Base64.getDecoder().decode(this)

private fun String.hexBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
