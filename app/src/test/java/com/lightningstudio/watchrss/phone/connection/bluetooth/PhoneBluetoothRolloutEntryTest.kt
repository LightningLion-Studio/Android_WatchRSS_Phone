package com.lightningstudio.watchrss.phone.connection.bluetooth

import android.app.Application
import android.content.Context
import com.lightningstudio.watchrss.phone.PhoneCompanionContainer
import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.account.PhoneInstallationIdentity
import com.lightningstudio.watchrss.phone.rollout.B2Cohort
import com.lightningstudio.watchrss.phone.rollout.B2RolloutDecision
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PhoneBluetoothRolloutEntryTest {
    private val guardedOffer = JSONObject()
        .put("guardedLibraryVersion", 1)
        .put("libraryBinary", JSONObject())

    @Test fun zeroPercentSelectsV1() = runBlocking {
        assertEquals(B2RolloutDecision(false, B2Cohort.OFF, 42),
            syncManager(percent = 0).decideLibraryRollout(binaryMerge = true, guardedOffer = guardedOffer))
    }

    @Test fun hundredPercentSelectsB2() = runBlocking {
        assertEquals(B2RolloutDecision(true, B2Cohort.ON, 42),
            syncManager(percent = 100).decideLibraryRollout(binaryMerge = true, guardedOffer = guardedOffer))
    }

    @Test fun forcedOffSelectsV1AtHundredPercent() = runBlocking {
        assertEquals(B2RolloutDecision(false, B2Cohort.FORCED_OFF, 42),
            syncManager(percent = 100, forced = "off")
                .decideLibraryRollout(binaryMerge = true, guardedOffer = guardedOffer))
    }

    @Test fun missingGuardedOfferOrBinaryMergeKeepsB2Ineligible() = runBlocking {
        val manager = syncManager(percent = 100)
        val ineligible = B2RolloutDecision(false, B2Cohort.INELIGIBLE, 42)
        assertEquals(ineligible, manager.decideLibraryRollout(binaryMerge = true))
        assertEquals(ineligible, manager.decideLibraryRollout(binaryMerge = false, guardedOffer = guardedOffer))
    }

    private fun syncManager(percent: Int, forced: String? = null): PhoneBluetoothSyncManager {
        val context = RuntimeEnvironment.getApplication()
        val suffix = AccountEnvironment.active(context).storageSuffix
        val config = JSONObject()
            .put("configVersion", 42)
            .put("ttlSeconds", 600)
            .put("features", JSONObject().put("b2", JSONObject()
                .put("percent", percent)
                .put("salt", "b2-2026-11")
                .put("minAppVersionCode", 0)
                .put("forced", forced ?: JSONObject.NULL)))
        val prefs = context.getSharedPreferences("watchrss_client_config$suffix", Context.MODE_PRIVATE)
        check(prefs.edit()
            .putString("install_id", PhoneInstallationIdentity(context).installId)
            .putString("last_config", config.toString())
            .putLong("fetched_at", System.currentTimeMillis())
            .commit())
        return PhoneCompanionContainer(context).bluetoothSyncManager
    }
}
