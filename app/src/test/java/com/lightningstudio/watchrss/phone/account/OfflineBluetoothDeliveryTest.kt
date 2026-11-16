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
class OfflineBluetoothDeliveryTest {
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

    @Test fun refusalAndRecoveryAfterBuilderReturnCannotWriteDeletedGrant() = runBlocking<Unit> {
        val request = cached()
        repo.recordAuthenticationResponse("owner", 401, "{}")
        repo.confirmOnlineAuthorization("owner", repo.offlineAuthorizationVersion("owner"))
        assertFalse(repo.offlineAuthorizationBlocked("owner"))
        assertNull(store.bluetoothGrant(key))
        refused(request)
    }

    @Test fun replacementAfterBuilderReturnCannotWriteOldGrant() = runBlocking<Unit> {
        val request = cached()
        val next = JSONObject(request.toString()).apply { getJSONObject("account").put("watchAccessToken", "synthetic-next") }
        store.saveBluetoothGrant(key, next)
        refused(request)
        val output = ByteArrayOutputStream()
        BluetoothSyncProtocol.writeFrame(output, cached())
        assertEquals("synthetic-next", BluetoothSyncProtocol.readFrame(ByteArrayInputStream(output.toByteArray()))
            .getJSONObject("account").getString("watchAccessToken"))
    }

    @Test fun deletingAndRestoringIdenticalBytesCannotResurrectQueuedGrant() = runBlocking<Unit> {
        val request = cached()
        store.removeBluetoothGrant(key)
        store.saveBluetoothGrant(key, JSONObject(request.toString()))
        refused(request)
        assertNotNull(cached())
    }

    @Test fun negotiationCopiesKeepTheLastMomentGuard() = runBlocking<Unit> {
        val request = BluetoothSyncProtocol.withPersistentSessionRequest(cached())
        assertTrue(BluetoothSyncProtocol.requestsPersistentSession(request))
        repo.recordAuthenticationResponse("owner", 403, "{}")
        refused(request)
    }

    @Test fun unchangedGrantRetainsExactlyTheOriginalWirePayload() = runBlocking<Unit> {
        val raw = store.bluetoothGrant(key)!!.toString()
        val output = ByteArrayOutputStream()
        BluetoothSyncProtocol.writeFrame(output, cached())
        assertEquals(raw, BluetoothSyncProtocol.readFrame(ByteArrayInputStream(output.toByteArray())).toString())
    }

    @Test fun anotherStoreCannotCommitRefusalInsideTheAuthorizedFrameWrite() = runBlocking<Unit> {
        val request = cached()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val refusalStarted = CompletableDeferred<Unit>()
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                super.write(bytes, offset, length)
            }
        }
        val writer = async(Dispatchers.IO) { BluetoothSyncProtocol.writeFrame(output, request) }
        var refusal: Deferred<Unit>? = null
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
            refusal = async(Dispatchers.IO) {
                val other = newStore()
                refusalStarted.complete(Unit)
                other.rejectOfflineAuthorization(scope, "owner", origin)
            }
            withTimeout(2000) { refusalStarted.await() }
            delay(100)
            assertFalse("Refusal and delivery must have one transaction order", refusal.isCompleted)
            release.countDown()
            withTimeout(2000) { writer.await(); refusal.await() }
            assertEquals("syncAccount", BluetoothSyncProtocol.readFrame(ByteArrayInputStream(output.toByteArray())).getString("action"))
            assertTrue(repo.offlineAuthorizationBlocked("owner"))
            refused(request)
        } finally {
            release.countDown()
            writer.cancelAndJoin()
            refusal?.cancelAndJoin()
        }
    }

    @Test fun transportWriteFailureDoesNotResetPreferencesOrRetryTheFrame() = runBlocking<Unit> {
        val request = cached()
        var writes = 0
        val failure = IOException("synthetic RFCOMM output failure")
        val output = object : OutputStream() {
            override fun write(value: Int) { writes++; throw failure }
        }
        assertSame(failure, runCatching { BluetoothSyncProtocol.writeFrame(output, request) }.exceptionOrNull())
        assertEquals(1, writes)
        assertNotNull(store.bluetoothGrant(key))
        assertEquals(owner, store.session.value)
    }

    @Test fun ownerChangesBeforeWritingRequireVerification() = runBlocking<Unit> {
        val request = cached()
        store.save(owner.copy(userId = "other", accessToken = "synthetic-other"))
        refused(request)
    }

    @Test fun onlineIssuedRequestAlsoStopsIfRefusedBeforeWriting() = runBlocking<Unit> {
        val request = repo.buildAccountSyncRequest("watch")
        assertEquals("synthetic-online-watch", request.getJSONObject("account").getString("watchAccessToken"))
        repo.recordAuthenticationResponse("owner", 403, "{}")
        refused(request)
    }
}
