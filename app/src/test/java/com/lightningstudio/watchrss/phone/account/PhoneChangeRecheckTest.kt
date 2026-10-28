package com.lightningstudio.watchrss.phone.account

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PhoneChangeRecheckTest {
    private val session = PhoneAccountSession("owner", "old-mask", "token", "refresh", Long.MAX_VALUE)
    private val phone = "13800138000"
    private fun proof() = LoginProgress("mfa", 1, listOf("sms"), true, null, "proof")
    private fun failure(code: String, status: Int = 409) = PhoneAccountHttpException(status,"{\"error\":\"$code\"}")
    private fun response(request: Request, body: String, status: Int = 200) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(status).message("test").body(body.toResponseBody("application/json".toMediaType())).build()

    @Test fun localTransactionExpiryDiscardsConsumedProofAndRequiresFreshIdentityBeforeResending() = runBlocking {
        var now = 100L; var starts = 0; var verifies = 0
        val vm = PhoneChangeViewModel({"owner"}, {proof()}, {_,_->starts++;PhoneChangeTransaction("tx",200)}, {_,_->verifies++;"mask"}, {now})
        vm.begin(); vm.sendCode(phone); now=201
        vm.verifyCode("123456"); assertEquals(PhoneChangePhase.VERIFY_IDENTITY,vm.state.value.phase)
        assertEquals(PhoneChangeError.TRANSACTION_EXPIRED,vm.state.value.error); assertNull(vm.state.value.loginProgress)
        assertEquals(0,vm.state.value.expiresAt); vm.sendCode(phone)
        assertEquals(1,starts); assertEquals(0,verifies)
    }
    @Test fun serverExpiredAndMissingTransactionsRequireNewIdentityInsteadOfReusingProof() = runBlocking {
        for (code in listOf("phone_change_expired","phone_change_not_found")) {
            var starts = 0
            val vm=PhoneChangeViewModel({"owner"},{proof()},{_,_->starts++;PhoneChangeTransaction("tx",1000)}, {_,_->throw failure(code)},{100})
            vm.begin();vm.sendCode(phone);vm.verifyCode("123456")
            assertEquals(PhoneChangePhase.VERIFY_IDENTITY,vm.state.value.phase); assertEquals(PhoneChangeError.TRANSACTION_EXPIRED,vm.state.value.error)
            vm.sendCode(phone);assertEquals(1,starts)
        }
    }
    @Test fun usedTransactionReReadsServerAndReportsSuccessAfterLostVerifyResponse() = runBlocking {
        var reads=0;var attempts=0
        val vm=PhoneChangeViewModel({"owner"},{proof()},{_,_->PhoneChangeTransaction("tx",1000)}, {_,_->
            if (++attempts==1) throw IOException("verify response lost") else throw failure("phone_change_used")
        },{100}, refreshPhoneChangeResult={ requested -> assertEquals("tx",requested);reads++;"fresh-server-mask" })
        vm.begin();vm.sendCode(phone);vm.verifyCode("123456")
        assertEquals(PhoneChangeError.NETWORK,vm.state.value.error);assertEquals(PhoneChangePhase.ENTER_CODE,vm.state.value.phase)
        vm.verifyCode("123456");assertEquals(PhoneChangePhase.COMPLETE,vm.state.value.phase)
        assertEquals("fresh-server-mask",vm.state.value.phoneMasked);assertEquals(1,reads)
    }
    @Test fun usedTransactionWithoutMatchingServerPhoneIsNotSuccessOrAnExpiredCode() = runBlocking {
        val vm=PhoneChangeViewModel({"owner"},{proof()},{_,_->PhoneChangeTransaction("tx",1000)}, {_,_->throw failure("phone_change_used")},
            {100},refreshPhoneChangeResult={null})
        vm.begin();vm.sendCode(phone);vm.verifyCode("123456")
        assertEquals(PhoneChangePhase.VERIFY_IDENTITY,vm.state.value.phase);assertEquals(PhoneChangeError.TRANSACTION_USED,vm.state.value.error)
    }
    @Test fun usedTransactionRefreshFailureRemainsRetryableAndNeverClaimsSuccess() = runBlocking {
        val vm=PhoneChangeViewModel({"owner"},{proof()},{_,_->PhoneChangeTransaction("tx",1000)}, {_,_->throw failure("phone_change_used")},
            {100},refreshPhoneChangeResult={throw IOException("offline")})
        vm.begin();vm.sendCode(phone);vm.verifyCode("123456")
        assertEquals(PhoneChangePhase.ENTER_CODE,vm.state.value.phase);assertEquals(PhoneChangeError.NETWORK,vm.state.value.error)
    }
    @Test fun verify429InvalidatesTransactionAndRequiresNewIdentity() = runBlocking {
        var verifies=0;var starts=0
        val vm=PhoneChangeViewModel({"owner"},{proof()},{_,_->starts++;PhoneChangeTransaction("tx",1000)}, {_,_->verifies++;throw failure("phone_change_rate_limited",429)},{100})
        vm.begin();vm.sendCode(phone);vm.verifyCode("123456")
        assertEquals(PhoneChangePhase.VERIFY_IDENTITY,vm.state.value.phase);assertEquals(PhoneChangeError.RATE_LIMITED,vm.state.value.error)
        assertEquals(0,vm.state.value.expiresAt);vm.verifyCode("123456")
        assertEquals(PhoneChangeError.RATE_LIMITED,vm.state.value.error);vm.sendCode(phone)
        assertEquals(1,starts);assertEquals(1,verifies)
    }
    @Test fun reconciliationReadsBackendTransactionWithTheRetainedAccountBearer() {
        val paths=mutableListOf<String>()
        val result = reconcilePhoneChange(AccountEnvironment(backendBaseUrl="https://test.invalid/test",supabaseAnonKey="anon"),session,"tx") { request ->
            assertEquals("GET",request.method);assertEquals("Bearer token",request.header("authorization"));assertEquals("anon",request.header("apikey"))
            paths+=request.url.encodedPath
            response(request,"{\"transactionId\":\"tx\",\"status\":\"completed\",\"phoneMasked\":\"138****8000\"}")
        }
        assertEquals("138****8000",result);assertEquals(listOf("/test/functions/v1/account/phone/change/tx"),paths)
        assertEquals("old-mask",session.phoneMasked)
    }
    @Test fun reconciliationRejectsPendingSupersededDifferentTransactionAndHttpFailures() {
        val env=AccountEnvironment(backendBaseUrl="https://test.invalid")
        assertNull(reconcilePhoneChange(env,session,"tx") { request -> response(request,"{\"transactionId\":\"tx\",\"status\":\"pending\"}") })
        for (status in listOf("superseded","expired","blocked")) assertThrows(PhoneAccountHttpException::class.java) {
            reconcilePhoneChange(env,session,"tx") { request -> response(request,"{\"transactionId\":\"tx\",\"status\":\"$status\"}") }
        }
        assertThrows(IllegalStateException::class.java) {
            reconcilePhoneChange(env,session,"tx") { request -> response(request,"{\"transactionId\":\"other\",\"status\":\"completed\",\"phoneMasked\":\"138****8000\"}") }
        }
        assertThrows(PhoneAccountHttpException::class.java) {
            reconcilePhoneChange(env,session,"tx") { request -> response(request,"{}",403) }
        }
    }
    @Test fun accountSwitchWhileReconcilingCannotFinishAnOldOwnersTransaction() = runBlocking {
        var user="owner"
        val vm=PhoneChangeViewModel({user},{proof()},{_,_->PhoneChangeTransaction("tx",1000)}, {_,_->throw failure("phone_change_used")},
            {100},refreshPhoneChangeResult={user="other";"mask"})
        vm.begin();vm.sendCode(phone);vm.verifyCode("123456")
        assertEquals(PhoneChangeError.ACCOUNT_CHANGED,vm.state.value.error);assertNotEquals(PhoneChangePhase.COMPLETE,vm.state.value.phase)
    }
}
