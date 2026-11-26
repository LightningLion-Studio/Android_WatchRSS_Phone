package com.lightningstudio.watchrss.phone.rollout

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.provider.Settings
import com.lightningstudio.watchrss.phone.BuildConfig
import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.data.telemetry.BtSyncDevice
import com.lightningstudio.watchrss.phone.data.telemetry.BtSyncEvent
import com.lightningstudio.watchrss.phone.data.telemetry.BtSyncProtocol
import com.lightningstudio.watchrss.phone.data.telemetry.BtSyncSession
import com.lightningstudio.watchrss.phone.data.telemetry.BtSyncTrigger
import com.lightningstudio.watchrss.phone.features.EvaluateAt
import com.lightningstudio.watchrss.phone.features.FeatureDecision
import com.lightningstudio.watchrss.phone.features.FeatureOverride
import com.lightningstudio.watchrss.phone.features.FeatureRegistry
import com.lightningstudio.watchrss.phone.features.RolloutSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ClientConfigRepositoryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var clock = 1_000_000L
    private val installId = "install-1"
    private val feature = FeatureRegistry.b2

    @org.junit.Before
    fun setBootCount() {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 12)
    }

    @org.junit.After
    fun stopRefreshes() { scope.cancel() }

    @Test
    fun anonymousRequestCachesOnlyRegisteredFeaturesAndReusesThemOfflineAfterRestart() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(config(3, 0, "off")))
            val first = repository(server)
            val downloaded = first.snapshotForSession()
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method)
            assertEquals("/functions/v1/client-config", request.requestUrl!!.encodedPath)
            assertEquals("phone", request.requestUrl!!.queryParameter("platform"))
            assertEquals(BuildConfig.VERSION_CODE.toString(), request.requestUrl!!.queryParameter("appVersionCode"))
            assertEquals(installId, request.requestUrl!!.queryParameter("installId"))
            assertNull(request.getHeader("Authorization"))
            assertEquals(setOf("b2"), downloaded.features.keys)
            assertEquals(downloaded, first.snapshotForSession())
            assertEquals(1, server.requestCount)

            advanceClock(3_601_000)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            val afterRestart = repository(server)
            assertEquals(downloaded, afterRestart.snapshotForSession())
            assertEquals(0, afterRestart.snapshotForSession().rollout(feature).percent)
        }
    }

    @Test
    fun syncHeadPollDefaultsOnAndCanBeDisabledRemotely() = runBlocking {
        MockWebServer().use { server ->
            val repo = repository(server)
            val head = FeatureRegistry.syncHeadPoll
            val oldConfig = JSONObject(config(1, 20, null))
            server.enqueue(MockResponse().setBody(oldConfig.toString()))
            assertTrue(repo.decide(repo.snapshotForSession(), head, eligible = true).enabled)

            advanceClock(601_000)
            oldConfig.put("configVersion", 2)
            oldConfig.getJSONObject("features").put("sync_head_poll_v1", JSONObject()
                .put("percent", 100).put("salt", "head-poll")
                .put("minAppVersionCode", 0).put("maxAppVersionCode", JSONObject.NULL)
                .put("forced", "off"))
            server.enqueue(MockResponse().setBody(oldConfig.toString()))
            val disabled = repo.snapshotForSession()
            assertEquals(2L, disabled.version)
            assertFalse(repo.decide(disabled, head, eligible = true).enabled)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun syncHeadPollRefreshesStaleConfigDuringTheSameForegroundSession() = runTest {
        MockWebServer().use { server ->
            val config = JSONObject(config(1, 20, null))
            server.enqueue(MockResponse().setBody(config.toString()))
            val repo = repository(server)
            assertEquals(1L, withContext(Dispatchers.IO) { repo.snapshotForSession().version })
            val active = MutableStateFlow(true)
            val job = backgroundScope.launch { repo.refreshWhileForeground(active) }
            runCurrent()
            assertEquals(1, server.requestCount)

            advanceClock(601_000)
            config.put("configVersion", 2)
            config.getJSONObject("features").put("sync_head_poll_v1", JSONObject()
                .put("percent", 0).put("salt", "head-poll")
                .put("minAppVersionCode", 0).put("maxAppVersionCode", JSONObject.NULL)
                .put("forced", JSONObject.NULL))
            server.enqueue(MockResponse().setBody(config.toString()))
            advanceTimeBy(60_000)
            runCurrent()
            withContext(Dispatchers.IO) {
                withTimeout(5_000) {
                    repo.changes.first { repo.currentSnapshot().version == 2L }
                }
            }
            assertFalse(repo.decide(repo.currentSnapshot(), FeatureRegistry.syncHeadPoll, eligible = true).enabled)

            active.value = false
            runCurrent()
            advanceClock(601_000)
            advanceTimeBy(600_000)
            assertEquals(2, server.requestCount)
            job.cancel()
        }
    }

    @Test
    fun invalidOrUnavailableFirstResponseUsesBuildDefaults() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody(config(4, 101, "on")))
            val repo = repository(server)
            repeat(2) {
                val snapshot = repo.snapshotForSession()
                assertEquals(0L, snapshot.version)
                assertEquals(feature.defaults, snapshot.rollout(feature))
                assertEquals(20, snapshot.rollout(feature).percent)
                assertFalse(snapshot.stale)
                advanceClock(TimeUnit.DAYS.toMillis(8))
            }
        }
    }

    @Test
    fun inFlightRefreshIsSharedAndDoesNotBlockPastTheSyncBudget() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(config(4, 90, "on")).setBodyDelay(2100, TimeUnit.MILLISECONDS))
            val repo = repository(server)
            val refresh = repo.refreshIfStale()!!
            assertTrue(refresh === repo.refreshIfStale())
            val now = System.nanoTime()
            assertEquals(0L, repo.snapshotForSession().version)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - now) < 2050)
            refresh.await()
            assertEquals(4L, repo.snapshotForSession().version)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun sessionClosesSessionFeaturesWhenIoCacheLoadExceedsItsBudget() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("watchrss_client_config", Context.MODE_PRIVATE)
        check(prefs.edit()
            .putString("install_id", installId)
            .putString("last_config", config(3, 100, null))
            .putLong("fetched_at", clock)
            .commit())
        val caller = Thread.currentThread()
        val loadStarted = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)
        val delayedContext = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                check(Thread.currentThread() != caller)
                loadStarted.countDown()
                check(releaseLoad.await(5, TimeUnit.SECONDS))
                return super.getSharedPreferences(name, mode)
            }
        }
        val repo = ClientConfigRepository(delayedContext, AccountEnvironment(backendBaseUrl = ""),
            installId, scope, now = { clock })
        try {
            assertTrue(loadStarted.await(2, TimeUnit.SECONDS))
            val cold = repo.snapshotForSession()
            assertEquals(0L, cold.version)
            assertFalse(cold.cacheLoaded)
            assertEquals(0, cold.rollout(feature).percent)
            val launchFeature = feature.copy(evaluateAt = EvaluateAt.LAUNCH)
            assertEquals(feature.defaults, cold.rollout(launchFeature))
        } finally {
            releaseLoad.countDown()
        }
        assertEquals(3L, repo.snapshotForSession(waitForRefresh = false).version)
    }

    @Test
    fun snapshotFreezesEachDecisionAndRejectsUnguardedPeers() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(config(3, 100, "on")))
            val repo = repository(server)
            val offer = JSONObject().put("guardedLibraryVersion", 1).put("libraryBinary", JSONObject())
            assertEquals(B2Cohort.INELIGIBLE, repo.decideB2(true, null).cohort)
            assertEquals(B2Cohort.INELIGIBLE, repo.decideB2(false, offer).cohort)
            val first = repo.decideB2(true, offer)
            assertTrue(first.enabled)
            assertEquals(B2Cohort.FORCED_ON, first.cohort)

            advanceClock(3_601_000)
            server.enqueue(MockResponse().setBody(config(4, 0, "on")))
            val next = repo.decideB2(true, offer)
            assertFalse(next.enabled)
            assertEquals(4L, next.configVersion)
            assertEquals(3L, first.configVersion)
        }
    }

    @Test
    fun oldServerTtlIsCappedAndOlderThanSevenDaysShutsOffUntilRefresh() = runBlocking {
        MockWebServer().use { server ->
            // install-1 falls in bucket 41 with the fixture salt.
            server.enqueue(MockResponse().setBody(config(3, 42, null)))
            val repo = repository(server)
            val offer = JSONObject().put("guardedLibraryVersion", 1).put("libraryBinary", JSONObject())
            assertEquals(600L, repo.snapshotForSession().ttlSeconds)
            val first = repo.decideB2(true, offer)
            assertEquals(B2RolloutDecision(true, B2Cohort.ON, 3), first)
            advanceClock(599_999)
            assertNull(repo.refreshIfStale())
            advanceClock(1)
            server.enqueue(MockResponse().setResponseCode(503))
            assertEquals(first, repo.decideB2(true, offer))
            assertEquals(2, server.requestCount)

            advanceClock(TimeUnit.DAYS.toMillis(6) + TimeUnit.HOURS.toMillis(23) - 600_000)
            server.enqueue(MockResponse().setResponseCode(503))
            assertEquals(first, repo.decideB2(true, offer))

            advanceClock(TimeUnit.HOURS.toMillis(1))
            assertFalse(repo.currentSnapshot().stale)
            advanceClock(1)
            server.enqueue(MockResponse().setResponseCode(503))
            val expired = repo.decideB2(true, offer)
            assertEquals(B2RolloutDecision(false, B2Cohort.OFF, 3, configStale = true), expired)
            assertTrue(first.enabled)
            assertFalse(first.configStale)
            assertEquals(expired, repo.decideB2(false, null))
            val staleSnapshot = repo.currentSnapshot()
            val launchFeature = feature.copy(evaluateAt = EvaluateAt.LAUNCH)
            assertEquals(
                FeatureDecision(false, RolloutSource.STALE),
                repo.decide(staleSnapshot, feature, eligible = true)
            )
            assertEquals(
                FeatureDecision(true, RolloutSource.BUCKET),
                repo.decide(staleSnapshot, launchFeature, eligible = true)
            )

            val events = mutableListOf<BtSyncEvent>()
            BtSyncSession(
                BtSyncDevice(installId, "fixture", 121, "sample", "sample", "14", 34),
                BtSyncTrigger.MANUAL, record = { events += it }, now = { clock }, monotonic = { clock }
            ).apply {
                decision = expired
                protocol = BtSyncProtocol.V1
            }.finishSuccess()
            val event = events.single().data
            assertTrue(event.getBoolean("configStale"))
            assertEquals("b2_off", event.getString("cohort"))
            assertEquals("off", event.getJSONObject("features").getString("b2"))
            assertEquals("v1", event.getString("protocol"))

            server.enqueue(MockResponse().setBody(config(4, 42, null)))
            assertEquals(B2RolloutDecision(true, B2Cohort.ON, 4), repo.decideB2(true, offer))
        }
    }

    @Test
    fun wallClockRollbackCannotExtendCacheAcrossProcessRestartInSameBoot() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 12)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(config(3, 42, null)))
            val repo = repository(server)
            val offer = JSONObject().put("guardedLibraryVersion", 1).put("libraryBinary", JSONObject())
            assertEquals(B2RolloutDecision(true, B2Cohort.ON, 3), repo.decideB2(true, offer))

            clock -= TimeUnit.DAYS.toMillis(8)
            ShadowSystemClock.advanceBy(Duration.ofDays(8))
            clock += TimeUnit.DAYS.toMillis(8)
            val expected = B2RolloutDecision(false, B2Cohort.OFF, 3, configStale = true)
            server.enqueue(MockResponse().setResponseCode(503))
            assertEquals(expected, repo.decideB2(true, offer))
            server.enqueue(MockResponse().setResponseCode(503))
            assertEquals(expected, repository(server).decideB2(true, offer))
        }
    }

    @Test
    fun rebootTreatsUnverifiedAgeAsExpiredUntilRefresh() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 12)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(config(3, 42, null)))
            val repo = repository(server)
            val offer = JSONObject().put("guardedLibraryVersion", 1).put("libraryBinary", JSONObject())
            val enabled = B2RolloutDecision(true, B2Cohort.ON, 3)
            assertEquals(enabled, repo.decideB2(true, offer))

            Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 13)
            // New uptime can already exceed the old fetch's elapsedRealtime.
            ShadowSystemClock.advanceBy(Duration.ofDays(8))
            clock += TimeUnit.DAYS.toMillis(1)
            server.enqueue(MockResponse().setResponseCode(503))
            val expired = B2RolloutDecision(false, B2Cohort.OFF, 3, configStale = true)
            assertEquals(expired, repository(server).decideB2(true, offer))

            Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 14)
            clock -= TimeUnit.DAYS.toMillis(8)
            server.enqueue(MockResponse().setResponseCode(503))
            val after = repository(server)
            assertEquals(expired, after.decideB2(true, offer))

            server.enqueue(MockResponse().setBody(config(4, 42, null)))
            assertEquals(B2RolloutDecision(true, B2Cohort.ON, 4), after.decideB2(true, offer))
        }
    }

    @Test
    fun rollbackAcrossDeviceBootDoesNotResurrectExpiredConfig() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 12)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(config(3, 100, null)))
            val offer = JSONObject().put("guardedLibraryVersion", 1).put("libraryBinary", JSONObject())
            val before = repository(server)
            assertEquals(B2RolloutDecision(true, B2Cohort.ON, 3), before.decideB2(true, offer))

            advanceClock(TimeUnit.DAYS.toMillis(8))
            server.enqueue(MockResponse().setResponseCode(503))
            val expired = B2RolloutDecision(false, B2Cohort.OFF, 3, configStale = true)
            assertEquals(expired, before.decideB2(true, offer))

            clock -= TimeUnit.DAYS.toMillis(7)
            ShadowSystemClock.reset()
            Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 13)
            server.enqueue(MockResponse().setResponseCode(503))
            val after = repository(server)
            assertEquals(expired, after.decideB2(true, offer))
        }
    }

    @Test
    fun oldAnchorAndUnavailableBootCountDoNotProveCacheAge() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("watchrss_client_config", Context.MODE_PRIVATE)
        check(prefs.edit()
            .putString("install_id", installId)
            .putString("last_config", config(3, 100, null))
            .putLong("fetched_at", clock)
            .putInt("fetched_boot", 12)
            .putLong("fetched_elapsed", android.os.SystemClock.elapsedRealtime())
            .commit())
        val offer = JSONObject().put("guardedLibraryVersion", 1).put("libraryBinary", JSONObject())
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            assertEquals(B2RolloutDecision(false, B2Cohort.OFF, 3, configStale = true),
                repository(server).decideB2(true, offer))

            Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
            server.enqueue(MockResponse().setBody(config(4, 100, null)))
            assertEquals(B2RolloutDecision(true, B2Cohort.ON, 4), repository(server).decideB2(true, offer))
            server.enqueue(MockResponse().setResponseCode(503))
            assertEquals(B2RolloutDecision(false, B2Cohort.OFF, 4, configStale = true),
                repository(server).decideB2(true, offer))
        }
    }

    @Test
    fun coldCacheTimeoutDoesNotBypassPreviouslyFetchedZeroPercent() =
        assertColdCacheTimesOutClosed(percent = 0, forced = null)

    @Test
    fun coldCacheTimeoutDoesNotBypassPreviouslyFetchedForcedOff() =
        assertColdCacheTimesOutClosed(percent = 100, forced = "off")

    private fun assertColdCacheTimesOutClosed(percent: Int, forced: String?) = runBlocking<Unit> {
        val app = RuntimeEnvironment.getApplication()
        val inDefaultBucket = "install-0" // Bucket 9 under the compiled 20% default.
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 12)
        val releaseLoad = CountDownLatch(1)
        val delayedContext = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                check(releaseLoad.await(5, TimeUnit.SECONDS))
                return super.getSharedPreferences(name, mode)
            }
        }
        MockWebServer().use { server ->
            val environment = AccountEnvironment(backendBaseUrl = server.url("/").toString().trimEnd('/'))
            val before = ClientConfigRepository(app, environment, inDefaultBucket, scope, now = { clock })
            val offer = JSONObject().put("guardedLibraryVersion", 1).put("libraryBinary", JSONObject())
            val cachedOff = B2RolloutDecision(false, if (forced == null) B2Cohort.OFF else B2Cohort.FORCED_OFF, 3)
            server.enqueue(MockResponse().setBody(config(3, percent, forced)))
            assertEquals(cachedOff, before.decideB2(true, offer))
            val repo = ClientConfigRepository(delayedContext, environment, inDefaultBucket, scope, now = { clock })
            val decision = try { repo.decideB2(true, offer) } finally { releaseLoad.countDown() }
            assertEquals(cachedOff, repo.decideB2(true, offer))
            assertFalse("Previously fetched shutdown was bypassed: $decision", decision.enabled)
        }
    }

    @Test
    fun localOverrideTakesPrecedenceAndZeroShutsDownWhenFollowingCloud() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(config(3, 1, "on")))
            val repo = repository(server)
            val offer = JSONObject().put("guardedLibraryVersion", 1).put("libraryBinary", JSONObject())
            assertTrue(repo.decideB2(true, offer).enabled)
            advanceClock(7L * 24 * 60 * 60 * 1000 + 1)
            server.enqueue(MockResponse().setResponseCode(503))
            assertEquals(B2RolloutDecision(true, B2Cohort.FORCED_ON, 3, configStale = true),
                repo.decideB2(true, offer))
            repo.setDebugOverride(feature, FeatureOverride.OFF)
            server.enqueue(MockResponse().setResponseCode(503))
            assertEquals(B2RolloutDecision(false, B2Cohort.DEBUG, 3, configStale = true),
                repo.decideB2(true, offer))
            repo.setDebugOverride(feature, FeatureOverride.ON)
            server.enqueue(MockResponse().setBody(config(4, 0, "on")))
            assertEquals(B2RolloutDecision(true, B2Cohort.DEBUG, 4), repo.decideB2(true, offer))
            repo.setDebugOverride(feature, null)
            assertEquals(B2RolloutDecision(false, B2Cohort.OFF, 4), repo.decideB2(true, offer))
        }
    }

    @Test
    fun registeredKeyAbsentInResponseUsesDefaultWithFetchedVersion() {
        val snapshot = parseClientConfig(JSONObject("""{"configVersion":9,"ttlSeconds":300,"features":{"other":42}}"""))
        assertEquals(9L, snapshot.version)
        assertEquals(300L, snapshot.ttlSeconds)
        assertEquals(feature.defaults, snapshot.rollout(feature))
    }

    @Test
    fun restoringOneDebugOverrideRemovesOnlyThatPersistedOverride() = runBlocking {
        MockWebServer().use { server ->
            val repo = repository(server)
            repo.setDebugOverride(feature, FeatureOverride.ON)
            repo.setDebugOverride(FeatureRegistry.syncHeadPoll, FeatureOverride.OFF)
            assertEquals(FeatureOverride.ON, repository(server).loadDebugOverride(feature))
            repo.setDebugOverride(feature, null)
            assertNull(repo.loadDebugOverride(feature))
            val restarted = repository(server)
            assertNull(restarted.loadDebugOverride(feature))
            assertEquals(FeatureOverride.OFF, restarted.loadDebugOverride(FeatureRegistry.syncHeadPoll))
        }
    }

    @Test
    fun cloudEntryUsesDefaultThenLiveConfigAndAlwaysKeepsExistingUsers() = runBlocking {
        MockWebServer().use { server ->
            val feature = FeatureRegistry.cloudSyncEntry
            val repo = repository(server)
            server.enqueue(MockResponse().setResponseCode(503))
            repo.refreshNow()
            assertEquals("未取到，使用包内默认", repo.debugSnapshot().configStatus)
            assertFalse(repo.cloudSyncEntryAllowed(alreadyEnabled = false))
            assertTrue(repo.cloudSyncEntryAllowed(alreadyEnabled = true))

            val salt = feature.defaultSalt
            val bucket = com.lightningstudio.watchrss.phone.features.FeatureRolloutPolicy.bucket(salt, installId)
            fun remote(version: Int, percent: Int) = MockResponse().setBody(
                """{"configVersion":$version,"ttlSeconds":300,"features":{
                    "${feature.key}":{"percent":$percent,"salt":"$salt","minAppVersionCode":0}
                }}"""
            )
            server.enqueue(remote(1, bucket))
            repo.refreshNow()
            assertEquals(false, repo.debugSnapshot().features.single { it.key == feature.key }.remoteEnabled)
            assertFalse(repo.cloudSyncEntryAllowed(alreadyEnabled = false))

            server.enqueue(remote(2, bucket + 1))
            repo.refreshNow() // Must bypass the still-fresh first response.
            val debug = repo.debugSnapshot()
            assertEquals(2L, debug.config.version)
            assertEquals(clock, debug.fetchedAt)
            assertEquals(true, debug.features.single { it.key == feature.key }.remoteEnabled)
            assertEquals("云控 · 放量 ${bucket + 1}% · 命中", debug.features.single { it.key == feature.key }.status)
            assertTrue(repo.cloudSyncEntryAllowed(alreadyEnabled = false))

            repo.setDebugOverride(feature, FeatureOverride.OFF)
            val overridden = repo.debugSnapshot().features.single { it.key == feature.key }
            assertEquals(true, overridden.remoteEnabled)
            assertEquals("本机强制 · 云端为 开（放量 ${bucket + 1}% · 命中）", overridden.status)
            assertFalse(repo.cloudSyncEntryAllowed(alreadyEnabled = false))
            assertTrue(repo.cloudSyncEntryAllowed(alreadyEnabled = true))

            server.enqueue(MockResponse().setResponseCode(503))
            repo.refreshNow()
            assertEquals("未取到新配置，使用上次成功的缓存", repo.debugSnapshot().configStatus)
            val restarted = repository(server)
            assertEquals(FeatureOverride.OFF, restarted.loadDebugOverride(feature))
            restarted.clearDebugOverrides()
            assertTrue(restarted.cloudSyncEntryAllowed(alreadyEnabled = false))

            server.enqueue(remote(3, 0))
            restarted.refreshNow()
            assertFalse(restarted.cloudSyncEntryAllowed(alreadyEnabled = false))
            restarted.setDebugOverride(feature, FeatureOverride.ON)
            assertTrue(restarted.cloudSyncEntryAllowed(alreadyEnabled = false))
            restarted.setDebugOverride(FeatureRegistry.b2, FeatureOverride.ON)
            assertTrue(restarted.b2BuildEligible())
        }
    }

    @Test
    fun debugSourceUsesEffectiveCloudDecisionAndShowsRetainedAccess() = runBlocking {
        MockWebServer().use { server ->
            val feature = FeatureRegistry.cloudSyncEntry
            val repo = repository(server)
            fun state(retained: Boolean = false) =
                repo.debugSnapshot(retained).features.single { it.key == feature.key }
            suspend fun refresh(version: Int, percent: Int, forced: String) {
                server.enqueue(MockResponse().setBody(
                    """{"configVersion":$version,"ttlSeconds":300,"features":{
                        "${feature.key}":{"percent":$percent,"salt":"fixture","minAppVersionCode":0,"forced":"$forced"}
                    }}"""
                ))
                repo.refreshNow()
            }

            refresh(1, 0, "on")
            assertEquals(FeatureOverride.ON, state().remote?.forced)
            assertFalse(state().enabled)
            assertEquals("云控 · 放量 0% · 未命中", state().status)
            assertTrue(state(retained = true).enabled)
            assertEquals("已启用账号 · 云控 · 放量 0% · 未命中", state(retained = true).status)

            repo.setDebugOverride(feature, FeatureOverride.ON)
            assertTrue(state().enabled)
            assertEquals("本机强制 · 云端为 关（放量 0% · 未命中）", state().status)
            repo.setDebugOverride(feature, null)

            refresh(2, 100, "on")
            assertTrue(state().enabled)
            assertEquals("云端强制开", state().status)
            repo.setDebugOverride(feature, FeatureOverride.OFF)
            assertFalse(state().enabled)
            assertEquals("本机强制 · 云端为 开（云端强制开）", state().status)

            repo.setDebugOverride(feature, null)
            refresh(3, 100, "off")
            assertFalse(state().enabled)
            assertEquals("云端强制关", state().status)
        }
    }

    private fun advanceClock(millis: Long) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(millis))
        clock += millis
    }

    private fun repository(server: MockWebServer): ClientConfigRepository = ClientConfigRepository(
        RuntimeEnvironment.getApplication(),
        AccountEnvironment(backendBaseUrl = server.url("/").toString().trimEnd('/')),
        installId, scope, OkHttpClient.Builder().retryOnConnectionFailure(false)
            .callTimeout(3, TimeUnit.SECONDS).build(),
        now = { clock }
    )

    private fun config(version: Int, percent: Int, forced: String?) = """
        {"configVersion":$version,"ttlSeconds":3600,"features":{
          "b2":{"percent":$percent,"salt":"b2-2026-11","minAppVersionCode":0,"maxAppVersionCode":null,"forced":${forced?.let(JSONObject::quote) ?: "null"}},
          "other":{"unknownShape":true}
        }}
    """.trimIndent()
}
