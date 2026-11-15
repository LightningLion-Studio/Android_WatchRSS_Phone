package com.lightningstudio.watchrss.phone.connection.bluetooth

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightningstudio.watchrss.phone.PhoneCompanionApplication
import com.lightningstudio.watchrss.phone.account.*
import com.lightningstudio.watchrss.phone.data.db.*
import com.lightningstudio.watchrss.phone.data.log.BluetoothDebugLog
import com.lightningstudio.watchrss.phone.data.note.NoteRepository
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetRepository
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/** Real Android RFCOMM/IP, manager, repository, coordinator, HTTP and Keystore. Issuer is local. */
@RunWith(AndroidJUnit4::class)
class Hotfix133RadioDeviceTest {
    @Test fun authenticatedTransferToFrozenWatch() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val address = args.getString("watchAddress").orEmpty()
        assumeTrue("This acceptance case requires the task-owned paired watch AVD", address.isNotBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as PhoneCompanionApplication
        val owner = "hotfix133-synthetic-owner"
        val identity = LicenseDeviceIdentity(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val db = Room.inMemoryDatabaseBuilder(context, PhoneCompanionDatabase::class.java).build()
        val store = EncryptedAccountSessionStore(context, prefsName="hotfix133-radio-session")
        val accessStore = AppAccessStore(context, "_hotfix133_radio")
        val signing = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val pem = "-----BEGIN PUBLIC KEY-----" + Base64.getEncoder().encodeToString(signing.public.encoded) + "-----END PUBLIC KEY-----"
        fun lease(): String {
            fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            val payload = JSONObject().put("licenseDeviceId",identity.deviceId).put("expiresAt",System.currentTimeMillis()/1000+3600)
            val input = b64("{\"alg\":\"ES256\"}".toByteArray()) + "." + b64(payload.toString().toByteArray())
            val der = Signature.getInstance("SHA256withECDSA").run { initSign(signing.private);update(input.toByteArray());sign() }
            val rn = der[3].toInt() and 255
            val r = der.copyOfRange(4,4+rn).takeLast(32).toByteArray()
            val sn = der[5+rn].toInt() and 255
            val s = der.copyOfRange(6+rn,6+rn+sn).takeLast(32).toByteArray()
            return input + "." + b64(ByteArray(32-r.size)+r+ByteArray(32-s.size)+s)
        }
        var rejected = false
        var issues = 0
        var refreshedAfterRejection = 0
        val server = MockWebServer()
        server.dispatcher = object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val now = System.currentTimeMillis()
                val response = when {
                    request.path!!.endsWith("/challenge") -> JSONObject().put("challengeId","synthetic").put("nonce","synthetic-nonce").put("expiresAt",now+60000)
                    request.path!!.endsWith("/app-access/refresh") -> {
                        if (rejected) refreshedAfterRejection++
                        JSONObject().put("deviceAccessToken",if(rejected)"synthetic-new" else "synthetic-old")
                            .put("deviceAccessTokenExpiresAt",now+3600000).put("lease",lease()).put("leaseExpiresAt",now+3600000)
                            .put("releaseGrant","synthetic").put("serverTime",now).put("access",JSONObject().put("deviceStatus","authorized").put("accessMode","paid"))
                    }
                    request.path!!.endsWith("/issue-watch-device-token") -> {
                        issues++
                        if (!rejected) {
                            rejected=true
                            return MockResponse().setResponseCode(403).setBody("{\"error\":\"device_session_revoked\"}")
                        }
                        JSONObject().put("watchAccessToken","synthetic-watch-access").put("accessTokenExpiresAt",now+3600000)
                            .put("watchRefreshToken","synthetic-watch-refresh").put("refreshTokenExpiresAt",now+86400000)
                            .put("entitlement",JSONObject().put("plan","paid").put("active",true))
                    }
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(response.toString()).setHeader("Content-Type","application/json")
            }
        }
        server.start()
        try {
            val environment = AccountEnvironment(backendBaseUrl=server.url("/").toString().trimEnd('/'),supabaseAnonKey="synthetic",appAccessPublicKey=pem)
            store.save(PhoneAccountSession(owner,"synthetic","synthetic-account","",Long.MAX_VALUE))
            val account = PhoneAccountRepository(environment,store,PhoneInstallationIdentity(context),
                PhoneAccountClient(environment,identity,deviceAccessToken={accessStore.load()?.deviceAccessToken}),app.container.syncDeviceId)
            val now=System.currentTimeMillis()
            accessStore.save(AppAuthorization("synthetic-old",now+3600000,lease(),now+3600000,"synthetic",AppAccessSummary(deviceStatus="authorized",accessMode="paid"),now))
            val coordinator=AppAccessCoordinator(context,account,identity,scope,accessStore,environmentOverride=environment)
            val repo=PhoneCompanionRepository(db.phoneSavedItemDao(),db.phoneArticleDao(),db.phoneRssSourceDao(),app.container.syncDeviceId,db.syncChangeLogDao(),db.syncPeerStateDao(),appMetaDao=db.appMetaDao())
            repo.replaceSavedItems(com.lightningstudio.watchrss.phone.data.model.PhoneSavedItemType.FAVORITE,
                org.json.JSONArray().put(JSONObject().put("link","https://example.invalid/hotfix133/"+args.getString("expectedRoute"))
                    .put("title","HOTFIX133 synthetic article").put("summary","Synthetic article body for authenticated local transfer.")))
            val reader=ReaderPresetRepository(context,db,db.readerPresetDao(),app.container.syncDeviceId,scope)
            val manager=PhoneBluetoothSyncManager(context,repo,NoteRepository(db.noteDao(),app.container.syncDeviceId),reader,PhoneLlmTokenUsageRepository(db.llmTokenUsageDao()),app.container.syncDeviceId,BluetoothDebugLog(context),
                buildAccountSyncRequest={watch,install,name -> coordinator.buildWatchAccountSyncRequest(watch,install,name).apply {
                    // The issuer transport is a local fixture; the frozen watch requires a secure logical origin.
                    getJSONObject("account").put("backendBaseUrl","https://127.0.0.1:1")
                }},canSyncAccount={account.hasUsableSession},currentPhoneUserId={account.session.value?.userId})
            if (args.getString("expectedRoute")=="ip") {
                val field=AppAccessCoordinator::class.java.getDeclaredField("_state").apply { isAccessible=true }
                @Suppress("UNCHECKED_CAST")
                (field.get(app.container.appAccessCoordinator) as MutableStateFlow<AppAccessState>).value=AppAccessState.Authorized(AppAccessSummary(deviceStatus="authorized",accessMode="paid"),false)
                check(app.startWatchBaseStationIfPermitted())
            }
            val result=withTimeout(180000) { manager.syncAll(PhoneBluetoothWatchDevice("HOTFIX133_WATCH",address,0,supportsPersistentSession=true)) }
            assertNotNull(result.libraryStats)
            assertTrue("Synthetic article must cross the production wire",result.libraryStats!!.sent>=1)
            assertEquals(2,issues)
            assertEquals(1,refreshedAfterRejection)
            val isIp=result.deviceAddress?.startsWith("ip:")==true
            if(args.getString("expectedRoute")=="ip")assertTrue("Expected authenticated IP route",isIp)
            else assertFalse("Expected RFCOMM route",isIp)
            val receipt=JSONObject().put("route",if(isIp)"ip" else "rfcomm").put("accountIssues",issues)
                .put("deviceRefreshAfterRejection",refreshedAfterRejection).put("libraryTransferred",true).put("sentArticles",result.libraryStats!!.sent)
                .put("watchVersion",args.getString("watchVersion"))
            java.io.File(context.getExternalFilesDir(null),"hotfix133-radio.json").writeText(receipt.toString())
        } finally {
            runCatching { app.javaClass.getDeclaredMethod("stopWatchBaseStation").apply { isAccessible=true }.invoke(app) }
            scope.cancel();db.close();server.shutdown();store.clear();accessStore.clear()
        }
    }
}
