package com.lightningstudio.watchrss.phone.connection.ip

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Real pipes and a real blocked consumer: no fake stream or polling reader can hide missing flush. */
class PhoneIpSyncSessionWakeupTest {
    private fun session()=PhoneIpSyncSession("watch","wakeup",IpTransportKind.WIFI_LAN,"192.0.2.1",{}, {})
    private fun awaitBlocked(thread:Thread) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
        while(thread.state!=Thread.State.TIMED_WAITING && System.nanoTime()<deadline) {
            check(thread.isAlive){"reader terminated before blocking"}
            Thread.sleep(1)
        }
        assertEquals("consumer must first block inside the empty pipe",Thread.State.TIMED_WAITING,thread.state)
    }
    private fun <T> withReader(read:(PhoneIpSyncSession)->T,send:(PhoneIpSyncSession,Thread,FutureTask<T>)->Unit) {
        val session=session();val task=FutureTask{read(session)}
        val thread=Thread(task,"ip-blocked-reader").apply{isDaemon=true;start()}
        try {awaitBlocked(thread);send(session,thread,task)} finally {session.close();thread.join(2000);assertFalse("reader leaked",thread.isAlive)}
    }
    private fun frame(body:ByteArray)=ByteArrayOutputStream().also{out->DataOutputStream(out).apply{writeInt(body.size);write(body)}}.toByteArray()
    private fun assertPrompt(label:String,started:Long) {
        val millis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)
        println("IP pipe $label: ${millis}ms")
        assertTrue("$label took ${millis}ms",millis<500)
    }
    @Test fun blockedReadWakesImmediatelyAfterAcceptBinary() {
        withReader({it.inputStream.read()}) {session,_,result->
            val start=System.nanoTime();session.acceptBinary(byteArrayOf(42))
            assertEquals(42,result.get(500,TimeUnit.MILLISECONDS));assertPrompt("single byte",start)
        }
    }
    @Test fun splitFourByteHeaderAndBodyBothWakeABlockedReadFully() {
        val headerRead=LinkedBlockingQueue<Int>();val body="split body".toByteArray();val wire=frame(body)
        withReader({session->
            val input=DataInputStream(session.inputStream);val size=input.readInt();headerRead.put(size)
            ByteArray(size).also{input.readFully(it)}
        }) {session,thread,result->
            val headerStart=System.nanoTime()
            session.acceptBinary(wire.copyOfRange(0,2));session.acceptBinary(wire.copyOfRange(2,4))
            assertEquals(body.size,headerRead.poll(500,TimeUnit.MILLISECONDS));assertPrompt("split header",headerStart)
            awaitBlocked(thread)
            val bodyStart=System.nanoTime()
            session.acceptBinary(wire.copyOfRange(4,7));session.acceptBinary(wire.copyOfRange(7,wire.size))
            assertArrayEquals(body,result.get(500,TimeUnit.MILLISECONDS));assertPrompt("split body",bodyStart)
        }
    }
    @Test fun consecutiveFramesKeepTheirBoundariesWithoutOneSecondGaps() {
        val bodies=listOf("one","two","three").map{it.toByteArray()}
        withReader({session->val input=DataInputStream(session.inputStream)
            List(bodies.size){ByteArray(input.readInt()).also{input.readFully(it)}}
        }) {session,_,result->
            val start=System.nanoTime()
            val firstTwo=frame(bodies[0])+frame(bodies[1])
            session.acceptBinary(firstTwo);session.acceptBinary(frame(bodies[2]))
            result.get(500,TimeUnit.MILLISECONDS).zip(bodies).forEach{(actual,expected)->assertArrayEquals(expected,actual)}
            assertPrompt("consecutive frames",start)
        }
    }
    @Test fun disconnectPromptlyWakesBlockedReaderAndIgnoresLaterFrames() {
        withReader({session->try{session.inputStream.read()}catch(_:IOException){-1}}) {session,_,result->
            val start=System.nanoTime();session.close()
            assertEquals(-1,result.get(500,TimeUnit.MILLISECONDS));assertPrompt("disconnect",start)
            session.acceptBinary(byteArrayOf(1));assertTrue(session.isClosed)
        }
    }
}
