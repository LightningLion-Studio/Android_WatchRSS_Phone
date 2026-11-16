package com.lightningstudio.watchrss.phone.account

import android.content.Context
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=android.app.Application::class,manifest=Config.NONE,sdk=[35])
class AppAccessBootIdentityTest {
    private val prefs=RuntimeEnvironment.getApplication().getSharedPreferences("boot-identity-test",Context.MODE_PRIVATE)
    private var boot:String?="boot-1"
    private fun store()=AppAccessStore(RuntimeEnvironment.getApplication(),preferencesOverride=prefs,bootIdentity={boot})
    private fun authorization()=System.currentTimeMillis().let { AppAuthorization("device",it+3600000,"lease",it+3600000,"",AppAccessSummary(),it) }
    @Test fun processRestartWithinSameBootRetainsServerAnchor() {
        store().save(authorization());assertNotNull(store().trustedNowMillis())
    }
    @Test fun rebootWithGreaterUptimeStillRejectsOldAnchor() {
        store().save(authorization());val old=prefs.getLong("trusted_elapsed",-1)
        SystemClock.sleep(100);assertTrue(SystemClock.elapsedRealtime()>old)
        boot="boot-2";assertNull(store().trustedNowMillis())
    }
    @Test fun unknownBootOrLegacyAnchorCannotAuthorizeOffline() {
        store().save(authorization());boot=null;assertNull(store().trustedNowMillis())
        boot="boot-1";prefs.edit().remove("trusted_boot").commit();assertNull(store().trustedNowMillis())
    }
    @Test fun advancingLocalTimeCannotRebindAnOldBoot() {
        store().save(authorization());boot="boot-2"
        store().recordTrustedTime(System.currentTimeMillis());assertNull(store().trustedNowMillis())
        store().save(authorization());assertNotNull(store().trustedNowMillis())
    }
}
