package com.lightningstudio.watchrss.phone.connection.watchs

import com.lightningstudio.watchrss.phone.syncv2.SyncV2Codec
import com.lightningstudio.watchrss.phone.syncv2.SyncV2Wire
import com.lightningstudio.watchrss.phone.syncv2.WatchSBleLibrary
import org.json.JSONObject

internal class WatchSBleEndpoint(
    private val bindings: WatchSBleBindings,
    private val owner: () -> String?,
    private val access: suspend ((WatchSBleLibrary) -> JSONObject) -> JSONObject,
    private val onLocalChanges: () -> Unit,
    private val alive: () -> Boolean
) : AutoCloseable {
    data class Reply(
        val bytes: ByteArray, val nextMtu: Int? = null, val disconnect: Boolean = false,
        val pending: WatchSBleBindings.Request? = null
    )

    private class Session(
        val owner: String, val watchId: String, val hello: WatchSBleBindings.Hello
    ) {
        var head: String? = null
    }

    private var session: Session? = null
    private var changed = false

    fun canSend(): Boolean = session?.let {
        alive() && it.owner == owner() && it.hello.revision == bindings.revision
    } == true

    private fun checkLive() = check(canSend())

    suspend fun exchange(requestId: Long, attMtu: Int, bytes: ByteArray): Reply {
        val request = parse(bytes)
        val current = session
        if (current == null) {
            check(requestId == 1L && alive())
            return hello(request, attMtu)
        }

        checkLive()
        val op = SyncV2Wire.string(request, "op")
        val mode = current.hello.mode
        if (mode == WatchSBleBindings.Mode.RESET || op == "forget") {
            val effectiveMode = if (current.head != null) WatchSBleBindings.Mode.SYNC else mode
            check(effectiveMode == WatchSBleBindings.Mode.RESET || current.head != null)
            SyncV2Codec.exact(request, setOf("op"))
            check(op == if (effectiveMode == WatchSBleBindings.Mode.RESET) "reset-applied" else "forget")
            synchronized(bindings) {
                checkLive()
                bindings.forget(current.owner, current.watchId, effectiveMode)
            }
            return Reply(json(JSONObject().put("op", "forgotten")), disconnect = true)
        }

        val head = checkNotNull(current.head)
        val result = access { library ->
            bindings.withActive(current.owner, current.watchId) {
                checkLive()
                try {
                    business(library, head, request, op)
                } catch (e: IllegalStateException) {
                    val code = e.message?.takeIf { it in BUSINESS_ERRORS } ?: throw e
                    JSONObject().put("op", "error").put("code", code)
                }
            }
        }
        checkLive()
        return Reply(json(result))
    }

    private suspend fun hello(request: JSONObject, attMtu: Int): Reply {
        SyncV2Codec.exact(request, setOf("op", "version", "watchId", "watchName", "mtu"))
        check(request.get("op") == "hello" && SyncV2Wire.uint(request, "version", 3) == 3L)
        val watchId = SyncV2Wire.string(request, "watchId")
        SyncV2Codec.unhex(watchId, 16)
        val name = SyncV2Wire.string(request, "watchName")
        val clientMtu = SyncV2Wire.uint(request, "mtu", 517).toInt()
        check(clientMtu >= WatchSBleFrames.MIN_MTU && attMtu >= WatchSBleFrames.MIN_MTU)
        val mtu = minOf(clientMtu, attMtu, WatchSBleFrames.MAX_MTU)
        val owner = checkNotNull(owner())
        val result = bindings.hello(owner, watchId, name)
        val current = Session(owner, watchId, result)
        session = current
        val response = JSONObject().put("op", "hello").put("version", 3).put("mode", result.mode.wire)
        if (!result.otherBound) response.put("mtu", mtu)
        if (result.mode == WatchSBleBindings.Mode.SYNC) response.put("view", view(current))
        return Reply(json(response), mtu, result.mode == WatchSBleBindings.Mode.REJECTED, result.request)
    }

    fun pendingRemaining(request: WatchSBleBindings.Request) = bindings.remaining(request)

    suspend fun completePending(request: WatchSBleBindings.Request, allowed: Boolean?): Reply {
        val current = checkNotNull(session)
        check(current.hello.request === request)
        checkLive()
        if (allowed == null) bindings.decide(request, false, null)
        if (!(allowed ?: request.decision.await())) {
            return Reply(json(JSONObject().put("op", "rejected")), disconnect = true)
        }
        return Reply(json(JSONObject().put("op", "ready").put("view", view(current))))
    }

    private suspend fun view(current: Session): String {
        val head = access { library ->
            bindings.withActive(current.owner, current.watchId) {
                checkLive()
                JSONObject().put("head", library.watchSBleHead())
            }
        }.getString("head")
        checkLive()
        current.head = head
        return SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.utf8(head)))
    }

    private fun business(library: WatchSBleLibrary, head: String, request: JSONObject, op: String): JSONObject =
        when (op) {
            "next" -> {
                SyncV2Codec.exact(request, setOf("op", "after"))
                val after = SyncV2Wire.string(request, "after")
                val result = library.watchSBleNext(head, after)
                if (result == null) JSONObject().put("op", "row").put("row", JSONObject.NULL)
                else {
                    val row = JSONObject().put("op", "row").put("row", result.row).put("after", result.cursor)
                    if (json(row).size <= WatchSBleFrames.LIMIT) row
                    else JSONObject().put("op", "skip").put("code", "object_too_large")
                        .put("entityKey", result.row.getString("entityKey")).put("after", result.cursor)
                }
            }
            "put" -> {
                SyncV2Codec.exact(request, setOf("op", "row"))
                val row = request.getJSONObject("row")
                if (library.watchSBlePut(head, row) { checkLive() }) changed = true
                JSONObject().put("op", "applied").put("entityKey", row.getString("entityKey"))
                    .put("sha256", SyncV2Codec.hash(row))
            }
            else -> SyncV2Codec.fail("unsupported_feature")
        }


    override fun close() {
        session?.hello?.request?.let { bindings.decide(it, false, null) }
        session = null
        if (changed) {
            changed = false
            onLocalChanges()
        }
    }

    private companion object {
        val BUSINESS_ERRORS = setOf("budget_paused", "choice_stale", "identity_conflict", "invalid_schema",
            "invalid_encoding", "object_too_large", "storage_pressure", "unsupported_feature")

        fun parse(bytes: ByteArray) = SyncV2Wire.parse(bytes, WatchSBleFrames.LIMIT)
        fun json(value: JSONObject) = SyncV2Codec.utf8(SyncV2Codec.canonical(value))
    }
}
