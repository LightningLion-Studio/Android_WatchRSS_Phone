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
enum class PhoneChangeError { PHONE_IN_USE, RATE_LIMITED, INVALID_CODE, MFA_EXPIRED, TRANSACTION_EXPIRED, TRANSACTION_USED, ACCOUNT_CHANGED, NETWORK }
data class PhoneChangeState(val phase: PhoneChangePhase = PhoneChangePhase.IDLE, val busy: Boolean = false,
    val loginProgress: LoginProgress? = null, val expiresAt: Long = 0, val phoneMasked: String = "", val error: PhoneChangeError? = null, val errorMessage: String = "")

/** Suspend callbacks let the existing account MFA flow and Lead's Compose page share one state machine. */
class PhoneChangeViewModel internal constructor(
    private val userId: () -> String?,
    private val beginVerification: suspend () -> LoginProgress,
    private val startChange: suspend (String, String) -> PhoneChangeTransaction,
    private val verifyChange: suspend (String, String) -> String,
    private val now: () -> Long = System::currentTimeMillis,
    private val refreshPhoneChangeResult: suspend (String) -> String? = { null }
) : ViewModel() {
    constructor(repository: PhoneAccountRepository) : this({ repository.session.value?.userId }, repository::startPhoneChangeVerification,
        repository::startPhoneChange, repository::verifyPhoneChange, refreshPhoneChangeResult = repository::refreshPhoneChangeResult)
    private val mutable = MutableStateFlow(PhoneChangeState())
    val state: StateFlow<PhoneChangeState> = mutable
    private val mutex = Mutex()
    private var owner: String? = null
    private var verification: String? = null
    private var verifiedUntil = 0L
    private var requestedPhone: String? = null
    private var transaction: PhoneChangeTransaction? = null
    fun reset() { owner = null; verification = null; transaction = null; requestedPhone = null; verifiedUntil = 0; mutable.value = PhoneChangeState() }
    private fun checkOwner() { check(owner != null && userId() == owner) { "phone_change_account_changed" } }
    private suspend fun action(requiredPhase: PhoneChangePhase? = null, block: suspend () -> Unit) = mutex.withLock {
        if (requiredPhase != null && state.value.phase != requiredPhase) return@withLock
        mutable.value = state.value.copy(busy = true, error = null)
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val error = phoneChangeError(e)
            if (error == PhoneChangeError.ACCOUNT_CHANGED) { reset() }
            if (error in setOf(PhoneChangeError.TRANSACTION_EXPIRED, PhoneChangeError.TRANSACTION_USED, PhoneChangeError.MFA_EXPIRED) ||
                (error == PhoneChangeError.RATE_LIMITED && state.value.phase == PhoneChangePhase.ENTER_CODE)) requireIdentity(error)
            mutable.value = state.value.copy(error = error, errorMessage = phoneChangeErrorText(e))
        } finally { mutable.value = state.value.copy(busy = false) }
    }
    private fun requireIdentity(error: PhoneChangeError) {
        verification = null; verifiedUntil = 0; transaction = null; requestedPhone = null
        mutable.value = state.value.copy(phase = PhoneChangePhase.VERIFY_IDENTITY, loginProgress = null, expiresAt = 0, error = error)
    }
    suspend fun begin() = action {
        reset(); owner = requireNotNull(userId()) { "phone_change_account_changed" }
        val progress = beginVerification(); checkOwner()
        mutable.value = PhoneChangeState(PhoneChangePhase.VERIFY_IDENTITY, busy = true, loginProgress = progress)
        if (progress.complete) acceptVerification(progress)
    }
    fun acceptVerification(progress: LoginProgress) {
        checkOwner(); require(progress.complete && "sms" in progress.completedFactors && !progress.verificationToken.isNullOrBlank()) { "请完成当前账号身份验证" }
        progress.session?.let { require(it.userId == owner) { "phone_change_account_changed" } }
        verification = progress.verificationToken; verifiedUntil = now() + 10 * 60 * 1000
        mutable.value = state.value.copy(phase = PhoneChangePhase.ENTER_PHONE, loginProgress = progress, error = null)
    }
    suspend fun sendCode(newPhone: String) = action {
        if (state.value.phase == PhoneChangePhase.ENTER_CODE || state.value.phase == PhoneChangePhase.COMPLETE) return@action
        checkOwner()
        if (verification == null || now() >= verifiedUntil) {
            verification = null; mutable.value = state.value.copy(phase = PhoneChangePhase.VERIFY_IDENTITY, error = PhoneChangeError.MFA_EXPIRED)
            return@action
        }
        transaction = try { startChange(newPhone, requireNotNull(verification)) }
        catch(e: Exception) {
            if ((e is java.io.IOException && e !is PhoneAccountHttpException) || (e as? PhoneAccountHttpException)?.statusCode?.let { it >= 500 } == true)
                requireIdentity(PhoneChangeError.MFA_EXPIRED)
            throw e
        }; checkOwner()
        // A successful start consumes this proof. A new transaction requires fresh identity verification.
        verification = null; verifiedUntil = 0; requestedPhone = newPhone
        mutable.value = state.value.copy(phase = PhoneChangePhase.ENTER_CODE, expiresAt = requireNotNull(transaction).expiresAt)
    }
    suspend fun verifyCode(code: String) = action(PhoneChangePhase.ENTER_CODE) {
        checkOwner(); val tx = requireNotNull(transaction)
        if (now() >= tx.expiresAt) { requireIdentity(PhoneChangeError.TRANSACTION_EXPIRED); return@action }
        if (!code.matches(Regex("[0-9]{6}"))) { mutable.value = state.value.copy(error = PhoneChangeError.INVALID_CODE); return@action }
        val masked = try { verifyChange(tx.transactionId, code) }
        catch (error: PhoneAccountHttpException) {
            if (phoneChangeError(error) != PhoneChangeError.TRANSACTION_USED) throw error
            checkOwner()
            val refreshed = refreshPhoneChangeResult(tx.transactionId); checkOwner()
            refreshed ?: throw error
        }
        checkOwner()
        verification = null; transaction = null; requestedPhone = null
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
        code in setOf("fresh_authentication_required", "old_phone_sms_required") -> PhoneChangeError.MFA_EXPIRED
        code == "phone_change_used" -> PhoneChangeError.TRANSACTION_USED
        code in setOf("phone_change_expired", "phone_change_not_found") -> PhoneChangeError.TRANSACTION_EXPIRED
        else -> PhoneChangeError.NETWORK
    }
}

