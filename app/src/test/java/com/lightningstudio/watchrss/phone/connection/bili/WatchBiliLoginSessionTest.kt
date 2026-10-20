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

    @Test
    fun disconnectedSessionCannotPublishLateLogin() {
        val token = WatchBiliLoginSession.begin()
        WatchBiliLoginSession.clear()
        WatchBiliLoginSession.complete("SESSDATA=old", "", token)
        assertFalse(WatchBiliLoginSession.watchResponse().has("cookie"))
        assertFalse(WatchBiliLoginSession.isCurrent(token))
    }

    @Test
    fun previousRequestCannotOverwriteNewLogin() {
        val old = WatchBiliLoginSession.begin()
        val current = WatchBiliLoginSession.begin()
        WatchBiliLoginSession.complete("SESSDATA=current", "", current)
        WatchBiliLoginSession.complete("SESSDATA=old", "", old)
        WatchBiliLoginSession.fail("old request failed", old)
        assertEquals("SESSDATA=current", WatchBiliLoginSession.watchResponse().getString("cookie"))
    }

    @Test
    fun cookieSubstringAndEmptySessionAreRejected() {
        for (cookie in listOf("NOTSESSDATA=value", "SESSDATA=", "SESSDATA=; sid=x")) {
            WatchBiliLoginSession.begin()
            assertTrue(runCatching { WatchBiliLoginSession.complete(cookie, "") }.isFailure)
        }
    }
}
