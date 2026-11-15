package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.account.PhoneAccountSession
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Existing cloud-records v1 contract; no real account, network or consent. */
class CloudRecordConsentTest {
    private val session = PhoneAccountSession("owner", "***", "synthetic", "", Long.MAX_VALUE)
    private val epoch = "11111111-1111-4111-8111-111111111111"
    private val generation = "22222222-2222-4222-8222-222222222222"
    private fun client(calls: MutableList<Request>, version: Int = 1) = PhoneCloudClient(
        AccountEnvironment(backendBaseUrl = "https://synthetic.invalid"), http = OkHttpClient.Builder().addInterceptor { chain ->
            calls += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(JSONObject().put("formatVersion", version).put("headRevision", "9").put("recordBytes", 128)
                    .toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
    ).also { it.useEpoch(session, epoch); it.useContentGeneration(session, generation) }

    @Test fun cancellationMakesZeroRequests() = runBlocking {
        val calls = mutableListOf<Request>()
        assertFalse(client(calls).consentRecords(session, false, epoch))
        assertTrue(calls.isEmpty())
    }

    @Test fun confirmationUsesExistingEndpointWithEpochAndNoRecordWrites() = runBlocking {
        val calls = mutableListOf<Request>()
        assertTrue(client(calls).consentRecords(session, true, epoch))
        val request = calls.single()
        assertEquals("POST", request.method)
        assertEquals("/functions/v1/cloud/records/consent", request.url.encodedPath)
        assertEquals(epoch, request.header("x-watchrss-library-epoch"))
        assertEquals(generation, request.header("x-watchrss-content-generation"))
        val bytes = okio.Buffer(); request.body!!.writeTo(bytes)
        assertEquals(1, JSONObject(bytes.readUtf8()).getInt("formatVersion"))
    }

    @Test fun futureReceiptIsNotReportedAsEnabled() = runBlocking {
        val calls = mutableListOf<Request>()
        assertTrue(runCatching { client(calls, 2).consentRecords(session, true, epoch) }.isFailure)
        assertEquals(1, calls.size)
    }
}
