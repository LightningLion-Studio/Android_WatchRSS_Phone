package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.account.PhoneAccountSession
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneCloudHistoryClientTest {
    private val session = PhoneAccountSession("user", "***", "session", "", Long.MAX_VALUE)
    private fun head(id: String) = JSONObject().put("id", id).put("sourceDeviceId", "phone")
        .put("deviceSequence", 1).put("keyVersion", 1).put("manifestSha256", "a".repeat(64))
        .put("manifestSizeBytes", 32).put("parentHeads", JSONObject()).put("observedHeads", JSONObject())
        .put("completedAt", "2026-10-19T12:00:00Z").put("totalSizeBytes", 1024)

    @Test fun uploadRefreshUsesReservationAndKeepsIntegrityDescriptor() = runBlocking {
        val id = "11111111-1111-4111-8111-111111111111"
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            assertEquals("/functions/v1/cloud/uploads/$id/refresh", chain.request().url.encodedPath)
            assertEquals("POST", chain.request().method)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                .body("{\"uploadUrl\":\"https://test.invalid/functions/v1/cloud/uploads/$id\",\"uploadMethod\":\"PUT\",\"transport\":\"epoch-proxy\"}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = PhoneCloudClient(AccountEnvironment(backendBaseUrl = "https://test.invalid"), http = http).also { it.useEpoch(session, "epoch");it.useContentGeneration(session,"22222222-2222-4222-8222-222222222222") }
        val target = CloudUploadObject("chunk", "a".repeat(64), 123, "owner/path", "https://storage.invalid/expired", "token", "https://storage.invalid/tus", "private", id)
        assertEquals(target.copy(signedUrl="https://test.invalid/functions/v1/cloud/uploads/$id", transport="epoch-proxy", uploadMethod="PUT"), client.refreshUpload(session, target))
        assertEquals(target.copy(reservationId=null), client.refreshUpload(session, target.copy(reservationId=null)))
        assertEquals(1, calls)
    }

    @Test fun verifiedExistingUploadCanSkipTransfer() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                .body("{\"alreadyUploaded\":true}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val target = CloudUploadObject("chunk", "a".repeat(64), 1, "path", "https://storage.invalid/expired", "token", "https://storage.invalid/tus", "private", "11111111-1111-4111-8111-111111111111")
        val result = PhoneCloudClient(AccountEnvironment(backendBaseUrl="https://test.invalid"), http=http).also { it.useEpoch(session, "epoch");it.useContentGeneration(session,"22222222-2222-4222-8222-222222222222") }.refreshUpload(session,target)
        assertTrue(result.alreadyUploaded)
        assertEquals(target.sha256,result.sha256)
    }

    @Test fun readingSignsOnlyManifestOrRequestedChunk() = runBlocking {
        val queries = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            queries += chain.request().url.encodedQuery.orEmpty()
            val body = JSONObject().put("snapshot", head("snapshot").put("manifestSignedUrl", "https://test.invalid/manifest"))
                .put("chunks", JSONArray())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                .body(body.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = PhoneCloudClient(AccountEnvironment(backendBaseUrl = "https://test.invalid"), http = http).also { it.useEpoch(session, "epoch");it.useContentGeneration(session,"22222222-2222-4222-8222-222222222222") }
        client.snapshot(session, "snapshot", manifestOnly = true)
        client.snapshot(session, "snapshot", chunk = "a".repeat(64))
        assertEquals(listOf("manifestOnly=true", "chunk=" + "a".repeat(64)), queries)
        try { client.snapshot(session, "snapshot", chunk = "bad&injected=true"); fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals(2, queries.size)
    }

    @Test fun historyFollowsPagesAndReadsSizeAndTimeWithoutChangingLatestHeads() = runBlocking {
        val offsets = mutableListOf<String?>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("true", request.url.queryParameter("history"))
            offsets += request.url.queryParameter("offset")
            val first = offsets.size == 1
            val body = JSONObject().put("heads", JSONArray().put(head(if (first) "latest" else "old")))
                .put("nextOffset", if (first) 100 else JSONObject.NULL)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                .body(body.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = PhoneCloudClient(AccountEnvironment(backendBaseUrl = "https://test.invalid"), http = http).snapshotHistory(session)
        assertEquals(listOf("0", "100"), offsets)
        assertEquals(listOf("latest", "old"), result.map { it.id })
        assertEquals(1024L, result[1].totalSizeBytes)
        assertEquals("2026-10-19T12:00:00Z", result[1].completedAt)
    }

    @Test fun olderBackendDoesNotDisguiseLatestHeadsAsFullHistory() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                .body("{\"heads\":[]}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            PhoneCloudClient(AccountEnvironment(backendBaseUrl = "https://test.invalid"), http = http).snapshotHistory(session)
            fail("must require backend history support")
        } catch (error: IllegalStateException) { assertTrue(error.message.orEmpty().contains("更新云服务")) }
    }
}
