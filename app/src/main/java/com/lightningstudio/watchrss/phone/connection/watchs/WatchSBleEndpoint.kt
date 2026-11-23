package com.lightningstudio.watchrss.phone.connection.watchs

import com.lightningstudio.watchrss.phone.syncv2.SyncV2Codec
import com.lightningstudio.watchrss.phone.syncv2.SyncV2PhoneProduct
import com.lightningstudio.watchrss.phone.syncv2.SyncV2Wire
import org.json.JSONObject

internal class WatchSBleEndpoint(
    private val product: SyncV2PhoneProduct,
    private val pairings: WatchSBlePairings,
    private val onLocalChanges: () -> Unit,
    private val alive: () -> Boolean
) : AutoCloseable {
    data class Reply(val bytes: ByteArray, val nextMtu: Int? = null, val disconnect: Boolean = false)

    private class Session(
        val runtime: SyncV2PhoneProduct.Runtime,
        val challenge: WatchSBleChallenge
    ) : AutoCloseable {
        var head: String? = null
        override fun close() = challenge.close()
    }

    private var session: Session? = null
    private var changed = false
    @Volatile private var pairingRevision = pairings.revision

    fun canSend(): Boolean = pairingRevision == pairings.revision

    suspend fun exchange(requestId: Long, responseId: Long, attMtu: Int, bytes: ByteArray): Reply =
        product.watchSBleAction { runtime ->
            check(alive())
            val current = session
            if (current == null) {
                check(requestId == 1L && responseId == 1L)
                val hello = parse(bytes)
                val challenge = WatchSBleChallenge.open(hello, attMtu, SyncV2Codec.random(16)) {
                    synchronized(pairings) {
                        pairings.lookup(runtime.watchSBleOwner, it).also { pairingRevision = pairings.revision }
                    }
                }
                session = Session(runtime, challenge)
                return@watchSBleAction Reply(json(challenge.hello), challenge.mtu)
            }
            check(runtime === current.runtime && canSend())
            val challenge = current.challenge
            if (!challenge.authenticated) {
                check(requestId == 2L && responseId == 2L)
                challenge.verify(parse(bytes))
                val ready = if (challenge.mode == WatchSBlePairings.Mode.RESET) {
                    JSONObject().put("op", "reset")
                } else {
                    val head = runtime.projection.watchSBleHead()
                    current.head = head
                    JSONObject().put("op", "ready").put("view", challenge.view(SyncV2Codec.utf8(head)))
                }
                check(alive())
                pairings.confirm(runtime.watchSBleOwner, challenge.id, challenge.mode, challenge.counter)
                return@watchSBleAction Reply(challenge.cipher.seal(responseId, json(ready)))
            }
            val request = parse(challenge.cipher.open(requestId, bytes))
            val op = SyncV2Wire.string(request, "op")
            val isReset = challenge.mode == WatchSBlePairings.Mode.RESET
            if (isReset || op == "forget") {
                SyncV2Codec.exact(request, setOf("op"))
                check(op == if (isReset) "reset-applied" else "forget")
                check(alive())
                pairings.forget(runtime.watchSBleOwner, challenge.id, challenge.mode)
                return@watchSBleAction Reply(challenge.cipher.seal(responseId, json(JSONObject().put("op", "forgotten"))),
                    disconnect = true)
            }
            pairings.withActive(runtime.watchSBleOwner, challenge.id) {
                val response = try {
                    business(runtime, checkNotNull(current.head), request, op)
                } catch (e: IllegalStateException) {
                    val code = e.message?.takeIf { it in BUSINESS_ERRORS } ?: throw e
                    errorResponse(code)
                }
                check(alive())
                Reply(challenge.cipher.seal(responseId, json(response)))
            }
        }

    private fun business(runtime: SyncV2PhoneProduct.Runtime, head: String, request: JSONObject, op: String): JSONObject =
        when (op) {
            "next" -> {
                SyncV2Codec.exact(request, setOf("op", "after"))
                val after = SyncV2Wire.string(request, "after")
                val result = runtime.projection.watchSBleNext(head, after)
                if (result == null) JSONObject().put("op", "row").put("row", JSONObject.NULL)
                else {
                    val row = JSONObject().put("op", "row").put("row", result.row).put("after", result.cursor)
                    if (json(row).size <= WatchSBleSecurity.MAX_PLAIN) row
                    else JSONObject().put("op", "skip").put("code", "object_too_large")
                        .put("entityKey", result.row.getString("entityKey")).put("after", result.cursor)
                }
            }
            "put" -> {
                SyncV2Codec.exact(request, setOf("op", "row"))
                val row = request.getJSONObject("row")
                if (runtime.projection.watchSBlePut(head, row) { check(alive()) }) changed = true
                JSONObject().put("op", "applied").put("entityKey", row.getString("entityKey"))
                    .put("sha256", SyncV2Codec.hash(row))
            }
            else -> SyncV2Codec.fail("unsupported_feature")
        }

    override fun close() {
        session?.close()
        session = null
        if (changed) {
            changed = false
            onLocalChanges()
        }
    }

    private companion object {
        val BUSINESS_ERRORS = setOf("budget_paused", "choice_stale", "identity_conflict", "invalid_schema",
            "invalid_encoding", "object_too_large", "storage_pressure", "unsupported_feature")

        fun parse(bytes: ByteArray) = SyncV2Wire.parse(bytes, WatchSBleSecurity.MAX_PLAIN)
        fun json(value: JSONObject) = SyncV2Codec.utf8(SyncV2Codec.canonical(value))
        fun errorResponse(code: String) = JSONObject().put("op", "error").put("code", code)
    }
}

