package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.account.PhoneAccountSession
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CloudSessionRefreshTest {
    @Test fun `rotated session refreshes once and retries original operation with fresh token`() = runBlocking {
        var calls = 0
        var refreshes = 0
        var token = "old-test-device-token"
        val headers = mutableListOf<String>()
        val http = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            headers += chain.request().header("x-watchrss-device-authorization").orEmpty()
            val response = if (++calls == 1) "{\"error\":\"device_session_revoked\"}" else "{}"
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(if (calls == 1) 403 else 200).message("test")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
            val client = PhoneCloudClient(AccountEnvironment(backendBaseUrl = "https://test.invalid"),
                deviceAccessToken = { token }, http = http, refreshDeviceSession = { rejected, user ->
                    assertEquals("old-test-device-token", rejected)
                    assertEquals("user", user)
                    refreshes++
                    token = "new-test-device-token"
                    true
                })
            val session = PhoneAccountSession(userId = "user", phoneMasked = "***", accessToken = "test-session", refreshToken = "", expiresAtMillis = Long.MAX_VALUE)
            client.useEpoch(session, "epoch")
            client.registerDevice(session, "device", "test", "public", 1)
            assertEquals(2, calls)
            assertEquals(1, refreshes)
            assertEquals(listOf("Bearer old-test-device-token", "Bearer new-test-device-token"), headers)

    }
}