/** Lead can bind state.errorMessage for new server errors without exposing server detail. */
internal fun phoneChangeErrorText(error: Exception): String {
    val code = (error as? PhoneAccountHttpException)?.let { runCatching { JSONObject(it.responseBody).optString("error") }.getOrDefault("") }
    return when(code) {
        "phone_change_disabled" -> "手机号换绑暂未开放，请稍后再试或联系支持"
        "phone_in_use" -> "该号码已绑定账号，请换用未注册号码；如需使用原账号，请退出后登录该账号"
        "old_phone_sms_required", "fresh_authentication_required" -> "请重新验证旧手机号短信及账号身份；旧号不可用请联系支持申诉"
        "sms_provider_timeout", "sms_send_failed", "sms_provider_unavailable" -> "短信未确认发送成功，本次尝试可能已计入额度，请稍后重新验证身份"
        else -> when(phoneChangeError(error)) {
            PhoneChangeError.RATE_LIMITED -> "验证次数已达上限，请稍后再试"
            PhoneChangeError.INVALID_CODE -> "验证码不正确，请检查后重新输入"
            PhoneChangeError.TRANSACTION_EXPIRED, PhoneChangeError.MFA_EXPIRED -> "验证已过期，请关闭后重新开始"
            PhoneChangeError.TRANSACTION_USED -> "本次验证已使用，请先核对换绑结果"
            PhoneChangeError.ACCOUNT_CHANGED -> "账号已切换，请重新开始"
            else -> "尚未确认换绑结果，请检查网络后重试核对"
        }
    }
}
