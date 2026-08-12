package com.lightningstudio.watchrss.phone.network

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchRssAppVersionHeaderTest {
    @Test
    fun `value contains only client and supplied version fields`() {
        assertEquals("phone-1.2.3+45", watchRssAppVersionHeaderValue("1.2.3", 45))
    }

    @Test
    fun `builder sets one canonical app version header`() {
        val request = Request.Builder()
            .url("https://backend.example/functions/v1/account/security")
            .header(WATCHRSS_APP_VERSION_HEADER, "stale")
            .withWatchRssAppVersionHeader()
            .build()

        assertEquals(1, request.headers(WATCHRSS_APP_VERSION_HEADER).size)
        assertEquals(watchRssAppVersionHeaderValue(), request.header(WATCHRSS_APP_VERSION_HEADER))
    }
}
