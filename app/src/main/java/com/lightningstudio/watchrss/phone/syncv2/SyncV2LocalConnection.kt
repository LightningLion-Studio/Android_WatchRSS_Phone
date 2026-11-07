package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONObject
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Exclusive ownership of an already authenticated RFCOMM/LAN stream, including its decoder.
 * Host must release old-session ownership before constructing this adapter. Never uses debug
 * frame logging: negotiate contains authentication proofs. All errors close the ambiguous socket. */
internal class SyncV2LocalConnection(val session:SyncV2LocalSession,private val input:InputStream,
    private val output:OutputStream):Closeable {
    companion object {
        private val deadlines=Executors.newSingleThreadScheduledExecutor{r->Thread(r,"wrss-local-handshake-deadline").apply{isDaemon=true}}
    }
    private var inboundId:String?=null
    var negotiatedWindow:JSONObject?=null;private set
    private fun <T> failClosed(block:()->T):T=try{block()}catch(t:Throwable){
        val code=if(t is IllegalStateException && Regex("[a-z][a-z0-9_]{0,63}").matches(t.message?:""))t.message!! else "invalid_schema"
        val retryable=code in setOf("ref_conflict","offset_conflict","closure_incomplete","window_expired","quota_exceeded","storage_pressure","feature_disabled","temporarily_unavailable","rate_limited")
        val retryAfter=if(code in setOf("feature_disabled","temporarily_unavailable","rate_limited"))1000 else JSONObject.NULL
        inboundId?.let{id->runCatching{SyncV2LocalProtocol.writeJson(output,JSONObject().put("requestId",id).put("ok",false).put("body",JSONObject.NULL)
            .put("error",JSONObject().put("code",code).put("retryable",retryable).put("retryAfterMillis",retryAfter).put("details",JSONObject())))}}
        close();throw t
    }finally{inboundId=null}
    private fun <T> deadline(millis:Long,block:()->T):T {
        require(millis in 1..30000)
        val timer=deadlines.schedule({close()},millis,TimeUnit.MILLISECONDS)
        try{return failClosed(block)}finally{timer.cancel(false)}
    }
    private fun request(action:String,body:JSONObject):String {
        val id=SyncV2Codec.hex(SyncV2Codec.random(16));SyncV2LocalProtocol.writeJson(output,JSONObject().put("requestId",id).put("action",action).put("body",body));return id
    }
    private fun response(id:String):JSONObject {
        val r=SyncV2LocalProtocol.readJson(input);SyncV2Codec.exact(r,setOf("requestId","ok","body","error"))
        if(r.get("requestId")!=id || r.get("ok") !is Boolean)SyncV2Codec.fail("invalid_schema")
        if(r.get("ok")!=true) {
            if(!r.isNull("body"))SyncV2Codec.fail("invalid_schema")
            val error=r.getJSONObject("error");val code=error.getString("code")
            if(!Regex("[a-z][a-z0-9_]{0,63}").matches(code))SyncV2Codec.fail("invalid_schema")
            SyncV2Codec.fail(code)
        }
        if(!r.isNull("error"))SyncV2Codec.fail("invalid_schema");return r.getJSONObject("body")
    }
    private fun reply(id:String,body:JSONObject) {SyncV2LocalProtocol.writeJson(output,JSONObject().put("requestId",id).put("ok",true).put("body",body).put("error",JSONObject.NULL));inboundId=null}
    private fun readRequest():JSONObject=SyncV2LocalProtocol.readJson(input).also{r->
        SyncV2Codec.exact(r,setOf("requestId","action","body"));SyncV2LocalProtocol.id(r.getString("requestId"));inboundId=r.getString("requestId");SyncV2Wire.string(r,"action");r.getJSONObject("body")}
    /** capability is the response from the old read-only probe, never an optimistic default. */
    fun initiate(ownCapability:JSONObject,peerCapability:JSONObject,millis:Long=5000) {
        session.requireAuthenticated()
        if(ownCapability.opt("supportsSyncObjectsV2")!=true || peerCapability.opt("supportsSyncObjectsV2")!=true)SyncV2Codec.fail("upgrade_required")
        val equal=runCatching{SyncV2Codec.scope(ownCapability.getJSONObject("scope"))==SyncV2Codec.scope(session.scope) && SyncV2Codec.scope(peerCapability.getJSONObject("scope"))==SyncV2Codec.scope(session.scope)}.getOrDefault(false)
        if(!equal)SyncV2Codec.fail("scope_mismatch")
        if(SyncV2LocalProtocol.select(ownCapability,peerCapability,true,false)!="objects-v2")SyncV2Codec.fail("feature_missing")
        if(ownCapability.opt("maxPartBytes")!=65536 || peerCapability.opt("maxPartBytes")!=65536)SyncV2Codec.fail("feature_missing")
        deadline(millis) {
            session.receiveHello(response(request("syncObjectsV2.negotiate",session.hello())))
            session.receiveProofBody(response(request("syncObjectsV2.negotiate",session.proofBody())))
            val features=SyncV2Wire.features(ownCapability.getJSONArray("supportedFeatures")).intersect(SyncV2Wire.features(peerCapability.getJSONArray("supportedFeatures")).toSet()).sorted()
            val window=response(request("syncObjectsV2.negotiate",JSONObject().put("scope",session.scope).put("knownRefs",org.json.JSONArray()).put("features",org.json.JSONArray(features))))
            SyncV2CloudClient.window(window)
            if(SyncV2Codec.scope(window.getJSONObject("scope"))!=SyncV2Codec.scope(session.scope))SyncV2Codec.fail("scope_mismatch")
            val remaining=window.getLong("expiresAtMillis")-System.currentTimeMillis()
            if(remaining !in 1..300000)SyncV2Codec.fail("window_expired")
            negotiatedWindow=window
            val switchId=SyncV2Codec.hex(SyncV2Codec.random(16))
            session.acceptReady(response(request("syncObjectsV2.prepareFraming",session.beginSwitch(switchId))))
            session.acceptCommit(response(request("syncObjectsV2.commitFraming",JSONObject().put("switchId",switchId))))
        }
    }
    /** Called only after BOTH old probe capabilities are true; no new bytes reach an old peer. */
    fun respondHandshake(ownCapability:JSONObject,peerCapability:JSONObject,objects:SyncV2LocalObjects,millis:Long=5000) {
        session.requireAuthenticated()
        if(ownCapability.opt("supportsSyncObjectsV2")!=true || peerCapability.opt("supportsSyncObjectsV2")!=true)SyncV2Codec.fail("upgrade_required")
        val equal=runCatching{SyncV2Codec.scope(ownCapability.getJSONObject("scope"))==SyncV2Codec.scope(session.scope) && SyncV2Codec.scope(peerCapability.getJSONObject("scope"))==SyncV2Codec.scope(session.scope)}.getOrDefault(false)
        if(!equal)SyncV2Codec.fail("scope_mismatch")
        if(SyncV2LocalProtocol.select(ownCapability,peerCapability,true,false)!="objects-v2")SyncV2Codec.fail("feature_missing")
        deadline(millis) {
            fun next(action:String):JSONObject=readRequest().also{if(it.get("action")!=action)SyncV2Codec.fail("unknown_action")}
            val hello=next("syncObjectsV2.negotiate");session.hello();session.receiveHello(hello.getJSONObject("body"))
            reply(hello.getString("requestId"),sessionHelloForResponse())
            val proof=next("syncObjectsV2.negotiate");session.receiveProofBody(proof.getJSONObject("body"));reply(proof.getString("requestId"),session.proofBody())
            val window=next("syncObjectsV2.negotiate");reply(window.getString("requestId"),objects.openWindow(window.getJSONObject("body")))
            val prepare=next("syncObjectsV2.prepareFraming");val ack=session.prepare(prepare.getJSONObject("body"));reply(prepare.getString("requestId"),ack)
            val commit=next("syncObjectsV2.commitFraming");SyncV2Codec.exact(commit.getJSONObject("body"),setOf("switchId"))
            val id=commit.getJSONObject("body").getString("switchId");session.commitRequest(commit.getJSONObject("body"))
            reply(commit.getString("requestId"),JSONObject().put("switchId",id).put("committed",true));session.finalAckWritten(id)
        }
    }
    private fun sessionHelloForResponse()=session.helloValue()
    fun exchange(action:String,body:JSONObject):JSONObject=failClosed {session.requireFraming();response(request(action,body))}
    fun putPart(oid:String,total:Int,offset:Int,payload:ByteArray,transferId:String=SyncV2Codec.hex(SyncV2Codec.random(16))):JSONObject=failClosed {
        session.requireFraming();val body=JSONObject().put("scope",session.scope).put("transferId",transferId).put("oid",oid).put("totalBytes",total).put("offset",offset)
            .put("partBytes",payload.size).put("partSha256",SyncV2Codec.hex(SyncV2Codec.sha(payload,"sha256.frame")))
        // Validate before emitting the control request, including payload size and scope.
        val header=JSONObject(body.toString()).put("sessionId",session.sessionId);SyncV2Wrp2.validate(session,header,payload)
        val id=request("syncObjectsV2.putPart",body);SyncV2Wrp2.write(output,session,oid,total,offset,transferId,payload)
        response(id).also{r->SyncV2Codec.exact(r,setOf("transferId","oid","durableOffset"));if(r.get("transferId")!=transferId || r.get("oid")!=oid || SyncV2Wire.uint(r,"durableOffset",total.toLong())<offset+payload.size)SyncV2Codec.fail("offset_conflict")}
    }
    fun get(body:JSONObject,accept:(SyncV2Wrp2.Frame,SyncV2LocalObjects.Read)->Unit):JSONObject=failClosed {
        session.requireFraming();val r=response(request("syncObjectsV2.objectGet",body))
        SyncV2Codec.exact(r,setOf("scope","oid","totalBytes","start","end","transferId"))
        if(SyncV2Codec.scope(r.getJSONObject("scope"))!=SyncV2Codec.scope(session.scope) || r.get("oid")!=body.get("oid"))SyncV2Codec.fail("scope_mismatch")
        val total=SyncV2Wire.uint(r,"totalBytes",266264).toInt();val start=SyncV2Wire.uint(r,"start",266264).toInt();val end=SyncV2Wire.uint(r,"end",266264).toInt()
        val range=body.optJSONObject("range")
        if(total==0 || start>end || end>=total || start!=(range?.getInt("start")?:0) || end!=(range?.getInt("end")?:total-1))SyncV2Codec.fail("invalid_range")
        val transfer=SyncV2LocalProtocol.id(r.getString("transferId"));val read=SyncV2LocalObjects.Read(r,body.getString("oid"),total,start,end,transfer)
        var offset=start
        while(offset<=end) {
            val frame=SyncV2LocalProfile.time("data.read.wait"){SyncV2Wrp2.read(input,session)};val h=frame.header
            if(h.get("transferId")!=transfer || h.get("oid")!=read.oid || h.getInt("totalBytes")!=total || h.getInt("offset")!=offset || offset+frame.payload.size-1>end)SyncV2Codec.fail("invalid_range")
            accept(frame,read);offset+=frame.payload.size
        }
        r
    }
    /** One dispatch never crosses old and WRP2 boundaries. Errors close partial data streams. */
    fun serveOne(objects:SyncV2LocalObjects,custody:SyncV2Custody?=null,witness:SyncV2PeerWitness?=null,control:SyncV2LocalControl?=null) = failClosed {
        session.requireFraming();val r=readRequest();val id=r.getString("requestId");val body=r.getJSONObject("body")
        val result=when(val action=r.getString("action")) {
            "syncObjectsV2.negotiate"->objects.openWindow(body)
            "syncObjectsV2.haveWant"->objects.haveWant(body)
            "syncObjectsV2.treeDiff"->objects.treeDiff(body)
            "syncObjectsV2.complete"->objects.complete(body)
            "syncObjectsV2.refUpdate"->(control?:SyncV2Codec.fail("feature_disabled")).refUpdate(body)
            "syncObjectsV2.demand"->(control?:SyncV2Codec.fail("feature_disabled")).demand(body)
            "syncObjectsV2.witness"->(witness?:SyncV2Codec.fail("feature_disabled")).receive(session,body)
            "syncObjectsV2.stateRefsGet","syncObjectsV2.recipeRefsGet"->objects.reference(action,body)
            "syncObjectsV2.objectGet"->{val read=objects.objectGet(body);reply(id,read.body);objects.send(read,output);return@failClosed}
            "syncObjectsV2.putPart"->{
                SyncV2Codec.exact(body,setOf("scope","transferId","oid","totalBytes","offset","partBytes","partSha256"))
                val frame=SyncV2LocalProfile.time("data.read.wait"){SyncV2Wrp2.read(input,session)}
                for(field in body.keys())if(SyncV2Codec.canonical(body.get(field))!=SyncV2Codec.canonical(frame.header.get(field)))SyncV2Codec.fail(if(field=="partSha256")"hash_mismatch" else "offset_conflict")
                val receipt=objects.putPart(frame)
                JSONObject().put("transferId",body.get("transferId")).put("oid",body.get("oid")).put("durableOffset",receipt.get("durableOffset"))
            }
            "syncObjectsV2.receipt"->when(body.getString("kind")) {
                "custody"->{SyncV2Codec.exact(body,setOf("kind","custody"));(custody?:SyncV2Codec.fail("feature_disabled")).receive(session,body.getJSONObject("custody"))}
                "local"->{objects.localReceipt(body);JSONObject().put("accepted",true)}
                else->SyncV2Codec.fail("invalid_schema")
            }
            else->SyncV2Codec.fail("unknown_action")
        }
        reply(id,result)
    }
    override fun close()=session.close()
}
