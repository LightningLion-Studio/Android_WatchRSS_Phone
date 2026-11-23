package com.lightningstudio.watchrss.phone.connection.watchs

import com.lightningstudio.watchrss.phone.syncv2.SyncV2Codec
import com.lightningstudio.watchrss.phone.syncv2.SyncV2Envelope
import com.lightningstudio.watchrss.phone.syncv2.SyncV2Identity
import com.lightningstudio.watchrss.phone.syncv2.SyncV2PhoneProduct
import com.lightningstudio.watchrss.phone.syncv2.SyncV2Projection
import com.lightningstudio.watchrss.phone.syncv2.SyncV2Wire
import com.lightningstudio.watchrss.phone.syncv2.WatchSBleRows
import org.json.JSONObject
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class WatchSBleEndpoint(
    private val product: SyncV2PhoneProduct,
    private val alive: () -> Boolean
) : AutoCloseable {
    data class Reply(val bytes: ByteArray, val nextMtu: Int? = null)

    private class Session(
        val runtime: SyncV2PhoneProduct.Runtime,
        val head: String,
        val peer: String,
        val transcript: ByteArray,
        val cipher: WatchSBleCipher
    ) {
        var authenticated = false
    }

    private var session: Session? = null

    suspend fun exchange(requestId: Long, responseId: Long, attMtu: Int, bytes: ByteArray): Reply =
        product.watchSBleAction {
            check(alive())
            val current = session
            if (current == null) return@watchSBleAction hello(it, attMtu, parse(bytes))
            check(it === current.runtime)
            it.checkWatchSBleHead(current.head)
            val peerKey = it.watchSBlePublicKey(current.peer)
            it.watchSBlePublicKey(it.vault.signer.deviceId)
            if (!current.authenticated) {
                val auth = parse(bytes)
                SyncV2Codec.exact(auth, setOf("op", "signature"))
                check(auth.get("op") == "auth")
                check(SyncV2Identity.verifySignature(peerKey, signed("watch", current.transcript),
                    SyncV2Wire.string(auth, "signature")))
                current.authenticated = true
                return@watchSBleAction Reply(current.cipher.encrypt("phone", responseId, json("ready")))
            }
            val request = parse(current.cipher.decrypt("watch", requestId, bytes))
            val response = try {
                business(it, current, request)
            } catch (e: IllegalStateException) {
                val code = e.message?.takeIf { code -> code in BUSINESS_ERRORS } ?: throw e
                JSONObject().put("op", "error").put("code", code)
            }
            check(alive())
            val encoded = json(response)
            val bounded = if (encoded.size <= WatchSBleFrames.LIMIT - 16) encoded else
                json(JSONObject().put("op", "error").put("code", "object_too_large"))
            Reply(current.cipher.encrypt("phone", responseId, bounded))
        }

    private fun hello(runtime: SyncV2PhoneProduct.Runtime, attMtu: Int, request: JSONObject): Reply {
        SyncV2Codec.exact(request, setOf("op", "versions", "scope", "head", "deviceId", "nonce", "keyVersion", "mtu"))
        check(request.get("op") == "hello")
        val versions = request.getJSONArray("versions")
        check(versions.length() in 1..16)
        var previous = 0L
        for (index in 0 until versions.length()) {
            val version = SyncV2Wire.uint(JSONObject().put("v", versions.get(index)), "v", 0xffffffffL)
            check(version > previous)
            if (index == 0) check(version == 1L)
            previous = version
        }
        check(SyncV2Codec.scope(request.getJSONObject("scope")) == runtime.identity.scopeJson)
        val head = runtime.checkWatchSBleHead(SyncV2Codec.canonical(request.getJSONObject("head")))
        val peer = SyncV2Wire.string(request, "deviceId")
        SyncV2Codec.unhex(peer, 16)
        SyncV2Codec.unhex(SyncV2Wire.string(request, "nonce"), 16)
        val signer = runtime.vault.signer
        check(peer != signer.deviceId)
        runtime.watchSBlePublicKey(peer)
        check(runtime.watchSBlePublicKey(signer.deviceId) == signer.publicHex)
        val keyVersion = SyncV2Wire.uint(request, "keyVersion", 0xffffffffL)
        check(keyVersion > 0)
        val clientMtu = SyncV2Wire.uint(request, "mtu", 517).toInt()
        check(clientMtu >= WatchSBleFrames.MIN_MTU)
        val mtu = minOf(clientMtu, attMtu)
        val response = JSONObject().put("op", "hello").put("version", 1)
            .put("deviceId", signer.deviceId).put("nonce", SyncV2Codec.hex(SyncV2Codec.random(16))).put("mtu", mtu)
        val transcript = SyncV2Codec.lp("wrss.watchs.ble.v1/transcript".toByteArray(), json(request), json(response))
        val cipher = WatchSBleCipher(runtime.keys.read(keyVersion), transcript)
        session = Session(runtime, head, peer, transcript, cipher)
        return Reply(json(response.put("signature", signer.sign(signed("phone", transcript)))), mtu)
    }

    private fun business(runtime: SyncV2PhoneProduct.Runtime, session: Session, request: JSONObject): JSONObject {
        val projection = runtime.projection
        val authorize: (SyncV2Projection.State) -> Unit = { state ->
            check(alive())
            runtime.watchSBlePublicKey(session.peer, state)
            runtime.watchSBlePublicKey(runtime.vault.signer.deviceId, state)
        }
        return when (request.get("op")) {
            "next" -> {
                SyncV2Codec.exact(request, setOf("op", "after"))
                val after = SyncV2Wire.string(request, "after")
                if (after.isNotEmpty()) SyncV2Codec.unhex(after, 32)
                val row = projection.watchSBleNext(session.head, after)
                JSONObject().put("op", "row").put("row", row ?: JSONObject.NULL)
            }
            "put" -> {
                SyncV2Codec.exact(request, setOf("op", "row"))
                val row = request.getJSONObject("row")
                projection.watchSBlePut(session.head, row, authorize)
                JSONObject().put("op", "applied").put("entityKey", row.getString("entityKey"))
                    .put("sha256", SyncV2Codec.hash(row))
            }
            "get-object" -> {
                SyncV2Codec.exact(request, setOf("op", "oid"))
                val oid = SyncV2Wire.string(request, "oid")
                val bytes = projection.watchSBleObject(session.head, oid)
                JSONObject().put("op", "object").put("oid", oid)
                    .put("bytes", bytes?.let(SyncV2Codec::b64) ?: JSONObject.NULL)
            }
            "put-object" -> {
                SyncV2Codec.exact(request, setOf("op", "oid", "bytes", "keyVersion"))
                val oid = SyncV2Wire.string(request, "oid")
                val bytes = SyncV2Codec.decode64(SyncV2Wire.string(request, "bytes"))
                if (!WatchSBleRows.matchesOid(bytes, oid)) SyncV2Codec.fail("hash_mismatch")
                val keyVersion = SyncV2Wire.uint(request, "keyVersion", 0xffffffffL)
                val plain = SyncV2Envelope.open(runtime.identity, runtime.keys.read(keyVersion), oid, bytes, keyVersion)
                plain.fill(0)
                projection.watchSBleCacheObject(session.head, oid, bytes, authorize)
                JSONObject().put("op", "cached").put("oid", oid)
            }
            else -> SyncV2Codec.fail("invalid_schema")
        }
    }

    override fun close() {
        session?.cipher?.close()
        session = null
    }

    private companion object {
        val BUSINESS_ERRORS = setOf("budget_paused", "choice_stale", "identity_conflict",
            "invalid_schema", "invalid_encoding", "hash_mismatch", "object_too_large", "storage_pressure", "unsupported_feature")

        fun parse(bytes: ByteArray) = SyncV2Wire.parse(bytes, WatchSBleFrames.LIMIT)
        fun json(op: String) = json(JSONObject().put("op", op))
        fun json(value: JSONObject) = SyncV2Codec.utf8(SyncV2Codec.canonical(value))
        fun signed(direction: String, transcript: ByteArray) = SyncV2Codec.lp(direction.toByteArray(), transcript)
    }
}

