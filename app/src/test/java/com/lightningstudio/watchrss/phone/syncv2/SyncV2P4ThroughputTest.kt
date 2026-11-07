package com.lightningstudio.watchrss.phone.syncv2

import com.lightningstudio.watchrss.phone.connection.bluetooth.ArticleSyncBody
import com.lightningstudio.watchrss.phone.connection.bluetooth.BluetoothSyncProtocol
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.FilterOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Actual loopback bytes + fsync + ACK. This is a framing/body-chunk benchmark, not RF or
 * device/whole-library acceptance. Both paths transfer the identical prepared ciphertext. */
class SyncV2P4ThroughputTest {
    private class Count(out:OutputStream,private val rate:Long=0):FilterOutputStream(out) {
        var bytes=0L
        private fun pace(size:Int){if(rate>0){val nanos=size*1000000000L/rate;Thread.sleep(nanos/1000000,(nanos%1000000).toInt())}}
        override fun write(v:Int){pace(1);out.write(v);bytes++}
        override fun write(b:ByteArray,off:Int,len:Int){pace(len);out.write(b,off,len);bytes+=len}
    }
    @Test fun loopbackLegacyBase64AndWrp2ReportWireBytesDurationsAndSamples() {SyncV2P4Fixture().use{f->
        val samples=12;val warmup=3
        val payloads=(0 until samples+warmup).map{f.blob(220000)}
        fun measure(modern:Boolean,rate:Long=0):JSONObject {
            val server=ServerSocket(0,1,InetAddress.getLoopbackAddress());val executor=Executors.newSingleThreadExecutor();val store=f.store(if(modern)"bench-v2-$rate" else "bench-old-$rate")
            val cap=SyncV2LocalProtocol.capability(f.identity.scope,SyncV2LocalProtocol.Gates(true))
            var serverBytes=0L
            val future=executor.submit<Unit>{server.accept().use{socket->
                socket.tcpNoDelay=true;socket.soTimeout=10000;val out=Count(socket.getOutputStream(),rate);val input=socket.getInputStream()
                if(modern) {
                    val s=f.session(f.b,f.a,close={socket.close()});val c=SyncV2LocalConnection(s,input,out);c.respondHandshake(cap,cap,SyncV2LocalObjects(s,f.store("handshake")));val objects=SyncV2LocalObjects(s,store)
                    repeat(payloads.size){i->val raw=payloads[i].second;repeat((raw.size+65535)/65536+1){c.serveOne(objects)}}
                }else repeat(payloads.size) {
                    val request=BluetoothSyncProtocol.readFrame(input);val oid=request.getString("oid");val chunks=request.getJSONArray("chunks");val raw=java.io.ByteArrayOutputStream()
                    for(i in 0 until chunks.length())raw.write(ArticleSyncBody.decodeChunkData(chunks.getString(i)))
                    store.putObject(oid,raw.toByteArray());BluetoothSyncProtocol.writeFrame(out,JSONObject().put("success",true))
                }
                serverBytes=out.bytes
            }}
            val times=JSONArray();val byteSamples=JSONArray();var payloadBytes=0L;var clientBytes=0L
            try {Socket(InetAddress.getLoopbackAddress(),server.localPort).use{socket->
                socket.tcpNoDelay=true;socket.soTimeout=10000;val out=Count(socket.getOutputStream(),rate);val input=socket.getInputStream()
                val session=if(modern)f.session(close={socket.close()})else null;val c=session?.let{SyncV2LocalConnection(it,input,out).also{it.initiate(cap,cap)}}
                for((i,p)in payloads.withIndex()) {
                    val (oid,raw)=p;val before=out.bytes;val start=System.nanoTime()
                    if(c!=null) {
                        val transfer=SyncV2Codec.hex(SyncV2Codec.random(16))
                        for(offset in raw.indices step 65536)c.putPart(oid,raw.size,offset,raw.copyOfRange(offset,minOf(raw.size,offset+65536)),transfer)
                        c.exchange("syncObjectsV2.complete",JSONObject().put("scope",f.identity.scope).put("transferId",transfer).put("oid",oid).put("totalBytes",raw.size))
                    }else {
                        val chunks=JSONArray();for(offset in raw.indices step ArticleSyncBody.CHUNK_SIZE_BYTES)chunks.put(ArticleSyncBody.encodeChunkData(raw.copyOfRange(offset,minOf(raw.size,offset+ArticleSyncBody.CHUNK_SIZE_BYTES))))
                        BluetoothSyncProtocol.writeFrame(out,JSONObject().put("version",14).put("action","syncLibrary").put("phase","articles").put("oid",oid).put("chunks",chunks))
                        assertTrue(BluetoothSyncProtocol.readFrame(input).getBoolean("success"))
                    }
                    val elapsed=System.nanoTime()-start
                    if(i>=warmup){times.put(elapsed);byteSamples.put(out.bytes-before);payloadBytes+=raw.size}
                }
                clientBytes=out.bytes;future.get(15,TimeUnit.SECONDS)
            }}finally{server.close();executor.shutdownNow()}
            val nanos=(0 until times.length()).sumOf{times.getLong(it)}
            for((oid,raw)in payloads)assertArrayEquals(raw,store.objectBytes(oid))
            return JSONObject().put("samples",samples).put("warmup",warmup).put("payloadBytes",payloadBytes).put("elapsedNanos",nanos).put("sampleNanos",times)
                .put("measuredClientWireBytes",(0 until byteSamples.length()).sumOf{byteSamples.getLong(it)}).put("sampleClientWireBytes",byteSamples)
                .put("rateLimitBytesPerSecond",rate).put("fileIO",store.counts.json()).put("totalClientWireBytesIncludingWarmupAndHandshake",clientBytes).put("totalServerWireBytesIncludingWarmupAndHandshake",serverBytes)
        }
        val old=measure(false);val modern=measure(true)
        val ratio=old.getLong("elapsedNanos").toDouble()/modern.getLong("elapsedNanos")
        val report=JSONObject().put("environment","JVM real TCP loopback; same ciphertext; SSD fsync; framing/body chunks only; no physical RFCOMM")
            .put("legacy",old).put("wrp2",modern).put("throughputRatio",ratio).put("meets90Percent",ratio>=0.9)
        // Predeclared additional synthetic link. Keep the uncapped result visible even if it fails.
        val pacedOld=measure(false,1048576);val pacedNew=measure(true,1048576)
        val pacedRatio=pacedOld.getLong("elapsedNanos").toDouble()/pacedNew.getLong("elapsedNanos")
        report.put("paced1MiBPerSecond",JSONObject().put("legacy",pacedOld).put("wrp2",pacedNew).put("throughputRatio",pacedRatio).put("meets90Percent",pacedRatio>=0.9))
        System.getenv("WRSS_P4_EVIDENCE")?.let{java.io.File(it,"throughput.json").writeText(report.toString(2)+"\n")}
        assertEquals(old.getLong("payloadBytes"),modern.getLong("payloadBytes"))
    }}
}
