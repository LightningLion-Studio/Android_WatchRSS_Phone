package com.lightningstudio.watchrss.phone

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.account.AppAccessCoordinator
import com.lightningstudio.watchrss.phone.account.AppAccessStore
import com.lightningstudio.watchrss.phone.account.EncryptedAccountSessionStore
import com.lightningstudio.watchrss.phone.account.LicenseDeviceIdentity
import com.lightningstudio.watchrss.phone.account.PhoneAccountClient
import com.lightningstudio.watchrss.phone.account.PhoneAccountRepository
import com.lightningstudio.watchrss.phone.account.PhoneAccountSession
import com.lightningstudio.watchrss.phone.account.PhoneInstallationIdentity
import com.lightningstudio.watchrss.phone.cloud.CloudKeyManager
import com.lightningstudio.watchrss.phone.cloud.PhoneCloudClient
import com.lightningstudio.watchrss.phone.cloud.PhoneCloudSyncService
import com.lightningstudio.watchrss.phone.data.backup.WatchRssBackupService
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.data.note.NoteRepository
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetRepository
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import com.lightningstudio.watchrss.phone.data.telemetry.PhoneUsageTelemetry
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeChatPurchaseNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db = Room.inMemoryDatabaseBuilder(context, PhoneCompanionDatabase::class.java).build()
    private val server = MockWebServer()
    private val issueStarted = CountDownLatch(1)
    private val issueCancelled = CountDownLatch(1)
    private val issues = AtomicInteger()
    private val http = OkHttpClient.Builder().eventListener(object : EventListener() {
        override fun canceled(call: Call) {
            if (call.request().url.encodedPath.endsWith("/wechat-handoff/issue")) {
                issueCancelled.countDown()
            }
        }
    }).build()

    @After
    fun close() {
        scope.cancel()
        db.close()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        server.shutdown()
    }

    @Test
    fun backFromCloudSyncCancelsIssuanceAndReturnsToAccount() {
        showAccount()
        startPurchase()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("购买云同步会员").assertDoesNotExist()
        compose.onNodeWithText("账号", substring = false).assertExists()

        assertNoPurchaseAfterLeavingPage()
    }

    @Test
    fun closingEmbeddedAccountDetailCancelsIssuanceWhileHostStaysOpen() {
        var detailOpen by mutableStateOf(true)
        showAccount(detailOpen = { detailOpen })

        startPurchase()
        compose.runOnIdle { detailOpen = false }
        compose.onNodeWithText("我的").assertExists()

        assertNoPurchaseAfterLeavingPage()
    }

    private fun startPurchase() {
        compose.onNodeWithText("购买云同步会员").performScrollTo().performClick()
        assertTrue("No handoff request received", issueStarted.await(2, TimeUnit.SECONDS))
    }

    private fun assertNoPurchaseAfterLeavingPage() {
        assertTrue("Page exit did not cancel the HTTP call", issueCancelled.await(2, TimeUnit.SECONDS))
        compose.runOnIdle {
            assertFalse(compose.activity.isFinishing)
            assertFalse(compose.activity.isDestroyed)
            assertNull(shadowOf(compose.activity).nextStartedActivity)
            assertEquals(1, issues.get())
        }
    }

    private fun showAccount(detailOpen: () -> Boolean = { true }) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/functions/v1/app/wechat-handoff/issue") {
                    issues.incrementAndGet()
                    issueStarted.countDown()
                    return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
                return MockResponse().setResponseCode(503).setBody("{}")
            }
        }
        val environment = AccountEnvironment(
            backendBaseUrl = server.url("/").toString().trimEnd('/'),
            supabaseAnonKey = "fixture-key"
        )
        val store = EncryptedAccountSessionStore(context).also {
            ReflectionHelpers.setField(it, "prefsRef", context.getSharedPreferences("purchase-session", Context.MODE_PRIVATE))
        }
        runBlocking { store.save(PhoneAccountSession("owner", "masked", "fixture-bearer", "", Long.MAX_VALUE)) }
        val identity = LicenseDeviceIdentity(context)
        val installation = PhoneInstallationIdentity(context)
        val accountRepository = PhoneAccountRepository(
            environment, store, installation, PhoneAccountClient(environment, identity, httpClient = http), "phone"
        )
        val coordinator = AppAccessCoordinator(
            context, accountRepository, identity, scope,
            purchaseTestAppAccessStore(context)
        )
        val articles = PhoneCompanionRepository(
            db.phoneSavedItemDao(), db.phoneArticleDao(), db.phoneRssSourceDao(), "phone"
        )
        val reader = ReaderPresetRepository(context, db, db.readerPresetDao(), "phone", scope)
        val cloud = PhoneCloudSyncService(
            context, accountRepository, WatchRssBackupService(context, db, articles, reader, "phone"),
            articles, NoteRepository(db.noteDao(), "phone"), "phone", PhoneCloudClient(environment),
            CloudKeyManager(context, preferencesOverride = context.getSharedPreferences("purchase-keys", Context.MODE_PRIVATE))
        )
        val telemetry = PhoneUsageTelemetry(context, environment, installation, "phone", accountRepository, scope)

        compose.setContent {
            WatchRssPhoneTheme {
                if (detailOpen()) {
                    AccountScreen(
                        accountRepository = accountRepository,
                        cloudSyncService = cloud,
                        rssSources = emptyList(),
                        usageTelemetry = telemetry,
                        onBack = { error("Unexpected Activity exit") },
                        onLoginComplete = {},
                        loginWithPasskey = { _, _ -> error("Unexpected login") },
                        createPasskey = {},
                        runAction = { action -> scope.launch { action() } },
                        initialSection = AccountSection.CLOUD_SYNC,
                        accessCoordinatorOverride = coordinator,
                        isAccountPageActive = detailOpen
                    )
                } else {
                    Text("我的")
                }
            }
        }
    }
}

internal fun purchaseTestAppAccessStore(context: Context): AppAccessStore =
    AppAccessStore(context, preferencesOverride = context.getSharedPreferences("purchase-access", Context.MODE_PRIVATE))
