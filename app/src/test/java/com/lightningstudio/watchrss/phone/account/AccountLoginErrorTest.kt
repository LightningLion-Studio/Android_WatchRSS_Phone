package com.lightningstudio.watchrss.phone.account

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountLoginErrorTest {
    @Test
    fun unexpectedFailureFromSmsHookUsesSafeActionableMessage() {
        val error = PhoneAccountHttpException(
            statusCode = 500,
            responseBody =
                """{"code":500,"error_code":"unexpected_failure","msg":"Unexpected status code returned from hook: 500","error_id":"test-error-id"}"""
        )

        assertEquals(
            "短信服务暂时繁忙，请稍后重试",
            accountLoginErrorMessage(AccountLoginAction.REQUEST_OTP, error)
        )
        assertFalse(error.message.orEmpty().contains("unexpected_failure"))
        assertFalse(error.message.orEmpty().contains("test-error-id"))
        assertTrue(error.responseBody.contains("unexpected_failure"))
        assertTrue(error.responseBody.contains("test-error-id"))
    }

    @Test
    fun ordinaryHttpErrorKeepsOtpGuidanceSpecific() {
        val error = PhoneAccountHttpException(
            statusCode = 400,
            responseBody = "Bad Request"
        )

        assertEquals(
            "验证码无效或已过期，请重新获取",
            accountLoginErrorMessage(AccountLoginAction.VERIFY_OTP, error)
        )
    }

    @Test
    fun invalidPhoneResponseExplainsWhatToFix() {
        val error = PhoneAccountHttpException(
            statusCode = 422,
            responseBody = """{"code":"validation_failed"}"""
        )

        assertEquals(
            "手机号格式不正确，请检查后重试",
            accountLoginErrorMessage(AccountLoginAction.REQUEST_OTP, error)
        )
    }

    @Test
    fun rateLimitResponseAsksUserToWait() {
        val error = PhoneAccountHttpException(
            statusCode = 429,
            responseBody = "Too Many Requests"
        )

        assertEquals(
            "操作太频繁，请稍后再试",
            accountLoginErrorMessage(AccountLoginAction.REQUEST_OTP, error)
        )
    }

    @Test
    fun nonJsonNetworkFailureNeverLeaksTransportDetail() {
        val error = IOException("Connection closed by peer at 10.0.0.8")

        assertEquals(
            "网络连接失败，请检查网络后重试",
            accountLoginErrorMessage(AccountLoginAction.REQUEST_OTP, error)
        )
        assertTrue(error.message.orEmpty().contains("10.0.0.8"))
    }

    @Test
    fun unknownFailureUsesGenericSafeMessage() {
        val error = IllegalStateException("internal implementation detail")

        assertEquals(
            "验证码发送失败，请稍后重试",
            accountLoginErrorMessage(AccountLoginAction.REQUEST_OTP, error)
        )
    }
}
