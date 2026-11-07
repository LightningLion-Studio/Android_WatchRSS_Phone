package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.FilterInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** P4R-06: deny after the entire request (including ID) has been read, not at a
 * direct validator call. Both participants use real TCP and the product C25/C28 codec. */
class SyncV2P4RevocationSocketTest {
    private fun counts(s:SyncV2ContentStore)=listOf(s.counts.reads,s.counts.readBytes,s.counts.writes,s.counts.writeBytes)
    private fun wireCase(status:String,action:String,breakErrorWrite:Boolean=false) {SyncV2P4Fixture().use{f->
        val store=f.store("socket-denial");val (oid,raw)=f.blob(30);store.putObject(oid,raw)
        store.save("legacy-cursor",JSONObject().put("received","R").put("applied","A"))
        val cap=SyncV2LocalProtocol.capability(f.identity.scope,SyncV2LocalProtocol.Gates(true));val member=AtomicReference("active")
        val server=ServerSocket(0,1,InetAddress.getLoopbackAddress());val executor=Executors.newSingleThreadExecutor()
        val id="77".repeat(16);val events=mutableListOf<String>();var closeCalls=0
        val receiving=executor.submit<Unit>{server.accept().use{socket->
            socket.soTimeout=5000;socket.tcpNoDelay=true
            val session=f.session(f.b,f.a,member={if(it==f.a)member.get()else "active"},close={events.add("close");closeCalls++;socket.close()})
            var denial=false;var capture:ByteArrayOutputStream?=null;var length=-1;var before:List<Long>?=null
            // This sees only the post-handshake control frame. Fragmentation cannot move
            // the injection before completion of its length-prefixed JSON envelope.
            val input=object:FilterInputStream(socket.getInputStream()) {
                private fun observe(bytes:ByteArray,offset:Int,size:Int) {
                    capture?.let{out->out.write(bytes,offset,size);val all=out.toByteArray()
                        if(length<0 && all.size>=4)length=DataInputStream(ByteArrayInputStream(all)).readInt()
                        if(length>=0 && all.size>=4+length && !denial) {
                            assertEquals(4+length,all.size)
                            val request=SyncV2Wire.parse(all.copyOfRange(4,all.size));assertEquals(id,request.getString("requestId"));assertEquals(action,request.getString("action"))
                            before=counts(store);events.add("complete-request");member.set(status);denial=true
                        }
                    }
                }
                override fun read():Int {val v=`in`.read();if(v>=0)observe(byteArrayOf(v.toByte()),0,1);return v}
                override fun read(bytes:ByteArray,offset:Int,size:Int):Int {val n=`in`.read(bytes,offset,size);if(n>0)observe(bytes,offset,n);return n}
            }
            val rawOut=socket.getOutputStream();var firstErrorWrite=true
            val output=object:OutputStream() {
                override fun write(v:Int)=write(byteArrayOf(v.toByte()),0,1)
                override fun write(bytes:ByteArray,offset:Int,size:Int) {
                    if(denial && firstErrorWrite) {firstErrorWrite=false;assertFalse(session.isCommitted);assertEquals(0,closeCalls);events.add("invalid-before-write")
                        if(breakErrorWrite)throw java.io.IOException("injected_error_write_failure")}
                    rawOut.write(bytes,offset,size)
                }
                override fun flush(){rawOut.flush();if(denial)events.add("error-flushed")}
            }
            val port=SyncV2LocalObjects(session,store);val connection=SyncV2LocalConnection(session,input,output)
            connection.respondHandshake(cap,cap,port);capture=ByteArrayOutputStream()
            val expected=if(status=="revoked")"member_revoked" else "member_unknown"
            p4Error(expected){connection.serveOne(port)}
            assertTrue(denial);assertFalse(session.isCommitted);assertEquals(before,counts(store));assertEquals(1,closeCalls)
            member.set("active")
            p4Error("authentication_required"){port.haveWant(JSONObject().put("scope",f.identity.scope).put("windowId",id).put("oids",JSONArray(listOf(oid))))}
            p4Error("authentication_required"){port.openWindow()};assertEquals(before,counts(store))
            connection.close();assertEquals(1,closeCalls)
        }}
        try {Socket(InetAddress.getLoopbackAddress(),server.localPort).use{socket->
            socket.soTimeout=5000;socket.tcpNoDelay=true
            val session=f.session(close={socket.close()});val client=SyncV2LocalConnection(session,socket.getInputStream(),socket.getOutputStream());client.initiate(cap,cap)
            val window=client.negotiatedWindow!!.getString("windowId")
            val body=when(action) {
                "syncObjectsV2.negotiate"->JSONObject().put("scope",f.identity.scope).put("knownRefs",JSONArray()).put("features",JSONArray())
                "syncObjectsV2.haveWant"->JSONObject().put("scope",f.identity.scope).put("windowId",window).put("oids",JSONArray(listOf(oid)))
                else->f.body(oid,window)
            }
            SyncV2LocalProtocol.writeJson(socket.getOutputStream(),JSONObject().put("requestId",id).put("action",action).put("body",body))
            if(breakErrorWrite)assertEquals(-1,socket.getInputStream().read())else {
                val response=SyncV2LocalProtocol.readJson(socket.getInputStream());SyncV2Codec.exact(response,setOf("requestId","ok","body","error"))
                assertEquals(id,response.getString("requestId"));assertFalse(response.getBoolean("ok"));assertTrue(response.isNull("body"))
                val error=response.getJSONObject("error");SyncV2Codec.exact(error,setOf("code","retryable","retryAfterMillis","details"))
                assertEquals(if(status=="revoked")"member_revoked" else "member_unknown",error.getString("code"));assertFalse(error.getBoolean("retryable"));assertTrue(error.isNull("retryAfterMillis"));assertEquals(0,error.getJSONObject("details").length())
                assertEquals(-1,socket.getInputStream().read())
            }
            receiving.get(5,TimeUnit.SECONDS)
            assertTrue(events.indexOf("invalid-before-write")>events.indexOf("complete-request"))
            if(!breakErrorWrite)assertTrue(events.indexOf("close")>events.indexOf("error-flushed"))
            assertEquals("A",store.load("legacy-cursor")!!.getString("applied"));assertArrayEquals(raw,store.objectBytes(oid))
        }}finally{server.close();executor.shutdownNow()}
    }}
    @Test fun revokedAfterCompleteRequestReceivesExactErrorBeforeSocketCloseAndNoObjectIo() {
        for(action in listOf("syncObjectsV2.haveWant","syncObjectsV2.objectGet","syncObjectsV2.negotiate"))wireCase("revoked",action)
    }
    @Test fun unknownAfterCompleteRequestReceivesExactErrorBeforeSocketCloseAndNoObjectIo() {
        for(action in listOf("syncObjectsV2.haveWant","syncObjectsV2.objectGet","syncObjectsV2.negotiate"))wireCase("unknown",action)
    }
    @Test fun failedErrorWriteStillInvalidatesSessionAndClosesSocketWithoutObjectIo() {
        wireCase("revoked","syncObjectsV2.haveWant",true);wireCase("unknown","syncObjectsV2.haveWant",true)
    }
}
