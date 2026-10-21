package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.account.PhoneAccountSession
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyPair
import java.security.SecureRandom

/** UI callbacks are explicit: discover never accepts and accept never wraps a key. */
enum class CloudApprovalPhase { IDLE, DISCOVERED, WAITING, COMPARE, DELIVERED, CANCELLED, EXPIRED, ERROR }
data class CloudApprovalState(val phase: CloudApprovalPhase = CloudApprovalPhase.IDLE, val requestId: String = "",
                              val displayName: String = "", val code: String? = null, val message: String = "",
                              val isRequester: Boolean = false, val locallyConfirmed: Boolean = false)

class CloudApprovalV2 internal constructor(
    private val client: PhoneCloudClient,
    private val deviceId: String,
    private val session: () -> PhoneAccountSession,
    private val spki: (PhoneAccountSession) -> String,
    private val prepare: suspend (PhoneAccountSession) -> Unit,
    private val deliverPayload: suspend (PhoneAccountSession, String, String) -> String,
    private val receivePayload: suspend (PhoneAccountSession, String) -> Unit
) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(CloudApprovalState())
    val state: StateFlow<CloudApprovalState> = mutable
    private val pending = MutableStateFlow<List<JSONObject>>(emptyList())
    val pendingRequests: StateFlow<List<JSONObject>> = pending
    private var owner: PhoneAccountSession? = null
    private var epoch: String? = null
    private var target: JSONObject? = null
    private var signingKey: KeyPair? = null
    private var nonce: ByteArray? = null
    private var transcript: ByteArray? = null
    private var requester = false
    private var requesterConfirmed = false
    private var revealed = false
    private var pendingDelivery: Pair<String, String>? = null
    private fun current(): PhoneAccountSession {
        val saved = requireNotNull(owner)
        check(session().userId == saved.userId && client.epoch(saved) == epoch) { "账号或资料库已变化" }
        return session()
    }
    fun clear() {
        nonce?.fill(0); nonce = null; signingKey = null; transcript = null; target = null; owner = null; epoch = null
        requesterConfirmed = false; revealed = false; pendingDelivery = null; pending.value = emptyList()
        mutable.value = CloudApprovalState()
    }
    private fun remember(s: PhoneAccountSession) { owner = s; epoch = client.epoch(s) }
    private fun path(id: String = state.value.requestId) : String {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        return "/functions/v1/cloud/approvals/v2/$id"
    }
    private suspend fun <T> relay(block: suspend () -> T): T = try { block() }
    catch (error: CloudHttpException) {
        if (error.status == 409 && runCatching { JSONObject(error.detail).optString("error") }.getOrDefault("") == "approval_expired") {
            mutable.value = state.value.copy(phase = CloudApprovalPhase.EXPIRED, code = null, message = "授权请求已过期")
            signingKey = null; nonce?.fill(0); pendingDelivery = null; requesterConfirmed = false
        }
        throw error
    }
    private suspend fun post(s: PhoneAccountSession, url: String, body: JSONObject): JSONObject =
        relay { client.v2Post(s, url, body, epoch) }
    private suspend fun get(s: PhoneAccountSession, url: String): JSONObject = relay { client.v2Get(s, url) }
    suspend fun discover(): List<JSONObject> = mutex.withLock {
        if (state.value.phase !in setOf(CloudApprovalPhase.IDLE, CloudApprovalPhase.DISCOVERED)) return@withLock emptyList()
        val s = session(); prepare(s)
        val rows = get(s, "/functions/v1/cloud/approvals/v2/pending").getJSONArray("requests")
        (0 until rows.length()).map { rows.getJSONObject(it) }.filterNot { it.optString("deviceId").startsWith("reader-migration-") }.also {
            pending.value = it
            if (it.isNotEmpty() && state.value.phase in setOf(CloudApprovalPhase.IDLE, CloudApprovalPhase.DISCOVERED))
                mutable.value = CloudApprovalState(CloudApprovalPhase.DISCOVERED, message = "有新设备请求授权")
        }
    }
    suspend fun begin(): String = mutex.withLock { beginLocked() }
    /** Activity recreation resumes exactly the existing requester nonce/transcript and request id. */
    suspend fun beginOrResume(): String = mutex.withLock {
        if (requester && owner?.userId == session().userId && epoch == client.knownEpoch(session()) &&
            state.value.requestId.isNotBlank() && state.value.phase != CloudApprovalPhase.IDLE) return@withLock state.value.requestId
        // A recreated view may cancel its waiter while create is in flight. Finish recording the request id.
        withContext(NonCancellable) { beginLocked() }
    }
    private suspend fun beginLocked(): String {
        clear(); val s = session(); prepare(s); remember(s); requester = true
        val n = ByteArray(32).also(SecureRandom()::nextBytes); nonce = n
        val public = spki(s)
        val result = post(s, "/functions/v1/cloud/approvals/v2", JSONObject().put("deviceId", deviceId)
            .put("commitment", CloudProtocolV2.approvalCommitment(public.base64Bytes(), n)))
        current()
        target = JSONObject().put("deviceId", deviceId).put("publicKeySpki", public)
        mutable.value = CloudApprovalState(CloudApprovalPhase.WAITING, result.getString("requestId"), message = "等待已登录设备开始授权", isRequester = true)
        return state.value.requestId
    }
    suspend fun start(targetRequest: JSONObject) = mutex.withLock {
        require(!targetRequest.getString("deviceId").startsWith("reader-migration-")) { "阅读迁移虚拟设备不能审批" }
        val id = targetRequest.optString("requestId", targetRequest.optString("id"))
        if (!state.value.isRequester && state.value.requestId == id && state.value.phase in setOf(CloudApprovalPhase.WAITING, CloudApprovalPhase.COMPARE)) {
            current(); return@withLock
        }
        check(state.value.phase !in setOf(CloudApprovalPhase.WAITING, CloudApprovalPhase.COMPARE)) { "已有授权正在核对" }
        clear(); val s = session(); prepare(s); remember(s); requester = false
        // Publish the busy state before any accept I/O, so discover cannot redisplay it.
        mutable.value = CloudApprovalState(CloudApprovalPhase.WAITING, id, targetRequest.optString("displayName"), message = "正在开始核对")
        val key = CloudProtocolV2.signingKey(); signingKey = key
        val n = ByteArray(32).also(SecureRandom()::nextBytes); nonce = n
        // Freeze exactly the public key and commitment shown in the pending request.
        target = JSONObject().apply { listOf("requestId", "deviceId", "publicKeySpki", "commitment").forEach { field ->
            put(field, if (field == "requestId") id else targetRequest.getString(field))
        } }.put("approverSpki", key.public.encoded.base64())
            .put("approverNonce", n.base64()).put("approverDeviceId", deviceId)
        post(s, path(id) + "/accept", JSONObject().put("approverDeviceId", deviceId)
            .put("approverSpki", key.public.encoded.base64()).put("approverNonce", n.base64()))
        current()
        mutable.value = CloudApprovalState(CloudApprovalPhase.WAITING, id, targetRequest.optString("displayName"), message = "等待新设备揭示")
    }
    suspend fun poll() = mutex.withLock {
        if (state.value.phase !in setOf(CloudApprovalPhase.WAITING, CloudApprovalPhase.COMPARE)) return@withLock
        val s = current(); val remote = get(s, path()); current()
        val status = remote.optString("status")
        if (status in setOf("expired", "cancelled")) {
            mutable.value = state.value.copy(phase = if (status == "expired") CloudApprovalPhase.EXPIRED else CloudApprovalPhase.CANCELLED, code = null)
            signingKey = null; nonce?.fill(0); pendingDelivery = null; requesterConfirmed = false; return@withLock
        }
        val pinned = requireNotNull(target)
        if (requester && remote.has("approverSpki") && !remote.isNull("approverSpki")) {
            if (!pinned.hasValue("approverSpki")) {
                listOf("approverSpki", "approverNonce", "approverDeviceId").forEach { pinned.put(it, remote.getString(it)) }
            } else listOf("approverSpki", "approverNonce", "approverDeviceId").forEach {
                require(if(it == "approverSpki") pinned.getString(it).base64Bytes().contentEquals(remote.getString(it).base64Bytes()) else pinned.getString(it) == remote.getString(it))
            }
            val encodedNonce = requireNotNull(nonce).base64()
            if (!revealed) {
                // A lost response may follow a successful reveal. GET is authoritative.
                if (remote.has("requesterNonce") && !remote.isNull("requesterNonce")) {
                    require(remote.getString("requesterNonce").base64Bytes().contentEquals(requireNotNull(nonce)))
                } else {
                    post(s, path() + "/reveal", JSONObject().put("requesterNonce", encodedNonce))
                    current()
                }
                revealed = true
            }
            pinned.put("requesterNonce", encodedNonce)
        } else if (!requester && remote.has("requesterNonce") && !remote.isNull("requesterNonce")) {
            pinned.put("requesterNonce", remote.getString("requesterNonce"))
            require(CloudProtocolV2.equal(pinned.getString("commitment"), CloudProtocolV2.approvalCommitment(
                pinned.getString("publicKeySpki").base64Bytes(), pinned.getString("requesterNonce").base64Bytes()))) { "新设备承诺不匹配" }
        }
        if (pinned.hasValue("requesterNonce") && pinned.hasValue("approverSpki")) {
            val t = CloudProtocolV2.transcript(s.userId, requireNotNull(epoch), state.value.requestId, pinned.getString("deviceId"),
                pinned.getString("publicKeySpki").base64Bytes(), pinned.getString("approverDeviceId"), pinned.getString("approverSpki").base64Bytes(),
                pinned.getString("requesterNonce").base64Bytes(), pinned.getString("approverNonce").base64Bytes())
            transcript?.let { require(it.contentEquals(t)) { "授权会话已改变" } }; transcript = t
            mutable.value = state.value.copy(phase = CloudApprovalPhase.COMPARE, code = CloudProtocolV2.code(t), message = "请在已登录设备上核对此码")
            if (requester && status == "delivered") {
                // R1: cache the exact bytes, but do not verify/decrypt/install until N's own user confirms.
                pendingDelivery = remote.getString("payload") to remote.getString("signature")
                if (requesterConfirmed) acceptPendingDelivery(s)
            }
        }
    }
    suspend fun confirmMatches() = mutex.withLock {
        check(state.value.phase == CloudApprovalPhase.COMPARE)
        val s = current()
        if (requester) {
            requesterConfirmed = true
            mutable.value = state.value.copy(locallyConfirmed = true, message = "已核对，等待已登录设备交付")
            acceptPendingDelivery(s)
            return@withLock
        }
        val pinned = requireNotNull(target)
        val payload = deliverPayload(s, pinned.getString("deviceId"), pinned.getString("publicKeySpki")); current()
        val signature = CloudProtocolV2.sign(requireNotNull(signingKey).private, requireNotNull(transcript), payload).base64()
        post(s, path() + "/deliver", JSONObject().put("payload", payload).put("signature", signature)); current()
        mutable.value = state.value.copy(phase = CloudApprovalPhase.DELIVERED); signingKey = null; nonce?.fill(0)
    }
    private suspend fun acceptPendingDelivery(s: PhoneAccountSession) {
        check(requester && requesterConfirmed)
        val (payload, signature) = pendingDelivery ?: return
        val pinned = requireNotNull(target)
        require(CloudProtocolV2.verify(pinned.getString("approverSpki").base64Bytes(), requireNotNull(transcript), payload, signature.base64Bytes())) { "授权签名验证失败" }
        receivePayload(s, payload); current()
        mutable.value = state.value.copy(phase = CloudApprovalPhase.DELIVERED)
        pendingDelivery = null; nonce?.fill(0); signingKey = null
    }
    suspend fun reject() = mutex.withLock {
        val s = current()
        try { post(s, path() + "/cancel", JSONObject()) }
        finally {
            // The relay may already be terminal-delivered. A local mismatch still irrevocably rejects it.
            mutable.value = state.value.copy(phase = CloudApprovalPhase.CANCELLED, code = null)
            pendingDelivery = null; requesterConfirmed = false; signingKey = null; nonce?.fill(0)
        }
    }
}

