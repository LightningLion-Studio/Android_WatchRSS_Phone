package com.lightningstudio.watchrss.phone.connection.ip

import fi.iki.elonen.NanoWSD.WebSocketFrame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.Socket
import java.net.SocketException

/** An actual NanoWSD socket, whose peer deliberately never acknowledges a WebSocket Close. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class WatchIpSyncServerCloseTest {
    @Test fun closingAuthenticatedSessionTerminatesTcpWithoutWaitingForPeerClose() {
        var port=0
        val provider=IpEndpointProvider(RuntimeEnvironment.getApplication(),"phone",{port})
        val server=WatchIpSyncServer(provider,0)
        server.start(3000,false);port=server.listeningPort
        try {
            Socket("127.0.0.1",port).use { peer ->
                peer.soTimeout=1000
                val output=peer.getOutputStream();val input=peer.getInputStream()
                output.write(("GET /sync HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"+
                    "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n").toByteArray());output.flush()
                val headers=ByteArrayOutputStream()
                while(!headers.toString("UTF-8").endsWith("\r\n\r\n")) {
                    val byte=input.read();if(byte<0)throw EOFException("HTTP upgrade failed")
                    headers.write(byte);require(headers.size()<8192)
                }
                assertTrue(headers.toString("UTF-8"),headers.toString("UTF-8").startsWith("HTTP/1.1 101"))
                val descriptor=provider.issueDescriptor("watch")
                val unsigned=IpHello("watch",descriptor.challengeId,descriptor.epoch,"nonce",null,0,"")
                val hello=unsigned.copy(hmac=IpSyncProtocol.hmac(descriptor.challengeSecret,unsigned.canonicalPayload()))
                val frame=WebSocketFrame(WebSocketFrame.OpCode.Text,true,hello.toJson().toString())
                frame.setMaskingKey(byteArrayOf(1,2,3,4));frame.write(output);output.flush()
                assertEquals("helloAck",org.json.JSONObject(WebSocketFrame.read(input).textPayload).getString("type"))
                val until=System.nanoTime()+2_000_000_000
                var session=PhoneIpSyncSessionRegistry.session("watch")
                while(session==null && System.nanoTime()<until){Thread.sleep(5);session=PhoneIpSyncSessionRegistry.session("watch")}
                assertNotNull(session)
                session!!.close()
                // Reading a Close frame alone is insufficient: the following TCP read must end too.
                peer.soTimeout=500
                try { while(input.read()!=-1){} } catch(_:SocketException){} // TCP reset also terminates it.
                assertTrue(session.isClosed);assertNull(PhoneIpSyncSessionRegistry.session("watch"))
            }
        }finally{server.close();PhoneIpSyncSessionRegistry.closeAll()}
    }
}
