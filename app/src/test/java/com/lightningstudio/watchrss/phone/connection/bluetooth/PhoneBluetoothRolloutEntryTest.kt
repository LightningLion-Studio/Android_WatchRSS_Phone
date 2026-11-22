package com.lightningstudio.watchrss.phone.connection.bluetooth

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.lightningstudio.watchrss.phone.PhoneCompanionContainer
import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.account.PhoneInstallationIdentity
import com.lightningstudio.watchrss.phone.rollout.B2Cohort
import com.lightningstudio.watchrss.phone.rollout.B2RolloutDecision
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    @Test fun initialRepositoryAccessIsInsideSelectionBudget() = runBlocking {
        val manager = syncManager(percent = 0)
        withSlowRepository(manager) {
            val start = System.nanoTime()
            val decision = manager.decideLibraryRollout(binaryMerge = true, guardedOffer = guardedOffer)
            val waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue("Repository access took $waited ms", waited < 2000L)
            assertFalse(requireNotNull(decision).enabled)
        }
    }

    @Test fun syncStartDoesNotWaitForSlowConfigInitialization() = runBlocking {
        val manager = syncManager(percent = 0)
        withSlowRepository(manager) {
            val start = System.nanoTime()
            manager.beginBtSync()
            val waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue("Sync start waited $waited ms for config", waited < 2000L)
        }
    }

    private suspend fun withSlowRepository(manager: PhoneBluetoothSyncManager, block: suspend () -> Unit) {
        val original = manager.b2RolloutConfig
        val release = CountDownLatch(1)
        val caller = Thread.currentThread()
        val timer = Timer(true)
        manager.b2RolloutConfig = {
            check(Thread.currentThread() != caller)
            check(release.await(5, TimeUnit.SECONDS))
            original()
        }
        timer.schedule(object : TimerTask() {
            override fun run() { release.countDown() }
        }, 2200L)
        try {
            block()
        } finally {
            release.countDown()
            timer.cancel()
        }
    }

    private fun syncManager(percent: Int, forced: String? = null): PhoneBluetoothSyncManager {
        val context = RuntimeEnvironment.getApplication()
        val suffix = AccountEnvironment.active(context).storageSuffix
        val boot = 12
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, boot)
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
            .putLong("fetched_elapsed_at_download", SystemClock.elapsedRealtime())
            .putInt("fetched_boot_at_download", boot)
            .commit())
        return PhoneCompanionContainer(context).bluetoothSyncManager
    }
}
