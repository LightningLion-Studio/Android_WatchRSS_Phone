package com.lightningstudio.watchrss.phone.connection.bluetooth

import android.content.Context
import com.lightningstudio.watchrss.phone.data.log.BluetoothDebugLog
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.*

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class BookReadingCommitTransportTest {
    private val context:Context=RuntimeEnvironment.getApplication()
    private val request=JSONObject().put("action","syncBooks").put("version",1).put("phase","readingCommit").put("windowId","window")
    private fun response()=JSONObject().put("success",true).put("action","syncBooks").put("version",1).put("phase","readingCommit").put("windowId","window").put("committed",true).put("root","a".repeat(64)).put("applied",0)
    private fun framed(v:JSONObject)=ByteArrayOutputStream().also{BluetoothSyncProtocol.writeFrame(it,v)}.toByteArray()
    private fun session(input:InputStream,output:OutputStream):PhoneSyncSession {
        val client=PhoneBluetoothSyncClient(context,BluetoothDebugLog(context))
        return PhoneSyncSession(client,PhoneBluetoothWatchDevice("watch","00:11:22:33:44:55",1),"phone",
            PhoneSyncSession.Transport(input,output,"test",false){},true,false,false)
    }
    @Test fun actualSessionValidatesBeforeWritingAckAndReturnsExplicitWriteEvidence()=runBlocking<Unit> {
        val out=ByteArrayOutputStream();val s=session(ByteArrayInputStream(framed(response())),out)
        var checked=false
        val result=s.exchangeApplied(request,"commit") { v ->
            val sent=ByteArrayInputStream(out.toByteArray());assertEquals("syncBooks",BluetoothSyncProtocol.readFrame(sent).getString("action"));assertEquals(0,sent.available())
            assertEquals("window",v.getString("windowId"));checked=true
        }
        assertTrue(checked);assertTrue(result.appliedAckWritten)
        val wire=ByteArrayInputStream(out.toByteArray());BluetoothSyncProtocol.readFrame(wire)
        val ack=BluetoothSyncProtocol.readFrame(wire);assertTrue(ack.getBoolean("success"));assertTrue(ack.getBoolean("applied"));assertEquals("applied",ack.getString("phase"));assertEquals(0,wire.available())
        s.close()
    }
    @Test fun actualSessionInvalidResponseNeverWritesPositiveAckAndClosesWindow()=runBlocking<Unit> {
        val out=ByteArrayOutputStream();val s=session(ByteArrayInputStream(framed(response().put("windowId","wrong"))),out)
        assertTrue(runCatching{s.exchangeApplied(request,"bad"){require(it.getString("windowId")=="window")}}.isFailure)
        val wire=ByteArrayInputStream(out.toByteArray());BluetoothSyncProtocol.readFrame(wire);assertEquals(0,wire.available());assertNull(s.transport)
    }
    @Test fun actualSessionAckIoFailureThrowsInsteadOfReturningCommittedResponse()=runBlocking<Unit> {
        val bytes=ByteArrayOutputStream();var flushed=0
        val out=object:OutputStream(){override fun write(b:Int){if(flushed>0)throw IOException("bt socket closed");bytes.write(b)};override fun flush(){flushed++}}
        val s=session(ByteArrayInputStream(framed(response())),out)
        assertTrue(runCatching{s.exchangeApplied(request,"lost"){}}.exceptionOrNull() is IOException)
        assertNull(s.transport)
    }
    @Test fun actualSessionMissingResponseDoesNotAckOrReplay()=runBlocking<Unit> {
        val out=ByteArrayOutputStream();val s=session(ByteArrayInputStream(byteArrayOf()),out)
        assertTrue(runCatching{s.exchangeApplied(request,"missing"){}}.exceptionOrNull() is IOException)
        val wire=ByteArrayInputStream(out.toByteArray());assertEquals("readingCommit",BluetoothSyncProtocol.readFrame(wire).getString("phase"));assertEquals(0,wire.available());s.close()
    }
    @Test fun cancellationBetweenValidationAndAckDoesNotWriteSuccess()=runBlocking<Unit> {
        val out=ByteArrayOutputStream();val s=session(ByteArrayInputStream(framed(response())),out)
        val job=launch(start=CoroutineStart.LAZY) {s.exchangeApplied(request,"cancel"){currentCoroutineContextForTest().cancel()}}
        // Capture this child job in a non-suspending validator.
        child=job;job.start();job.join()
        assertTrue(job.isCancelled)
        val wire=ByteArrayInputStream(out.toByteArray());BluetoothSyncProtocol.readFrame(wire);assertEquals(0,wire.available());s.close()
    }
    private var child:Job?=null
    private fun currentCoroutineContextForTest()=requireNotNull(child)
}
