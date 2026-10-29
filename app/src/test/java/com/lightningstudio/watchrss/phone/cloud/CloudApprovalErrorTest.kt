package com.lightningstudio.watchrss.phone.cloud

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudApprovalErrorTest {
    @Test fun `device binding rejection explains how to recover`() {
        assertEquals("本机设备授权已失效，请重新登录后再请求批准",
            cloudApprovalRequestError(IOException("HTTP 403: {\"error\":\"approval_device_mismatch\"}")))
    }
    @Test fun `unknown server and network errors never expose raw internals`() {
        for (message in listOf("HTTP 502: <html>internal proxy path</html>", "socket reset /private/path")) {
            assertEquals("暂时无法发起授权请求，请检查网络后重试", cloudApprovalRequestError(IOException(message)))
        }
    }
    @Test fun `rate limit gets actionable message`() {
        assertEquals("请求次数较多，请稍后再试",
            cloudApprovalRequestError(IOException("HTTP 400: {\"error\":\"approval_rate_limited\"}")))
    }
}
