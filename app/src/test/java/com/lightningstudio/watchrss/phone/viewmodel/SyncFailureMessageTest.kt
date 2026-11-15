package com.lightningstudio.watchrss.phone.viewmodel

import java.io.EOFException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncFailureMessageTest {
    @Test fun transientFailureAsksForLaterRetryWithoutConnectionOrLoginMisclassification() {
        val failure=com.lightningstudio.watchrss.phone.account.PhoneAccountTemporarilyUnavailable(IOException("offline"))
        assertEquals("账号服务暂时不可用，请稍后重试",syncFailureMessage(failure,"建立连接中"))
        org.junit.Assert.assertFalse(com.lightningstudio.watchrss.phone.connection.bluetooth.isRetryableBluetoothDisconnect(failure))
    }
    @Test fun deviceIdentityFailureDoesNotAskForAccountLogin() {
        val failure=com.lightningstudio.watchrss.phone.account.PhoneDeviceVerificationRequired()
        assertEquals("手机设备身份需要验证，请前往账号页面完成验证",syncFailureMessage(failure,"建立连接中"))
    }

    @Test fun loginFailureDoesNotUseConnectionStageOrRetryItsHttpCause() {
        val failure = com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneLoginRequired(IOException("expired"))
        assertEquals("登录已失效，请重新登录", syncFailureMessage(failure, "建立连接中"))
        org.junit.Assert.assertFalse(com.lightningstudio.watchrss.phone.connection.bluetooth.isRetryableBluetoothDisconnect(failure))
    }

    @Test fun repeatedAccountRejectionKeepsItsReasonWithoutConnectionStage() {
        val failure = com.lightningstudio.watchrss.phone.connection.bluetooth.WatchAccountSyncRequired(
            org.json.JSONObject().put("message", "请重新连接并同步账号后再同步资料"))
        assertEquals("手表账号同步后仍拒绝资料同步：请重新连接并同步账号后再同步资料", syncFailureMessage(failure, "建立连接中"))
    }

    @Test fun accountHttpFailureDoesNotRetryBluetoothOrUseConnectionStage() {
        val failure = com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneAccountSyncFailed(IOException("HTTP 503"))
        assertEquals("账号同步失败：HTTP 503", syncFailureMessage(failure, "建立连接中"))
        org.junit.Assert.assertFalse(com.lightningstudio.watchrss.phone.connection.bluetooth.isRetryableBluetoothDisconnect(failure))
    }
    @Test
    fun includesStageExceptionAndTransportMessage() {
        assertEquals(
            "校验失败：IOException：Connection reset by peer",
            syncFailureMessage(IOException("Connection reset by peer"), "校验中")
        )
    }

    @Test
    fun usesCauseMessageWhenOuterExceptionHasNoMessage() {
        assertEquals(
            "信息传输失败：IllegalStateException：socket closed",
            syncFailureMessage(
                IllegalStateException(null, EOFException("socket closed")),
                "信息传输中"
            )
        )
    }

    @Test
    fun neverFallsBackToGenericOperationFailedText() {
        assertEquals(
            "同步失败：EOFException",
            syncFailureMessage(EOFException(), null)
        )
    }
}
