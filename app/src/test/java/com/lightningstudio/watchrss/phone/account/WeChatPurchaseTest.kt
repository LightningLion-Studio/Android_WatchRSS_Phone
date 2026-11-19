package com.lightningstudio.watchrss.phone.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WeChatPurchaseTest {
    private val credential = buildString { repeat(32) { append('x') } }
    private val target = "pages/purchase/index?ticket=" + credential
    private val expiry = 2_000L

    private fun payload(path: String = target, expires: Long = expiry) = JSONObject()
        .put("ticket", credential).put("expiresAt", expires).put("miniPath", path).toString()

    @Test fun authenticatedHandoffPassesTheOriginalPathOnlyToTheSdk() = runBlocking {
        var issued = 0
        var closed = 0
        var sent = 0
        val purchase = WeChatPurchase({ true }, "mobile-id", "gh_fixture", {
            assertEquals("mobile-id", it)
            object : WeChatMiniProgram {
                override fun send(userName: String, path: String): Boolean {
                    assertEquals("gh_fixture", userName)
                    assertTrue(path == target)
                    sent++
                    return true
                }
                override fun close() { closed++ }
            }
        }, { 1_000L })
        assertNull(purchase.launch { issued++; parseWeChatHandoff(payload()) })
        assertEquals(1, issued)
        assertEquals(1, sent)
        assertEquals(1, closed)
    }

    @Test fun localFallbacksDoNotRequestATicket() = runBlocking {
        val missing = WeChatPurchase({ false }, "mobile-id", "gh_fixture", { error("unexpected SDK connection") })
        val noAppId = WeChatPurchase({ true }, "", "gh_fixture", { error("unexpected SDK connection") })
        val unavailable = WeChatPurchase({ true }, "mobile-id", "gh_fixture", { throw NoClassDefFoundError() })
        val old = WeChatPurchase({ true }, "mobile-id", "gh_fixture", { null })
        assertEquals(WeChatPurchaseFallback.WECHAT_NOT_INSTALLED, missing.launch { error("unexpected request") })
        assertEquals(WeChatPurchaseFallback.APP_ID_MISSING, noAppId.launch { error("unexpected request") })
        for (purchase in listOf(unavailable, old)) {
            assertEquals(WeChatPurchaseFallback.SDK_UNAVAILABLE, purchase.launch { error("unexpected request") })
        }
    }

    @Test fun rejectedLaunchAndUnavailableHandoffsAreCovered() = runBlocking {
        var sent = 0
        var closed = 0
        val purchase = WeChatPurchase({ true }, "mobile-id", "gh_fixture", {
            object : WeChatMiniProgram {
                override fun send(userName: String, path: String): Boolean { sent++; return false }
                override fun close() { closed++ }
            }
        }, { 1_000L })
        assertEquals(WeChatPurchaseFallback.HANDOFF_UNAVAILABLE, purchase.launch { parseWeChatHandoff(payload(expires = 999L)) })
        assertEquals(WeChatPurchaseFallback.HANDOFF_UNAVAILABLE, purchase.launch { throw WeChatHandoffUnavailable() })
        assertEquals(0, sent)
        assertEquals(WeChatPurchaseFallback.SDK_UNAVAILABLE, purchase.launch { parseWeChatHandoff(payload()) })
        assertEquals(1, sent)
        assertEquals(3, closed)
    }

    @Test fun invalidNavigationParametersCannotEscapeThroughDiagnostics() {
        for (invalid in listOf(
            "https://example.invalid/$target",
            "pages/purchase/index",
            "$target&ticket=duplicate",
            "$target#fragment",
            "pages/../purchase/index?ticket=$credential"
        )) {
            val failure = assertThrows(WeChatHandoffUnavailable::class.java) { parseWeChatHandoff(payload(path = invalid)) }
            assertFalse(failure.toString().contains(credential))
            assertNull(failure.cause)
        }
        assertFalse(parseWeChatHandoff(payload()).toString().contains(credential))
    }

    @Test fun cancellationPropagatesAndReleasesTheSdk() = runBlocking {
        var closed = false
        val purchase = WeChatPurchase({ true }, "mobile-id", "gh_fixture", {
            object : WeChatMiniProgram {
                override fun send(userName: String, path: String): Boolean = error("unexpected launch")
                override fun close() { closed = true }
            }
        })
        try {
            purchase.launch { throw CancellationException("cancelled") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertTrue(closed)
        }
    }
}
