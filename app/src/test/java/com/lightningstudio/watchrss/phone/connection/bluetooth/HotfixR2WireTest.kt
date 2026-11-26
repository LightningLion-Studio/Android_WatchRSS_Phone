package com.lightningstudio.watchrss.phone.connection.bluetooth
import com.lightningstudio.watchrss.phone.connection.ip.*
import fi.iki.elonen.NanoWSD
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.*
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class HotfixR2WireTest {
 private class Link:AutoCloseable {
  lateinit var server:WatchIpSyncServer
  val accepted=AtomicReference<Socket>();val provider=IpEndpointProvider(RuntimeEnvironment.getApplication(),"review-phone"){server.listeningPort}
  val client:Socket;val session:PhoneIpSyncSession
  init {
   server=WatchIpSyncServer(provider,0)
   server.setServerSocketFactory {object:ServerSocket(){
    override fun bind(a:SocketAddress?,b:Int){super.bind(InetSocketAddress(InetAddress.getLoopbackAddress(),0),b)}
    override fun accept():Socket=super.accept().also{it.sendBufferSize=1024;accepted.set(it)}
   }}
   server.start(120000,false)
   client=Socket().apply{receiveBufferSize=1024;connect(InetSocketAddress(InetAddress.getLoopbackAddress(),server.listeningPort));soTimeout=3000}
   val out=client.getOutputStream();val input=client.getInputStream()
   out.write(("GET /sync HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: MTIzNDU2Nzg5MDEyMzQ1Ng==\r\n\r\n").toByteArray());out.flush()
   val header=StringBuilder();while(!header.endsWith("\r\n\r\n")){val n=input.read();check(n>=0);header.append(n.toChar())};check(header.startsWith("HTTP/1.1 101"))
   val d=provider.issueDescriptor("review-watch");val u=IpHello("review-watch",d.challengeId,d.epoch,"nonce",null,0,"")
   val h=u.copy(hmac=IpSyncProtocol.hmac(d.challengeSecret,u.canonicalPayload()))
   NanoWSD.WebSocketFrame(NanoWSD.WebSocketFrame.OpCode.Text,true,h.toJson().toString(),byteArrayOf(1,2,3,4)).write(out);out.flush()
   NanoWSD.WebSocketFrame.read(input)
   val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
   while(PhoneIpSyncSessionRegistry.session("review-watch")==null && System.nanoTime()<until)Thread.sleep(1)
   session=PhoneIpSyncSessionRegistry.session("review-watch")!!
  }
  override fun close(){client.close();accepted.get()?.close();session.close();server.close()}
 }
 @Test fun realBufferedWebSocketCanReadForOverThirtySecondsWithoutIdleExpiry() {
  val link=Link();val closed=AtomicBoolean();val err=AtomicReference<Throwable?>()
  val payload=org.json.JSONObject().put("payload","r".repeat(280000))
  val app=ByteArrayOutputStream().also{BluetoothSyncProtocol.writeFrame(it,payload)}.toByteArray()
  val wire=ByteArrayOutputStream().also{NanoWSD.WebSocketFrame(NanoWSD.WebSocketFrame.OpCode.Binary,true,app,byteArrayOf(1,2,3,4)).write(it)}.toByteArray()
  val writer=thread {try{var at=0;while(at<wire.size){val n=minOf(8192,wire.size-at);link.client.getOutputStream().write(wire,at,n);link.client.getOutputStream().flush();at+=n;if(at<wire.size)Thread.sleep(1000)}}catch(t:Throwable){err.set(t)}}
  try {
   val started=System.nanoTime()
   val guard=LibraryIoGuard(1,{true},{closed.set(true);link.session.close()},wireReadActivityNanos={link.session.wireReadActivityNanos()})
   assertEquals(payload.toString(),BluetoothSyncProtocol.readFrame(guard.input(link.session.inputStream)).toString())
   val elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);println("real buffered slow read elapsedMs=$elapsed bytes=${wire.size}")
   assertTrue(elapsed>30000);assertFalse(closed.get());writer.join(2000);assertNull(err.get())
  }finally{link.close();writer.join(2000)}
 }
 @Test fun blockedWebSocketCloseMustNotPreventOtherSilentIoDeadlines() {
  val link=Link();val timeoutEntered=CountDownLatch(1);val closeReturned=CountDownLatch(1)
  val stopped=AtomicBoolean();val completedWrites=AtomicInteger();val writerError=AtomicReference<Throwable?>()
  val guarded=LibraryIoGuard(1,{true},{timeoutEntered.countDown();link.session.close();closeReturned.countDown()},150).output(link.session.outputStream)
  val writer=thread {try{while(!stopped.get()){guarded.write(ByteArray(8192));completedWrites.incrementAndGet()}}catch(t:Throwable){writerError.set(t)}}
  val otherClosed=CountDownLatch(1);val releaseOther=CountDownLatch(1);val otherEntered=CountDownLatch(1)
  var other:Thread?=null
  try {
   assertTrue("fill actual TCP send/receive windows",timeoutEntered.await(3,TimeUnit.SECONDS))
   other=thread {runCatching{LibraryIoGuard(2,{true},{otherClosed.countDown();releaseOther.countDown()},100).input(object:InputStream(){override fun read():Int{otherEntered.countDown();releaseOther.await(3,TimeUnit.SECONDS);throw IOException("cleanup")}}).read()}}
   assertTrue(otherEntered.await(1,TimeUnit.SECONDS))
   val expired=otherClosed.await(600,TimeUnit.MILLISECONDS)
   if(!expired)Thread.getAllStackTraces().filterKeys{it.name=="library-io-deadline"}.forEach{(t,stack)->println("deadline-thread=${t.state}\n"+stack.joinToString("\n"))}
   println("backpressure writes=${completedWrites.get()} closeReturned=${closeReturned.count==0L} writerAlive=${writer.isAlive} otherExpired=$expired")
   assertTrue("one blocked WebSocket close monopolizes the sole deadline worker",expired)
  }finally{stopped.set(true);link.close();releaseOther.countDown();writer.join(2000);other?.join(2000)}
 }
 @Test fun closingCapturedOldSocketDoesNotCloseItsReplacement() {
  val old=Link();var replacement:Link?=null
  try {
   replacement=Link();old.session.close()
   assertSame(replacement.session,PhoneIpSyncSessionRegistry.session("review-watch"));assertFalse(replacement.session.isClosed)
   val payload=byteArrayOf(11,22,33)
   NanoWSD.WebSocketFrame(NanoWSD.WebSocketFrame.OpCode.Binary,true,payload,byteArrayOf(1,2,3,4)).write(replacement.client.getOutputStream())
   assertArrayEquals(payload,ByteArray(3).also{DataInputStream(replacement.session.inputStream).readFully(it)})
  }finally{replacement?.close();old.close()}
 }

}
