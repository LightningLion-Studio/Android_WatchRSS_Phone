package com.lightningstudio.watchrss.phone.syncv2

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SyncV2ControlUploadTest {
    private fun oid(number: Int) = "state:sha256:" + number.toString(16).padStart(64, '0')

    @Test fun keepLocalRenewsExpiredWindowAndSignsAtFirstPublish() = SyncV2P5Fixture().use { live ->
        live.pending()
        val actions = live.actions()
        val preview = actions.preview()
        val expired = AtomicBoolean()
        live.enforcePublishExpiry = true
        live.uploadHook = { route ->
            if (route == "objects/have-want" && expired.compareAndSet(false, true)) {
                live.f.now += 301_000
                live.windowId = "34".repeat(16)
                live.error("window_expired", 410)
            } else null
        }

        actions.choose(preview, "local")

        assertTrue(expired.get())
        val have = live.requests.indexOf("objects/have-want")
        assertEquals(listOf("negotiate", "objects/have-want"), live.requests.drop(have + 1).take(2))
        assertEquals(1, live.publishCalls)
        assertTrue(live.projection.state().body.isNull("pendingAccountAction"))
        assertEquals("offline\nline\n", live.text())
    }

    @Test fun resumedUploadUsesReceiptWhenPriorPublishCommitsDuringWindowRenewal() =
        resumeAfterDelayedPublish(receiptVisible = true)

    @Test fun resumedUploadReplaysOriginalRequestWhenReceiptStillUnknownAfterWindowRenewal() =
        resumeAfterDelayedPublish(receiptVisible = false)

    private fun resumeAfterDelayedPublish(receiptVisible: Boolean) = SyncV2P5Fixture().use { live ->
        live.pending()
        val actions = live.actions()
        val dispatch = checkNotNull(live.f.route)
        var delayedPublish: RecordedRequest? = null
        var originalBody: String? = null
        var requestLookups = 0
        var renewalTriggered = false
        live.f.route = { request ->
            val route = request.requestUrl!!.encodedPath.substringAfter("/functions/v1/sync/v2/")
            when {
                route.startsWith("requests/") -> {
                    requestLookups++
                    if (renewalTriggered && !receiptVisible) {
                        live.f.response(live.f.dto().put("status", "unknown").put("response", JSONObject.NULL))
                    } else dispatch(request)
                }
                route == "account/publish" && delayedPublish == null -> {
                    delayedPublish = request
                    originalBody = request.body.clone().readUtf8()
                    live.error("temporarily_unavailable", 503)
                }
                route == "objects/have-want" && delayedPublish != null && !renewalTriggered -> {
                    assertEquals(2, requestLookups)
                    dispatch(checkNotNull(delayedPublish))
                    assertEquals(1, live.results.size)
                    renewalTriggered = true
                    live.windowId = "34".repeat(16)
                    live.error("window_expired", 410)
                }
                route == "account/publish" -> {
                    assertEquals(originalBody, request.body.clone().readUtf8())
                    dispatch(request)
                }
                else -> dispatch(request)
            }
        }

        val error = assertThrows(SyncV2CloudError::class.java) { actions.choose(actions.preview(), "local") }
        assertEquals("temporarily_unavailable", error.code)
        val pending = live.projection.state().body.getJSONObject("pendingAccountAction")
        assertTrue(pending.getBoolean("publicationAttempted"))
        assertTrue(live.results.isEmpty())
        val id = pending.getString("id")
        val expectedOid = pending.getJSONObject("request").getJSONObject("account")
            .getJSONObject("body").getString("newCommitOid")
        live.reopen()

        val result = checkNotNull(live.actions().resume())

        assertTrue(renewalTriggered)
        assertEquals(3, requestLookups)
        assertEquals(if (receiptVisible) 1 else 2, live.publishCalls)
        assertEquals(expectedOid, result.getJSONObject("account").getString("commitOid"))
        assertTrue(result.getBoolean("localCommitted"))
        val state = live.projection.state().body
        assertTrue(state.isNull("pendingAccountAction"))
        assertTrue(state.isNull("accountActionError"))
        assertEquals(id, state.getJSONObject("accountActionReceipt").getString("id"))
        assertEquals(id, state.getString("appliedRequestId"))
        assertEquals("offline\nline\n", live.text())
    }

    @Test fun retriesOnlyUnsettledObjectsAndOrdersPartsWithinAnObject() = SyncV2P5Fixture().use { live ->
        val client = live.f.client()
        client.capabilities()
        val account = live.current
        val upload = SyncV2ControlUpload(client, account.getString("commitOid"), account.getString("revision"),
            account.getString("rollbackGeneration"), { live.f.now },
            { SyncV2Codec.hex(SyncV2Codec.random(16)) }, "ref_conflict")
        val objects = (0..9).associate { index ->
            oid(index) to ByteArray(if (index == 1) 131073 else if (index == 2) 65537 else 1) { index.toByte() }
        }
        live.f.objects[oid(0)] = objects.getValue(oid(0))
        val concurrent = CountDownLatch(2)
        val settled = CountDownLatch(1)
        val expired = AtomicBoolean()
        val completionCount = AtomicInteger()
        live.uploadHook = { route ->
            when {
                route == "objects/${oid(1)}/parts/0" || route == "objects/${oid(2)}/parts/0" -> {
                    concurrent.countDown()
                    check(concurrent.await(5, TimeUnit.SECONDS))
                    null
                }
                route == "objects/${oid(1)}/parts/65536" && expired.compareAndSet(false, true) -> {
                    check(settled.await(5, TimeUnit.SECONDS))
                    live.windowId = "34".repeat(16)
                    live.error("window_expired", 410)
                }
                route == "objects/${oid(2)}/complete" -> {
                    live.f.objects[oid(2)] = live.f.prefix.getValue(oid(2))
                    completionCount.incrementAndGet()
                    settled.countDown()
                    null
                }
                else -> null
            }
        }

        upload.objects(objects.keys.sorted(), objects::getValue)

        assertTrue(expired.get())
        assertEquals(0L, concurrent.count)
        assertEquals(1, completionCount.get())
        assertFalse(oid(0) in live.f.putOids)
        for ((key, bytes) in objects) {
            assertArrayEquals(bytes, live.f.objects.getValue(key))
            val offsets = live.f.putOids.indices.filter { live.f.putOids[it] == key }.map { live.f.putOffsets[it] }
            assertEquals(if (key == oid(0)) emptyList() else (bytes.indices step 65536).toList(), offsets)
        }
    }

    @Test fun nonRetryableFailureCancelsAnInFlightSibling() = SyncV2P5Fixture().use { live ->
        val client = live.f.client()
        client.capabilities()
        val account = live.current
        val upload = SyncV2ControlUpload(client, account.getString("commitOid"), account.getString("revision"),
            account.getString("rollbackGeneration"), { live.f.now },
            { SyncV2Codec.hex(SyncV2Codec.random(16)) }, "ref_conflict")
        val blocked = CountDownLatch(1)
        live.uploadHook = { route ->
            when (route) {
                "objects/${oid(1)}/parts/0" -> {
                    blocked.countDown()
                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
                "objects/${oid(2)}/parts/0" -> {
                    check(blocked.await(5, TimeUnit.SECONDS))
                    live.error("quota_exceeded", 507)
                }
                else -> null
            }
        }

        val start = System.nanoTime()
        val error = assertThrows(SyncV2CloudError::class.java) {
            upload.objects(listOf(oid(1), oid(2)), { byteArrayOf(1) })
        }
        assertEquals("quota_exceeded", error.code)
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 10)
        assertTrue(live.f.putOids.isEmpty())
    }

    @Test fun renewalStopsOnChangedAccountGenerationOrRevision() {
        for (change in listOf("revision", "rollbackGeneration")) SyncV2P5Fixture().use { live ->
            val client = live.f.client()
            client.capabilities()
            val account = live.current
            val upload = SyncV2ControlUpload(client, account.getString("commitOid"), account.getString("revision"),
                account.getString("rollbackGeneration"), { live.f.now },
                { SyncV2Codec.hex(SyncV2Codec.random(16)) }, "ref_conflict")
            val expired = AtomicBoolean()
            live.uploadHook = { route ->
                if (route == "objects/have-want" && expired.compareAndSet(false, true)) {
                    live.current.put(change, "100")
                    live.windowId = "34".repeat(16)
                    live.error("window_expired", 410)
                } else null
            }

            val error = assertThrows(SyncV2CloudError::class.java) {
                upload.objects(listOf(oid(1)), { byteArrayOf(1) })
            }
            assertEquals("ref_conflict", error.code)
            assertTrue(live.f.putOids.isEmpty())
        }
    }
}
