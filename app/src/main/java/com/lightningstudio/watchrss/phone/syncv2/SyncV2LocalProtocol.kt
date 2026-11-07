package com.lightningstudio.watchrss.phone.syncv2

import com.lightningstudio.watchrss.phone.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** P4 never creates a repo or delivers keys. Keys come from an already installed repo. */
internal object SyncV2LocalProtocol {
    data class Gates(val localObjects:Boolean=BuildConfig.SYNC_OBJECTS_V2_LOCAL_OBJECTS,
        val relay:Boolean=BuildConfig.SYNC_OBJECTS_V2_RELAY,val witness:Boolean=BuildConfig.SYNC_OBJECTS_V2_WITNESS)
    fun id(value:String)=value.also { if(!Regex("[0-9a-f]{32}").matches(it))SyncV2Codec.fail("invalid_schema") }
    fun bytes(value:JSONObject)=SyncV2Codec.utf8(SyncV2Codec.canonical(value))
    /** The existing read-only probe carries this field; missing/false never triggers new actions. */
    fun capability(scope:JSONObject,gates:Gates,required:List<String> = emptyList(),supported:List<String> = emptyList())=
        JSONObject().put("supportsSyncObjectsV2",gates.localObjects).put("syncObjectsVersions",JSONArray(listOf(2)))
            .put("requiredFeatures",JSONArray(required)).put("supportedFeatures",JSONArray(supported))
            .put("scope",scope).put("maxPartBytes",65536)
    fun select(a:JSONObject,b:JSONObject,scopeEqual:Boolean,safeLegacy:Boolean):String {
        val legacy=minOf(a.optInt("legacyMax",14),b.optInt("legacyMax",14))
        if(legacy<13)return "upgrade_required"
        if(a.opt("supportsSyncObjectsV2")==true && b.opt("supportsSyncObjectsV2")==true && scopeEqual) {
            fun list(o:JSONObject,long:String,short:String)=o.optJSONArray(long)?:o.optJSONArray(short)?:JSONArray()
            fun versions(o:JSONObject):List<Int> { val v=list(o,"syncObjectsVersions","versions");return (0 until v.length()).map{v.getInt(it)} }
            val av=versions(a);val bv=versions(b)
            val ar=SyncV2Wire.features(list(a,"requiredFeatures","required"));val br=SyncV2Wire.features(list(b,"requiredFeatures","required"))
            val asup=SyncV2Wire.features(list(a,"supportedFeatures","supported"));val bsup=SyncV2Wire.features(list(b,"supportedFeatures","supported"))
            if(!bsup.containsAll(ar) || !asup.containsAll(br))return if(safeLegacy)"legacy-v$legacy" else "upgrade_required"
            if(2 in av && 2 in bv)return "objects-v2"
        }
        return if(safeLegacy)"legacy-v$legacy" else "upgrade_required"
    }
    fun proof(repoKey:ByteArray,scope:JSONObject,session:String,self:String,peer:String,selfNonce:String,peerNonce:String):String {
        if(repoKey.size!=32)SyncV2Codec.fail("authentication_required")
        listOf(session,self,peer,selfNonce,peerNonce).forEach(::id);SyncV2Codec.scope(scope)
        // RFC5869: absent salt is HashLen zero octets. JCA rejects an empty HMAC key.
        val key=SyncV2Codec.hkdf(repoKey,ByteArray(32),SyncV2Codec.utf8("wrss.sync.v2/local-proof"))
        return try { SyncV2Codec.b64(SyncV2Codec.hmac(key,SyncV2Codec.lp(*listOf("wrss.sync.v2/local-proof",SyncV2Codec.canonical(scope),session,self,peer,selfNonce,peerNonce).map(SyncV2Codec::utf8).toTypedArray()))) }
        finally {key.fill(0)}
    }
    fun framingAllowed(v:JSONObject):Boolean = listOf("authenticated","scopeMatched","ready","commitAck","sessionCommitted").all{v.opt(it)==true} &&
        (v.opt("switchId") as? String)?.let{Regex("[0-9a-f]{32}").matches(it) && it==v.opt("ackSwitchId")}==true
    fun writeJson(output:OutputStream,value:JSONObject) {val raw=bytes(value);if(raw.size>262144)SyncV2Codec.fail("object_too_large");DataOutputStream(output).apply{writeInt(raw.size);write(raw);flush()}}
    fun readJson(input:InputStream):JSONObject {val data=DataInputStream(input);val length=data.readInt();if(length !in 2..262144)SyncV2Codec.fail("invalid_encoding");return SyncV2Wire.parse(ByteArray(length).also(data::readFully),262144)}
}

