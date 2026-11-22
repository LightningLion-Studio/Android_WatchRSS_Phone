package com.lightningstudio.watchrss.phone.rollout

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import com.lightningstudio.watchrss.phone.PhoneCompanionContainer
import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneLoginRequired
import com.lightningstudio.watchrss.phone.data.telemetry.BtSyncEventQueue
import com.lightningstudio.watchrss.phone.data.telemetry.BtSyncStage
import com.lightningstudio.watchrss.phone.data.telemetry.BtSyncTelemetry
import com.lightningstudio.watchrss.phone.features.FeatureRegistry
import com.lightningstudio.watchrss.phone.features.FeatureRolloutPolicy
import com.lightningstudio.watchrss.phone.privacy.PhonePrivacyConsentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReviewBoundaryProbeTest {
    @Test
    fun cachePublicationMidSnapshotCannotExposeTwentyPercentDefaults() {
        val app = RuntimeEnvironment.getApplication()
        val feature = FeatureRegistry.b2
        val installId = (0..1_000).map { "review-$it" }.first {
            FeatureRolloutPolicy.bucket(feature.defaults.salt, it) < feature.defaults.percent
        }
        val boot = 12
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, boot)
        val prefs = app.getSharedPreferences("watchrss_client_config", Context.MODE_PRIVATE)
        check(prefs.edit()
            .putString("install_id", installId)
            .putString("last_config", """{"configVersion":3,"ttlSeconds":600,"features":{
                "b2":{"percent":0,"salt":"b2-2026-11","minAppVersionCode":0,"forced":null}
            }}""")
            .putLong("fetched_elapsed_at_download", SystemClock.elapsedRealtime())
            .putInt("fetched_boot_at_download", boot)
            .commit())

        val loadStarted = CountDownLatch(1)
        val finishLoad = CountDownLatch(1)
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                loadStarted.countDown()
                check(finishLoad.await(5, TimeUnit.SECONDS))
                return super.getSharedPreferences(name, mode)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = ClientConfigRepository(context, AccountEnvironment(backendBaseUrl = ""), installId, scope)
            assertTrue(loadStarted.await(2, TimeUnit.SECONDS))
            val storageField = ClientConfigRepository::class.java.getDeclaredField("storage").apply {
                isAccessible = true
            }
            val storage = storageField.get(repo) as Deferred<*>
            // Resume the actual loader between the null cache read and the completion read.
            // Delegate all results to the real loader so the hook only controls scheduling.
            storageField.set(repo, object : Deferred<Any?> by storage {
                override val isCompleted: Boolean
                    get() {
                        finishLoad.countDown()
                        runBlocking { withTimeout(2_000L) { storage.await() } }
                        return storage.isCompleted
                    }
            })

            val switching = repo.b2Decision(repo.currentSnapshot(), eligible = true)
            assertFalse("Stored 0% was bypassed during publication: $switching", switching.enabled)
            finishLoad.countDown()
            runBlocking { withTimeout(2_000L) { storage.await() } }
            assertEquals(B2RolloutDecision(false, B2Cohort.OFF, 3),
                repo.b2Decision(repo.currentSnapshot(), eligible = true))
        } finally {
            finishLoad.countDown()
            scope.cancel()
        }
    }

    @Test
    fun slowTelemetryInitializationPreservesTheFirstLoginFailure() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        PhonePrivacyConsentStore(app).acceptRequiredPolicies()
        val manager = PhoneCompanionContainer(app).bluetoothSyncManager
        val caller = Thread.currentThread()
        val loadStarted = CountDownLatch(1)
        val finishLoad = CountDownLatch(1)
        val scheduled = CountDownLatch(1)
        manager.b2RolloutConfig = { null }
        manager.btSyncTelemetry = {
            check(Thread.currentThread() != caller)
            loadStarted.countDown()
            check(finishLoad.await(5, TimeUnit.SECONDS))
            BtSyncTelemetry(app, AccountEnvironment(backendBaseUrl = "http://127.0.0.1:1"), "review-install",
                scheduleUpload = { scheduled.countDown() })
        }
        try {
            val start = System.nanoTime()
            val first = manager.beginBtSync()
            val waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue("Telemetry start took $waited ms", waited < 2_000L)
            assertEquals("The slow initializer was exercised", 0L, loadStarted.count)
            assertNotNull("First consented start has no session to record a terminal event", first)
            val session = requireNotNull(first)
            session.stage = BtSyncStage.ACCOUNT
            withTimeout(1_000L) {
                session.finishFailure(PhoneLoginRequired())
                session.finishCancelledIfOpen()
            }
            assertEquals("The recorder is still initializing", 1L, scheduled.count)
        } finally {
            finishLoad.countDown()
        }

        assertTrue("The terminal event was not queued", scheduled.await(3, TimeUnit.SECONDS))
        val rows = BtSyncEventQueue(File(app.noBackupFilesDir, "bt_sync_events.json")).batch()
        val event = rows.single().data
        assertEquals("review-install", event.getString("installId"))
        assertEquals("failure", event.getString("result"))
        assertEquals("account", event.getString("failureStage"))
    }
}
