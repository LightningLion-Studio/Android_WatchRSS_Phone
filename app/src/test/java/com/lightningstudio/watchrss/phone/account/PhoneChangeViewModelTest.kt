package com.lightningstudio.watchrss.phone.account

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PhoneChangeViewModelTest {
    private fun verified() = LoginProgress("mfa",1,listOf("sms"),true,null,"proof")
    @Test fun requiresMfaThenChangesPhoneAndPreservesOwner() = runBlocking {
        var starts=0;var verifies=0
        val vm=PhoneChangeViewModel({"owner"},{LoginProgress("mfa",1,emptyList(),false,null)}, { phone,token ->
            assertEquals("13800138000",phone);assertEquals("proof",token);starts++;PhoneChangeTransaction("change",10000)
        },{tx,code -> assertEquals("change",tx);assertEquals("123456",code);verifies++;"138****8000"},{100})
        vm.begin();assertEquals(PhoneChangePhase.VERIFY_IDENTITY,vm.state.value.phase)
        vm.sendCode("13800138000");assertEquals(0,starts);assertEquals(PhoneChangeError.MFA_EXPIRED,vm.state.value.error)
        vm.acceptVerification(verified());vm.sendCode("13800138000");assertEquals(PhoneChangePhase.ENTER_CODE,vm.state.value.phase)
        vm.verifyCode("x");assertEquals(0,verifies);assertEquals(PhoneChangeError.INVALID_CODE,vm.state.value.error)
        vm.verifyCode("123456");assertEquals(PhoneChangePhase.COMPLETE,vm.state.value.phase);assertEquals("138****8000",vm.state.value.phoneMasked)
    }
    @Test fun expiredMfaAndAccountSwitchPreventAnyWrite() = runBlocking {
        var user="owner";var time=100L;var writes=0
        val vm=PhoneChangeViewModel({user},{verified()},{_,_->writes++;PhoneChangeTransaction("tx",time+1000)},{_,_->writes++;"mask"},{time})
        vm.begin();time+=600001;vm.sendCode("phone");assertEquals(0,writes)
        assertEquals(PhoneChangeError.MFA_EXPIRED,vm.state.value.error)
        vm.acceptVerification(verified());user="other";vm.sendCode("phone")
        assertEquals(0,writes);assertEquals(PhoneChangeError.ACCOUNT_CHANGED,vm.state.value.error)
    }
    @Test fun serverErrorsMapToActionableState() {
        val codes=mapOf("phone_in_use" to PhoneChangeError.PHONE_IN_USE,"phone_change_rate_limited" to PhoneChangeError.RATE_LIMITED,
            "invalid_sms_code" to PhoneChangeError.INVALID_CODE,"fresh_authentication_required" to PhoneChangeError.MFA_EXPIRED,
            "phone_change_expired" to PhoneChangeError.TRANSACTION_EXPIRED)
        codes.forEach { (code,expected) -> assertEquals(expected,phoneChangeError(PhoneAccountHttpException(400,"{\"error\":\"$code\"}"))) }
    }
}