/** One instance per authenticated old socket. Never survives reconnect or owns legacy cursors.
 * member reads the host's latest verified member table, returning active/unknown/revoked.
 * alive must check the existing v14/v13 application authentication, not system pairing. */
internal class SyncV2LocalSession(scope:JSONObject,val sessionId:String,val selfDeviceId:String,
    private val repoKey:()->ByteArray,private val member:(String)->String,private val alive:()->Boolean,
    private val closeSocket:()->Unit,private val gates:SyncV2LocalProtocol.Gates=SyncV2LocalProtocol.Gates(),
    private val required:List<String> = emptyList(),private val supported:List<String> = emptyList()):Closeable {
    private val scopeJson=SyncV2Codec.scope(scope)
    val scope:JSONObject get()=JSONObject(scopeJson)
    private var selfHello:JSONObject?=null;private var peerHello:JSONObject?=null
    private val nonces=mutableSetOf<String>();private var proofAccepted=false;private var closed=false
    private var switch:String?=null;private var ready=false;private var committed=false
    private var initiator=false;private var readyAcknowledged=false;private var commitReceived=false
    var peerDeviceId:String?=null;private set
    val isCommitted:Boolean get()=committed && !closed && alive()
    init {SyncV2Codec.scope(scope);SyncV2LocalProtocol.id(sessionId);SyncV2LocalProtocol.id(selfDeviceId)
        SyncV2Wire.features(JSONArray(required));SyncV2Wire.features(JSONArray(supported))}
    fun requireAuthenticated() {if(!gates.localObjects)SyncV2Codec.fail("feature_disabled");if(closed || !alive())SyncV2Codec.fail("authentication_required");checkMember(selfDeviceId)}
    private fun checkMember(device:String) {when(member(device)){"active"->Unit;"revoked"->SyncV2Codec.fail("member_revoked");else->SyncV2Codec.fail("member_unknown")}}
    fun hello(nonce:String=SyncV2Codec.hex(SyncV2Codec.random(16))):JSONObject {
        requireAuthenticated();if(selfHello!=null)SyncV2Codec.fail("proof_invalid")
        SyncV2LocalProtocol.id(nonce);if(!nonces.add(nonce))SyncV2Codec.fail("proof_invalid")
        return JSONObject().put("scope",scope).put("deviceId",selfDeviceId).put("nonce",nonce).put("features",JSONArray(supported))
            .also{selfHello=JSONObject(it.toString())}
    }
    fun receiveHello(value:JSONObject) {
        requireAuthenticated();SyncV2Codec.exact(value,setOf("scope","deviceId","nonce","features"))
        if(SyncV2Codec.scope(value.getJSONObject("scope"))!=SyncV2Codec.scope(scope))SyncV2Codec.fail("scope_mismatch")
        val device=SyncV2LocalProtocol.id(SyncV2Wire.string(value,"deviceId"));checkMember(device)
        if(device==selfDeviceId || peerHello!=null)SyncV2Codec.fail("proof_invalid")
        val features=SyncV2Wire.features(value.getJSONArray("features"));if(!features.containsAll(required))SyncV2Codec.fail("feature_missing")
        val nonce=SyncV2LocalProtocol.id(SyncV2Wire.string(value,"nonce"));if(!nonces.add(nonce))SyncV2Codec.fail("proof_invalid")
        peerDeviceId=device;peerHello=JSONObject(value.toString())
    }
    fun ownProof():String {requireAuthenticated();val self=selfHello?:SyncV2Codec.fail("proof_invalid");val peer=peerHello?:SyncV2Codec.fail("proof_invalid")
        val key=repoKey().copyOf();return try {SyncV2LocalProtocol.proof(key,scope,sessionId,selfDeviceId,peer.getString("deviceId"),self.getString("nonce"),peer.getString("nonce"))}finally{key.fill(0)}}
    fun helloValue():JSONObject=JSONObject((selfHello?:SyncV2Codec.fail("proof_invalid")).toString())
    fun receiveProof(value:String) {
        requireAuthenticated();val peer=peerHello?:SyncV2Codec.fail("proof_invalid");val self=selfHello?:SyncV2Codec.fail("proof_invalid");checkMember(peer.getString("deviceId"))
        val key=repoKey().copyOf();val expected=try{SyncV2LocalProtocol.proof(key,scope,sessionId,peer.getString("deviceId"),selfDeviceId,peer.getString("nonce"),self.getString("nonce"))}finally{key.fill(0)}
        val supplied=runCatching{SyncV2Codec.decode64(value,32)}.getOrNull()?:SyncV2Codec.fail("proof_invalid")
        if(!MessageDigest.isEqual(SyncV2Codec.decode64(expected,32),supplied))SyncV2Codec.fail("proof_invalid")
        proofAccepted=true
    }
    fun requireProof() {requireAuthenticated();if(!proofAccepted)SyncV2Codec.fail("proof_invalid");checkMember(peerDeviceId?:SyncV2Codec.fail("member_unknown"))}
    fun prepare(body:JSONObject):JSONObject {
        requireProof();SyncV2Codec.exact(body,setOf("sessionId","framing","switchId"))
        if(body.get("sessionId")!=sessionId || body.get("framing")!="WRP2")SyncV2Codec.fail("proof_invalid")
        val next=SyncV2LocalProtocol.id(SyncV2Wire.string(body,"switchId"))
        if(switch!=null || committed)SyncV2Codec.fail("invalid_schema")
        switch=next;ready=true;return JSONObject().put("switchId",next).put("ready",true)
    }
    fun requireSwitch(id:String) {requireProof();SyncV2LocalProtocol.id(id);if(!ready || switch!=id || committed)SyncV2Codec.fail("invalid_schema")}
    /** Receiver calls only after successfully flushing its final OLD JSON response. */
    fun commitRequest(body:JSONObject) {
        SyncV2Codec.exact(body,setOf("switchId"));requireSwitch(SyncV2Wire.string(body,"switchId"))
        if(initiator)SyncV2Codec.fail("invalid_schema");commitReceived=true
    }
    fun finalAckWritten(id:String) {requireSwitch(id);if(initiator || !commitReceived)SyncV2Codec.fail("invalid_schema");committed=true}
    /** Initiator records its prepare before sending, then authenticates both OLD JSON ACKs. */
    fun beginSwitch(id:String):JSONObject=JSONObject().put("sessionId",sessionId).put("framing","WRP2").put("switchId",id).also{prepare(it);initiator=true}
    fun acceptReady(ack:JSONObject) {SyncV2Codec.exact(ack,setOf("switchId","ready"));requireSwitch(SyncV2Wire.string(ack,"switchId"));if(!initiator || ack.get("ready")!=true)SyncV2Codec.fail("invalid_schema");readyAcknowledged=true}
    fun acceptCommit(ack:JSONObject) {SyncV2Codec.exact(ack,setOf("switchId","committed"));requireSwitch(SyncV2Wire.string(ack,"switchId"));if(!initiator || !readyAcknowledged || ack.get("committed")!=true)SyncV2Codec.fail("invalid_schema");committed=true}
    fun requireFraming() {requireProof();if(!isCommitted)SyncV2Codec.fail("framing_not_committed")}
    fun requireFeature(feature:String) {
        requireFraming()
        if(feature !in supported || !(peerHello?.getJSONArray("features")?.let{SyncV2Wire.features(it)}?:emptyList()).contains(feature))SyncV2Codec.fail("feature_missing")
    }
    fun requireNegotiatedFeatures(features:List<String>) {
        requireProof()
        val peer=SyncV2Wire.features(peerHello!!.getJSONArray("features"))
        if(!supported.containsAll(features) || !peer.containsAll(features))SyncV2Codec.fail("feature_missing")
    }
    fun proofBody()=JSONObject().put("scope",scope).put("proof",ownProof())
    fun receiveProofBody(value:JSONObject) {
        SyncV2Codec.exact(value,setOf("scope","proof"))
        if(SyncV2Codec.scope(value.getJSONObject("scope"))!=SyncV2Codec.scope(scope))SyncV2Codec.fail("scope_mismatch")
        receiveProof(SyncV2Wire.string(value,"proof"))
    }
    /** Timeout, malformed ACK, cancellation or disconnect closes this socket; no cursor writes. */
    override fun close() {if(closed)return;closed=true;committed=false;ready=false;readyAcknowledged=false;commitReceived=false;switch=null;proofAccepted=false;closeSocket()}
}

