package com.lightningstudio.watchrss.phone.rollout

import android.app.Application
import com.lightningstudio.watchrss.phone.BuildConfig
import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.features.FeatureOverride
import com.lightningstudio.watchrss.phone.features.FeatureRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ClientConfigRepositoryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var clock = 1_000_000L
    private val installId = "install-1"
    private val feature = FeatureRegistry.b2

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

            clock += 3_601_000
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            val afterRestart = repository(server)
            assertEquals(downloaded, afterRestart.snapshotForSession())
            assertEquals(0, afterRestart.snapshotForSession().rollout(feature).percent)
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

            clock += 3_601_000
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
            server.enqueue(MockResponse().setBody(config(3, 100, "on")))
            val repo = repository(server)
            val offer = JSONObject().put("guardedLibraryVersion", 1).put("libraryBinary", JSONObject())
            assertEquals(600L, repo.snapshotForSession().ttlSeconds)
            val first = repo.decideB2(true, offer)
            assertTrue(first.enabled)
            clock += 599_999
            assertNull(repo.refreshIfStale())
            clock++
            server.enqueue(MockResponse().setResponseCode(503))
            assertEquals(first, repo.decideB2(true, offer))
            assertEquals(2, server.requestCount)

            clock += 7L * 24 * 60 * 60 * 1000 - 600_000
            assertFalse(repo.currentSnapshot().stale)
            clock++
            repo.setDebugOverride(feature, FeatureOverride.ON)
            server.enqueue(MockResponse().setResponseCode(503))
            val expired = repo.decideB2(true, offer)
            assertEquals(B2RolloutDecision(false, B2Cohort.OFF, 3, configStale = true), expired)
            assertTrue(first.enabled)
            assertFalse(first.configStale)
            assertEquals(expired, repo.decideB2(false, null))

            repo.setDebugOverride(feature, null)
            server.enqueue(MockResponse().setBody(config(4, 100, "on")))
            assertEquals(B2RolloutDecision(true, B2Cohort.FORCED_ON, 4), repo.decideB2(true, offer))
        }
    }

    @Test
    fun registeredKeyAbsentInResponseUsesDefaultWithFetchedVersion() {
        val snapshot = parseClientConfig(JSONObject("""{"configVersion":9,"ttlSeconds":3600,"features":{"other":42}}"""))
        assertEquals(9L, snapshot.version)
        assertEquals(feature.defaults, snapshot.rollout(feature))
    }

    @Test
    fun debugOverrideCanBeCleared() = runBlocking {
        MockWebServer().use { server ->
            val repo = repository(server)
            repo.setDebugOverride(feature, FeatureOverride.ON)
            assertEquals(if (BuildConfig.DEBUG) FeatureOverride.ON else null, repo.debugOverride(feature))
            repo.setDebugOverride(feature, null)
            assertNull(repo.debugOverride(feature))
        }
    }

    private fun repository(server: MockWebServer): ClientConfigRepository = ClientConfigRepository(
        RuntimeEnvironment.getApplication(),
        AccountEnvironment(backendBaseUrl = server.url("/").toString().trimEnd('/')),
        installId, scope, OkHttpClient.Builder().retryOnConnectionFailure(false)
            .callTimeout(3, TimeUnit.SECONDS).build(),
        now = { clock }
    )

    private fun config(version: Int, percent: Int, forced: String) = """
        {"configVersion":$version,"ttlSeconds":3600,"features":{
          "b2":{"percent":$percent,"salt":"b2-2026-11","minAppVersionCode":0,"maxAppVersionCode":null,"forced":"$forced"},
          "other":{"unknownShape":true}
        }}
    """.trimIndent()
}
