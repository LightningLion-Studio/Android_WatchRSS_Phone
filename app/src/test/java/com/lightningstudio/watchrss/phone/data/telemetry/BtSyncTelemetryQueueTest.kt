package com.lightningstudio.watchrss.phone.data.telemetry

import android.app.Application
import androidx.work.ExistingWorkPolicy
import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.privacy.PhonePrivacyConsentStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BtSyncTelemetryQueueTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun queueSurvivesRestartEvictsOldestAndExpiresAfterSevenDays() {
        var clock = BT_SYNC_EVENT_LIMIT_MS * 2
        val path = File(temp.root, "events")
        val queue = BtSyncEventQueue(path) { clock }
        repeat(501) { index ->
            queue.append(BtSyncEvent(JSONObject().put("eventId", "$index").put("occurredAt", clock)))
        }
        val reopened = BtSyncEventQueue(path) { clock }
        val remaining = mutableListOf<Int>()
        while (reopened.hasPending()) {
            val rows = reopened.batch()
            assertEquals(50, rows.size)
            remaining += rows.map { it.id.toInt() }
            reopened.remove(rows.mapTo(mutableSetOf()) { it.id })
        }
        assertEquals((1..500).toList(), remaining)
        queue.append(BtSyncEvent(JSONObject().put("eventId", "live").put("occurredAt", clock)))
        clock += BT_SYNC_EVENT_LIMIT_MS + 1
        assertFalse(reopened.hasPending())
        assertFalse(path.exists())
    }

    @Test
    fun anonymousFailedBatchRetriesWithSameEventIdThenRemovesOnlyOnSuccess() = runBlocking {
        MockWebServer().use { server ->
            val scheduled = mutableListOf<ExistingWorkPolicy>()
            val service = service(server, schedule = { scheduled.add(it) })
            assertNull(service.begin(BtSyncTrigger.MANUAL))
            assertTrue(service.uploadPending())
            assertEquals(0, server.requestCount)
            acceptConsent()
            service.begin(BtSyncTrigger.MANUAL)!!.finishCancelledIfOpen()
            assertEquals(listOf(ExistingWorkPolicy.APPEND_OR_REPLACE), scheduled)

            server.enqueue(MockResponse().setResponseCode(429))
            assertFalse(service.uploadPending())
            val first = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/functions/v1/telemetry-events", first.path)
            assertEquals("POST", first.method)
            assertNull(first.getHeader("Authorization"))
            val firstPayload = JSONObject(first.body.readUtf8())
            val event = firstPayload.getJSONArray("events").getJSONObject(0)
            assertEquals("cancelled", event.getString("result"))
            assertEquals("off", event.getJSONObject("features").getString("b2"))
            assertEquals("install-0", event.getString("installId"))

            server.enqueue(MockResponse().setResponseCode(200))
            val reopened = service(server)
            assertTrue(reopened.uploadPending())
            val second = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertNull(second.getHeader("Authorization"))
            assertEquals(event.getString("eventId"),
                JSONObject(second.body.readUtf8()).getJSONArray("events").getJSONObject(0).getString("eventId"))
            assertTrue(reopened.uploadPending())
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun mixedAcceptedResponseAndBadRequestBothRemoveTheWholeBatch() = runBlocking {
        MockWebServer().use { server ->
            acceptConsent()
            val service = service(server)
            repeat(2) { service.begin(BtSyncTrigger.MANUAL)!!.finishSuccess() }
            server.enqueue(MockResponse().setResponseCode(503))
            assertFalse(service.uploadPending())
            val retried = server.takeRequest(2, TimeUnit.SECONDS)!!
            val first = JSONObject(retried.body.readUtf8()).getJSONArray("events")
            assertEquals(2, first.length())
            val rejectedId = first.getJSONObject(1).getString("eventId")
            server.enqueue(MockResponse().setBody(
                """{"accepted":1,"rejected":[{"eventId":"$rejectedId","reason":"invalid_event"}]}"""
            ))
            assertTrue(service.uploadPending())
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertNull(request.getHeader("Authorization"))
            val body = JSONObject(request.body.readUtf8()).getJSONArray("events")
            assertEquals(rejectedId, body.getJSONObject(1).getString("eventId"))
            assertFalse(body.getJSONObject(0).has("userId"))

            service.begin(BtSyncTrigger.MANUAL)!!.finishSuccess()
            server.enqueue(MockResponse().setResponseCode(400))
            assertTrue(service.uploadPending())
            val badRequest = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals(1, JSONObject(badRequest.body.readUtf8()).getJSONArray("events").length())
            assertTrue(service.uploadPending())
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun consentWithdrawalClearsPendingAndStopsNewRecords() = runBlocking {
        MockWebServer().use { server ->
            acceptConsent()
            val service = service(server)
            service.begin(BtSyncTrigger.MANUAL)!!.finishSuccess()
            val active = service.begin(BtSyncTrigger.MANUAL)!!
            val application = RuntimeEnvironment.getApplication()
            application.getSharedPreferences(PhonePrivacyConsentStore.PREFERENCES_NAME, 0).edit().clear().commit()
            active.finishSuccess()
            assertTrue(service.uploadPending())
            assertNull(service.begin(BtSyncTrigger.MANUAL))
            acceptConsent()
            assertTrue(service.uploadPending())
            assertEquals(0, server.requestCount)
        }
    }

    private fun acceptConsent() =
        PhonePrivacyConsentStore(RuntimeEnvironment.getApplication()).acceptRequiredPolicies()

    private fun service(
        server: MockWebServer,
        schedule: (ExistingWorkPolicy) -> Unit = {}
    ) = BtSyncTelemetry(
        RuntimeEnvironment.getApplication(),
        AccountEnvironment(backendBaseUrl = server.url("/").toString().trimEnd('/')),
        "install-0", scheduleUpload = schedule
    )
}