internal class WatchSBleCipher(contentKey: ByteArray, transcript: ByteArray) : AutoCloseable {
    private val transcriptHash = SyncV2Codec.sha(transcript)
    private val watch = derive(contentKey, "watch")
    private val phone = derive(contentKey, "phone")

    private fun derive(key: ByteArray, direction: String) = SyncV2Codec.hmac(key, SyncV2Codec.lp(
        "wrss.watchs.ble.v1/key".toByteArray(), direction.toByteArray(), transcriptHash
    ))

    private fun crypt(mode: Int, direction: String, id: Long, bytes: ByteArray): ByteArray {
        require(id in 1..0xffffffffL)
        val key = when (direction) {
            "watch" -> watch
            "phone" -> phone
            else -> error("invalid_direction")
        }
        val seq = ByteBuffer.allocate(4).putInt(id.toInt()).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ByteArray(8) + seq))
        cipher.updateAAD(SyncV2Codec.lp("wrss.watchs.ble.v1/message".toByteArray(), transcriptHash,
            direction.toByteArray(), seq))
        return cipher.doFinal(bytes)
    }

    fun encrypt(direction: String, id: Long, bytes: ByteArray) = crypt(Cipher.ENCRYPT_MODE, direction, id, bytes)
    fun decrypt(direction: String, id: Long, bytes: ByteArray) = crypt(Cipher.DECRYPT_MODE, direction, id, bytes)

    override fun close() {
        watch.fill(0)
        phone.fill(0)
    }
}
