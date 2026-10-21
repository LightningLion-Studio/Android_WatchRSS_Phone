package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.account.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CloudRecheckTest {
    private val session = PhoneAccountSession("u", "***", "token", "", Long.MAX_VALUE)
    private val device = CloudProtocolV2.signingKey()
    private val requesterNonce = ByteArray(32) { 7 }
    private fun response(request: Request, body: JSONObject = JSONObject(), status: Int = 200) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(status).message("test")
        .body(body.toString().toResponseBody("application/json".toMediaType())).build()
    private fun client(handle: (Request, JSONObject) -> Response) = PhoneCloudClient(AccountEnvironment(backendBaseUrl="https://test.invalid"),
        http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); val bytes = okio.Buffer(); request.body?.writeTo(bytes)
            handle(request, if (bytes.size > 0) JSONObject(bytes.readUtf8()) else JSONObject())
        }.build()).also { it.useEpoch(session, "epoch") }
    private fun member() = CloudMemberState("free", true, true, true, 100, 0, 0, 30, null, null)
    // Exact cloud_approval_v2::view shape, plus /pending's displayName/platform. JSON null keys are intentional.
    private fun pending() = JSONObject().put("requestId", "r1").put("deviceId", "new").put("publicKeySpki", device.public.encoded.base64())
        .put("commitment", CloudProtocolV2.approvalCommitment(device.public.encoded, requesterNonce)).put("status", "pending")
        .put("expiresAt", 1800000000000L).put("displayName", "Watch").put("platform", "android_watch").apply {
            listOf("approverDeviceId", "approverSpki", "approverNonce", "requesterNonce", "payload", "signature").forEach { put(it, JSONObject.NULL) }
        }
    private fun flow(client: PhoneCloudClient, prepare: suspend (PhoneAccountSession) -> Unit = {}, deliver: suspend (PhoneAccountSession,String,String)->String = { _,_,_->"{}" }) =
        CloudApprovalV2(client, "old", {session}, {device.public.encoded.base64()}, prepare, deliver, {_,_->})

    // Backend cloud_v2::Rotate and KeyEnvelope, both deny_unknown_fields. No sibling worktree dependency.
    private fun strictRotate(body: JSONObject) {
        assertEquals(setOf("deviceId", "keyVersion", "commitment", "rotationProof", "recoveryEnvelope", "deviceEnvelope"), body.keySet())
        body.getString("deviceId"); body.getInt("keyVersion"); body.getString("commitment")
        require(body.isNull("rotationProof") || body.get("rotationProof") is String)
        for (name in listOf("recoveryEnvelope", "deviceEnvelope")) {
            val envelope = body.getJSONObject(name)
            assertEquals(setOf("recipientType", "recipientDeviceId", "createdByDeviceId", "algorithm", "keyVersion", "wrappedKeyBase64", "nonceBase64", "ephemeralPublicKeySpki"), envelope.keySet())
            assertEquals(body.getInt("keyVersion"), envelope.getInt("keyVersion"))
            assertEquals("old", envelope.getString("createdByDeviceId"))
            if (name == "recoveryEnvelope") { assertEquals("recovery", envelope.getString("recipientType")); assertTrue(envelope.isNull("recipientDeviceId")) }
            else { assertEquals("device", envelope.getString("recipientType")); assertEquals("old", envelope.getString("recipientDeviceId")) }
        }
    }
    @Test fun high1ActivationAndRotationUseBackendStrictRotateFieldSetAndKeepEnvelopeAuthors() = runBlocking {
        val entropy = ByteArray(32) { 4 }; val oldKey = ByteArray(32) { 1 }; val newKey = ByteArray(32) { 2 }
        var posts = 0
        val c = client { request, body -> assertEquals("/functions/v1/cloud/keys/rotate", request.url.encodedPath); strictRotate(body); posts++; response(request) }
        for (previous in listOf(null, oldKey)) {
            val version = if (previous == null) 1 else 2
            val setup = RecoveryKeySetup(newKey, RecoveryWords.encode(entropy), CloudKeyEnvelopeCodec.createRecoveryEnvelope(newKey, "u", version, entropy))
            val body = cloudRotationBody("u", "epoch", "old", device.public.encoded.base64(), setup, previous)
            assertFalse(body.has("createdByDeviceId")); assertEquals(previous == null, body.isNull("rotationProof"))
            assertArrayEquals(newKey, CloudKeyEnvelopeCodec.unwrapDeviceEnvelope(body.getJSONObject("deviceEnvelope").cloudEnvelope(), "u", "old", device.private))
            assertArrayEquals(newKey, CloudKeyEnvelopeCodec.unwrapRecoveryEnvelope(body.getJSONObject("recoveryEnvelope").cloudEnvelope(), "u", entropy))
            c.rotate(session, body, "epoch")
            assertThrows(AssertionError::class.java) { strictRotate(JSONObject(body.toString()).put("createdByDeviceId", "old")) }
        }
        assertEquals(2, posts)
    }
    @Test fun high2BackendNullPendingAndAcceptedViewsWaitForRevealThenSignCorrectTranscript() = runBlocking {
        var accept = JSONObject(); var revealed = false; var wraps = 0; var delivered = JSONObject()
        val c = client { request, body -> when {
            request.url.encodedPath.endsWith("/pending") -> response(request, JSONObject().put("requests", JSONArray().put(pending())))
            request.url.encodedPath.endsWith("/accept") -> { accept = body; response(request) }
            request.url.encodedPath.endsWith("/deliver") -> { delivered = body; response(request) }
            else -> response(request, pending().put("status", if (revealed) "revealed" else "accepted")
                .put("approverSpki", accept.getString("approverSpki")).put("approverDeviceId", "old")
                .put("approverNonce", accept.getString("approverNonce")).apply { if (revealed) put("requesterNonce", requesterNonce.base64()) })
        } }
        val f = flow(c, deliver = {_,_,_->wraps++; "{\"original\": true}"})
        val request = f.discover().single(); assertTrue(request.has("requesterNonce")); assertTrue(request.isNull("requesterNonce"))
        f.start(request); repeat(2) { f.poll(); assertNull(f.state.value.code); assertEquals(CloudApprovalPhase.WAITING, f.state.value.phase) }
        assertEquals(0, wraps)
        revealed = true; f.poll()
        val transcript = CloudProtocolV2.transcript("u", "epoch", "r1", "new", device.public.encoded, "old",
            accept.getString("approverSpki").base64Bytes(), requesterNonce, accept.getString("approverNonce").base64Bytes())
        assertEquals(CloudProtocolV2.code(transcript), f.state.value.code)
        f.confirmMatches(); assertEquals(1, wraps)
        assertTrue(CloudProtocolV2.verify(accept.getString("approverSpki").base64Bytes(), transcript,
            delivered.getString("payload"), delivered.getString("signature").base64Bytes()))
    }
    @Test fun low3DiscoverAndDuplicateStartCannotRedisplayOrReplaceAnInFlightApproval() = runBlocking {
        val preparing = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var accepts = 0; var discovers = 0
        val c = client { request, _ -> if (request.url.encodedPath.endsWith("/accept")) accepts++ else discovers++; response(request) }
        val f = flow(c, prepare = { preparing.complete(Unit); release.await() })
        val first = async { f.start(pending()) }; preparing.await()
        val discovered = async { f.discover() }; val duplicate = async { f.start(pending()) }
        yield(); assertFalse(discovered.isCompleted); assertFalse(duplicate.isCompleted)
        release.complete(Unit); first.await(); duplicate.await()
        assertTrue(discovered.await().isEmpty()); assertEquals(1, accepts); assertEquals(0, discovers)
        assertEquals("r1", f.state.value.requestId); assertEquals(CloudApprovalPhase.WAITING, f.state.value.phase)
    }
    @Test fun low2ApproverPollNetworkRetryRetainsPinnedTranscriptAndHasABoundedBudget() = runBlocking {
        var accept = JSONObject(); var polls = 0; var wraps = 0
        val c = client { request, body -> if (request.method == "POST") { accept = body; response(request) }
            else { if (++polls <= 2) throw IOException("jitter")
                response(request, pending().put("requesterNonce", requesterNonce.base64()).put("status", "revealed")) } }
        val f = flow(c, deliver={_,_,_->wraps++; "{}"}); f.start(pending())
        val retries = CloudApprovalPollRetries()
        repeat(2) {
            try { f.poll(); fail("expected jitter") } catch (error: IOException) { assertTrue(retries.retry(error)) }
            assertEquals(CloudApprovalPhase.WAITING, f.state.value.phase); assertEquals("r1", f.state.value.requestId)
        }
        f.poll(); retries.succeeded(); assertEquals(CloudApprovalPhase.COMPARE, f.state.value.phase); assertEquals(0, wraps)
        repeat(3) { assertTrue(retries.retry(IOException("offline"))) }
        assertFalse(retries.retry(IOException("offline")))
        retries.succeeded(); assertTrue(retries.retry(IOException("brief")))
        assertFalse(CloudApprovalPollRetries().retry(CloudTrustException()))
    }
    @Test fun low4RequesterRecreationResumesExistingIdNonceCodeAndConfirmation() = runBlocking {
        var creates = 0; var reveals = 0; var commitment = ""; var nonce = ""
        val signing = CloudProtocolV2.signingKey()
        val c = client { request, body -> when {
            request.url.encodedPath.endsWith("/v2") -> { creates++; commitment=body.getString("commitment"); response(request, JSONObject().put("requestId", "r1")) }
            request.url.encodedPath.endsWith("/reveal") -> { reveals++; nonce=body.getString("requesterNonce"); response(request) }
            else -> response(request, pending().put("status", "accepted").put("approverSpki", signing.public.encoded.base64())
                .put("approverNonce", ByteArray(32){9}.base64()).put("approverDeviceId", "other"))
        } }
        val f = flow(c); assertEquals("r1", f.beginOrResume()); assertEquals("r1", f.beginOrResume())
        f.poll(); val code = f.state.value.code; assertNotNull(code); f.confirmMatches()
        assertEquals("r1", f.beginOrResume()); f.poll()
        assertEquals(1, creates); assertEquals(1, reveals); assertEquals(code, f.state.value.code); assertTrue(f.state.value.locallyConfirmed)
        assertEquals(commitment, CloudProtocolV2.approvalCommitment(device.public.encoded, nonce.base64Bytes()))
    }
    @Test fun low4RequesterRecreationDuringCreationDoesNotCancelAndDuplicateTheRequest() = runBlocking {
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>(); var creates = 0
        val c = client { request, _ -> creates++; response(request, JSONObject().put("requestId", "r1")) }
        val f = flow(c, prepare = { started.complete(Unit); finish.await() })
        val oldView = launch { f.beginOrResume() }; started.await()
        oldView.cancel() // Configuration recreation cancels the old LaunchedEffect.
        val newView = async { f.beginOrResume() }
        finish.complete(Unit); oldView.join()
        assertEquals("r1", newView.await()); assertEquals(1, creates)
        assertEquals(CloudApprovalPhase.WAITING, f.state.value.phase)
    }
    @Test fun low1BootstrapIncludesLegacySnapshotAndRecordVersionsInMigrationAndFirstActivationGuards() = runBlocking {
        val c = client { request, _ -> response(request, JSONObject().put("member", JSONObject().put("plan", "free"))
            .put("devices", JSONArray()).put("keyEnvelopes", JSONArray()).put("libraryEpoch", "epoch")
            .put("legacyKeyVersions", JSONArray(listOf(1,2,3)))) }
        val bootstrap = c.bootstrap(session)
        assertEquals(listOf(1,2,3), bootstrap.legacyKeyVersions)
        assertFalse(legacyMigrationHasCompleteKeySet(bootstrap, setOf(3)))
        assertFalse(legacyMigrationHasCompleteKeySet(bootstrap, setOf(1,3)))
        assertTrue(legacyMigrationHasCompleteKeySet(bootstrap, setOf(1,2,3)))
        // Sparse visible objects still require every intermediate local version (backend R26 range check).
        val sparse = bootstrap.copy(legacyKeyVersions=listOf(1,3))
        assertFalse(legacyMigrationHasCompleteKeySet(sparse,setOf(1,3)))
        assertTrue(legacyMigrationHasCompleteKeySet(sparse,setOf(1,2,3)))
        try { requireCloudFirstActivationAllowed(bootstrap); fail("legacy cloud claimed as empty") } catch (_: IllegalArgumentException) { }
        requireCloudFirstActivationAllowed(bootstrap.copy(legacyKeyVersions=emptyList()))
    }
    @Test fun low1MigrationOnlyReReadsKeyVersionExistsAndPropagatesEveryOther409() = runBlocking {
        var error = "key_version_exists"; var calls = 0
        val c = client { request, _ -> calls++; response(request, JSONObject().put("error", error), 409) }
        storeLegacyCommitments(c, session, "old", listOf(CloudKeyCommitment(1,"c")), "epoch")
        for (code in listOf("legacy_key_versions_required", "key_version_gap", "device_key_conflict", "library_epoch_mismatch")) {
            error = code
            try { storeLegacyCommitments(c, session, "old", listOf(CloudKeyCommitment(1,"c")), "epoch"); fail("swallowed $code") }
            catch (failure: CloudHttpException) { assertEquals(code, JSONObject(failure.detail).getString("error")) }
        }
        assertEquals(5,calls)
    }
    @Test fun low7StaleMissingDeviceBootstrapCannotDeleteANewlyRegisteredIdentity() {
        var identity = "original"; val old = CloudBootstrap(member(), emptyList(), emptyList(), "epoch")
        identity = "newly-registered"
        clearRevokedCloudDeviceIdentity(old, "old") { identity = "deleted" }
        assertEquals("newly-registered", identity)
        val own = RegisteredCloudDevice("old", "phone", "Phone", device.public.encoded.base64(), 1, 0, null)
        clearRevokedCloudDeviceIdentity(old.copy(devices=listOf(own)), "old") { identity = "deleted" }
        assertEquals("newly-registered", identity)
        clearRevokedCloudDeviceIdentity(old.copy(devices=listOf(own.copy(revokedAt="now"))), "old") { identity = "deleted" }
        assertEquals("deleted", identity)
    }
}

private fun JSONObject.keySet(): Set<String> = keys().asSequence().toSet()
