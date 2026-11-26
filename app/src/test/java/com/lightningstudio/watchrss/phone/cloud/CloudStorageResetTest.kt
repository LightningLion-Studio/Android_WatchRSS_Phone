package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.account.PhoneAccountSession
import com.lightningstudio.watchrss.phone.syncv2.SyncV2Codec
import com.lightningstudio.watchrss.phone.syncv2.SyncV2RepoBootstrap
import com.lightningstudio.watchrss.phone.syncv2.SyncV2ResetSources
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudStorageResetTest {
    private val user = "12222222-2222-2222-2222-222222222222"
    private val old = "22222222-2222-2222-2222-222222222222"
    private val next = "32222222-2222-2222-2222-222222222222"
    private val generation = "42222222-2222-2222-2222-222222222222"
    private val key = ByteArray(32) { 7 }
    private val commitment = CloudProtocolV2.commitment(key, user, next, 3)
    private val member = CloudMemberState("fixture", true, true, true, 1_048_576, 0, 0, null, null, null)
    private val scope = JSONObject().put("repoId", "34".repeat(16)).put("libraryEpoch", "2").put("contentGeneration", "1")
    private val intentJson = JSONObject().put("resetToken", "56".repeat(32)).put("libraryEpoch", old)
        .put("nextLibraryEpoch", next).put("nextKeyVersion", 3).put("expiresAt", "2026-11-26T13:00:00Z")

    private fun pending() = CloudResetPending.stage(
        CloudResetIntent(intentJson, old),
        RecoveryKeySetup(key, listOf("synthetic-test"), CloudKeyEnvelope("fixture", 3, "", "")),
        commitment
    )

    private fun bootstrap(epoch: String = next, hash: String = commitment) =
        CloudBootstrap(member, emptyList(), emptyList(), libraryEpoch = epoch, cloudResetEnabled = true,
            cloudResetAt = "2026-11-26T13:00:00Z", syncV2ResetScope = scope, contentGeneration = generation,
            keyCommitments = listOf(CloudKeyCommitment(3, hash, null)))

    private fun bootstrapJson() = JSONObject()
        .put("member", JSONObject().put("plan", member.plan).put("active", true).put("writable", true)
            .put("readable", true).put("quotaBytes", member.quotaBytes).put("usedBytes", 0).put("reservedBytes", 0))
        .put("devices", JSONArray()).put("keyEnvelopes", JSONArray())
        .put("libraryEpoch", next).put("contentGeneration", generation).put("contentEmpty", true)
        .put("cloudResetEnabled", true).put("cloudResetAt", "2026-11-26T13:00:00Z").put("syncV2ResetScope", scope)
        .put("keyCommitments", JSONArray().put(JSONObject().put("keyVersion", 3).put("commitment", commitment)))

    @Test fun entryRequiresServerAnnouncementWriteEligibilityAndMissingKey() {
        assertTrue(cloudResetEntry(bootstrap(), false, true, false))
        assertFalse(cloudResetEntry(null, false, true, false))
        assertFalse(cloudResetEntry(bootstrap().copy(cloudResetEnabled = false), false, true, false))
        assertFalse(cloudResetEntry(bootstrap().copy(member = member.copy(writable = false)), false, true, false))
        assertFalse(cloudResetEntry(bootstrap(), true, true, false))
        assertFalse(cloudResetEntry(bootstrap(), false, false, false))
        assertFalse(cloudResetEntry(bootstrap(), false, true, true))
    }

    @Test fun confirmationRequiresSavingAndTheEntireCountdown() {
        assertFalse(canSubmitCloudReset(false, 0))
        for (remaining in 1..5) assertFalse(canSubmitCloudReset(true, remaining))
        assertTrue(canSubmitCloudReset(true, 0))
    }

    @Test fun lostExecuteResponseUsesBootstrapWithoutResubmission() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(intentJson.toString()))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setBody(bootstrapJson().toString()))
            val session = PhoneAccountSession(user, "***", "wrp_fixture", "", Long.MAX_VALUE)
            val client = PhoneCloudClient(AccountEnvironment(backendBaseUrl = server.url("/").toString().trimEnd('/')),
                deviceAccessToken = { "device-fixture" })
            client.currentDeviceId = "fixture-installation"
            client.cloudResetSupported = { true }
            val intent = client.resetIntent(session, old)
            var stored: CloudResetPending? = null
            executeCloudResetOnce(pending(), stage = { stored = pending() }, execute = {
                check(stored != null)
                client.executeReset(session, old, JSONObject().put("resetToken", intent.token)
                    .put("confirmation", "DELETE_CLOUD_LIBRARY"))
            }, confirmed = { fail("the result response was deliberately lost") }, rejected = { fail("unknown outcome") })
            val observed = client.readBootstrap(session, observe = false)
            assertTrue(requireNotNull(stored).matches(user, observed))
            assertEquals(3, server.requestCount)
            val requests = List(3) { server.takeRequest() }
            assertEquals(listOf("reset-intent", "reset", "bootstrap"),
                requests.map { it.path?.substringAfterLast('/') })
            requests.forEach {
                assertEquals("1", it.getHeader(CLOUD_RESET_HEADER))
                assertEquals("Bearer wrp_fixture", it.getHeader("Authorization"))
                assertEquals("Bearer device-fixture", it.getHeader("x-watchrss-device-authorization"))
                assertEquals("fixture-installation", it.getHeader("x-watchrss-cloud-device-id"))
            }
            assertEquals(old, requests[0].getHeader("x-watchrss-library-epoch"))
            assertEquals(old, requests[1].getHeader("x-watchrss-library-epoch"))
            assertNull(requests[2].getHeader("x-watchrss-library-epoch"))
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun oldEpochPollsAtFiveSecondsAndStopsAtTheForegroundLimit() = runTest {
        val reads = mutableListOf<Long>()
        val result = withTimeoutOrNull(60_000) {
            pollCloudReset(pending(), user, active = { true }, elapsed = { testScheduler.currentTime }) {
                reads.add(testScheduler.currentTime)
                delay(1_500)
                bootstrap(epoch = old)
            }
        }
        assertNull(result)
        assertEquals((0L until 60_000L step 5_000L).toList(), reads)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun loginRefreshDoesNotMakeTheOldEpochTerminal() = runTest {
        var reads = 0
        val result = pollCloudReset(pending(), user, active = { true }, elapsed = { testScheduler.currentTime }) {
            when (++reads) {
                1 -> throw error(401, "invalid_session")
                2 -> bootstrap(epoch = old)
                else -> bootstrap()
            }
        }
        assertTrue(requireNotNull(result).let { pending().matches(user, it) })
        assertEquals(3, reads)
    }

    @Test fun aMalformedOrWrongEpochResultCannotConfirmAnAttempt() {
        val attempt = CloudResetPending(JSONObject(pending().encode()))
        assertFalse(attempt.matches(user, bootstrap(epoch = old)))
        assertFalse(attempt.matches(user, bootstrap(hash = "00".repeat(32))))
        assertFalse(attempt.matches(user, bootstrap().copy(syncV2ResetScope = null)))
        assertFalse(attempt.matches(user, bootstrap().copy(cloudResetAt = null)))
        assertTrue(attempt.matches(user, bootstrap()))
        assertFalse(attempt.accepts(JSONObject().put("libraryEpoch", next).put("keyVersion", 3)))
    }

    private fun error(status: Int, code: String) =
        CloudHttpException(status, JSONObject().put("error", code).put("detail", JSONObject.NULL).toString(), true)

    @Test fun ambiguousFailuresAndCancellationKeepStagedAttempt() = runBlocking {
        val failures = listOf(IOException(), error(409, "cloud_reset_intent_invalid"), error(409, "cloud_reset"),
            error(502, "cloud_reset_disabled"), error(504, "timeout"), error(503, "unavailable"))
        for (failure in failures) {
            var staged = false
            var sends = 0
            executeCloudResetOnce(pending(), { staged = true }, { sends++; throw failure },
                { fail("no accepted result") }, { staged = false })
            assertTrue(staged)
            assertEquals(1, sends)
        }
        var staged = false
        try {
            executeCloudResetOnce(pending(), { staged = true }, { throw CancellationException() },
                { fail("no accepted result") }, { staged = false })
            fail("cancellation must be propagated")
        } catch (_: CancellationException) { assertTrue(staged) }
    }

    @Test fun definitiveErrorsReleaseOnlyThisAttemptAndHaveChineseCopy() = runBlocking {
        val cases = listOf(
            Triple(400, "cloud_reset_confirmation_required", "重新确认"),
            Triple(400, "invalid_public_key", "新密钥验证失败"),
            Triple(400, "invalid_cloud_reset_key_material", "新密钥验证失败"),
            Triple(400, "invalid_key_envelope", "新密钥验证失败"),
            Triple(400, "invalid_crypto_encoding", "新密钥验证失败"),
            Triple(401, "invalid_session", "登录已过期"),
            Triple(402, "cloud_membership_required", "配额不足"),
            Triple(403, "cloud_reset_phone_required", "手机或小程序"),
            Triple(403, "cloud_reset_device_not_registered", "重新登记本机"),
            Triple(403, "device_token_mismatch", "重新登记本机"),
            Triple(409, "cloud_reset_key_version_changed", "云端密钥已变化"),
            Triple(409, "cloud_reset_key_version_exhausted", "密钥版本已用尽"),
            Triple(409, "cloud_write_busy", "正在写入"),
            Triple(429, "cloud_reset_rate_limited", "过于频繁"),
            Triple(503, "cloud_reset_disabled", "当前无法重置"),
            Triple(503, "cloud_reset_repo_init_unavailable", "当前无法重置")
        )
        for ((status, code, message) in cases) {
            val failure = error(status, code)
            var staged = false
            try {
                executeCloudResetOnce(pending(), { staged = true }, { throw failure }, { fail("no accepted result") },
                    { rejected -> assertSame(failure, rejected); staged = false })
                fail("expected definitive rejection")
            } catch (caught: CloudHttpException) { assertSame(failure, caught) }
            assertFalse(staged)
            assertTrue(cloudStorageResetError(failure).contains(message))
        }
        assertTrue(cloudStorageResetError(error(409, "cloud_reset_intent_invalid")).contains("已过期"))
        assertEquals(CLOUD_RESET_QUOTA, cloudStorageResetError(error(507, "quota_exceeded")))
        assertTrue(cloudStorageResetError(IOException()).contains("网络"))
    }

    @Test fun retiredEndpointsDoNotSetTheResetPauseAndLocalEditsStayCandidates() {
        assertTrue(cloudResetSignal(409, "cloud_reset", null))
        assertTrue(cloudResetSignal(426, "client_upgrade_required", CLOUD_RESET_CAPABILITY))
        assertTrue(cloudResetSignal(426, "upgrade_required", null, JSONArray().put(CLOUD_RESET_CAPABILITY)))
        assertFalse(cloudResetSignal(426, "upgrade_required", null, JSONArray().put("sync-objects.v2")))
        assertFalse(cloudResetSignal(426, "client_upgrade_required", null))
        val prior = JSONObject().put("noteId", "stable").put("markdown", "prior")
        val edited = JSONObject(prior.toString()).put("markdown", "unsent edit")
        assertTrue(SyncV2ResetSources.sameFootprint(prior, prior))
        assertFalse(SyncV2ResetSources.sameFootprint(edited, prior))
        assertFalse(SyncV2ResetSources.sameFootprint(edited, null))
        assertEquals("unsent edit", edited.getString("markdown"))
    }

    @Test fun repositoryRetriesPreserveTheServerScopeAndCreationBytes() {
        var material: JSONObject? = null
        fun prepare() = SyncV2RepoBootstrap.prepareStored(user.replace("-", ""), "installation", next,
            generation, 3, { key }, { material }, { material = JSONObject(it.toString()) }, scope) { "57".repeat(16) }
        val first = prepare()
        val retry = prepare()
        assertEquals(SyncV2Codec.scope(scope), SyncV2Codec.scope(retry.scope))
        assertEquals(SyncV2Codec.canonical(first.descriptor), SyncV2Codec.canonical(retry.descriptor))
        assertEquals(SyncV2Codec.canonical(first.recoveryCapsule), SyncV2Codec.canonical(retry.recoveryCapsule))
    }
}
