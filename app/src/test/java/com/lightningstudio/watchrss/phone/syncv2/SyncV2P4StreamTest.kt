package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream
import java.io.OutputStream

class SyncV2P4StreamTest {
    private fun <T> sockets(f:SyncV2P4Fixture,operations:Int,serverSetup:(SyncV2LocalObjects)->Unit={},block:(SyncV2LocalConnection,SyncV2ContentStore)->T):T {
        val server=ServerSocket(0,1,InetAddress.getLoopbackAddress());val executor=Executors.newSingleThreadExecutor()
        val capability=SyncV2LocalProtocol.capability(f.identity.scope,SyncV2LocalProtocol.Gates(true))
        val source=f.store("stream-source")
        val future=executor.submit<Unit>{server.accept().use{socket->
            socket.soTimeout=5000;socket.tcpNoDelay=true
            val session=f.session(f.b,f.a,close={socket.close()});val c=SyncV2LocalConnection(session,socket.getInputStream(),socket.getOutputStream())
            c.respondHandshake(capability,capability,SyncV2LocalObjects(session,source))
            val port=SyncV2LocalObjects(session,source);serverSetup(port)
            repeat(operations){c.serveOne(port)}
        }}
        try {return Socket(InetAddress.getLoopbackAddress(),server.localPort).use{socket->
            socket.soTimeout=5000;socket.tcpNoDelay=true
            val session=f.session(close={socket.close()});val c=SyncV2LocalConnection(session,socket.getInputStream(),socket.getOutputStream())
            c.initiate(capability,capability);assertTrue(session.isCommitted);val result=block(c,source);future.get(10,TimeUnit.SECONDS);result
        }}finally{server.close();executor.shutdownNow()}
    }
    @Test fun twoRoundHandshakePutCompleteAndRawGetWorkOnRealLoopbackStreams() {SyncV2P4Fixture().use{f->
        val (oid,raw)=f.blob();val frames=(raw.size+65535)/65536
        sockets(f,frames+3){c,source->
            val transfer="99".repeat(16)
            for(offset in raw.indices step 65536)c.putPart(oid,raw.size,offset,raw.copyOfRange(offset,minOf(offset+65536,raw.size)),transfer)
            val complete=c.exchange("syncObjectsV2.complete",JSONObject().put("scope",f.identity.scope).put("transferId",transfer).put("oid",oid).put("totalBytes",raw.size))
            assertEquals("local",complete.getString("kind"));assertArrayEquals(raw,source.objectBytes(oid))
            val window=c.exchange("syncObjectsV2.negotiate",JSONObject().put("scope",f.identity.scope).put("knownRefs",JSONArray()).put("features",JSONArray())).getString("windowId")
            val read=ByteArrayOutputStream();c.get(f.body(oid,window)){frame,_->read.write(frame.payload)}
            assertArrayEquals(raw,read.toByteArray())
        }
    }}
    @Test fun lostFinalOldJsonAckClosesSocketWithoutSendingWrp2() {SyncV2P4Fixture().use{f->
        val cap=SyncV2LocalProtocol.capability(f.identity.scope,SyncV2LocalProtocol.Gates(true));val server=ServerSocket(0,1,InetAddress.getLoopbackAddress());val executor=Executors.newSingleThreadExecutor()
        val future=executor.submit<Unit>{server.accept().use{socket->
            socket.soTimeout=2000;val s=f.session(f.b,f.a,close={socket.close()});val wire=socket.getOutputStream()
            // Drop precisely the fifth old JSON response (commit ACK), leaving this peer waiting.
            var writes=0;val drop=object:OutputStream(){override fun write(v:Int){wire.write(v)};override fun write(b:ByteArray,off:Int,len:Int){if(writes<4)wire.write(b,off,len)};override fun flush(){writes++;wire.flush();if(writes==5)socket.close()}}
            runCatching{SyncV2LocalConnection(s,socket.getInputStream(),drop).respondHandshake(cap,cap,SyncV2LocalObjects(s,f.store("handshake")))}
        }}
        try {Socket(InetAddress.getLoopbackAddress(),server.localPort).use{socket->
            socket.soTimeout=2000;var closed=false;val s=f.session(close={closed=true;socket.close()});val c=SyncV2LocalConnection(s,socket.getInputStream(),socket.getOutputStream())
            assertNotNull(runCatching{c.initiate(cap,cap,1000)}.exceptionOrNull());assertTrue(closed);assertFalse(s.isCommitted)
        };future.get(3,TimeUnit.SECONDS)}finally{server.close();executor.shutdownNow()}
    }}
    @Test fun handshakeTimeoutClosesSocketAndDoesNotTouchCursors() {SyncV2P4Fixture().use{f->
        val server=ServerSocket(0,1,InetAddress.getLoopbackAddress());val cap=SyncV2LocalProtocol.capability(f.identity.scope,SyncV2LocalProtocol.Gates(true))
        try {Socket(InetAddress.getLoopbackAddress(),server.localPort).use{client->server.accept().use{_->
            var closed=false;val s=f.session(close={closed=true;client.close()});val c=SyncV2LocalConnection(s,client.getInputStream(),client.getOutputStream())
            val store=f.store("cursor");store.save("legacy-cursor",JSONObject().put("received","opaque-R").put("applied","opaque-A"))
            assertNotNull(runCatching{c.initiate(cap,cap,50)}.exceptionOrNull());assertTrue(closed)
            assertEquals("opaque-A",store.load("legacy-cursor")!!.getString("applied"))
        }}}finally{server.close()}
    }}
    @Test fun controlHeaderMismatchFailsWithoutPersistingAnyPrefix() {SyncV2P4Fixture().use{f->
        val server=ServerSocket(0,1,InetAddress.getLoopbackAddress());val cap=SyncV2LocalProtocol.capability(f.identity.scope,SyncV2LocalProtocol.Gates(true));val executor=Executors.newSingleThreadExecutor()
        val (oid,raw)=f.blob();val store=f.store("mismatch")
        val future=executor.submit<Unit>{server.accept().use{socket->val s=f.session(f.b,f.a,close={socket.close()});val c=SyncV2LocalConnection(s,socket.getInputStream(),socket.getOutputStream());c.respondHandshake(cap,cap,SyncV2LocalObjects(s,f.store("handshake")));p4Error("offset_conflict"){c.serveOne(SyncV2LocalObjects(s,store))}}}
        try {Socket(InetAddress.getLoopbackAddress(),server.localPort).use{socket->
            socket.soTimeout=3000;val s=f.session(close={socket.close()});SyncV2LocalConnection(s,socket.getInputStream(),socket.getOutputStream()).initiate(cap,cap)
            val transfer="77".repeat(16);val part=raw.copyOf(65536)
            val body=JSONObject().put("scope",f.identity.scope).put("transferId",transfer).put("oid",oid).put("totalBytes",raw.size).put("offset",65536).put("partBytes",part.size).put("partSha256",SyncV2Codec.hex(SyncV2Codec.sha(part)))
            SyncV2LocalProtocol.writeJson(socket.getOutputStream(),JSONObject().put("requestId","88".repeat(16)).put("action","syncObjectsV2.putPart").put("body",body))
            SyncV2Wrp2.write(socket.getOutputStream(),s,oid,raw.size,0,transfer,part)
            val error=SyncV2LocalProtocol.readJson(socket.getInputStream()).getJSONObject("error");assertEquals("offset_conflict",error.getString("code"));assertTrue(error.getBoolean("retryable"))
            future.get(5,TimeUnit.SECONDS);assertEquals(0,store.prefix(oid,raw.size));assertNull(store.load("local-upload/$oid"))
        }}finally{server.close();executor.shutdownNow()}
    }}
}
