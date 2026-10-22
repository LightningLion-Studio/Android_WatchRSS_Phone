package com.lightningstudio.watchrss.phone.connection.bluetooth

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest

class WatchSyncBindingGateTest {
    private fun hash(user: String) = MessageDigest.getInstance("SHA-256").digest(user.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private fun bound(user: String) = JSONObject().put("account", JSONObject().put("bound", true).put("userIdHash", hash(user)))
    private fun exchange(response: JSONObject) = BluetoothSyncExchange("watch", "selected-watch", JSONObject(), response)

    @Test fun unboundStopsBeforeAnySyncOrCredentialIssuanceAndRequiresQrBinding() = runBlocking {
        var writes = 0
        try {
            checkWatchSyncBinding({ "user-1" }) { exchange(JSONObject().put("account", JSONObject().put("bound", false))) }
            writes++
            fail("sync should stop")
        } catch (blocked: WatchSyncBlocked) {
            assertEquals(WatchSyncBindingEvent.RequireWatchBinding, blocked.event)
            assertTrue(blocked.message!!.contains("扫码绑定"))
        }
        assertEquals(0, writes)
    }
    @Test fun otherAccountStopsBeforeSyncAndExplicitlyRequiresLogoutOnWatch() = runBlocking {
        var writes = 0
        try {
            checkWatchSyncBinding({ "user-1" }) { exchange(bound("other-user")) }
            writes++
            fail("sync should stop")
        } catch (blocked: WatchSyncBlocked) {
            assertEquals(WatchSyncBindingEvent.WatchAccountMismatch, blocked.event)
            assertEquals("这块手表已登录其他账号，请先在手表上退出", blocked.message)
            assertFalse(blocked.toString().contains("other-user"))
        }
        assertEquals(0, writes)
    }
    @Test fun boundSameAccountContinuesOnTheExactProbedWatch() = runBlocking {
        val peer = JSONObject().put("account", JSONObject().put("bound", true)
            .put("userIdHash", "c6c289e49e9c05b2145860387b73bcb18df43fb09a1e4a4a9713c76c88bb541b"))
        val address = checkWatchSyncBinding({ "user-1" }) { exchange(peer) }
        assertEquals("selected-watch", address)
        // Standard SHA-256, matching the PodJS node:crypto reference, including non-ASCII UTF-8.
        requireWatchSyncBinding(bound("用户😀"), "用户😀")
    }
    @Test fun legacyAbsentOrNullBindingFieldsContinueWithoutInventingAnUnboundWatch() = runBlocking {
        for (response in listOf(JSONObject(), JSONObject().put("account", JSONObject.NULL),
            JSONObject().put("account", JSONObject()), JSONObject().put("account", JSONObject().put("bound", JSONObject.NULL)))) {
            assertEquals("selected-watch", checkWatchSyncBinding({ "user-1" }) { exchange(response) })
        }
    }
    @Test fun phoneWithoutLoginKeepsExistingBehaviorAndDoesNotEvenRequestAProbe() = runBlocking {
        for (user in listOf(null, "", " ")) {
            var probes = 0
            assertNull(checkWatchSyncBinding({ user }) { probes++; exchange(JSONObject()) })
            assertEquals(0, probes)
        }
    }
    @Test fun phoneOwnerChangeDuringProbeRejectsLateStateInsteadOfAuthorizingAnotherAccount() = runBlocking {
        for (next in listOf(null, "other-user")) {
            var owner: String? = "user-1"
            try {
                checkWatchSyncBinding({ owner }) { owner = next; exchange(bound("user-1")) }
                fail("owner changed")
            } catch (failure: IllegalStateException) { assertFalse(failure is WatchSyncBlocked) }
        }
    }
    @Test fun malformedNewBindingIsAProtocolErrorAndTransportFailureIsNeverTreatedAsLegacy() = runBlocking {
        for (account in listOf(JSONObject().put("bound", "false"), JSONObject().put("bound", true),
            JSONObject().put("bound", true).put("userIdHash", "bad"))) {
            try { checkWatchSyncBinding({ "user-1" }) { exchange(JSONObject().put("account", account)) }; fail("invalid") }
            catch (failure: Exception) { assertFalse(failure is WatchSyncBlocked) }
        }
        try { checkWatchSyncBinding({ "user-1" }) { throw IOException("offline") }; fail("offline") }
        catch (expected: IOException) { assertEquals("offline", expected.message) }
    }
}