internal class WatchSBleChallenge private constructor(
    val id: String,
    val counter: String,
    val mtu: Int,
    private val nonce: ByteArray,
    private val credential: WatchSBlePairings.Credential,
    private val hash: ByteArray
) : AutoCloseable {
    val mode get() = credential.mode
    val hello: JSONObject get() = JSONObject().put("op", "hello").put("version", 2)
        .put("nonce", SyncV2Codec.hex(nonce)).put("mtu", mtu).put("mode", mode.label)
        .put("proof", SyncV2Codec.hex(WatchSBleSecurity.proof(credential.key, hash, WatchSBleDirection.PHONE)))
    private var secured: WatchSBleCipher? = null
    val authenticated get() = secured != null
    val cipher get() = checkNotNull(secured)

    fun verify(auth: JSONObject) {
        check(!authenticated)
        SyncV2Codec.exact(auth, setOf("op", "proof"))
        check(auth.get("op") == "auth")
        val expected = WatchSBleSecurity.proof(credential.key, hash, WatchSBleDirection.WATCH)
        check(WatchSBleSecurity.matches(expected, SyncV2Wire.string(auth, "proof")))
        secured = WatchSBleCipher(credential.key, hash, WatchSBleDirection.PHONE)
    }

    fun view(head: ByteArray): String {
        check(authenticated && mode == WatchSBlePairings.Mode.SYNC)
        return SyncV2Codec.hex(WatchSBleSecurity.derive(credential.key, "view", head))
    }

    override fun close() {
        secured?.close()
        credential.close()
    }

    companion object {
        fun open(hello: JSONObject, attMtu: Int, nonce: ByteArray, lookup: (String) -> WatchSBlePairings.Credential): WatchSBleChallenge {
            SyncV2Codec.exact(hello, setOf("op", "version", "pairId", "counter", "mtu"))
            check(hello.get("op") == "hello" && SyncV2Wire.uint(hello, "version", 2) == 2L)
            val pairId = SyncV2Wire.string(hello, "pairId")
            SyncV2Codec.unhex(pairId, 16)
            val clientMtu = SyncV2Wire.uint(hello, "mtu", 517).toInt()
            check(clientMtu >= WatchSBleFrames.MIN_MTU && attMtu >= WatchSBleFrames.MIN_MTU)
            val mtu = minOf(clientMtu, attMtu, WatchSBleFrames.MAX_MTU)
            val credential = lookup(pairId)
            return try {
                val counter = SyncV2Wire.string(hello, "counter")
                val hash = WatchSBleSecurity.transcript(pairId, counter, nonce, clientMtu, mtu, credential.mode.label)
                WatchSBleChallenge(pairId, counter, mtu, nonce, credential, hash)
            } catch (error: Exception) {
                credential.close()
                throw error
            }
        }
    }
}
