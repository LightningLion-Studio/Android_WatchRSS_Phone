package com.lightningstudio.watchrss.phone.connection.bluetooth

import android.bluetooth.*
import com.lightningstudio.watchrss.phone.data.log.BluetoothDebugLog
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadow.api.Shadow
import java.io.*
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35],shadows=[DirectProbeCancellationTest.SocketShadow::class])
class DirectProbeCancellationTest {
    @Implements(BluetoothSocket::class)
    class SocketShadow {
        lateinit var state:BlockingIo
        @Implementation fun connect(){state.connect()}
        @Implementation fun getInputStream():InputStream=state.inputOverride ?: state.input
        @Implementation fun getOutputStream():OutputStream=state.output
        @Implementation fun close(){state.close()}
    }
    class BlockingIo(val blockConnect:Boolean) {
        val entered=CountDownLatch(1);val closed=CountDownLatch(1)
        var connectCalls=0
        var inputOverride:InputStream?=null
        val closeCalls=java.util.concurrent.atomic.AtomicInteger()
        val output=ByteArrayOutputStream()
        val input=object:InputStream(){override fun read():Int {entered.countDown();closed.await();throw IOException("socket closed")}}
        fun connect(){connectCalls++;if(blockConnect){entered.countDown();closed.await();throw IOException("socket closed")}}
        fun close(){closeCalls.incrementAndGet();closed.countDown()}
    }
    private val context=RuntimeEnvironment.getApplication()
    private val client=PhoneBluetoothSyncClient(context,BluetoothDebugLog(context))
    private val device=BluetoothAdapter.getDefaultAdapter().getRemoteDevice("11:22:33:44:55:66")
    private val timer=Executors.newSingleThreadScheduledExecutor()
    @After fun close(){timer.shutdownNow()}
    private fun socket(io:BlockingIo):BluetoothSocket=Shadow.newInstanceOf(BluetoothSocket::class.java).also {
        Shadow.extract<SocketShadow>(it).state=io;client.probeSocketFactory={_->it}
    }
    private fun blocked(blockConnect:Boolean,cancelExplicitly:Boolean,prefix:ByteArray=byteArrayOf())=runBlocking<Unit> {
        val io=BlockingIo(blockConnect);socket(io)
        if(prefix.isNotEmpty())io.inputOverride=SequenceInputStream(ByteArrayInputStream(prefix),io.input)
        // A broken implementation must fail in finite time without leaking a reader thread.
        timer.schedule({io.close()},1200,TimeUnit.MILLISECONDS)
        val started=System.nanoTime()
        val job=async(Dispatchers.IO) {
            withTimeout(150){client.probeDirectPersistentOnce(device,"phone","probe-test",false)}
        }
        assertTrue(io.entered.await(2,TimeUnit.SECONDS))
        if(cancelExplicitly)job.cancel()
        try {job.await();fail("probe survived cancellation")}catch(_:kotlinx.coroutines.CancellationException){}
        val elapsed=(System.nanoTime()-started)/1_000_000
        assertEquals(0L,io.closed.count)
        println("DIRECT_PROBE_BLOCKING_IO connect=$blockConnect explicitCancel=$cancelExplicitly prefixBytes=${prefix.size} elapsedMs=$elapsed closed=true")
        assertTrue("Cancellation took $elapsed ms; close must interrupt native IO",elapsed<700)
    }
    @Test fun cancellationWhileSocketIsCreatedClosesItBeforeConnect()=runBlocking<Unit> {
        val io=BlockingIo(false);val socket=socket(io)
        val creating=CountDownLatch(1);val release=CountDownLatch(1)
        client.probeSocketFactory={creating.countDown();release.await();socket}
        val job=async(Dispatchers.IO){client.probeDirectPersistentOnce(device,"phone","creating",false)}
        assertTrue(creating.await(2,TimeUnit.SECONDS));job.cancel();release.countDown()
        try{job.await();fail("cancel ignored")}catch(_:kotlinx.coroutines.CancellationException){}
        assertTrue(io.closed.await(500,TimeUnit.MILLISECONDS));assertEquals(0,io.connectCalls)
    }
    @Test fun timeoutClosesPartiallyReceivedFrameBody() {
        val frame=ByteArrayOutputStream().also{BluetoothSyncProtocol.writeFrame(it,org.json.JSONObject().put("success",true))}.toByteArray()
        blocked(false,false,frame.copyOf(6))
    }
    @Test fun timeoutClosesNeverReturningConnect()=blocked(true,false)
    @Test fun timeoutClosesNeverReturningReadFrame()=blocked(false,false)
    @Test fun cancelClosesNeverReturningConnect()=blocked(true,true)
    @Test fun cancelClosesNeverReturningReadFrame()=blocked(false,true)
}
