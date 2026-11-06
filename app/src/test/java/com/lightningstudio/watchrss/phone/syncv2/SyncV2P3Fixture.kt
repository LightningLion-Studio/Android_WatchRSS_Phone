package com.lightningstudio.watchrss.phone.syncv2

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Synthetic HTTP server. Never logs request bodies or authentication material. */
internal class SyncV2P3Fixture:AutoCloseable {
    val identity=SyncV2Fixtures.identity()
    val signer=SyncV2Signing.fromPrivate(ByteArray(31)+byteArrayOf(1),"aa".repeat(16),"ab".repeat(16))
    val objects=ConcurrentHashMap<String,ByteArray>()
    val prefix=ConcurrentHashMap<String,ByteArray>()
    val putOffsets=mutableListOf<Int>();val putOids=mutableListOf<String>()
    var completed=0;var ranges=0;var dropAck=false;var busy=false;var wrongRange=false;var partDelay=0L
    var requests=0;var controlRequests=0
    var replayReserve=false
    var afterReserve:(()->Unit)?=null
    val reserveIds=mutableListOf<String>();val reserveOffsets=mutableListOf<Int>()
    private val reserved=mutableMapOf<String,Pair<String,JSONObject>>()
    var now=100000L
    private val algorithm=AlgorithmIdentifier(ASN1ObjectIdentifier("1.2.840.10045.2.1"),ASN1ObjectIdentifier("1.2.840.10045.3.1.7"))
    val bootstrap=JSONObject().put("algorithm","ES256").put("receiptKeyId","cd".repeat(16))
        .put("receiptSigningPublicKey",SyncV2Codec.b64(SubjectPublicKeyInfo(algorithm,SyncV2Codec.unhex(signer.publicHex,65)).encoded))
    fun trust()=SyncV2ServiceTrust.fromBootstrap(identity.accountId,bootstrap,null){}
    fun dto()=JSONObject().put("version",2).put("scope",identity.scope)
    fun receipt(oid:String,total:Int,offset:Int)=signer.signed("wrss.sync.v2/object-receipt-sign",JSONObject().put("version",2).put("scope",identity.scope).put("oid",oid).put("totalBytes",total).put("durableOffset",offset)
        .put("storeId","99".repeat(16)).put("receiptRevision","9007199254740993").put("createdAtMillis",now).put("expiresAtMillis",now+604800000))
    fun response(value:JSONObject)=MockResponse().setBody(SyncV2Codec.canonical(value)).setHeader("Content-Type","application/json")
    val server=MockWebServer()
    var route:((RecordedRequest)->MockResponse?)?=null
    init {
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            requests++
            route?.invoke(request)?.let{return it}
            val path=request.requestUrl!!.encodedPath.substringAfter("/functions/v1/sync/v2/")
            if(path=="capabilities")return response(dto().put("minVersion",2).put("maxVersion",2).put("maxPartBytes",65536).put("historyDays",7).put("features",JSONArray())
                .put("receiptKeyId",bootstrap.getString("receiptKeyId")).put("receiptSigningPublicKey",bootstrap.getString("receiptSigningPublicKey"))
                .put("flags",JSONObject().put("read",true).put("contentWrite",true)))
            if(busy)return response(JSONObject().put("error",JSONObject().put("code","cloud_account_busy").put("retryable",true).put("retryAfterMillis",1000).put("details",JSONObject()))).setResponseCode(503)
            if(path=="negotiate")return response(dto().put("windowId","12".repeat(16)).put("expiresAtMillis",now+300000).put("serverRevision","1").put("historyFloorRevision","0").put("account",JSONObject().put("commitOid",JSONObject.NULL).put("revision","0").put("rollbackGeneration","0")))
            if(path=="objects/have-want") {
                val body=SyncV2Wire.parse(request.body.readByteArray());val rows=JSONArray();val oids=body.getJSONArray("oids")
                for(i in 0 until oids.length()) {
                    val oid=oids.getString(i);val bytes=objects[oid];val part=prefix[oid]
                    // C8: a complete durable prefix is settled before returning present.
                    val total=bytes?.size;val off=part?.size?:0
                    val status=if(bytes!=null && off==total)"present" else if(part!=null)"partial" else "missing"
                    if(status=="present" && oid!="${oid.substringBefore(':')}:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(part!!))}")return MockResponse().setResponseCode(422)
                    rows.put(JSONObject().put("oid",oid).put("status",status).put("totalBytes",if(status=="missing")JSONObject.NULL else total)
                        .put("durableOffset",off).put("receipt",if(status=="missing")JSONObject.NULL else receipt(oid,total!!,off)))
                }
                return response(dto().put("windowId",body.get("windowId")).put("objects",rows))
            }
            if(path=="objects/reserve") {
                val body=SyncV2Wire.parse(request.body.readByteArray());assert(body.getString("channel")=="content")
                val requestId=body.getString("requestId");val hash=SyncV2Codec.hash(body);reserveIds.add(requestId)
                val previous=if(replayReserve)reserved[requestId]else null
                if(previous!=null && previous.first!=hash)return response(JSONObject().put("error",JSONObject().put("code","idempotency_conflict").put("retryable",false).put("retryAfterMillis",JSONObject.NULL).put("details",JSONObject()))).setResponseCode(409)
                val value=previous?.second?:run {
                    val rows=body.getJSONArray("objects");for(i in 0 until rows.length())rows.getJSONObject(i).put("durableOffset",prefix[rows.getJSONObject(i).getString("oid")]?.size?:0)
                    dto().put("reservationId","34".repeat(16)).put("expiresAtMillis",now+600000).put("reservedBytes",0).put("objects",rows)
                }
                if(replayReserve && previous==null)reserved[requestId]=hash to JSONObject(value.toString())
                reserveOffsets.add(value.getJSONArray("objects").getJSONObject(0).getInt("durableOffset"))
                afterReserve?.invoke()
                return response(value)
            }
            if(path.startsWith("objects/") && "/parts/" in path) {
                val oid=path.substringAfter("objects/").substringBefore("/parts/");val offset=path.substringAfter("/parts/").toInt()
                val bytes=request.body.readByteArray();putOffsets.add(offset);putOids.add(oid)
                if(partDelay>0)Thread.sleep(partDelay)
                val prior=prefix[oid]?:ByteArray(0)
                if(offset<prior.size)check(offset+bytes.size<=prior.size && prior.copyOfRange(offset,offset+bytes.size).contentEquals(bytes))
                else {check(offset==prior.size);prefix[oid]=prior+bytes}
                val durable=prefix[oid]!!.size
                if(dropAck){dropAck=false;return MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)}
                return response(dto().put("oid",oid).put("durableOffset",durable).put("receipt",receipt(oid,objects[oid]!!.size,durable)))
            }
            if(path.endsWith("/complete")) {
                val oid=path.substringAfter("objects/").substringBefore("/complete");val bytes=prefix[oid]!!
                check(bytes.contentEquals(objects[oid]!!));completed++
                return response(dto().put("oid",oid).put("receipt",receipt(oid,bytes.size,bytes.size)))
            }
            if(path.startsWith("objects/") && request.method=="GET") {
                val oid=path.substringAfter("objects/");val bytes=objects[oid]!!;val range=request.getHeader("Range")
                if(range==null)return MockResponse().setHeader("ETag",oid).setBody(Buffer().write(bytes))
                ranges++;val (start,end)=range.removePrefix("bytes=").split('-').map{it.toInt()}
                return MockResponse().setResponseCode(if(wrongRange)200 else 206).setHeader("ETag",oid).setHeader("Content-Range","bytes $start-$end/${bytes.size}").setBody(Buffer().write(bytes.copyOfRange(start,end+1)))
            }
            if(path=="demand") {
                if(request.method=="GET")return response(dto().put("serverNowMillis",now).put("demands",JSONArray()))
                controlRequests++;return response(dto().put("accepted",true))
            }
            return MockResponse().setResponseCode(404)
        }}
        server.start()
    }
    fun authors():SyncV2Authors {
        val mapping=JSONObject().put("scope",identity.scope).put("deviceId",signer.deviceId).put("keyId",signer.keyId).put("legacyDeviceId","synthetic-phone").put("approvalRevision","1")
        val binding=identity.signingBinding(signer,now,"0").put("deviceMapping",signer.signed("wrss.sync.v2/device-mapping",mapping))
        return SyncV2Authors(identity,trust()){it=="synthetic-phone"}.also{it.accept(binding,"0")}
    }
    fun client()=SyncV2CloudClient(server.url("/functions/v1/sync/v2/"),{SyncV2CloudClient.Session(identity.accountId.replace(Regex("(.{8})(.{4})(.{4})(.{4})(.{12})"),"$1-$2-$3-$4-$5"),"synthetic-phone","old-epoch","old-generation",identity.scopeJson,"aa".repeat(16),"synthetic","synthetic")},trust(),true)
    override fun close(){server.shutdown()}
}
