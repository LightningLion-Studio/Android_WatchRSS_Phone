package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.account.PhoneAccountSession
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class PhoneCloudRecordClientTest {
    private val session = PhoneAccountSession(userId = "user", phoneMasked = "***", accessToken = "session", refreshToken = "", expiresAtMillis = Long.MAX_VALUE)
    private fun client(status: Int, json: String, inspect: (Request) -> Unit = {}) = PhoneCloudClient(
        AccountEnvironment(backendBaseUrl = "https://test.invalid"),
        http = OkHttpClient.Builder().addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()).also { it.useEpoch(session, "epoch");it.useContentGeneration(session,"22222222-2222-4222-8222-222222222222") }
    @Test fun missingDeploymentSkipsOnlyRecords() = runBlocking {
        assertNull(client(404, "{}").recordPage(session, "0"))
    }
    @Test fun forbiddenIsNeverDowngradedToLegacySuccess() = runBlocking {
        var failed = false
        try { client(403, "{\"error\":\"forbidden\"}").recordPage(session, "0") }
        catch (error: CloudHttpException) { failed = true; assertEquals(403, error.status) }
        assertTrue(failed)
    }
    @Test fun probeDoesNotGrantConsentAndLimitsPage() = runBlocking {
        val page = client(200, "{\"formatVersion\":0,\"items\":[]}") { request ->
            assertEquals("GET", request.method)
            assertEquals("50", request.url.queryParameter("limit"))
            assertFalse(request.url.toString().contains("consent"))
        }.recordPage(session, "123")
        assertEquals(0, page!!.getInt("formatVersion"))
    }
    @Test fun conflictsRemainTypedForPullMergeRetry() = runBlocking {
        var failed = false
        try { client(409, "{\"error\":\"cloud_record_conflict\"}").postRecords(session, JSONArray()) }
        catch (error: CloudHttpException) { failed = true; assertEquals(409, error.status); assertTrue(error.detail.contains("cloud_record_conflict")) }
        assertTrue(failed)
    }
}
