package com.lightningstudio.watchrss.phone.account

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class PhoneChangeTransaction(val transactionId: String, val expiresAt: Long)
enum class PhoneChangePhase { IDLE, VERIFY_IDENTITY, ENTER_PHONE, ENTER_CODE, COMPLETE }
enum class PhoneChangeError { PHONE_IN_USE, RATE_LIMITED, INVALID_CODE, MFA_EXPIRED, TRANSACTION_EXPIRED, ACCOUNT_CHANGED, NETWORK }
data class PhoneChangeState(val phase: PhoneChangePhase = PhoneChangePhase.IDLE, val busy: Boolean = false,
    val loginProgress: LoginProgress? = null, val expiresAt: Long = 0, val phoneMasked: String = "", val error: PhoneChangeError? = null)

/** Suspend callbacks let the existing account MFA flow and Lead's Compose page share one state machine. */
class PhoneChangeViewModel internal constructor(
    private val userId: () -> String?,
    private val beginVerification: suspend () -> LoginProgress,
    private val startChange: suspend (String, String) -> PhoneChangeTransaction,
    private val verifyChange: suspend (String, String) -> String,
    private val now: () -> Long = System::currentTimeMillis
) : ViewModel() {
    constructor(repository: PhoneAccountRepository) : this({ repository.session.value?.userId }, repository::startPhoneChangeVerification,
        repository::startPhoneChange, repository::verifyPhoneChange)
    private val mutable = MutableStateFlow(PhoneChangeState())
    val state: StateFlow<PhoneChangeState> = mutable
    private val mutex = Mutex()
    private var owner: String? = null
    private var verification: String? = null
    private var verifiedUntil = 0L
    private var transaction: PhoneChangeTransaction? = null
    fun reset() { owner = null; verification = null; transaction = null; verifiedUntil = 0; mutable.value = PhoneChangeState() }
    private fun checkOwner() { check(owner != null && userId() == owner) { "phone_change_account_changed" } }
    private suspend fun action(block: suspend () -> Unit) = mutex.withLock {
        mutable.value = state.value.copy(busy = true, error = null)
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val error = phoneChangeError(e)
            if (error == PhoneChangeError.ACCOUNT_CHANGED) { reset() }
            if (error == PhoneChangeError.TRANSACTION_EXPIRED) { transaction = null; mutable.value = state.value.copy(phase = PhoneChangePhase.ENTER_PHONE) }
            if (error == PhoneChangeError.MFA_EXPIRED) { verification = null; mutable.value = state.value.copy(phase = PhoneChangePhase.VERIFY_IDENTITY) }
            mutable.value = state.value.copy(error = error)
        } finally { mutable.value = state.value.copy(busy = false) }
    }
    suspend fun begin() = action {
        reset(); owner = requireNotNull(userId()) { "phone_change_account_changed" }
        val progress = beginVerification(); checkOwner()
        mutable.value = PhoneChangeState(PhoneChangePhase.VERIFY_IDENTITY, busy = true, loginProgress = progress)
        if (progress.complete) acceptVerification(progress)
    }
    fun acceptVerification(progress: LoginProgress) {
        checkOwner(); require(progress.complete && !progress.verificationToken.isNullOrBlank()) { "请完成当前账号身份验证" }
        progress.session?.let { require(it.userId == owner) { "phone_change_account_changed" } }
        verification = progress.verificationToken; verifiedUntil = now() + 10 * 60 * 1000
        mutable.value = state.value.copy(phase = PhoneChangePhase.ENTER_PHONE, loginProgress = progress, error = null)
    }
    suspend fun sendCode(newPhone: String) = action {
        checkOwner()
        if (verification == null || now() >= verifiedUntil) {
            verification = null; mutable.value = state.value.copy(phase = PhoneChangePhase.VERIFY_IDENTITY, error = PhoneChangeError.MFA_EXPIRED)
            return@action
        }
        transaction = startChange(newPhone, requireNotNull(verification)); checkOwner()
        mutable.value = state.value.copy(phase = PhoneChangePhase.ENTER_CODE, expiresAt = requireNotNull(transaction).expiresAt)
    }
    suspend fun verifyCode(code: String) = action {
        checkOwner(); val tx = requireNotNull(transaction)
        if (now() >= tx.expiresAt) { mutable.value = state.value.copy(phase = PhoneChangePhase.ENTER_PHONE, error = PhoneChangeError.TRANSACTION_EXPIRED); return@action }
        if (!code.matches(Regex("[0-9]{6}"))) { mutable.value = state.value.copy(error = PhoneChangeError.INVALID_CODE); return@action }
        val masked = verifyChange(tx.transactionId, code); checkOwner()
        verification = null; transaction = null
        mutable.value = PhoneChangeState(PhoneChangePhase.COMPLETE, phoneMasked = masked)
    }
}

internal fun phoneChangeError(error: Exception): PhoneChangeError {
    val code = (error as? PhoneAccountHttpException)?.let { runCatching { JSONObject(it.responseBody).optString("error") }.getOrDefault("") }
    return when {
        error.message == "phone_change_account_changed" -> PhoneChangeError.ACCOUNT_CHANGED
        code == "phone_in_use" -> PhoneChangeError.PHONE_IN_USE
        code == "phone_change_rate_limited" || (error as? PhoneAccountHttpException)?.statusCode == 429 -> PhoneChangeError.RATE_LIMITED
        code == "invalid_sms_code" -> PhoneChangeError.INVALID_CODE
        code == "fresh_authentication_required" -> PhoneChangeError.MFA_EXPIRED
        code in setOf("phone_change_expired", "phone_change_used", "phone_change_not_found") -> PhoneChangeError.TRANSACTION_EXPIRED
        else -> PhoneChangeError.NETWORK
    }
}
