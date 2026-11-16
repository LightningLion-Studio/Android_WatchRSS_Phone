package com.lightningstudio.watchrss.phone.account

import android.content.Context
import com.lightningstudio.watchrss.phone.connection.bluetooth.BluetoothSyncProtocol
import com.lightningstudio.watchrss.phone.connection.bluetooth.isRetryableBluetoothDisconnect
import com.lightningstudio.watchrss.phone.data.ble.BleDomainStore
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercise the real frame writer AFTER the account builder has returned. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, manifest = Config.NONE, sdk = [35])
class OfflineBluetoothR3ReviewTest {
    private val context = RuntimeEnvironment.getApplication()
    private val origin = "https://delivery.invalid"
    private val environment = AccountEnvironment(backendBaseUrl = origin, supabaseAnonKey = "fixture")
    private val prefs = context.getSharedPreferences("delivery-session", Context.MODE_PRIVATE)
    private fun newStore() = EncryptedAccountSessionStore(context, "delivery-session").also {
        ReflectionHelpers.setField(it, "prefsRef", prefs)
    }
    private val store = newStore()
    private val owner = PhoneAccountSession("owner", "masked", "synthetic-account", "", Long.MAX_VALUE)
    private val key = BleDomainStore.hash("$origin\nowner\nwatch")
    private val scope = BleDomainStore.hash("$origin\nowner")
    private val repo = PhoneAccountRepository(environment, store, PhoneInstallationIdentity(context),
        PhoneAccountClient(environment, LicenseDeviceIdentity(context), { "synthetic-device" },
            OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body(JSONObject().put("watchAccessToken", "synthetic-online-watch")
                        .put("accessTokenExpiresAt", System.currentTimeMillis() + 3600000)
                        .put("watchRefreshToken", "synthetic-refresh")
                        .put("refreshTokenExpiresAt", System.currentTimeMillis() + 7200000).toString().toResponseBody()).build()
            }.build()), "phone")
    private fun grant() = JSONObject().put("action", "syncAccount").put("account", JSONObject()
        .put("userId", "owner").put("phoneMasked", "masked").put("backendBaseUrl", origin)
        .put("watchAccessToken", "synthetic-watch").put("accessTokenExpiresAt", System.currentTimeMillis() + 3600000))

    @Before fun setup() = runBlocking<Unit> {
        store.save(owner)
        store.saveBluetoothGrant(key, grant())
    }

    private suspend fun cached() = repo.cachedBluetoothAccount("watch", System.currentTimeMillis())!!
    private fun refused(request: JSONObject) {
        val output = ByteArrayOutputStream()
        val failure = runCatching { BluetoothSyncProtocol.writeFrame(output, request) }.exceptionOrNull()
        assertTrue(failure is PhoneAccountNetworkRequired)
        assertEquals("需要联网完成账号验证", failure?.message)
        assertFalse(isRetryableBluetoothDisconnect(failure!!))
        assertEquals("Even the length prefix must not escape", 0, output.size())
    }

    private fun keyFor(watch:String)=BleDomainStore.hash("$origin\nowner\n$watch")
    private suspend fun read(watch:String)=repo.cachedBluetoothAccount(watch,System.currentTimeMillis())!!
    private fun delivered(request:JSONObject):JSONObject {
        val output=ByteArrayOutputStream()
        BluetoothSyncProtocol.writeFrame(output,request)
        return BluetoothSyncProtocol.readFrame(ByteArrayInputStream(output.toByteArray()))
    }
    private suspend fun issue(watch:String,tag:String) = store.saveAuthorizedBluetoothGrant(
        keyFor(watch),scope,repo.offlineAuthorizationVersion("owner"),grant().apply {
            getJSONObject("account").put("watchAccessToken","synthetic-$tag")
        })

    @Test fun replacingOneWatchDoesNotInvalidateAnotherWatch()=runBlocking<Unit> {
        store.saveBluetoothGrant(keyFor("second"),grant())
        val first=cached();val second=read("second")
        issue("watch","new-first")
        refused(first)
        assertEquals("syncAccount",delivered(second).getString("action"))
        assertEquals("synthetic-new-first",delivered(cached()).getJSONObject("account").getString("watchAccessToken"))
    }

    @Test fun accountRefusalInvalidatesAllWatchesAndRecoveryOnlyEnablesNewGrants()=runBlocking<Unit> {
        val watches=listOf("watch","second","third")
        watches.drop(1).forEach {store.saveBluetoothGrant(keyFor(it),grant())}
        val pending=watches.map {read(it)}
        repo.recordAuthenticationResponse("owner",403,"{}")
        pending.forEach(::refused)
        repo.confirmOnlineAuthorization("owner",repo.offlineAuthorizationVersion("owner"))
        issue("second","after-recovery")
        pending.forEach(::refused)
        assertNull(repo.cachedBluetoothAccount("watch",System.currentTimeMillis()))
        assertNull(repo.cachedBluetoothAccount("third",System.currentTimeMillis()))
        assertEquals("synthetic-after-recovery",delivered(read("second")).getJSONObject("account").getString("watchAccessToken"))
    }

    @Test fun everyRefusalRecoveryIssuanceOrderRejectsTheOriginalDelivery()=runBlocking<Unit> {
        val orders=listOf("RCN","RNC","CRN","CNR","NRC","NCR")
        for(order in orders) {
            repo.confirmOnlineAuthorization("owner",repo.offlineAuthorizationVersion("owner"))
            issue("watch","start-$order")
            val pending=cached()
            var proof:Long?=null
            for(action in order) when(action) {
                'R' -> repo.recordAuthenticationResponse("owner",401,"{}")
                'C' -> {proof=repo.offlineAuthorizationVersion("owner");repo.confirmOnlineAuthorization("owner",proof!!)}
                'N' -> {
                    val expectedVersion=proof?:repo.offlineAuthorizationVersion("owner")
                    val blocked=repo.offlineAuthorizationBlocked("owner") || expectedVersion!=repo.offlineAuthorizationVersion("owner")
                    val result=runCatching {store.saveAuthorizedBluetoothGrant(key,scope,expectedVersion,grant())}
                    assertEquals("order=$order",blocked,result.isFailure)
                }
            }
            refused(pending)
        }
    }

    @Test fun sameBytesFromNewOnlineIssuanceStillInvalidateOlderRequest()=runBlocking<Unit> {
        val pending=cached();val raw=store.bluetoothGrant(key)!!
        store.saveAuthorizedBluetoothGrant(key,scope,repo.offlineAuthorizationVersion("owner"),raw)
        refused(pending)
        assertEquals(raw.toString(),delivered(cached()).toString())
    }

    @Test fun bulkClearAndRepeatedNegotiationCannotResurrectOldFrames()=runBlocking<Unit> {
        val pending=cached()
        val copied=BluetoothSyncProtocol.withPersistentSessionRequest(BluetoothSyncProtocol.withPersistentSessionRequest(pending))
        val raw=store.bluetoothGrant(key)!!
        store.clearBluetoothGrants();store.saveBluetoothGrant(key,raw)
        refused(pending);refused(copied)
        assertEquals(raw.toString(),delivered(cached()).toString())
    }

    @Test fun flushCompletionOrdersRefusalAndBothWatchDeliveries()=runBlocking<Unit> {
        store.saveBluetoothGrant(keyFor("second"),grant())
        val first=cached();val second=read("second")
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val started=CompletableDeferred<Unit>()
        val output=object:ByteArrayOutputStream() {
            override fun flush() {entered.countDown();check(release.await(5,TimeUnit.SECONDS));super.flush()}
        }
        val writer=async(Dispatchers.IO) {BluetoothSyncProtocol.writeFrame(output,first)}
        var refusal:Deferred<Unit>?=null
        try {
            assertTrue(withContext(Dispatchers.IO) {entered.await(3,TimeUnit.SECONDS)})
            refusal=async(Dispatchers.IO) {started.complete(Unit);newStore().rejectOfflineAuthorization(scope,"owner",origin)}
            withTimeout(2000) {started.await()};delay(80)
            assertFalse("The frame includes flush, not only payload bytes",refusal.isCompleted)
            release.countDown();withTimeout(2000) {writer.await();refusal.await()}
            assertEquals("syncAccount",BluetoothSyncProtocol.readFrame(ByteArrayInputStream(output.toByteArray())).getString("action"))
            refused(first);refused(second)
        } finally {release.countDown();writer.cancelAndJoin();refusal?.cancelAndJoin()}
    }

    @Test fun replacementWaitsForActiveFrameAndThenInvalidatesItsNextWrite()=runBlocking<Unit> {
        val first=cached()
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val started=CompletableDeferred<Unit>()
        val output=object:ByteArrayOutputStream() {
            override fun write(b:ByteArray,offset:Int,length:Int) {
                entered.countDown();check(release.await(5,TimeUnit.SECONDS));super.write(b,offset,length)
            }
        }
        val writer=async(Dispatchers.IO) {BluetoothSyncProtocol.writeFrame(output,first)}
        var replacing:Deferred<Unit>?=null
        try {
            assertTrue(withContext(Dispatchers.IO) {entered.await(3,TimeUnit.SECONDS)})
            replacing=async(Dispatchers.IO) {started.complete(Unit);issue("watch","replacement");Unit}
            withTimeout(2000) {started.await()};delay(80);assertFalse(replacing.isCompleted)
            release.countDown();withTimeout(2000) {writer.await();replacing.await()}
            refused(first)
            assertEquals("synthetic-replacement",delivered(cached()).getJSONObject("account").getString("watchAccessToken"))
        } finally {release.countDown();writer.cancelAndJoin();replacing?.cancelAndJoin()}
    }

    @Test fun refusalTransactionAlreadyHoldingTheLockStopsNewDelivery()=runBlocking<Unit> {
        val request=cached();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val other=newStore()
        ReflectionHelpers.setField(other,"prefsRef",object:android.content.SharedPreferences by prefs {
            override fun edit():android.content.SharedPreferences.Editor {
                val e=prefs.edit()
                return object:android.content.SharedPreferences.Editor by e {
                    override fun putBoolean(k:String?,v:Boolean):android.content.SharedPreferences.Editor {e.putBoolean(k,v);return this}
                    override fun putLong(k:String?,v:Long):android.content.SharedPreferences.Editor {e.putLong(k,v);return this}
                    override fun remove(k:String?):android.content.SharedPreferences.Editor {e.remove(k);return this}
                    override fun commit():Boolean {entered.countDown();check(release.await(5,TimeUnit.SECONDS));return e.commit()}
                }
            }
        })
        val refusal=async(Dispatchers.IO) {other.rejectOfflineAuthorization(scope,"owner",origin)}
        val bytes=ByteArrayOutputStream();var writer:Deferred<Throwable?>?=null
        try {
            assertTrue(withContext(Dispatchers.IO) {entered.await(3,TimeUnit.SECONDS)})
            writer=async(Dispatchers.IO) {runCatching {BluetoothSyncProtocol.writeFrame(bytes,request)}.exceptionOrNull()}
            delay(80);assertEquals(0,bytes.size());assertFalse(writer.isCompleted)
            release.countDown();withTimeout(2000) {refusal.await()}
            assertTrue(withTimeout(2000) {writer.await()} is PhoneAccountNetworkRequired)
            assertEquals(0,bytes.size())
        } finally {release.countDown();refusal.cancelAndJoin();writer?.cancelAndJoin()}
    }

    @Test fun twoCompetingIssuancesOnlyTheLatestCommittedGrantCanBeDelivered()=runBlocking<Unit> {
        val version=repo.offlineAuthorizationVersion("owner")
        val a=async(Dispatchers.IO) {store.saveAuthorizedBluetoothGrant(key,scope,version,grant().apply {getJSONObject("account").put("watchAccessToken","synthetic-A")})}
        val b=async(Dispatchers.IO) {newStore().saveAuthorizedBluetoothGrant(key,scope,version,grant().apply {getJSONObject("account").put("watchAccessToken","synthetic-B")})}
        val snapshots=listOf(a.await(),b.await())
        assertEquals(1,snapshots.count {store.isCurrentBluetoothGrant(it)})
        for(snapshot in snapshots) {
            var writes=0
            val failure=runCatching {store.withCurrentBluetoothGrant(snapshot) {writes++}}.exceptionOrNull()
            assertEquals(store.isCurrentBluetoothGrant(snapshot),failure==null)
            assertEquals(if(failure==null)1 else 0,writes)
        }
    }

    @Test fun recoverWithOldProofCannotUndoASecondRefusal()=runBlocking<Unit> {
        val request=cached()
        repo.recordAuthenticationResponse("owner",401,"{}")
        val proof=repo.offlineAuthorizationVersion("owner")
        repo.recordAuthenticationResponse("owner",403,"{}")
        repo.confirmOnlineAuthorization("owner",proof)
        assertTrue(repo.offlineAuthorizationBlocked("owner"));refused(request)
        assertTrue(runCatching {store.saveAuthorizedBluetoothGrant(key,scope,proof,grant())}.exceptionOrNull() is PhoneDeviceVerificationRequired)
    }

    @Test fun pauseAndResumeAuthenticationCannotUseARequestDuringThePause()=runBlocking<Unit> {
        val request=cached();store.beginSameAccountReauthentication();refused(request)
        store.finishReauthentication()
        assertEquals("syncAccount",delivered(cached()).getString("action"))
    }
    @Test fun simultaneousTwoWatchWritersAndRefusalHaveOneCommitOrder()=runBlocking<Unit> {
        repeat(12) {round->
            repo.confirmOnlineAuthorization("owner",repo.offlineAuthorizationVersion("owner"))
            issue("watch","first-$round");issue("second","second-$round")
            val requests=listOf(read("watch"),read("second"))
            val events=java.util.Collections.synchronizedList(mutableListOf<String>())
            val rejectingStore=newStore()
            ReflectionHelpers.setField(rejectingStore,"prefsRef",object:android.content.SharedPreferences by prefs {
                override fun edit():android.content.SharedPreferences.Editor {
                    val e=prefs.edit()
                    return object:android.content.SharedPreferences.Editor by e {
                        override fun putBoolean(k:String?,v:Boolean):android.content.SharedPreferences.Editor {e.putBoolean(k,v);return this}
                        override fun putLong(k:String?,v:Long):android.content.SharedPreferences.Editor {e.putLong(k,v);return this}
                        override fun remove(k:String?):android.content.SharedPreferences.Editor {e.remove(k);return this}
                        override fun commit():Boolean {val ok=e.commit();events.add("refusal");return ok}
                    }
                }
            })
            val ready=CountDownLatch(3);val start=CountDownLatch(1)
            val outputs=(0..1).map {index->object:ByteArrayOutputStream() {
                override fun flush() {super.flush();events.add("write-$index")}
            }}
            val writers=requests.mapIndexed {index,request->async(Dispatchers.IO) {
                ready.countDown();check(start.await(3,TimeUnit.SECONDS))
                runCatching {BluetoothSyncProtocol.writeFrame(outputs[index],request)}.exceptionOrNull()
            }}
            val refusal=async(Dispatchers.IO) {
                ready.countDown();check(start.await(3,TimeUnit.SECONDS))
                rejectingStore.rejectOfflineAuthorization(scope,"owner",origin)
            }
            try {
                assertTrue(withContext(Dispatchers.IO) {ready.await(3,TimeUnit.SECONDS)})
                start.countDown()
                val outcomes=withTimeout(5000) {writers.map {it.await()}.also {refusal.await()}}
                val refusalIndex=events.indexOf("refusal")
                assertTrue(refusalIndex>=0)
                outcomes.forEachIndexed {index,error->
                    if(error==null) {
                        assertTrue("round=$round order=$events",events.indexOf("write-$index") in 0 until refusalIndex)
                        assertEquals("syncAccount",BluetoothSyncProtocol.readFrame(ByteArrayInputStream(outputs[index].toByteArray())).getString("action"))
                    } else {
                        assertTrue(error is PhoneAccountNetworkRequired)
                        assertFalse(isRetryableBluetoothDisconnect(error));assertEquals(0,outputs[index].size())
                    }
                }
                assertTrue(repo.offlineAuthorizationBlocked("owner"))
                requests.forEach(::refused)
            } finally {start.countDown();writers.forEach {it.cancelAndJoin()};refusal.cancelAndJoin()}
        }
    }
}
