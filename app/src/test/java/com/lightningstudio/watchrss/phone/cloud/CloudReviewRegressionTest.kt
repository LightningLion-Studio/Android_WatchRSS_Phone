package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.account.*
import com.lightningstudio.watchrss.phone.connection.bluetooth.watchBluetoothFailureMessage
import com.lightningstudio.watchrss.phone.testing.MemoryPreferences
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CloudReviewRegressionTest {
    private val session = PhoneAccountSession("u", "***", "token", "", Long.MAX_VALUE)
    private val requester = CloudProtocolV2.signingKey()
    private val approver = CloudProtocolV2.signingKey()
    private val approverNonce = ByteArray(32) { 9 }
    private fun response(request: Request, code: Int = 200, body: String = "{}") = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
        .body(body.toResponseBody("application/json".toMediaType())).build()
    private fun client(handle: (Request, JSONObject) -> Response): PhoneCloudClient = PhoneCloudClient(
        AccountEnvironment(backendBaseUrl = "https://test.invalid"), http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); val bytes = okio.Buffer(); request.body?.writeTo(bytes)
            handle(request, if (bytes.size > 0) JSONObject(bytes.readUtf8()) else JSONObject())
        }.build()
    ).also { it.useEpoch(session, "epoch") }
    private fun accepted() = JSONObject().put("status", "accepted").put("approverDeviceId", "old")
        .put("approverSpki", approver.public.encoded.base64()).put("approverNonce", approverNonce.base64())
        .put("expiresAt", "2000-01-01T00:00:00Z") // Local wall clock must never decide validity.
    private fun flow(client: PhoneCloudClient) = CloudApprovalV2(client, "new", { session },
        { requester.public.encoded.base64() }, {}, { _, _, _ -> error("requester") }, { _, _ -> error("must not install") })

    @Test fun low4RevealNetworkFailureRetriesWithoutDisplayingAPrematureCode() = runBlocking {
        var reveals = 0
        val c = client { request, _ ->
            when {
                request.url.encodedPath.endsWith("/v2") -> response(request, body = "{\"requestId\":\"r1\"}")
                request.url.encodedPath.endsWith("/reveal") -> {
                    reveals++; if (reveals == 1) throw IOException("offline")
                    response(request)
                }
                else -> response(request, body = accepted().toString())
            }
        }
        val f = flow(c); f.begin()
        try { f.poll(); fail("offline reveal") } catch (_: IOException) { }
        assertEquals(CloudApprovalPhase.WAITING, f.state.value.phase); assertNull(f.state.value.code)
        f.poll(); assertEquals(2, reveals); assertEquals(CloudApprovalPhase.COMPARE, f.state.value.phase)
        f.poll(); assertEquals(2, reveals)
    }

    @Test fun low4LostRevealResponseUsesServerNonceWithoutRepeatingAcceptedWrite() = runBlocking {
        var reveals = 0; var revealedNonce: String? = null
        val c = client { request, body -> when {
            request.url.encodedPath.endsWith("/v2") -> response(request, body = "{\"requestId\":\"r1\"}")
            request.url.encodedPath.endsWith("/reveal") -> {
                reveals++; revealedNonce = body.getString("requesterNonce"); throw IOException("response lost")
            }
            else -> response(request, body = accepted().apply { revealedNonce?.let {
                put("status", "revealed"); put("requesterNonce", it)
            } }.toString())
        } }
        val f = flow(c); f.begin()
        try { f.poll(); fail("lost response") } catch (_: IOException) { }
        f.poll(); assertEquals(1, reveals); assertEquals(CloudApprovalPhase.COMPARE, f.state.value.phase)
    }

    @Test fun low4FailedRevealStillPinsApproverAgainstSubstitutionOnRetry() = runBlocking {
        var reveals = 0
        val c = client { request, _ -> when {
            request.url.encodedPath.endsWith("/v2") -> response(request, body = "{\"requestId\":\"r1\"}")
            request.url.encodedPath.endsWith("/reveal") -> { reveals++; throw IOException("offline") }
            else -> response(request, body = accepted().apply {
                if (reveals > 0) put("approverSpki", requester.public.encoded.base64())
            }.toString())
        } }
        val f = flow(c); f.begin()
        try { f.poll(); fail("offline") } catch (_: IOException) { }
        try { f.poll(); fail("substituted approver") } catch (_: IllegalArgumentException) { }
        assertEquals(1, reveals); assertNull(f.state.value.code)
    }

    @Test fun low4PollingRetriesTransportFailuresButNeverProtocolOrTrustRejections() {
        assertTrue(retryableCloudApprovalFailure(IOException("offline")))
        assertTrue(retryableCloudApprovalFailure(CloudHttpException(503, "busy")))
        assertTrue(retryableCloudApprovalFailure(CloudHttpException(429, "limited")))
        assertFalse(retryableCloudApprovalFailure(CloudHttpException(409, "{\"error\":\"approval_expired\"}")))
        assertFalse(retryableCloudApprovalFailure(CloudDeviceAuthorizationException()))
        assertFalse(retryableCloudApprovalFailure(CloudTrustException()))
    }

    @Test fun r23ServerExpiryIsTerminalAndLocalPastExpiresAtDoesNotBlockReveal() = runBlocking {
        var expired = false; var gets = 0
        val c = client { request, _ -> when {
            request.url.encodedPath.endsWith("/v2") -> response(request, body = "{\"requestId\":\"r1\"}")
            request.method == "GET" -> { gets++; response(request, body = accepted().put("status", if (expired) "expired" else "accepted").toString()) }
            else -> response(request)
        } }
        val f = flow(c); f.begin(); f.poll(); assertEquals(CloudApprovalPhase.COMPARE, f.state.value.phase)
        expired = true; f.poll(); assertEquals(CloudApprovalPhase.EXPIRED, f.state.value.phase)
        assertNull(f.state.value.code); f.poll(); assertEquals(2, gets)
    }

    @Test fun r23ApprovalExpired409DiscardsCodeAndStopsPolls() = runBlocking {
        var gets = 0
        val c = client { request, _ -> when {
            request.url.encodedPath.endsWith("/v2") -> response(request, body = "{\"requestId\":\"r1\"}")
            request.method == "GET" -> { gets++; response(request, body = accepted().toString()) }
            else -> response(request, 409, "{\"error\":\"approval_expired\"}")
        } }
        val f = flow(c); f.begin()
        try { f.poll(); fail("expired reveal") } catch (_: CloudHttpException) { }
        assertEquals(CloudApprovalPhase.EXPIRED, f.state.value.phase); assertNull(f.state.value.code)
        f.poll(); assertEquals(1, gets)
    }

    @Test fun low5RequestConstructionCannotReplaceCapturedOperationEpoch() = runBlocking {
        val headers = mutableListOf<String?>()
        val c = client { request, _ -> headers += request.header("x-watchrss-library-epoch"); response(request) }
        c.checkSession = { c.useEpoch(session, "concurrent-new-epoch") }
        c.revokeDevice(session, "other") // Capture occurs before IO dispatcher and checkSession.
        c.v2Post(session, "/functions/v1/cloud/keys/rotate", JSONObject(), "original-operation")
        assertEquals(listOf("epoch", "original-operation"), headers)
    }

    @Test fun low5DeviceAuthorizationRetryRetainsEpochEvenAfterConcurrentBootstrap() = runBlocking {
        val headers = mutableListOf<String?>(); var token = "old"; lateinit var c: PhoneCloudClient
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            headers += chain.request().header("x-watchrss-library-epoch")
            if (headers.size == 1) response(chain.request(), 403, "{\"error\":\"device_session_expired\"}")
            else response(chain.request())
        }.build()
        c = PhoneCloudClient(AccountEnvironment(backendBaseUrl = "https://test.invalid"), { token }, http,
            refreshDeviceSession = { _, _ -> token = "new"; c.useEpoch(session, "new-epoch"); true })
        c.useEpoch(session, "start-epoch"); c.revokeDevice(session, "other")
        assertEquals(listOf("start-epoch", "start-epoch"), headers)
    }

    @Test fun low5CloudWriteWithoutCapturedEpochFailsBeforeNetwork() = runBlocking {
        var calls = 0
        val c = client { request, _ -> calls++; response(request) }; c.forgetEpoch(session)
        try { c.revokeDevice(session, "other"); fail("unfenced cloud write") } catch (_: IllegalArgumentException) { }
        assertEquals(0, calls)
    }

    @Test fun low6AccountSwitchFailureUsesExplicitWatchLogoutGuidance() {
        for (field in listOf("errorCode", "error")) {
            assertEquals("这块手表已登录其他账号，请先在手表上退出", watchBluetoothFailureMessage(JSONObject()
                .put(field, "account_switch_requires_logout").put("message", "generic failure")))
        }
        assertEquals("server detail", watchBluetoothFailureMessage(JSONObject().put("message", "server detail")))
    }

    private fun keys() = (1..3).associateWith { v -> ByteArray(32) { v.toByte() } }
    private fun rows(keys: Map<Int, ByteArray>) = keys.map { (v, k) ->
        val commitment = CloudProtocolV2.commitment(k, "u", "epoch", v)
        CloudKeyCommitment(v, commitment, if (v == 1) null else CloudProtocolV2.rotationProof(keys.getValue(v - 1), "u", "epoch", v, commitment))
    }
    @Test fun low7CorruptNewVersionPreservesVerifiedKeysAndLowersCurrentAtomically() {
        val good = keys(); val candidates = good + (3 to ByteArray(32))
        val prefs = MemoryPreferences(); val manager = CloudKeyManager(null, preferencesOverride = prefs)
        manager.bindBackend("backend"); manager.saveVerifiedKeys("u", candidates)
        val verified = CloudKeyTrust.verifiedSubset("u", "epoch", 0, rows(good), candidates)
        assertEquals(setOf(1, 2), verified.keys)
        manager.replaceVerifiedKeys("u", verified)
        assertEquals(listOf(1, 2), manager.keyVersions("u")); assertEquals(2, manager.currentKeyVersion("u"))
        assertNull(manager.getAccountKey("u", 3)); assertArrayEquals(good[1], manager.getAccountKey("u", 1))
        assertArrayEquals(good[2], manager.getAccountKey("u", 2))
    }
    @Test fun low7BrokenMiddleProofRejectsUnanchoredSuccessorsButPreservesAnchor() {
        val good = keys(); val broken = rows(good).map { if (it.keyVersion == 2) it.copy(rotationProof = "bad") else it }
        assertEquals(setOf(1), CloudKeyTrust.verifiedSubset("u", "epoch", 0, broken, good).keys)
        assertTrue(CloudKeyTrust.verifiedSubset("u", "epoch", 0, rows(good), mapOf(3 to good.getValue(3))).isEmpty())
        // Approval/recovery full-set validation remains atomic; it must never silently accept the prefix.
        try { CloudKeyTrust.validate("u", "epoch", 0, broken, good); fail("partial delivery") } catch (_: CloudTrustException) { }
    }
    @Test fun r19MigrationRequiresUnionOfAllVisibleDeviceAndRecoveryVersionsAndContinuity() {
        val good = keys(); val entropy = ByteArray(32) { 9 }
        fun stored(v: Int, type: String) = StoredCloudKeyEnvelope("e$v", type, if (type == "device") "other" else null,
            CloudKeyEnvelopeCodec.createRecoveryEnvelope(good.getValue(v), "u", v, entropy))
        val bootstrap = CloudBootstrap(CloudMemberState("free", true, true, true, 1, 0, 0, 30, null, null), emptyList(),
            listOf(stored(1, "recovery"), stored(2, "device"), stored(3, "recovery")), "epoch")
        assertFalse(legacyMigrationHasCompleteKeySet(bootstrap, setOf(3)))
        assertFalse(legacyMigrationHasCompleteKeySet(bootstrap, setOf(1, 3))) // Recovery envelopes alone miss device-only v2.
        assertFalse(legacyMigrationHasCompleteKeySet(bootstrap, setOf(1, 2)))
        assertFalse(legacyMigrationHasCompleteKeySet(bootstrap, setOf(1, 2, 3, 4)))
        assertTrue(legacyMigrationHasCompleteKeySet(bootstrap, setOf(1, 2, 3)))
        val gap = bootstrap.copy(keyEnvelopes = bootstrap.keyEnvelopes.filter { it.envelope.keyVersion != 2 })
        assertFalse(legacyMigrationHasCompleteKeySet(gap, setOf(1, 3)))
        assertFalse(legacyMigrationHasCompleteKeySet(bootstrap.copy(keyVersionFloor = 1), good.keys))
        assertFalse(legacyMigrationHasCompleteKeySet(bootstrap.copy(keyEnvelopes = emptyList()), good.keys))
    }
    @Test fun r20ReregistrationRequiresSuccessfulSelfRevocationBeforeIdentityReplacement() = runBlocking {
        val events = mutableListOf<String>()
        try {
            reregisterCloudDevice({ events += "revoke"; throw IOException("offline") }, { events += "delete" }, { events += "register" })
            fail("failed revoke ignored")
        } catch (_: IOException) { }
        assertEquals(listOf("revoke"), events); events.clear()
        reregisterCloudDevice({ events += "revoke" }, { events += "delete" }, { events += "register" })
        assertEquals(listOf("revoke", "delete", "register"), events)
    }
    @Test fun r20DeviceConflictPromptsForReregistrationWithoutAutomaticMutation() = runBlocking {
        var conflicts = 0; var calls = 0
        val c = client { request, _ -> calls++; response(request, 409, "{\"error\":\"device_key_conflict\"}") }
        c.onDeviceKeyConflict = { conflicts++ }
        try { c.registerDevice(session, "phone", "Phone", requester.public.encoded.base64(), 1); fail("conflict") }
        catch (error: CloudHttpException) { assertTrue(error.message!!.contains("重新登记本机")) }
        assertEquals(1, conflicts); assertEquals(1, calls)
    }
}
