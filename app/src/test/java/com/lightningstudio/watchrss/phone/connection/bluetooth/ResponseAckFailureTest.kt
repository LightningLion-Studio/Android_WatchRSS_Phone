package com.lightningstudio.watchrss.phone.connection.bluetooth

import java.io.IOException
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ResponseAckFailureTest {
    @Test
    fun successfulAckReturnsNoFailure() {
        assertNull(captureResponseAckFailure { })
    }

    @Test
    fun disconnectedAckReturnsOriginalTransportFailure() {
        val failure = IOException("Connection reset by peer")

        val captured = captureResponseAckFailure { throw failure }

        assertSame(failure, captured)
    }
}
