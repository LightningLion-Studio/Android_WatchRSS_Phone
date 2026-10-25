package com.lightningstudio.watchrss.phone.connection.ip

import org.junit.Test
import org.junit.Assert.*
import java.io.DataInputStream
import java.net.SocketTimeoutException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class IpReadDeadlineTest {
    private fun session(timeout:Long=150)=PhoneIpSyncSession("watch","deadline",IpTransportKind.WIFI_LAN,"192.0.2.1",{}, {},readIdleTimeoutMs=timeout)
    @Test fun emptyAndPartialFramesTimeOutAndCloseTheRealPipe() {
        for(partial in listOf(byteArrayOf(),byteArrayOf(0,0))) {
            val s=session();val task=FutureTask {try{DataInputStream(s.inputStream).readInt();null}catch(e:Exception){e}}
            val thread=Thread(task).apply{start()}
            try {
                if(partial.isNotEmpty())s.acceptBinary(partial)
                assertTrue(task.get(2,TimeUnit.SECONDS) is SocketTimeoutException);assertTrue(s.isClosed)
                assertEquals(partial.size.toLong(),s.diagnostics()["consumedBytes"])
            }finally{s.close();thread.join(2000);assertFalse(thread.isAlive)}
        }
    }
    @Test fun positiveByteProgressKeepsALongReadAliveWithoutRaisingTheTotalBudget() {
        val s=session(400);val task=FutureTask {ByteArray(5).also{DataInputStream(s.inputStream).readFully(it)}}
        val thread=Thread(task).apply{start()}
        try {
            repeat(5){Thread.sleep(150);s.acceptBinary(byteArrayOf(it.toByte()))}
            assertArrayEquals(byteArrayOf(0,1,2,3,4),task.get(2,TimeUnit.SECONDS));assertFalse(s.isClosed)
        }finally{s.close();thread.join(2000)}
    }
    @Test fun manifestFirstByteBudgetDoesNotExtendAStalledBody() {
        val s=session(3000);val input=s.inputStream as IpReadDeadline
        val task=FutureTask {try{input.withBudget(1000,150){DataInputStream(input).readInt()};null}catch(e:Exception){e}}
        val thread=Thread(task).apply{start()}
        try {
            Thread.sleep(300);assertFalse(s.isClosed);s.acceptBinary(byteArrayOf(0))
            assertTrue(task.get(2,TimeUnit.SECONDS) is SocketTimeoutException);assertTrue(s.isClosed)
        }finally{s.close();thread.join(2000)}
    }
}
