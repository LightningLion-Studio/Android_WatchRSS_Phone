package com.lightningstudio.watchrss.phone.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.lightningstudio.watchrss.phone.connection.bluetooth.AuthorizedBluetoothRequest
import com.lightningstudio.watchrss.phone.network.CloudHttpDeadline
import com.lightningstudio.watchrss.phone.network.LogoutHttpTimeout
import org.json.JSONArray
import org.json.JSONObject

class PhoneAccountRepository(
    private val environment: AccountEnvironment,
    private val sessionStore: EncryptedAccountSessionStore,
    private val installationIdentity: PhoneInstallationIdentity,
    private val accountClient: PhoneAccountClient,
    private val phoneDeviceId: String
) {
    internal var onReleaseCloudAuthorization: suspend (PhoneAccountSession) -> Unit = {}
    internal var onClearCloudState: (PhoneAccountSession) -> Unit = {}
    val session: StateFlow<PhoneAccountSession?> = sessionStore.session
    private val sessionChanges = Mutex()
    // The old session stays published while secure storage suspends.
    internal val cloudSessionLock = Any()
    @Volatile private var departingCloudOwner: String? = null

    internal val cloudSessionChanging: Boolean
        get() = departingCloudOwner?.let { it == session.value?.userId } == true

    private suspend fun <T> retireCloudSession(owner: PhoneAccountSession?, change: suspend () -> T): T {
        if (owner == null) return change()
        return withPurchasesBlockedForLogout { withDepartingCloudOwner(owner, change) }
    }

    private suspend fun <T> withDepartingCloudOwner(owner: PhoneAccountSession?, change: suspend () -> T): T {
        synchronized(cloudSessionLock) { departingCloudOwner = owner?.userId }
        return try {
            change()
        } finally {
            synchronized(cloudSessionLock) { departingCloudOwner = null }
        }
    }

    internal fun cloudAuthentication() = sessionStore.cloudAuthentication()

    private suspend fun clearSession(owner: PhoneAccountSession) = sessionChanges.withLock {
        if (session.value?.userId != owner.userId) return@withLock
        retireCloudSession(owner) {
            try { onClearCloudState(owner) } finally { sessionStore.clear() }
        }
    }

    init {
        accountClient.authenticatedOwner = { token -> session.value?.takeIf { it.accessToken == token }?.userId }
        accountClient.onAuthenticatedFailure = ::recordAuthenticationFailure
    }

    private fun offlineScope(owner: String) = com.lightningstudio.watchrss.phone.data.ble.BleDomainStore.hash("${environment.backendBaseUrl}\n$owner")
    internal fun offlineAuthorizationVersion(owner: String) = sessionStore.offlineAuthorizationVersion(offlineScope(owner))
    internal fun offlineAuthorizationBlocked(owner: String) = sessionStore.offlineAuthorizationBlocked(offlineScope(owner))
    internal fun <T> withOfflineAuthorizationStatus(owner: String, action: (Boolean) -> T): T =
        sessionStore.withOfflineAuthorizationStatus(offlineScope(owner), action)
    internal val offlineAuthorizationChanges get() = sessionStore.offlineAuthorizationChanges
    internal fun confirmOnlineAuthorization(owner: String, version: Long) = sessionStore.confirmOnlineAuthorization(offlineScope(owner), version)

    internal fun recordAuthenticationResponse(owner: String, status: Int, body: String) =
        recordAuthenticationFailure(owner, PhoneAccountHttpException(status, body))

    internal fun recordAuthenticationFailure(owner: String, error: Throwable) {
        if (isExplicitAccountRejection(error)) {
            sessionStore.rejectOfflineAuthorization(offlineScope(owner), owner, environment.backendBaseUrl)
        }
    }

    val reauthenticationOwner: StateFlow<String?> = sessionStore.reauthenticationOwner
    internal val b2AccountFence: B2AccountFence get() = sessionStore.b2AccountFence

    suspend fun beginSameAccountReauthentication() = sessionStore.beginSameAccountReauthentication()

    val hasUsableSession: Boolean
        get() = accountSessionStatus(session.value, reauthenticationOwner.value) == AccountSessionStatus.USABLE

    val installId: String
        get() = installationIdentity.installId

    suspend fun initialize() {
        sessionStore.load()
    }

    suspend fun refreshAccountSafety():AccountSafetyProfile {
        val expected=requireSession();val profile=accountClient.accountSafetyProfile(expected)
        val current=requireSession();check(current.userId==expected.userId && current.accessToken==expected.accessToken) { "phone_change_account_changed" }
        sessionStore.save(current.copy(phoneMasked=profile.phoneMasked));return profile
    }
    suspend fun cancelPhoneAppeal(id:String):AccountSafetyProfile {
        val expected=requireSession();accountClient.cancelPhoneAppeal(expected,id)
        val current=requireSession();check(current.userId==expected.userId && current.accessToken==expected.accessToken) { "phone_change_account_changed" }
        return refreshAccountSafety()
    }
    suspend fun startPhoneChangeVerification(): LoginProgress =
        accountClient.startActionSecurityVerification(requireSession(), "change-phone")
    suspend fun startPhoneChange(newPhone: String, verificationToken: String): PhoneChangeTransaction =
        accountClient.startPhoneChange(requireSession(), newPhone, verificationToken)
    suspend fun verifyPhoneChange(transactionId: String, code: String): String {
        val expected = requireSession()
        val masked = accountClient.verifyPhoneChange(expected, transactionId, code)
        val current = requireSession()
        check(current.userId == expected.userId) { "账号已切换" }
        sessionStore.save(current.copy(phoneMasked = masked))
        return masked
    }

    suspend fun refreshPhoneChangeResult(transactionId: String): String? {
        val expected = requireSession()
        val masked = accountClient.refreshPhoneChangeResult(expected, transactionId) ?: return null
        val current = requireSession()
        check(current.userId == expected.userId) { "phone_change_account_changed" }
        sessionStore.save(current.copy(phoneMasked = masked))
        return masked
    }

    suspend fun startLogin(phone: String): LoginProgress = accountClient.startLogin(phone)

    suspend fun loginWithPasswordFactor(transactionId: String, password: String): LoginProgress {
        return accountClient.loginWithPasswordFactor(transactionId, password).let { progress ->
            saveCompletedLogin(progress)
            progress
        }
    }

    suspend fun requestPhoneOtpFactor(transactionId: String) {
        accountClient.requestPhoneOtpFactor(transactionId)
    }

    suspend fun verifyPhoneOtpFactor(transactionId: String, otp: String): LoginProgress {
        return accountClient.verifyPhoneOtpFactor(transactionId, otp).let { progress ->
            saveCompletedLogin(progress)
            progress
        }
    }

    suspend fun verifyTotpFactor(transactionId: String, code: String): LoginProgress {
        return accountClient.verifyTotpFactor(transactionId, code).let { progress ->
            saveCompletedLogin(progress)
            progress
        }
    }

    suspend fun updatePassword(password: String, verificationToken: String) {
        val current = requireSession()
        try {
            accountClient.updatePassword(current, password, verificationToken)
        } finally {
            // The backend revokes all opaque sessions before attempting the
            // upstream credential update, including on an upstream failure.
            clearSession(current)
        }
    }

    suspend fun listTotpFactors(): List<TotpFactor> =
        accountClient.listTotpFactors(requireSession())

    suspend fun beginTotpEnrollment(): TotpEnrollment =
        accountClient.beginTotpEnrollment(requireSession())

    suspend fun confirmTotpEnrollment(
        enrollment: TotpEnrollment,
        code: String
    ) {
        accountClient.confirmTotpEnrollment(requireSession(), enrollment, code)
    }

    suspend fun disableTotp(factor: TotpFactor, code: String) {
        accountClient.disableTotp(requireSession(), factor, code)
    }

    suspend fun securityStatus(): AccountSecurityStatus =
        accountClient.securityStatus(requireSession())

    suspend fun setTwoFactorEnabled(
        enabled: Boolean,
        verificationToken: String? = null
    ): AccountSecurityStatus {
        val current = requireSession()
        val status = accountClient.setTwoFactorEnabled(
            current,
            enabled,
            verificationToken
        )
        if (enabled) clearSession(current)
        return status
    }

    suspend fun startSecurityVerification(): LoginProgress =
        accountClient.startSecurityVerification(requireSession())

    suspend fun startPasswordSecurityVerification(): LoginProgress =
        accountClient.startPasswordSecurityVerification(requireSession())

    suspend fun startActionSecurityVerification(action: String): LoginProgress =
        accountClient.startActionSecurityVerification(requireSession(), action)

    suspend fun startPasskeyRegistration(): PasskeyOptions {
        val session = requireSession()
        return accountClient.startPasskeyRegistration(session)
    }

    suspend fun listRegisteredPasskeys(): List<RegisteredPasskey> {
        val session = requireSession()
        return accountClient.listRegisteredPasskeys(session)
    }

    suspend fun renameRegisteredPasskey(credentialId: String, displayName: String) {
        val session = requireSession()
        accountClient.renameRegisteredPasskey(session, credentialId, displayName)
    }

    suspend fun deleteRegisteredPasskey(credentialId: String) {
        val session = requireSession()
        accountClient.deleteRegisteredPasskey(session, credentialId)
    }

    suspend fun finishPasskeyRegistration(
        challengeId: String,
        credentialJson: String
    ) {
        val session = requireSession()
        accountClient.finishPasskeyRegistration(session, challengeId, credentialJson)
    }

    suspend fun startPasskeyAuthentication(
        phone: String,
        transactionId: String? = null
    ): PasskeyOptions = accountClient.startPasskeyAuthentication(phone, transactionId)

    suspend fun finishPasskeyAuthentication(
        challengeId: String,
        credentialJson: String
    ): LoginProgress {
        return accountClient.finishPasskeyAuthentication(challengeId, credentialJson).let { progress ->
            saveCompletedLogin(progress)
            progress
        }
    }

    internal suspend fun saveCompletedLogin(progress: LoginProgress) {
        val next = progress.session ?: return
        sessionChanges.withLock {
            val previous = session.value?.takeIf { it.userId != next.userId }
            retireCloudSession(previous) {
                previous?.let(onClearCloudState)
                sessionStore.clearBluetoothGrants()
                sessionStore.save(next)
                sessionStore.finishReauthentication()
            }
        }
    }

    internal suspend fun <T> withPurchasesBlockedForLogout(action: suspend (PhoneAccountSession?) -> T): T {
        val current = sessionStore.beginLogout()
        return try {
            action(current)
        } finally {
            sessionStore.finishLogout()
        }
    }

    suspend fun logout() = withPurchasesBlockedForLogout { expected ->
        logoutWithSessionCheck(expected) {}
    }

    /** The caller holds the purchase block from logout entry through this operation. */
    internal suspend fun <T> logoutWithSessionCheck(
        expected: PhoneAccountSession?,
        beforeLogout: suspend () -> T
    ): T = sessionChanges.withLock {
        val current = session.value
        check(current.hasSameSessionCredentials(expected)) { "账号已变更，请重试" }
        withDepartingCloudOwner(current) {
            sessionStore.revokeB2Authorization()
            // Revoke the cloud device while the phone authorization is still available.
            current?.let { runCatching { onReleaseCloudAuthorization(it.copy(authenticationPaused = false)) } }
            val result = beforeLogout()
            logoutSession(current)
            result
        }
    }

    private suspend fun logoutSession(current: PhoneAccountSession?) {
        if (current != null && !current.isExpired) {
            val queued = runCatching { sessionStore.queueRevocation(current) }.isSuccess
            val revoked = runCatching {
                CloudHttpDeadline.runOrNull(LogoutHttpTimeout.ACCOUNT_SESSION_MILLIS) {
                    accountClient.logout(current)
                } != null
            }.getOrDefault(false)
            check(revoked || queued) { "无法安全退出，请检查网络后重试" }
            if (revoked) {
                if (queued) {
                    sessionStore.clearPendingRevocation()
                }
            } else {
                sessionStore.schedulePendingRevocation()
            }
        }
        try { current?.let(onClearCloudState) } finally { sessionStore.clear() }
    }

    suspend fun consumeActivationProof(expectedProof: String) {
        val current = session.value ?: return
        if (expectedProof.isBlank() || current.activationProof != expectedProof) return
        sessionStore.save(
            current.copy(
                activationProof = "",
                updatedAtMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun appAccessStatus(): AppAccessSummary =
        accountClient.appAccessStatus(requireSession())

    suspend fun claimAppAccess(idempotencyKey: String): AppAuthorization =
        accountClient.claimAppAccess(requireSession(), idempotencyKey)

    suspend fun startTrialAppAccess(idempotencyKey: String): AppAuthorization =
        accountClient.startTrialAppAccess(requireSession(), idempotencyKey)

    suspend fun refreshAppAccess(current: AppAuthorization): AppAuthorization =
        accountClient.refreshAppAccess(requireSession(), current)

    suspend fun releaseAppAccess(current: AppAuthorization): Boolean =
        accountClient.releaseAppAccess(requireSession(), current)

    internal fun captureWeChatPurchaseSession(expected: PhoneAccountSession?, authenticated: Boolean) =
        sessionStore.capturePurchaseSession(expected, authenticated)

    internal fun <T> withCurrentWeChatPurchaseSession(
        snapshot: EncryptedAccountSessionStore.PurchaseSession,
        action: () -> T
    ): T? = sessionStore.withCurrentPurchaseSession(snapshot, action)

    internal suspend fun createWeChatHandoff(
        snapshot: EncryptedAccountSessionStore.PurchaseSession
    ): WeChatHandoff = coroutineScope {
        val owner = sessionStore.withCurrentPurchaseSession(snapshot) {
            snapshot.session?.takeIf { snapshot.authenticated }
        } ?: throw CancellationException("Purchase session changed")
        val sessionWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            sessionStore.awaitPurchaseSessionChange(snapshot)
            this@coroutineScope.cancel(CancellationException("Purchase session changed"))
        }
        try {
            ensureActive()
            val handoff = accountClient.createWeChatHandoff(owner)
            sessionStore.withCurrentPurchaseSession(snapshot) { handoff }
                ?: throw CancellationException("Purchase session changed")
        } finally {
            sessionWatcher.cancel()
        }
    }

    suspend fun createPaymentOrder(idempotencyKey: String): AppPaymentOrder =
        accountClient.createPaymentOrder(requireSession(), idempotencyKey)

    suspend fun paymentOrder(orderId: String): AppPaymentOrder =
        accountClient.paymentOrder(requireSession(), orderId)

    internal suspend fun createMembershipPaymentOrder(
        expectedUserId: String,
        productId: String,
        idempotencyKey: String
    ): AppPaymentOrder = withMembershipPaymentOwner(expectedUserId) {
        accountClient.createMembershipPaymentOrder(it, productId, idempotencyKey).also { order ->
            check(order.product.productId == productId) { "订单商品不匹配" }
        }
    }

    internal suspend fun resumeMembershipPaymentOrder(expectedUserId: String, orderId: String): AppPaymentOrder =
        withMembershipPaymentOwner(expectedUserId) {
            accountClient.resumeMembershipPaymentOrder(it, orderId).also { order ->
                check(order.orderId == orderId) { "订单号不匹配" }
            }
        }

    internal suspend fun membershipPaymentOrder(expectedUserId: String, orderId: String): AppPaymentOrder =
        withMembershipPaymentOwner(expectedUserId) {
            accountClient.paymentOrder(it, orderId).also { order ->
                check(order.orderId == orderId) { "订单号不匹配" }
            }
        }

    private suspend fun withMembershipPaymentOwner(
        expectedUserId: String,
        action: suspend (PhoneAccountSession) -> AppPaymentOrder
    ): AppPaymentOrder {
        val owner = requireSession()
        check(owner.userId == expectedUserId && hasUsableSession) { "请先登录当前账号" }
        val order = action(owner)
        check(requireSession().userId == expectedUserId && hasUsableSession) { "账号已切换" }
        check(order.isXunhuMembership) { "订单商品与支付方式不匹配" }
        return order
    }

    suspend fun paymentOrders(): List<AppPaymentOrder> =
        accountClient.paymentOrders(requireSession())

    suspend fun refundPaymentOrder(
        order: AppPaymentOrder,
        verificationToken: String
    ): AppPaymentOrder = accountClient.refundPaymentOrder(
        requireSession(),
        order,
        verificationToken
    )

    suspend fun deleteAccount(verificationToken: String): AccountDeletionResult {
        val current = requireSession()
        val result = accountClient.deleteAccount(current, verificationToken)
        sessionStore.revokeB2Authorization(current.userId)
        clearSession(current)
        return result
    }

    private fun requireSession(): PhoneAccountSession {
        val current = session.value ?: error("请先登录")
        check(!current.authenticationPaused) { "请先完成账号重新认证" }
        require(!current.isExpired) { "登录已过期，请重新登录" }
        return current
    }

    suspend fun issuePairingWatchCredentials(watchDeviceId: String, watchInstallId: String, expectedUserId: String): WatchDeviceToken {
        val owner = requireSession()
        check(owner.userId == expectedUserId) { "账号已切换，请重新配对" }
        val token = accountClient.issueWatchDeviceToken(owner, phoneDeviceId, watchDeviceId, watchInstallId, "手机扫码绑定的手表")
        check(requireSession().userId == owner.userId) { "账号已切换，请重新配对" }
        return token
    }

    private fun bluetoothGrantKey(watch:String, owner:String?=session.value?.userId)=com.lightningstudio.watchrss.phone.data.ble.BleDomainStore.hash("${environment.backendBaseUrl}\n$owner\n$watch")
    private suspend fun validBluetoothGrant(watch:String, nowMillis:Long=System.currentTimeMillis()):JSONObject? {
        val current=session.value?:return null
        if (!hasUsableSession || offlineAuthorizationBlocked(current.userId)) return null
        if (current.expiresAtMillis > 0 && current.expiresAtMillis <= nowMillis) return null
        val snapshot = sessionStore.bluetoothGrantSnapshot(bluetoothGrantKey(watch, current.userId), offlineScope(current.userId)) ?: return null
        val request = runCatching { JSONObject(snapshot.raw) }.getOrNull() ?: return null
        return request.takeIf {
            val a=it.optJSONObject("account")
            a?.optString("userId")==current.userId && a.optString("phoneMasked")==current.phoneMasked &&
                a.optString("backendBaseUrl")==environment.backendBaseUrl &&
                a.optString("watchAccessToken").isNotBlank() &&
                a.optLong("accessTokenExpiresAt")>maxOf(nowMillis,System.currentTimeMillis())+300_000 &&
                session.value == current && hasUsableSession && sessionStore.isCurrentBluetoothGrant(snapshot)
        }?.let { guardedBluetoothGrant(it, snapshot, current) }
    }

    private fun guardedBluetoothGrant(request: JSONObject, snapshot: EncryptedAccountSessionStore.BluetoothGrantSnapshot,
        expectedSession: PhoneAccountSession): JSONObject = AuthorizedBluetoothRequest(request) { write ->
        sessionStore.withCurrentBluetoothGrant(snapshot) {
            if (session.value != expectedSession || !hasUsableSession) throw PhoneAccountNetworkRequired(
                IllegalStateException("手机账号已变化，请重新验证")
            )
            write()
        }
    }

    internal suspend fun cachedBluetoothAccount(watch:String, nowMillis:Long):JSONObject? =
        validBluetoothGrant(watch, nowMillis)

    // A cached token may only be replayed while the peer still has the state acknowledged with it.
    // A watch can refresh/revoke its own credentials independently of this phone.
    internal suspend fun prepareBluetoothAccount(watch:String,remoteRoot:String?) {
        val key=bluetoothGrantKey(watch)
        val marker=sessionStore.bluetoothGrant("$key:peer")
        val grant=sessionStore.bluetoothGrant(key)
        if(grant!=null && (remoteRoot==null || marker?.optString("root")!=remoteRoot || marker?.optString("grant")!=com.lightningstudio.watchrss.phone.data.ble.BleDomainStore.hash(grant.toString())))
            sessionStore.removeBluetoothGrant(key)
    }
    internal suspend fun acknowledgeBluetoothAccount(watch:String,remoteRoot:String) {
        val key=bluetoothGrantKey(watch);val grant=validBluetoothGrant(watch)?:return
        sessionStore.saveBluetoothGrant("$key:peer",JSONObject().put("root",remoteRoot).put("grant",com.lightningstudio.watchrss.phone.data.ble.BleDomainStore.hash(grant.toString())))
    }

    internal suspend fun bluetoothAccountWitness(watch:String):String? {
        val current=session.value?:return null
        if(!hasUsableSession)return null
        val grant=validBluetoothGrant(watch)
        return com.lightningstudio.watchrss.phone.data.ble.BleDomainStore.hash("${current.userId}\n${current.phoneMasked}\n${grant?.toString()?:"unissued"}")
    }

    suspend fun buildAccountSyncRequest(
        watchDeviceId: String,
        watchInstallId: String? = null,
        watchDisplayName: String? = null
    ): JSONObject {
        val session = requireSession()
        val authorizationVersion = offlineAuthorizationVersion(session.userId)
        val token = accountClient.issueWatchDeviceToken(
            session = session,
            phoneDeviceId = phoneDeviceId,
            watchDeviceId = watchDeviceId,
            watchInstallId = watchInstallId,
            displayName = watchDisplayName
        )
        val request=JSONObject().apply {
            put("version", 1)
            put("action", "syncAccount")
            put("deviceId", phoneDeviceId)
            put("account", JSONObject().apply {
                put("userId", session.userId)
                put("phoneMasked", session.phoneMasked)
                put("installId", installId)
                put("watchDeviceToken", token.accessToken)
                put("tokenExpiresAt", token.accessTokenExpiresAtMillis)
                put("watchAccessToken", token.accessToken)
                put("accessTokenExpiresAt", token.accessTokenExpiresAtMillis)
                put("watchRefreshToken", token.refreshToken)
                put("refreshTokenExpiresAt", token.refreshTokenExpiresAtMillis)
                put("backendBaseUrl", environment.backendBaseUrl)
                put("posthogHost", "")
                put("posthogProjectApiKey", "")
            })
            put("entitlement", JSONObject().apply {
                put("plan", token.entitlement.plan)
                put("active", token.entitlement.active)
                put("expiresAt", token.entitlement.expiresAtMillis)
                put("features", JSONArray(token.entitlement.features))
            })
            put("telemetry", JSONObject().apply {
                put("anonymousEnabled", true)
                put("diagnosticsEnabled", false)
                put("sampleRate", 1.0)
            })
        }
        check(this.session.value == session && hasUsableSession){"手机账号已变化，请重新同步"}
        val snapshot = sessionStore.saveAuthorizedBluetoothGrant(bluetoothGrantKey(watchDeviceId, session.userId), offlineScope(session.userId), authorizationVersion, request)
        check(this.session.value == session && hasUsableSession){"手机账号已变化，请重新同步"}
        return guardedBluetoothGrant(request, snapshot, session)
    }
}
