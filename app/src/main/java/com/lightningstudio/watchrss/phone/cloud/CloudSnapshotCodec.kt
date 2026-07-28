package com.lightningstudio.watchrss.phone.cloud

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CloudSnapshotCodec(
    private val secureRandom: SecureRandom = SecureRandom()
) {
    fun create(
        accountKey: ByteArray,
        keyVersion: Int,
        sourceDeviceId: String,
        deviceSequence: Long,
        logicalObjects: List<CloudLogicalObject>,
        parentHeads: Map<String, String> = emptyMap(),
        observedHeads: Map<String, Long> = emptyMap(),
        previousManifest: CloudSnapshotManifest? = null,
        carryForwardObjects: List<CloudObjectDescriptor> = emptyList(),
        snapshotId: String = UUID.randomUUID().toString(),
        createdAtMillis: Long = System.currentTimeMillis()
    ): EncryptedCloudSnapshot {
        require(accountKey.size == KEY_BYTES) { "账号主密钥必须为256位" }
        require(keyVersion > 0) { "账号密钥版本无效" }
        require(sourceDeviceId.isNotBlank()) { "设备标识不能为空" }
        require(deviceSequence > 0L) { "设备快照序列无效" }
        val allObjectNames = logicalObjects.map { it.name } + carryForwardObjects.map { it.name }
        require(allObjectNames.distinct().size == allObjectNames.size) {
            "云快照对象名称重复"
        }

        val reusableByPlaintextHash = previousManifest
            ?.allChunks
            ?.associateBy(CloudChunkDescriptor::plaintextSha256)
            .orEmpty()
        val newChunks = linkedMapOf<String, ByteArray>()
        val objectDescriptors = logicalObjects.map { logicalObject ->
            require(logicalObject.name.matches(OBJECT_NAME_PATTERN)) {
                "云快照对象名称无效：${logicalObject.name}"
            }
            val encodedChunks = if (logicalObject.compress) {
                logicalObject.bytes
                    .asListOfChunks(CLOUD_SNAPSHOT_CHUNK_BYTES)
                    .map(::gzip)
            } else {
                logicalObject.bytes.asListOfChunks(CLOUD_SNAPSHOT_CHUNK_BYTES)
            }
            val descriptors = encodedChunks.map { plaintext ->
                val plaintextHash = sha256(plaintext)
                reusableByPlaintextHash[plaintextHash] ?: encryptChunk(
                    accountKey = accountKey,
                    plaintext = plaintext,
                    plaintextHash = plaintextHash
                ).also { encrypted ->
                    newChunks[encrypted.first.ciphertextSha256] = encrypted.second
                }.first
            }
            CloudObjectDescriptor(
                name = logicalObject.name,
                encoding = if (logicalObject.compress) "gzip-chunks-v1" else "identity",
                originalBytes = logicalObject.bytes.size.toLong(),
                encodedBytes = encodedChunks.sumOf { it.size.toLong() },
                chunks = descriptors
            )
        }
        val manifest = CloudSnapshotManifest(
            snapshotId = snapshotId,
            sourceDeviceId = sourceDeviceId,
            deviceSequence = deviceSequence,
            keyVersion = keyVersion,
            createdAtMillis = createdAtMillis,
            parentHeads = parentHeads,
            observedHeads = observedHeads,
            objects = carryForwardObjects + objectDescriptors
        )
        val encryptedManifest = encryptManifest(accountKey, manifest)
        return EncryptedCloudSnapshot(
            manifest = manifest,
            encryptedManifest = encryptedManifest,
            newCiphertextChunks = newChunks
        )
    }

    fun decryptManifest(
        accountKey: ByteArray,
        snapshotId: String,
        encryptedManifest: ByteArray
    ): CloudSnapshotManifest {
        require(accountKey.size == KEY_BYTES) { "账号主密钥必须为256位" }
        require(encryptedManifest.size > MANIFEST_HEADER_BYTES + GCM_TAG_BYTES) {
            "云快照清单损坏"
        }
        require(encryptedManifest.copyOfRange(0, MANIFEST_MAGIC.size).contentEquals(MANIFEST_MAGIC)) {
            "不是腕上RSS加密云快照"
        }
        val saltStart = MANIFEST_MAGIC.size
        val nonceStart = saltStart + SALT_BYTES
        val ciphertextStart = nonceStart + NONCE_BYTES
        val salt = encryptedManifest.copyOfRange(saltStart, nonceStart)
        val nonce = encryptedManifest.copyOfRange(nonceStart, ciphertextStart)
        val ciphertext = encryptedManifest.copyOfRange(ciphertextStart, encryptedManifest.size)
        val key = hkdfSha256(
            inputKey = accountKey,
            salt = salt,
            info = MANIFEST_INFO,
            length = KEY_BYTES
        )
        val plaintext = decryptAesGcm(
            key = key,
            nonce = nonce,
            ciphertext = ciphertext,
            aad = manifestAad(snapshotId)
        )
        return CloudSnapshotManifest.fromJson(JSONObject(plaintext.toString(Charsets.UTF_8))).also {
            require(it.snapshotId == snapshotId) { "云快照标识不匹配" }
        }
    }

    fun restoreObjects(
        accountKey: ByteArray,
        manifest: CloudSnapshotManifest,
        ciphertextChunk: (String) -> ByteArray
    ): Map<String, ByteArray> =
        manifest.objects.associate { descriptor ->
            val encodedChunks = descriptor.chunks.map { chunk ->
                decryptChunk(
                    accountKey = accountKey,
                    descriptor = chunk,
                    blob = ciphertextChunk(chunk.ciphertextSha256)
                )
            }
            require(encodedChunks.sumOf { it.size.toLong() } == descriptor.encodedBytes) {
                "云快照对象长度不匹配：${descriptor.name}"
            }
            val restored = when (descriptor.encoding) {
                "gzip-chunks-v1" -> ByteArrayOutputStream().use { output ->
                    encodedChunks.forEach { output.write(gunzip(it)) }
                    output.toByteArray()
                }
                "gzip" -> gunzip(encodedChunks.join())
                "identity" -> encodedChunks.join()
                else -> error("不支持的云快照编码：${descriptor.encoding}")
            }
            require(restored.size.toLong() == descriptor.originalBytes) {
                "云快照对象恢复长度不匹配：${descriptor.name}"
            }
            descriptor.name to restored
        }

    private fun encryptChunk(
        accountKey: ByteArray,
        plaintext: ByteArray,
        plaintextHash: String
    ): Pair<CloudChunkDescriptor, ByteArray> {
        val salt = randomBytes(SALT_BYTES)
        val nonce = randomBytes(NONCE_BYTES)
        val key = hkdfSha256(
            inputKey = accountKey,
            salt = salt,
            info = CHUNK_INFO,
            length = KEY_BYTES
        )
        val ciphertext = encryptAesGcm(
            key = key,
            nonce = nonce,
            plaintext = plaintext,
            aad = chunkAad(plaintextHash)
        )
        val blob = ByteArrayOutputStream().use { output ->
            output.write(CHUNK_MAGIC)
            output.write(salt)
            output.write(nonce)
            output.write(ciphertext)
            output.toByteArray()
        }
        val descriptor = CloudChunkDescriptor(
            plaintextSha256 = plaintextHash,
            ciphertextSha256 = sha256(blob),
            plaintextBytes = plaintext.size,
            ciphertextBytes = blob.size,
            saltBase64 = salt.toBase64(),
            nonceBase64 = nonce.toBase64()
        )
        return descriptor to blob
    }

    private fun decryptChunk(
        accountKey: ByteArray,
        descriptor: CloudChunkDescriptor,
        blob: ByteArray
    ): ByteArray {
        require(blob.size == descriptor.ciphertextBytes) { "加密块长度不匹配" }
        require(sha256(blob) == descriptor.ciphertextSha256) { "加密块哈希不匹配" }
        require(blob.size > CHUNK_HEADER_BYTES + GCM_TAG_BYTES) { "加密块损坏" }
        require(blob.copyOfRange(0, CHUNK_MAGIC.size).contentEquals(CHUNK_MAGIC)) {
            "加密块格式无效"
        }
        val saltStart = CHUNK_MAGIC.size
        val nonceStart = saltStart + SALT_BYTES
        val ciphertextStart = nonceStart + NONCE_BYTES
        val salt = blob.copyOfRange(saltStart, nonceStart)
        val nonce = blob.copyOfRange(nonceStart, ciphertextStart)
        require(salt.contentEquals(descriptor.saltBase64.fromBase64())) { "加密块盐值不匹配" }
        require(nonce.contentEquals(descriptor.nonceBase64.fromBase64())) { "加密块随机数不匹配" }
        val key = hkdfSha256(accountKey, salt, CHUNK_INFO, KEY_BYTES)
        val plaintext = decryptAesGcm(
            key = key,
            nonce = nonce,
            ciphertext = blob.copyOfRange(ciphertextStart, blob.size),
            aad = chunkAad(descriptor.plaintextSha256)
        )
        require(plaintext.size == descriptor.plaintextBytes) { "加密块明文长度不匹配" }
        require(sha256(plaintext) == descriptor.plaintextSha256) { "加密块明文哈希不匹配" }
        return plaintext
    }

    private fun encryptManifest(
        accountKey: ByteArray,
        manifest: CloudSnapshotManifest
    ): ByteArray {
        val salt = randomBytes(SALT_BYTES)
        val nonce = randomBytes(NONCE_BYTES)
        val key = hkdfSha256(accountKey, salt, MANIFEST_INFO, KEY_BYTES)
        val plaintext = manifest.toJson().toString().toByteArray(Charsets.UTF_8)
        val ciphertext = encryptAesGcm(
            key = key,
            nonce = nonce,
            plaintext = plaintext,
            aad = manifestAad(manifest.snapshotId)
        )
        return ByteArrayOutputStream().use { output ->
            output.write(MANIFEST_MAGIC)
            output.write(salt)
            output.write(nonce)
            output.write(ciphertext)
            output.toByteArray()
        }
    }

    private fun randomBytes(size: Int): ByteArray =
        ByteArray(size).also(secureRandom::nextBytes)

    companion object {
        private const val KEY_BYTES = 32
        private const val SALT_BYTES = 16
        private const val NONCE_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        private val CHUNK_MAGIC = "WRSSCC2".toByteArray(Charsets.US_ASCII)
        private val MANIFEST_MAGIC = "WRSSCM2".toByteArray(Charsets.US_ASCII)
        private val CHUNK_INFO = "watchrss/cloud/chunk/v1".toByteArray(Charsets.UTF_8)
        private val MANIFEST_INFO = "watchrss/cloud/manifest/v1".toByteArray(Charsets.UTF_8)
        private val OBJECT_NAME_PATTERN = Regex("""[a-z0-9][a-z0-9._/-]{0,127}""")
        private val CHUNK_HEADER_BYTES = CHUNK_MAGIC.size + SALT_BYTES + NONCE_BYTES
        private val MANIFEST_HEADER_BYTES = MANIFEST_MAGIC.size + SALT_BYTES + NONCE_BYTES

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }

        fun hkdfSha256(
            inputKey: ByteArray,
            salt: ByteArray,
            info: ByteArray,
            length: Int
        ): ByteArray {
            require(length in 1..(255 * 32)) { "HKDF输出长度无效" }
            val extract = Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(salt, "HmacSHA256"))
            }.doFinal(inputKey)
            val output = ByteArrayOutputStream()
            var previous = ByteArray(0)
            var counter = 1
            while (output.size() < length) {
                val block = Mac.getInstance("HmacSHA256").apply {
                    init(SecretKeySpec(extract, "HmacSHA256"))
                    update(previous)
                    update(info)
                    update(counter.toByte())
                }.doFinal()
                output.write(block)
                previous = block
                counter += 1
            }
            return output.toByteArray().copyOf(length)
        }

        fun encryptAesGcm(
            key: ByteArray,
            nonce: ByteArray,
            plaintext: ByteArray,
            aad: ByteArray
        ): ByteArray =
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
                updateAAD(aad)
                doFinal(plaintext)
            }

        fun decryptAesGcm(
            key: ByteArray,
            nonce: ByteArray,
            ciphertext: ByteArray,
            aad: ByteArray
        ): ByteArray =
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
                updateAAD(aad)
                doFinal(ciphertext)
            }

        private fun chunkAad(plaintextHash: String): ByteArray =
            "watchrss-cloud-chunk-v1:$plaintextHash".toByteArray(Charsets.UTF_8)

        private fun manifestAad(snapshotId: String): ByteArray =
            "watchrss-cloud-manifest-v2:$snapshotId".toByteArray(Charsets.UTF_8)

        private fun gzip(bytes: ByteArray): ByteArray =
            ByteArrayOutputStream().use { output ->
                GZIPOutputStream(output).use { it.write(bytes) }
                output.toByteArray()
            }

        private fun gunzip(bytes: ByteArray): ByteArray =
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

        private fun List<ByteArray>.join(): ByteArray =
            ByteArrayOutputStream().use { output ->
                forEach { output.write(it) }
                output.toByteArray()
            }

        private fun ByteArray.asListOfChunks(chunkSize: Int): List<ByteArray> {
            if (isEmpty()) return listOf(ByteArray(0))
            return buildList {
                var offset = 0
                while (offset < this@asListOfChunks.size) {
                    val end = minOf(this@asListOfChunks.size, offset + chunkSize)
                    add(this@asListOfChunks.copyOfRange(offset, end))
                    offset = end
                }
            }
        }

        private fun ByteArray.toBase64(): String =
            Base64.getEncoder().withoutPadding().encodeToString(this)

        private fun String.fromBase64(): ByteArray =
            Base64.getDecoder().decode(this)
    }
}