internal object SyncV2Wrp2 {
    data class Frame(val header:JSONObject,val payload:ByteArray)
    fun write(output:OutputStream,session:SyncV2LocalSession,oid:String,total:Int,offset:Int,transferId:String,payload:ByteArray) {
        session.requireFraming();val header=JSONObject().put("scope",session.scope).put("sessionId",session.sessionId).put("oid",oid)
            .put("totalBytes",total).put("offset",offset).put("partBytes",payload.size).put("partSha256",SyncV2Codec.hex(SyncV2Codec.sha(payload))).put("transferId",transferId)
        validate(session,header,payload);val raw=SyncV2LocalProtocol.bytes(header);if(raw.size>4096)SyncV2Codec.fail("object_too_large")
        DataOutputStream(output).apply{writeBytes("WRP2");writeInt(raw.size);write(raw);write(payload);flush()}
    }
    fun read(input:InputStream,session:SyncV2LocalSession):Frame {
        session.requireFraming();val data=DataInputStream(input)
        if(data.readInt()!=0x57525032)SyncV2Codec.fail("invalid_encoding")
        val size=data.readInt();if(size !in 2..4096)SyncV2Codec.fail("invalid_encoding")
        val header=SyncV2Wire.parse(ByteArray(size).also(data::readFully),4096,true)
        val length=SyncV2Wire.uint(header,"partBytes",65536).toInt();if(length==0)SyncV2Codec.fail("invalid_schema")
        val payload=ByteArray(length).also(data::readFully);validate(session,header,payload);return Frame(header,payload)
    }
    fun validate(session:SyncV2LocalSession,h:JSONObject,payload:ByteArray) {
        session.requireFraming()
        SyncV2Codec.exact(h,setOf("scope","sessionId","oid","totalBytes","offset","partBytes","partSha256","transferId"))
        if(SyncV2Codec.scope(h.getJSONObject("scope"))!=SyncV2Codec.scope(session.scope))SyncV2Codec.fail("scope_mismatch")
        if(h.get("sessionId")!=session.sessionId)SyncV2Codec.fail("proof_invalid")
        SyncV2LocalProtocol.id(SyncV2Wire.string(h,"transferId"));SyncV2Wire.oid(SyncV2Wire.string(h,"oid"))
        val total=SyncV2Wire.uint(h,"totalBytes",266264);val offset=SyncV2Wire.uint(h,"offset",266264)
        if(total==0L || payload.size !in 1..65536 || h.get("partBytes")!=payload.size || offset+payload.size>total)SyncV2Codec.fail("invalid_range")
        if(h.get("partSha256")!=SyncV2Codec.hex(SyncV2Codec.sha(payload)))SyncV2Codec.fail("hash_mismatch")
    }
}
