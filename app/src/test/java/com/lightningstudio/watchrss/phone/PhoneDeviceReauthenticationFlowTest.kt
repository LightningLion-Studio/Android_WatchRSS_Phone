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
import org.robolectric.util.ReflectionHelpers

/** Actual production dialog and AccountScreen; only platform Passkey and secure storage are replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(application=android.app.Application::class,sdk=[35],qualifiers="w412dp-h915dp-xhdpi")
class PhoneDeviceReauthenticationFlowTest {
    @get:Rule val compose=createComposeRule()
    private val context=RuntimeEnvironment.getApplication()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val server=MockWebServer()
    private val db=Room.inMemoryDatabaseBuilder(context,PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
    private val store=EncryptedAccountSessionStore(context).also { ReflectionHelpers.setField(it,"prefsRef",context.getSharedPreferences("device-flow-session",Context.MODE_PRIVATE)) }
    private val keys=CloudKeyManager(context,preferencesOverride=context.getSharedPreferences("device-flow-keys",Context.MODE_PRIVATE))
    private val appStore=AppAccessStore(context,preferencesOverride=context.getSharedPreferences("device-flow-access",Context.MODE_PRIVATE))
    private val cloudState=context.getSharedPreferences("device-flow-cloud",Context.MODE_PRIVATE)
    private val root=ByteArray(32){it.toByte()}
    private val requests=java.util.concurrent.CopyOnWriteArrayList<String>()
    private lateinit var repo:PhoneAccountRepository
    private lateinit var coordinator:AppAccessCoordinator
    private lateinit var cloud:PhoneCloudSyncService
    private lateinit var telemetry:PhoneUsageTelemetry
    @Volatile private var result:WatchAccountRefreshResult?=null
    private var releases=0
    private var clears=0
    private lateinit var beforeState:Map<String,*>
    @Before fun setup()=runBlocking<Unit>{
        java.security.Security.addProvider(object:java.security.Provider("device-flow-keystore",1.0,"synthetic identity") {
            init {put("KeyStore.AndroidKeyStore",com.lightningstudio.watchrss.phone.cloud.CloudContentRebuildIntegrationTest.FixtureKeyStore::class.java.name)}
        })
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            val path=request.path.orEmpty().substringBefore('?');requests+=path
            val body=when {
                path.endsWith("/account/login/start")->"""{"transactionId":"login","requiredFactorCount":1,"completedFactors":[],"complete":false}"""
                path.endsWith("/account/app-access")->"""{"accessMode":"paid","deviceStatus":"authorized"}"""
                path.endsWith("/phone-authorizations/challenge")->JSONObject().put("challengeId","challenge").put("nonce","nonce").put("expiresAt",System.currentTimeMillis()+60000).toString()
                path.endsWith("/phone-authorizations/claim")->JSONObject().put("deviceAccessToken","new-device").put("deviceAccessTokenExpiresAt",Long.MAX_VALUE).put("lease","synthetic-lease").put("leaseExpiresAt",Long.MAX_VALUE).put("serverTime",System.currentTimeMillis()).put("access",JSONObject().put("accessMode","paid").put("deviceStatus","authorized")).toString()
                else->"{}"
            }
            return MockResponse().setBody(body)
        }}
        server.start();val environment=AccountEnvironment(backendBaseUrl=server.url("/").toString().trimEnd('/'),supabaseAnonKey="synthetic")
        val identity=LicenseDeviceIdentity(context);val install=PhoneInstallationIdentity(context)
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
    @Test fun validAccountDeviceVerificationOpensRealLoginFormAndReconcilesWithoutLosingKeys() {
        var showAccount by mutableStateOf(false)
        var force=false
        compose.setContent {
            WatchRssPhoneTheme {
                if (!showAccount) PhoneDeviceVerificationPrompt({})
                else AccountScreen(repo,cloud,emptyList(),telemetry,{},
                    onLoginComplete={scope.launch{result=completePhoneAccountAuthentication(coordinator)}},
                    loginWithPasskey={_,_->
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
        compose.onNodeWithText("手机号").assertExists().performTextInput("13800138000")
        compose.onNodeWithContentDescription("切换登录方式").performClick()
        compose.onNodeWithText("通行密钥",useUnmergedTree=true).performClick()
        compose.onNodeWithText("继续").performScrollTo().performClick()
        compose.waitUntil(15000){result==WatchAccountRefreshResult.Ready}
        compose.onNodeWithText("手机号").assertDoesNotExist()
        assertTrue(repo.hasUsableSession);assertNull(repo.reauthenticationOwner.value)
        assertEquals("new-device",coordinator.deviceAccessToken)
        assertTrue(requests.any{it.endsWith("/phone-authorizations/claim")})
        assertArrayEquals(root,keys.getAccountKey("owner",1));assertEquals("synthetic-secret",keys.syncV2Read("owner","secret/K_id"))
        assertEquals("epoch-A",cloud.settings.libraryEpoch);assertEquals(17L,cloud.settings.lastSnapshotPublishedAt)
        assertEquals(beforeState,cloudState.all);assertEquals(0,releases);assertEquals(0,clears)
    }
}
