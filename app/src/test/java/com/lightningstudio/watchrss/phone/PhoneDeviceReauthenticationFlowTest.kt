package com.lightningstudio.watchrss.phone

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import com.lightningstudio.watchrss.phone.account.*
import com.lightningstudio.watchrss.phone.cloud.*
import com.lightningstudio.watchrss.phone.data.backup.WatchRssBackupService
import com.lightningstudio.watchrss.phone.data.db.*
import com.lightningstudio.watchrss.phone.data.note.NoteRepository
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetRepository
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import com.lightningstudio.watchrss.phone.data.telemetry.PhoneUsageTelemetry
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/** Actual production dialog and AccountScreen; only platform Passkey and secure storage are replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(application=android.app.Application::class,sdk=[35],qualifiers="w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneDeviceReauthenticationFlowTest {
    @get:Rule val compose=createComposeRule()
    private val restoration=androidx.compose.ui.test.junit4.StateRestorationTester(compose)
    private val context=RuntimeEnvironment.getApplication()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val server=MockWebServer()
    private val db=Room.inMemoryDatabaseBuilder(context,PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
    private val store=EncryptedAccountSessionStore(context).also { ReflectionHelpers.setField(it,"prefsRef",context.getSharedPreferences("device-flow-session",Context.MODE_PRIVATE)) }
    private val keys=CloudKeyManager(context,preferencesOverride=context.getSharedPreferences("device-flow-keys",Context.MODE_PRIVATE))
    private val appStore=AppAccessStore(context,preferencesOverride=context.getSharedPreferences("device-flow-access",Context.MODE_PRIVATE))
    private val cloudState=context.getSharedPreferences("device-flow-cloud",Context.MODE_PRIVATE)
    private val root=ByteArray(32){it.toByte()}
    private lateinit var identity: LicenseDeviceIdentity
    private val leaseKeys = java.security.KeyPairGenerator.getInstance("EC").apply {
        initialize(256)
    }.generateKeyPair()
    private val publicPem = "-----BEGIN PUBLIC KEY-----" +
        java.util.Base64.getEncoder().encodeToString(leaseKeys.public.encoded) +
        "-----END PUBLIC KEY-----"

    private fun validLease(): String {
        fun base64Url(bytes: ByteArray) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val payload = JSONObject()
            .put("licenseDeviceId", identity.deviceId)
            .put("expiresAt", System.currentTimeMillis() / 1000 + 3600)
        val signed = base64Url("""{"alg":"ES256"}""".toByteArray()) + "." +
            base64Url(payload.toString().toByteArray())
        val der = java.security.Signature.getInstance("SHA256withECDSA").run {
            initSign(leaseKeys.private)
            update(signed.toByteArray())
            sign()
        }
        // A P-256 JWS uses two fixed-width coordinates in place of the DER integers.
        fun coordinate(start: Int, size: Int): ByteArray {
            val bytes = der.copyOfRange(start, start + size).takeLast(32).toByteArray()
            return ByteArray(32 - bytes.size) + bytes
        }
        val rLength = der[3].toInt() and 255
        val sLength = der[5 + rLength].toInt() and 255
        return "$signed.${base64Url(coordinate(4, rLength) + coordinate(6 + rLength, sLength))}"
    }

    private val requests=java.util.concurrent.CopyOnWriteArrayList<String>()
    private lateinit var repo:PhoneAccountRepository
    private lateinit var coordinator:AppAccessCoordinator
    private lateinit var cloud:PhoneCloudSyncService
    private lateinit var telemetry:PhoneUsageTelemetry
    @Volatile private var completed=0
    @Volatile private var claimStatus=200
    private var loginCount=0
    private var releases=0
    private var clears=0
    private lateinit var beforeState:Map<String,*>
    @Before fun setup()=runBlocking<Unit>{
        android.provider.Settings.Global.putInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 100)
        java.security.Security.addProvider(object:java.security.Provider("device-flow-keystore",1.0,"synthetic identity") {
            init {put("KeyStore.AndroidKeyStore",com.lightningstudio.watchrss.phone.cloud.CloudContentRebuildIntegrationTest.FixtureKeyStore::class.java.name)}
        })
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            val path=request.path.orEmpty().substringBefore('?');requests+=path
            if(path.endsWith("/phone-authorizations/claim") && claimStatus!=200) {
                return MockResponse().setResponseCode(claimStatus).setBody("""{"error":"unauthorized"}""")
            }
            val body=when {
                path.endsWith("/account/login/start")->"""{"transactionId":"login","requiredFactorCount":1,"completedFactors":[],"complete":false}"""
                path.endsWith("/account/app-access")->"""{"accessMode":"paid","deviceStatus":"authorized"}"""
                path.endsWith("/phone-authorizations/challenge")->JSONObject().put("challengeId","challenge").put("nonce","nonce").put("expiresAt",System.currentTimeMillis()+60000).toString()
                path.endsWith("/phone-authorizations/claim")->JSONObject().put("deviceAccessToken","new-device").put("deviceAccessTokenExpiresAt",Long.MAX_VALUE).put("lease",validLease()).put("leaseExpiresAt",Long.MAX_VALUE).put("serverTime",System.currentTimeMillis()).put("access",JSONObject().put("accessMode","paid").put("deviceStatus","authorized")).toString()
                else->"{}"
            }
            return MockResponse().setBody(body)
        }}
        server.start();val environment=AccountEnvironment(backendBaseUrl=server.url("/").toString().trimEnd('/'),supabaseAnonKey="synthetic",appAccessPublicKey=publicPem)
        identity=LicenseDeviceIdentity(context);val install=PhoneInstallationIdentity(context)
        repo=PhoneAccountRepository(environment,store,install,PhoneAccountClient(environment,identity),"phone")
        store.save(PhoneAccountSession("owner","phone","old-account","",Long.MAX_VALUE))
        repo.onClearCloudState={error("same-account flow must preserve cloud custody")}
        repo.onReleaseCloudAuthorization={error("reauthentication must not release cloud authorization")}
        keys.bindBackend(environment.backendBaseUrl)
        keys.saveAccountKey("owner",1,root,true);keys.syncV2Write("owner","secret/K_id","synthetic-secret")
        cloudState.edit().putString("epoch","epoch-A").putLong("pending",17).commit()
        coordinator=AppAccessCoordinator(context,repo,identity,scope,appStore,environmentOverride=environment)
        val articles=PhoneCompanionRepository(db.phoneSavedItemDao(),db.phoneArticleDao(),db.phoneRssSourceDao(),"phone",db.syncChangeLogDao(),db.syncPeerStateDao(),appMetaDao=db.appMetaDao())
        val reader=ReaderPresetRepository(context,db,db.readerPresetDao(),"phone",scope)
        val backup=WatchRssBackupService(context,db,articles,reader,"phone")
        cloud=PhoneCloudSyncService(context,repo,backup,articles,NoteRepository(db.noteDao(),"phone"),"phone",PhoneCloudClient(environment),keys,settings=PhoneCloudStateStore(cloudState))
        cloud.settings.libraryEpoch="epoch-A"
        cloud.settings.lastSnapshotPublishedAt=17L
        val release=repo.onReleaseCloudAuthorization;val clear=repo.onClearCloudState
        repo.onReleaseCloudAuthorization={releases++;release(it)}
        repo.onClearCloudState={clears++;clear(it)}
        beforeState=cloudState.all.toMap()
        assertArrayEquals(root,keys.getAccountKey("owner",1))
        telemetry=PhoneUsageTelemetry(context,environment,install,"phone",repo,scope)
    }
    @After fun close(){scope.cancel();db.close();server.shutdown();java.security.Security.removeProvider("device-flow-keystore")}
    private fun openDeviceLogin() {
        var showAccount by mutableStateOf(false)
        var force=false
        restoration.setContent {
            WatchRssPhoneTheme {
                if (!showAccount) PhoneDeviceVerificationPrompt({})
                else AccountScreen(repo,cloud,emptyList(),telemetry,{},
                    onLoginComplete={completed++},
                    loginWithPasskey={_,_->
                        loginCount++
                        LoginProgress("login",1,listOf("passkey"),true,PhoneAccountSession("owner","phone","new-account","",Long.MAX_VALUE,"activation-proof"))
                            .also {repo.saveCompletedLogin(it)}
                    },createPasskey={},runAction={action->scope.launch{action()}},
                    forceReauthentication=force,accessCoordinatorOverride=coordinator)
            }
        }
        while(Shadows.shadowOf(context).nextStartedActivity!=null){}
        compose.onNodeWithText("前往账号").performClick()
        val intent=Shadows.shadowOf(context).nextStartedActivity
        assertTrue(intent.getBooleanExtra(AccountActivity.EXTRA_REAUTHENTICATE,false));force=true
        runBlocking {repo.beginSameAccountReauthentication()}
        compose.runOnIdle{showAccount=true}
    }
    private fun signIn() {
        compose.onNodeWithText("手机号").assertExists()
        if (loginCount == 0) {
            compose.onNodeWithText("手机号").performTextInput("13800138000")
            compose.onNodeWithContentDescription("切换登录方式").performClick()
            compose.onNodeWithText("通行密钥",useUnmergedTree=true).performClick()
        }
        compose.onNodeWithText("继续").performScrollTo().assertIsEnabled().performClick()
    }
    private fun assertCustodyPreserved() {
        assertArrayEquals(root,keys.getAccountKey("owner",1))
        assertEquals("synthetic-secret",keys.syncV2Read("owner","secret/K_id"))
        assertEquals("epoch-A",cloud.settings.libraryEpoch);assertEquals(17L,cloud.settings.lastSnapshotPublishedAt)
        assertEquals(beforeState,cloudState.all);assertEquals(0,releases);assertEquals(0,clears)
    }
    private fun assertReady() {
        compose.waitUntil(15000){completed==1}
        compose.onNodeWithText("手机号").assertDoesNotExist()
        compose.onNodeWithText("重试设备授权").assertDoesNotExist()
        assertTrue(repo.hasUsableSession);assertNull(repo.reauthenticationOwner.value)
        assertEquals("new-device",coordinator.deviceAccessToken)
        assertCustodyPreserved()
    }
    @Test fun validAccountDeviceVerificationOpensRealLoginFormAndReconcilesWithoutLosingKeys() {
        openDeviceLogin();signIn();assertReady()
        assertTrue(requests.any{it.endsWith("/phone-authorizations/claim")})
    }
    /** Regression from the independent r3 host-page counterexample, now part of the repository. */
    @Test fun reviewAccount401AfterLoginKeepsReauthenticationFormAvailable() {
        claimStatus=401
        openDeviceLogin();signIn()
        compose.waitUntil(15000){repo.reauthenticationOwner.value=="owner" && requests.any{it.endsWith("/phone-authorizations/claim")}}
        compose.onNodeWithText("手机号").assertExists()
        compose.onNodeWithText("登录已失效，请重新登录").performScrollTo().assertIsDisplayed()
        assertEquals(0,completed)
        assertFalse(repo.hasUsableSession);assertEquals(Long.MAX_VALUE,repo.session.value?.expiresAtMillis)
        assertCustodyPreserved();captureEvidence("fix3-claim401-reauthentication-host.png")
        claimStatus=200
        signIn();assertReady()
        assertEquals(2,loginCount)
        assertEquals(2,requests.count{it.endsWith("/account/login/start")})
        assertEquals(2,requests.count{it.endsWith("/phone-authorizations/claim")})
    }
    @Test fun deviceVerificationFailureOffersAuthorizationRetryWithoutAnotherLogin() = retryAuthorization(403)
    @Test fun temporaryFailureOffersAuthorizationRetryWithoutAnotherLogin() = retryAuthorization(503)
    @Test fun rateLimitOffersAuthorizationRetryWithoutAnotherLogin() = retryAuthorization(429)
    private fun retryAuthorization(status:Int) {
        claimStatus=status
        openDeviceLogin();signIn()
        val reason=if(status==403) "手机设备身份需要验证，请重试设备授权" else "账号服务暂时不可用，请稍后重试"
        compose.waitUntil(15000){compose.onAllNodesWithText("重试设备授权").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText(reason).assertExists()
        compose.onNodeWithText("重试设备授权").performScrollTo().assertIsEnabled()
        assertEquals(0,completed);assertTrue(repo.hasUsableSession);assertNull(repo.reauthenticationOwner.value)
        assertCustodyPreserved();captureEvidence("fix3-claim$status-retry-host.png")
        claimStatus=200
        compose.onNodeWithText("重试设备授权").performClick();assertReady()
        assertEquals(1,loginCount)
        assertEquals(2,requests.count{it.endsWith("/phone-authorizations/claim")})
    }
    @Test fun authorizationRetry401ReturnsToLoginWithoutLogout() {
        claimStatus=503
        openDeviceLogin();signIn()
        compose.waitUntil(15000){compose.onAllNodesWithText("重试设备授权").fetchSemanticsNodes().isNotEmpty()}
        claimStatus=401
        compose.onNodeWithText("重试设备授权").performScrollTo().performClick()
        compose.waitUntil(15000){repo.reauthenticationOwner.value=="owner"}
        compose.onNodeWithText("手机号").assertExists()
        compose.onNodeWithText("登录已失效，请重新登录").assertExists()
        assertEquals(0,completed);assertCustodyPreserved()
        claimStatus=200
        signIn();assertReady()
    }
    /** Regression from the independent r4 StateRestorationTester probe. */
    @Test fun reviewTemporaryFailureKeepsValidAccountAndRetryAfterSavedStateRestore() = restoreAuthorizationRetry(503)
    @Test fun deviceVerificationRetrySurvivesSavedStateRestore() = restoreAuthorizationRetry(403)
    @Test fun rateLimitRetrySurvivesSavedStateRestore() = restoreAuthorizationRetry(429)
    private fun restoreAuthorizationRetry(status:Int) {
        claimStatus=status;openDeviceLogin();signIn()
        compose.waitUntil(15000){compose.onAllNodesWithText("重试设备授权").fetchSemanticsNodes().isNotEmpty()}
        assertTrue(repo.hasUsableSession);assertCustodyPreserved()
        val beforeRequests=requests.count{it.endsWith("/phone-authorizations/claim")}
        repeat(2) {
            restoration.emulateSavedInstanceStateRestore()
            compose.waitForIdle()
            assertTrue("screen recreation must not pause the account again after a temporary device failure",repo.hasUsableSession)
            assertNull(repo.reauthenticationOwner.value)
            compose.onNodeWithText("手机号").assertDoesNotExist()
            compose.onNodeWithText("重试设备授权").performScrollTo().assertIsEnabled()
            compose.onNodeWithText(if(status==403) "手机设备身份需要验证，请重试设备授权" else "账号服务暂时不可用，请稍后重试").assertExists()
            assertEquals(beforeRequests,requests.count{it.endsWith("/phone-authorizations/claim")})
            assertEquals(0,completed);assertCustodyPreserved()
        }
        captureEvidence("fix4-claim$status-restored-host.png")
        claimStatus=200
        compose.onNodeWithText("重试设备授权").performClick();assertReady()
        assertEquals(1,loginCount)
        assertEquals(beforeRequests+1,requests.count{it.endsWith("/phone-authorizations/claim")})
    }
    @Test fun completedAuthenticationSurvivesSavedStateRestoreWithoutConsumingForceAgain() {
        openDeviceLogin();signIn();assertReady()
        val beforeRequests=requests.count{it.endsWith("/phone-authorizations/claim")}
        repeat(2) {
            restoration.emulateSavedInstanceStateRestore()
            compose.waitForIdle()
            assertReady()
            assertEquals(beforeRequests,requests.count{it.endsWith("/phone-authorizations/claim")})
        }
        assertEquals(1,loginCount)
    }
    @Test fun accountReauthenticationFormAndReasonSurviveSavedStateRestore() {
        claimStatus=401;openDeviceLogin();signIn()
        compose.waitUntil(15000){repo.reauthenticationOwner.value=="owner" && requests.any{it.endsWith("/phone-authorizations/claim")}}
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithText("手机号").assertExists()
        compose.onNodeWithText("登录已失效，请重新登录").assertExists()
        assertEquals("owner",repo.reauthenticationOwner.value)
        assertEquals(0,completed);assertCustodyPreserved()
        // Fields and factor state intentionally reset; start a fresh authentication transaction.
        claimStatus=200
        compose.onNodeWithText("手机号").performTextInput("13800138000")
        compose.onNodeWithContentDescription("切换登录方式").performClick()
        compose.onNodeWithText("通行密钥",useUnmergedTree=true).performClick()
        signIn();assertReady()
        assertEquals(2,requests.count{it.endsWith("/account/login/start")})
    }
    @Test fun restoredAuthorizationRetryIsNotAppliedToAnotherAccount() {
        claimStatus=503;openDeviceLogin();signIn()
        compose.waitUntil(15000){compose.onAllNodesWithText("重试设备授权").fetchSemanticsNodes().isNotEmpty()}
        val beforeRequests=requests.count{it.endsWith("/phone-authorizations/claim")}
        // Simulate a session replaced outside this screen; repository switch cleanup has its own tests.
        runBlocking {store.save(PhoneAccountSession("other-owner","other-phone","other-account","",Long.MAX_VALUE))}
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithText("重试设备授权").assertDoesNotExist()
        compose.onNodeWithText("手机号").assertDoesNotExist()
        assertTrue(repo.hasUsableSession);assertNull(repo.reauthenticationOwner.value)
        assertEquals(beforeRequests,requests.count{it.endsWith("/phone-authorizations/claim")})
        assertEquals(0,completed)
    }
    private fun captureEvidence(name:String) {
        System.getenv("WRSS_ACCTGATE_EVIDENCE")?.let { path ->
            compose.runOnIdle {
                val global=ReflectionHelpers.callStaticMethod<Any>(Class.forName("android.view.WindowManagerGlobal"),"getInstance")
                val view=ReflectionHelpers.getField<List<android.view.View>>(global,"mViews").last()
                assertTrue(view.width>0 && view.height>0)
                val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                java.io.File(path,name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
            }
        }
    }
}
