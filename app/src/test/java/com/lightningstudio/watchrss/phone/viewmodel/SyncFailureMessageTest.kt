package com.lightningstudio.watchrss.phone.viewmodel

import java.io.EOFException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncFailureMessageTest {
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
