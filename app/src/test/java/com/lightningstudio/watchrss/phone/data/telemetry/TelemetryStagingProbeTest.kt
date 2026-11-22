package com.lightningstudio.watchrss.phone.data.telemetry

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.lightningstudio.watchrss.phone.PhoneCompanionContainer
import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.privacy.PhonePrivacyConsentStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TelemetryStagingProbeTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val consent get() = PhonePrivacyConsentStore(app)
    private val queueFile get() = File(app.noBackupFilesDir, "bt_sync_events.json")

    private fun recorder() = BtSyncTelemetry(app, AccountEnvironment(backendBaseUrl = ""), "review-install")

    @Test
    fun deniedConsentSkipsInitializationAndRevokedConsentSkipsPendingWrite() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        try {
            assertNull(BtSyncTelemetry.beginWithBudget(app, scope, BtSyncTrigger.MANUAL, 1_500) {
                error("Unconsented attempt initialized the recorder")
            })
            consent.acceptRequiredPolicies()
            val session = BtSyncTelemetry.beginWithBudget(app, scope, BtSyncTrigger.MANUAL, 150) {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                recorder()
            }
            assertEquals(0L, started.count)
            session?.finishDiscoveryFailure("clear_probe_not_found")
            assertFalse(queueFile.exists())
            assertTrue(app.getSharedPreferences(PhonePrivacyConsentStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit())
            release.countDown()
            withTimeout(3_000) { scope.coroutineContext.job.children.toList().joinAll() }
            assertFalse("Revoked consent allowed a pending event to reach disk", queueFile.exists())
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @Test
    fun consentGrantedAfterTerminalEventDoesNotAuthorizeThatEvent() = runBlocking {
        val release = CountDownLatch(1)
        val consentReadStarted = CountDownLatch(1)
        val scheduled = CompletableDeferred<Unit>()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                if (name == PhonePrivacyConsentStore.PREFERENCES_NAME) {
                    consentReadStarted.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                return super.getSharedPreferences(name, mode)
            }
        }
        try {
            assertFalse(consent.hasRequiredConsent())
            val container = PhoneCompanionContainer(context)
            val manager = container.bluetoothSyncManager
            manager.b2RolloutConfig = { null }
            manager.btSyncTelemetry = {
                BtSyncTelemetry(app, AccountEnvironment(backendBaseUrl = "http://127.0.0.1:1"), "review-install",
                    scheduleUpload = { scheduled.complete(Unit) })
            }
            val session = manager.beginBtSync()
            assertEquals(0L, consentReadStarted.count)
            session?.finishDiscoveryFailure("clear_probe_not_found")
            assertFalse(queueFile.exists())
            consent.acceptRequiredPolicies()
            release.countDown()
            withTimeoutOrNull(2_000) { scheduled.await() }
            val rows = BtSyncEventQueue(queueFile).batch()
            assertEquals("Event ended before consent; queued rows after consent", 0, rows.size)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun busyRecorderDoesNotRetainTerminalEvents() = runBlocking {
        consent.acceptRequiredPolicies()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val count = BtSyncEventQueue.MAX_EVENTS + 1
        val sharedRecorder by lazy {
            started.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            recorder()
        }
        try {
            withTimeout(5_000) {
                List(count) {
                    async {
                        val session = BtSyncTelemetry.beginWithBudget(
                            app, scope, BtSyncTrigger.MANUAL, 200
                        ) { sharedRecorder }
                        session?.finishDiscoveryFailure("clear_probe_not_found")
                    }
                }.awaitAll()
            }
            assertEquals(0L, started.count)
            assertFalse("Staged events reached disk before initialization", queueFile.exists())
            val activeJobs = scope.coroutineContext.job.children.count()
            val waitingEvents = activeJobs - count
            assertTrue("Staging retained $waitingEvents terminal-event jobs", waitingEvents <= 0)
        } finally {
            scope.cancel()
            release.countDown()
            withTimeout(5_000) { scope.coroutineContext.job.join() }
            assertFalse("Cancelled application scope flushed the staged events", queueFile.exists())
        }
    }

    @Test
    fun initializationFailureCountsOneDropAndDeniedConsentCountsNone() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val before = BtSyncTelemetry.droppedInitializationEvents
        try {
            assertNull(BtSyncTelemetry.beginWithBudget(app, scope, BtSyncTrigger.MANUAL, 1_500) {
                error("Unconsented attempt initialized the recorder")
            })
            assertEquals(before, BtSyncTelemetry.droppedInitializationEvents)

            consent.acceptRequiredPolicies()
            assertNull(BtSyncTelemetry.beginWithBudget(app, scope, BtSyncTrigger.MANUAL, 1_500) {
                error("injected recorder initialization failure")
            })
            assertEquals(before + 1, BtSyncTelemetry.droppedInitializationEvents)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun lateInitializationFailureDoesNotReachTheApplicationExceptionHandler() = runBlocking {
        consent.acceptRequiredPolicies()
        val uncaught = AtomicReference<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, failure -> uncaught.set(failure) })
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        try {
            val session = BtSyncTelemetry.beginWithBudget(app, scope, BtSyncTrigger.MANUAL, 150) {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                throw IllegalStateException("injected recorder initialization failure")
            }
            assertEquals(0L, started.count)
            session?.finishDiscoveryFailure("clear_probe_not_found")
            release.countDown()
            withTimeout(3_000) { scope.coroutineContext.job.children.toList().joinAll() }
            assertNull("Optional telemetry surfaced an uncaught coroutine exception", uncaught.get())
        } finally {
            release.countDown()
            scope.cancel()
        }
    }
}
