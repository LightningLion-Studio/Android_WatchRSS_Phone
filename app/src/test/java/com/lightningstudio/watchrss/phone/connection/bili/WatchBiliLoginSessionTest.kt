package com.lightningstudio.watchrss.phone.connection.bili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchBiliLoginSessionTest {
    @Test
    fun publishesOnlyACompleteSessionToTheWatch() {
        WatchBiliLoginSession.begin()
        assertEquals("waiting", WatchBiliLoginSession.watchResponse().getString("status"))

        WatchBiliLoginSession.complete(
            cookie = "DedeUserID=42; SESSDATA=session; bili_jct=csrf",
            refreshToken = "refresh"
        )

        val response = WatchBiliLoginSession.watchResponse()
        assertEquals("success", response.getString("status"))
        assertTrue(response.getString("cookie").contains("SESSDATA="))
        assertEquals("refresh", response.getString("refreshToken"))
    }

    @Test
    fun rejectsACookieWithoutSessionData() {
        WatchBiliLoginSession.begin()

        val error = runCatching {
            WatchBiliLoginSession.complete(cookie = "sid=only", refreshToken = "")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertFalse(WatchBiliLoginSession.watchResponse().has("cookie"))
    }
}
