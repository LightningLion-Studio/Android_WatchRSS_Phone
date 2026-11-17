package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.connection.ip.*
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.*
import java.net.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class IpWireIdleTest {
    @Test fun bufferedWebSocketReadUsesItsSocketActivityAndStillExpiresOnRealSilence() {
        lateinit var server:WatchIpSyncServer
        val provider=IpEndpointProvider(RuntimeEnvironment.getApplication(),"idle-phone"){server.listeningPort}
        server=WatchIpSyncServer(provider,0)
        server.setServerSocketFactory { object:ServerSocket() {
            override fun bind(endpoint:SocketAddress?,backlog:Int) {
                super.bind(InetSocketAddress(InetAddress.getLoopbackAddress(),0),backlog)
            }
        }}
        server.start(2000,false)
        val client=Socket(InetAddress.getLoopbackAddress(),server.listeningPort).apply{soTimeout=2000}
        var writer:Thread?=null
        try {
            val output=client.getOutputStream();val input=client.getInputStream()
            output.write(("GET /sync HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"+
                "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: MTIzNDU2Nzg5MDEyMzQ1Ng==\r\n\r\n").toByteArray());output.flush()
            val headers=StringBuilder()
            while(!headers.endsWith("\r\n\r\n")){val n=input.read();check(n>=0);headers.append(n.toChar())}
            assertTrue(headers.startsWith("HTTP/1.1 101"))
            val descriptor=provider.issueDescriptor("idle-watch")
            val unsigned=IpHello("idle-watch",descriptor.challengeId,descriptor.epoch,"synthetic-nonce",null,0,"")
            val hello=unsigned.copy(hmac=IpSyncProtocol.hmac(descriptor.challengeSecret,unsigned.canonicalPayload()))
            NanoWSD.WebSocketFrame(NanoWSD.WebSocketFrame.OpCode.Text,true,hello.toJson().toString(),byteArrayOf(1,2,3,4)).write(output);output.flush()
            NanoWSD.WebSocketFrame.read(input) // authenticated HELLO_ACK, not an application frame
            val readyDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1)
            while(PhoneIpSyncSessionRegistry.session("idle-watch")==null&&System.nanoTime()<readyDeadline)Thread.sleep(1)
            val session=PhoneIpSyncSessionRegistry.session("idle-watch")!!
            assertTrue(session.wireReadActivityNanos()>0)
            val payload=JSONObject().put("data","x".repeat(512))
            val app=ByteArrayOutputStream().also{BluetoothSyncProtocol.writeFrame(it,payload)}.toByteArray()
            val wire=ByteArrayOutputStream().also {
                NanoWSD.WebSocketFrame(NanoWSD.WebSocketFrame.OpCode.Binary,true,app,byteArrayOf(1,2,3,4)).write(it)
            }.toByteArray()
            val firstPart=CountDownLatch(1);val error=AtomicReference<Throwable?>()
            writer=thread {
                try {
                    var at=0
                    while(at<wire.size){val n=minOf(16,wire.size-at);output.write(wire,at,n);output.flush();at+=n;firstPart.countDown();Thread.sleep(15)}
                }catch(t:Throwable){error.set(t)}
            }
            assertTrue(firstPart.await(1,TimeUnit.SECONDS));Thread.sleep(30)
            assertEquals("NanoWSD must still be buffering the unfinished binary message",0,session.inputStream.available())
            val closed=AtomicBoolean(false)
            val guard=LibraryIoGuard(1,{true},{closed.set(true);session.close()},200,
                wireReadActivityNanos={session.wireReadActivityNanos()})
            val started=System.nanoTime()
            assertEquals(payload.toString(),BluetoothSyncProtocol.readFrame(guard.input(session.inputStream)).toString())
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)>200)
            assertFalse(closed.get());writer.join(1000);assertNull(error.get())
            val idle=LibraryIoGuard(1,{true},{closed.set(true);session.close()},50,
                wireReadActivityNanos={session.wireReadActivityNanos()})
            val failure=runCatching{idle.input(session.inputStream).read()}.exceptionOrNull()
            assertTrue(failure is LibraryTransportFailure);assertTrue(failure!!.cause is SocketTimeoutException)
            assertTrue(closed.get())
        }finally{client.close();writer?.join(1000);server.close()}
    }

    @Test fun anotherSocketCannotRefreshThisConnectionsIdleClock() {
        val idle=IpReadActivity();val active=IpReadActivity();val closed=CountDownLatch(1)
        val blocked=object:InputStream(){override fun read():Int {check(closed.await(2,TimeUnit.SECONDS));throw IOException("closed")}}
        val unrelated=thread {repeat(20){active.input(ByteArrayInputStream(byteArrayOf(1))).read();Thread.sleep(5)}}
        try {
            val failure=runCatching{LibraryIoGuard(1,{true},{closed.countDown()},30,
                wireReadActivityNanos={idle.lastNanos}).input(blocked).read()}.exceptionOrNull()
            assertTrue(failure is LibraryTransportFailure);assertTrue(failure!!.cause is SocketTimeoutException)
            assertEquals(0L,idle.lastNanos);assertTrue(active.lastNanos>0)
        }finally{closed.countDown();unrelated.join(1000)}
    }
}
