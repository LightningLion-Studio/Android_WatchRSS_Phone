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
        val profiling=System.getenv("WRSS_P4_PROFILE")=="1"
        SyncV2LocalProfile.enabled=profiling
        // Android's compile bootclasspath omits JDK management interfaces; the host JVM has them.
        val bean=Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val cpuMethod=Class.forName("java.lang.management.ThreadMXBean").getMethod("getCurrentThreadCpuTime")
        val allocationMethod=runCatching{Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes",java.lang.Long.TYPE)}.getOrNull()
        fun cpu()=(cpuMethod.invoke(bean) as Number).toLong()
        fun heap()=allocationMethod?.let{(it.invoke(bean,Thread.currentThread().id) as Number).toLong()}?:0L
        val payloads=(0 until samples+warmup).map{f.blob(220000)}
        fun measure(modern:Boolean,rate:Long=0):JSONObject {
            val server=ServerSocket(0,1,InetAddress.getLoopbackAddress());val executor=Executors.newSingleThreadExecutor();val store=f.store(if(modern)"bench-v2-$rate" else "bench-old-$rate")
            val cap=SyncV2LocalProtocol.capability(f.identity.scope,SyncV2LocalProtocol.Gates(true))
            var serverBytes=0L;var serverCpu=0L;var serverHeap=0L
            val sender=SyncV2LocalProfile.Collector();val receiver=SyncV2LocalProfile.Collector()
            val future=executor.submit<Unit>{server.accept().use{socket->
                socket.tcpNoDelay=true;socket.soTimeout=10000;val out=Count(socket.getOutputStream(),rate);val input=socket.getInputStream()
                if(modern) {
                    val s=f.session(f.b,f.a,close={socket.close()});val c=SyncV2LocalConnection(s,input,out);c.respondHandshake(cap,cap,SyncV2LocalObjects(s,f.store("handshake")));val objects=SyncV2LocalObjects(s,store)
                    repeat(payloads.size){i->
                        if(i==warmup){serverCpu=cpu();serverHeap=heap();if(profiling)SyncV2LocalProfile.attach(receiver)}
                        val raw=payloads[i].second;repeat((raw.size+65535)/65536+1){c.serveOne(objects)}
                    }
                }else repeat(payloads.size) {i->
                    if(i==warmup){serverCpu=cpu();serverHeap=heap();if(profiling)SyncV2LocalProfile.attach(receiver)}
                    val request=SyncV2LocalProfile.time("legacy.control.read.wait"){BluetoothSyncProtocol.readFrame(input)};val oid=request.getString("oid");val chunks=request.getJSONArray("chunks");val raw=java.io.ByteArrayOutputStream()
                    for(i in 0 until chunks.length())raw.write(SyncV2LocalProfile.time("legacy.base64.decode"){ArticleSyncBody.decodeChunkData(chunks.getString(i))})
                    store.putObject(oid,raw.toByteArray());BluetoothSyncProtocol.writeFrame(out,JSONObject().put("success",true))
                }
                serverBytes=out.bytes;serverCpu=cpu()-serverCpu;serverHeap=heap()-serverHeap;SyncV2LocalProfile.attach(null)
            }}
            val times=JSONArray();val byteSamples=JSONArray();var payloadBytes=0L;var clientBytes=0L;var clientCpu=0L;var clientHeap=0L
            try {Socket(InetAddress.getLoopbackAddress(),server.localPort).use{socket->
                socket.tcpNoDelay=true;socket.soTimeout=10000;val out=Count(socket.getOutputStream(),rate);val input=socket.getInputStream()
                val session=if(modern)f.session(close={socket.close()})else null;val c=session?.let{SyncV2LocalConnection(it,input,out).also{it.initiate(cap,cap)}}
                for((i,p)in payloads.withIndex()) {
                    if(i==warmup){clientCpu=cpu();clientHeap=heap();if(profiling)SyncV2LocalProfile.attach(sender)}
                    val (oid,raw)=p;val before=out.bytes;val start=System.nanoTime()
                    if(c!=null) {
                        val transfer=SyncV2Codec.hex(SyncV2Codec.random(16))
                        for(offset in raw.indices step 65536)c.putPart(oid,raw.size,offset,SyncV2LocalProfile.copy(raw,offset,minOf(raw.size,offset+65536)),transfer)
                        c.exchange("syncObjectsV2.complete",JSONObject().put("scope",f.identity.scope).put("transferId",transfer).put("oid",oid).put("totalBytes",raw.size))
                    }else {
                        val chunks=JSONArray();for(offset in raw.indices step ArticleSyncBody.CHUNK_SIZE_BYTES)chunks.put(SyncV2LocalProfile.time("legacy.base64.encode"){ArticleSyncBody.encodeChunkData(SyncV2LocalProfile.copy(raw,offset,minOf(raw.size,offset+ArticleSyncBody.CHUNK_SIZE_BYTES)))})
                        SyncV2LocalProfile.time("legacy.frame.encode.write"){BluetoothSyncProtocol.writeFrame(out,JSONObject().put("version",14).put("action","syncLibrary").put("phase","articles").put("oid",oid).put("chunks",chunks))}
                        assertTrue(SyncV2LocalProfile.time("legacy.ack.read.wait"){BluetoothSyncProtocol.readFrame(input)}.getBoolean("success"))
                    }
                    val elapsed=System.nanoTime()-start
                    if(i>=warmup){times.put(elapsed);byteSamples.put(out.bytes-before);payloadBytes+=raw.size}
                }
                clientBytes=out.bytes;clientCpu=cpu()-clientCpu;clientHeap=heap()-clientHeap;SyncV2LocalProfile.attach(null);future.get(15,TimeUnit.SECONDS)
            }}finally{server.close();executor.shutdownNow()}
            val nanos=(0 until times.length()).sumOf{times.getLong(it)}
            for((oid,raw)in payloads)assertArrayEquals(raw,store.objectBytes(oid))
            return JSONObject().put("profiling",profiling).put("senderProfile",sender.json()).put("receiverProfile",receiver.json()).put("senderCpuNanos",clientCpu).put("receiverCpuNanos",serverCpu).put("senderAllocatedBytes",clientHeap).put("receiverAllocatedBytes",serverHeap).put("samples",samples).put("warmup",warmup).put("payloadBytes",payloadBytes).put("elapsedNanos",nanos).put("sampleNanos",times)
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
        System.getenv("WRSS_P4_EVIDENCE")?.let{java.io.File(it,System.getenv("WRSS_P4_PROFILE_OUTPUT")?:"throughput.json").writeText(report.toString(2)+"\n")}
        SyncV2LocalProfile.enabled=false;SyncV2LocalProfile.attach(null)
        assertEquals(old.getLong("payloadBytes"),modern.getLong("payloadBytes"))
    }}
}
