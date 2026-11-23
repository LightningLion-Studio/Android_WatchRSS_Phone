package com.lightningstudio.watchrss.phone.connection.watchs

import com.lightningstudio.watchrss.phone.syncv2.SyncV2Codec
import com.lightningstudio.watchrss.phone.syncv2.SyncV2Wire
import com.lightningstudio.watchrss.phone.syncv2.WatchSBleLibrary
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WatchSBleBindingsTest {
    private val first = "01".repeat(16)
    private val second = "02".repeat(16)
    private var time = 0L
    private var owner = "local"
    private var stored = "{}"
    private fun open() = WatchSBleBindings({ time }, { JSONObject(stored) }, { stored = it.toString() })

    private fun WatchSBleBindings.endpoint() = WatchSBleEndpoint(this, { owner }, { run ->
        run(object : WatchSBleLibrary {
            override fun watchSBleHead() = "null"
            override fun watchSBleNext(head: String, after: String): WatchSBleLibrary.Row? = null
            override fun watchSBlePut(head: String, row: JSONObject, authorize: () -> Unit): Boolean =
                error("unexpected write")
        })
    }, { fail("unexpected local change") }, { true })

    private suspend fun WatchSBleEndpoint.hello(id: String) = exchange(1, 185,
        """{"mtu":247,"op":"hello","version":3,"watchId":"$id","watchName":"手表"}""".toByteArray())

    private fun WatchSBleEndpoint.Reply.json() = SyncV2Wire.parse(bytes)

    @Test fun windowAcceptsOnlyOneRequestAndBindingSurvivesRestart() = runBlocking {
        val bindings = open()
        bindings.endpoint().use { endpoint ->
            assertEquals("rejected", endpoint.hello(first).json().getString("mode"))
        }
        bindings.begin(owner)
        time = 119_999
        bindings.endpoint().use { endpoint ->
            val pending = endpoint.hello(first)
            assertEquals("pending", pending.json().getString("mode"))
            assertEquals(185, pending.nextMtu)
            assertFalse(pending.json().has("view"))
            val request = checkNotNull(pending.pending)
            assertEquals("手表", request.name)
            assertEquals("{}", stored)
            assertEquals(120_000L, endpoint.pendingRemaining(request))
            assertEquals(WatchSBleBindings.Mode.REJECTED, bindings.hello(owner, second, "Other").mode)
            time += 119_999
            bindings.decide(request, true, owner)
            val ready = endpoint.completePending(request, request.decision.await()).json()
            assertEquals("ready", ready.getString("op"))
            assertEquals(SyncV2Codec.hash(null), ready.getString("view"))
        }

        open().endpoint().use { endpoint ->
            val sync = endpoint.hello(first).json()
            assertEquals("sync", sync.getString("mode"))
            assertEquals(SyncV2Codec.hash(null), sync.getString("view"))
        }
        open().endpoint().use { endpoint ->
            val rejected = endpoint.hello(second)
            assertTrue(rejected.disconnect)
            assertEquals("""{"mode":"rejected","op":"hello","version":3}""", rejected.bytes.decodeToString())
        }
    }

    @Test fun windowAndPendingExpireWithoutBinding() = runBlocking {
        val bindings = open()
        bindings.begin(owner)
        time = 120_000
        bindings.endpoint().use { endpoint ->
            assertEquals("rejected", endpoint.hello(first).json().getString("mode"))
        }
        bindings.begin(owner)
        bindings.endpoint().use { endpoint ->
            val request = checkNotNull(endpoint.hello(first).pending)
            time += 120_000
            bindings.decide(request, true, owner)
            assertFalse(request.decision.await())
            val rejected = endpoint.completePending(request, null)
            assertTrue(rejected.disconnect)
            assertEquals("""{"op":"rejected"}""", rejected.bytes.decodeToString())
        }
        assertEquals("{}", stored)
        assertNull(bindings.ui.value.request)
    }

    @Test fun denialAndDisconnectMakeOldPromptInert() = runBlocking {
        val bindings = open()
        bindings.begin(owner)
        bindings.endpoint().use { endpoint ->
            val request = checkNotNull(endpoint.hello(first).pending)
            bindings.decide(request, false, owner)
            assertTrue(endpoint.completePending(request, request.decision.await()).disconnect)
        }
        bindings.begin(owner)
        val stale = bindings.endpoint().use { checkNotNull(it.hello(first).pending) }
        bindings.begin(owner)
        bindings.endpoint().use { endpoint ->
            val current = checkNotNull(endpoint.hello(second).pending)
            bindings.decide(stale, true, owner)
            assertSame(current, bindings.ui.value.request)
            assertEquals("{}", stored)
        }
    }

    @Test fun accountChangeAndResetReceiptPreserveOnlyEightWatchIds() = runBlocking {
        val bindings = open()
        val ids = (1..9).map { "%032x".format(it) }
        for (id in ids) {
            bindings.begin(owner)
            bindings.endpoint().use { endpoint ->
                val request = checkNotNull(endpoint.hello(id).pending)
                bindings.decide(request, true, owner)
                endpoint.completePending(request, true)
            }
            owner += "x"
            bindings.refresh(owner)
        }
        val resets = JSONObject(stored).getJSONArray("resets")
        assertEquals(ids.drop(1), List(resets.length(), resets::getString))
        val reopened = open()
        reopened.endpoint().use { endpoint ->
            assertEquals("rejected", endpoint.hello(ids.first()).json().getString("mode"))
        }
        val retired = ids.last()
        reopened.endpoint().use { endpoint ->
            val reset = endpoint.hello(retired).json()
            assertEquals("reset", reset.getString("mode"))
            assertFalse(reset.has("view"))
            val reply = endpoint.exchange(2, 185, """{"op":"reset-applied"}""".toByteArray())
            assertEquals("forgotten", reply.json().getString("op"))
            assertTrue(reply.disconnect)
        }
        reopened.endpoint().use { endpoint ->
            assertEquals("rejected", endpoint.hello(retired).json().getString("mode"))
        }
    }

    @Test fun fullPlaintextLimitReachesBusinessAndOversizeDownloadSkips() = runBlocking {
        val bindings = open()
        bindings.begin(owner)
        val url = "https://example.com/large-feed"
        val largeRow = JSONObject().put("kind", "source").put("entityKey", "ab".repeat(32))
        val value = JSONObject().put("url", url).put("title", "x".repeat(140_000)).put("description", "")
        largeRow.put("value", value)
        val put = JSONObject().put("op", "put").put("row", largeRow)
        val emptySize = SyncV2Codec.utf8(SyncV2Codec.canonical(put)).size
        value.put("description", "y".repeat(WatchSBleFrames.LIMIT - emptySize))
        val message = SyncV2Codec.utf8(SyncV2Codec.canonical(put))
        assertEquals(WatchSBleFrames.LIMIT, message.size)
        var writes = 0
        val library = object : WatchSBleLibrary {
            override fun watchSBleHead() = "null"
            override fun watchSBleNext(head: String, after: String) = WatchSBleLibrary.Row("source:$url", largeRow)
            override fun watchSBlePut(head: String, row: JSONObject, authorize: () -> Unit): Boolean {
                authorize()
                assertEquals(SyncV2Codec.hash(largeRow), SyncV2Codec.hash(row))
                writes++
                return false
            }
        }
        WatchSBleEndpoint(bindings, { owner }, { it(library) }, { fail("unexpected local change") }, { true }).use { endpoint ->
            val request = checkNotNull(endpoint.hello(first).pending)
            bindings.decide(request, true, owner)
            endpoint.completePending(request, true)
            assertEquals("applied", endpoint.exchange(2, 185, message).json().getString("op"))
            assertEquals(1, writes)
            val skip = endpoint.exchange(3, 185, """{"op":"next","after":""}""".toByteArray()).json()
            assertEquals("skip", skip.getString("op"))
            assertEquals("object_too_large", skip.getString("code"))
            assertEquals("source:$url", skip.getString("after"))
            assertEquals(largeRow.getString("entityKey"), skip.getString("entityKey"))
        }
    }

    @Test fun watchForgetCompletesWithoutSchedulingReset() = runBlocking {
        val bindings = open()
        bindings.begin(owner)
        bindings.endpoint().use { endpoint ->
            val request = checkNotNull(endpoint.hello(first).pending)
            bindings.decide(request, true, owner)
            endpoint.completePending(request, true)
            val reply = endpoint.exchange(2, 185, """{"op":"forget"}""".toByteArray())
            assertEquals("forgotten", reply.json().getString("op"))
            assertTrue(reply.disconnect)
        }
        assertFalse(bindings.ui.value.hasResets)
        assertNull(bindings.ui.value.boundName)
    }
}
