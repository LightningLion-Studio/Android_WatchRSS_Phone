package com.lightningstudio.watchrss.phone.syncv2

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class SyncV2P6TrustedTimeTest {
    @Test fun monotonicRollbackThresholdExpiryAndRestart() {
        var mono=0L;val clock=SyncV2TrustedTime{mono}
        assertNull(clock.now());assertTrue(clock.acceptVerifiedCheckpoint(1000000))
        mono=1000;assertEquals(1001000L,clock.now())
        assertFalse(clock.acceptVerifiedCheckpoint(701000-1));assertEquals(1001000L,clock.now())
        assertTrue(clock.acceptVerifiedCheckpoint(701000));assertEquals(701000L,clock.now())
        mono+=86400000;assertNull(clock.now())
        try{clock.requireNow();fail("expired clock accepted")}catch(e:IllegalStateException){assertEquals("trusted_time_unavailable",e.message)}
        assertNull(SyncV2TrustedTime{mono}.now())
        assertTrue(clock.acceptVerifiedCheckpoint(2000000));mono--;assertNull(clock.now())
    }
    @Test fun defaultElapsedRealtimeAdvancesInIntegerMillis() {
        val clock=SyncV2TrustedTime();clock.acceptVerifiedCheckpoint(1000000)
        val before=clock.requireNow()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1500))
        val after=clock.requireNow()
        assertEquals(1500L,after-before)
    }
    @Test fun onlyFullyVerifiedCheckpointCanSetClock() {
        SyncV2P5Fixture().use {f->
            val clock=SyncV2TrustedTime{0}
            val client=SyncV2CloudClient(f.f.server.url(SyncV2Origin.path),{SyncV2CloudClient.Session(f.identity.accountId.replace(Regex("(.{8})(.{4})(.{4})(.{4})(.{12})"),"$1-$2-$3-$4-$5"),"synthetic-phone","old-epoch","old-generation",f.identity.scopeJson,f.f.signer.deviceId,"synthetic","synthetic")},f.f.trust(),true,trustedTime=clock)
            client.capabilities();client.checkpoint(9000000000L)
            assertEquals(f.f.now,clock.now())
            val route=f.f.route
            f.f.route={request->if(request.requestUrl!!.encodedPath.endsWith("checkpoint"))f.f.response(f.f.signer.signed("wrss.sync.v2/checkpoint-sign",f.window().apply{remove("version")}.put("checkpointId","33".repeat(16)).put("issuedAtMillis",f.f.now+100).put("historyFloorMillis",0)).put("version",2).put("scope",f.identity.scope).put("signature","AAAA"))else route?.invoke(request)}
            try{client.checkpoint();fail("bad signature accepted")}catch(_:Exception){}
            assertEquals(f.f.now,clock.now())
        }
    }
}