internal fun CloudKeyEnvelope.wireJson(): JSONObject = JSONObject().put("algorithm", algorithm).put("keyVersion", keyVersion)
    .put("wrappedKeyBase64", wrappedKeyBase64).put("nonceBase64", nonceBase64).put("ephemeralPublicKeySpki", ephemeralPublicKeySpki ?: JSONObject.NULL)
internal fun JSONObject.cloudEnvelope(): CloudKeyEnvelope = CloudKeyEnvelope(getString("algorithm"), getInt("keyVersion"),
    getString("wrappedKeyBase64"), getString("nonceBase64"), optString("ephemeralPublicKeySpki").takeUnless { it.isBlank() || it == "null" })

/** A retry keeps the same nonce, pinned approver and epoch. Protocol rejection is terminal. */
internal fun retryableCloudApprovalFailure(error: Exception): Boolean = when (error) {
    is CloudHttpException -> error.status in setOf(408, 429) || error.status >= 500
    is CloudDeviceAuthorizationException -> false
    is java.io.IOException -> true
    else -> false
}

private fun JSONObject.hasValue(name: String): Boolean = has(name) && !isNull(name)

/** Bounded consecutive transport failures; a successful poll restores the retry budget. */
internal class CloudApprovalPollRetries(private val maximumRetries: Int = 3) {
    private var failures = 0
    fun succeeded() { failures = 0 }
    fun retry(error: Exception): Boolean = retryableCloudApprovalFailure(error) && ++failures <= maximumRetries
    val delayMillis: Long get() = (1500L * failures.coerceAtLeast(1)).coerceAtMost(6000L)
}
