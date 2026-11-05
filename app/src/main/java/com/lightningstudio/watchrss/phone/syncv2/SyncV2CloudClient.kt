package com.lightningstudio.watchrss.phone.syncv2

import com.lightningstudio.watchrss.phone.BuildConfig
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class SyncV2CloudError(val code: String, val retryable: Boolean = false, val retryAfterMillis: Long? = null) : IOException(code) {
    val display: String get() = when(code) {
        "upgrade_required", "unsupported_feature" -> "云资料格式需要升级"
        "cloud_account_busy", "library_busy" -> "同步忙，请稍后重试"
        "service_key_changed" -> "云同步服务密钥变化，请更新应用"
        "budget_paused" -> "同步暂停，稍后继续"
        else -> "同步暂未完成：$code"
    }
}

/** P2 is deliberately isolated: no production hostname, redirects, logging interceptor or device API. */
internal class SyncV2CloudClient(
    private val base: HttpUrl, private val session: () -> Session,
    private val serviceTrust: SyncV2ServiceTrust,
    private val enabled: Boolean = BuildConfig.SYNC_OBJECTS_V2_CLOUD_INDEX,
    private val http: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).callTimeout(30, TimeUnit.SECONDS).build()
) {
    data class Session(val userId: String, val legacyDeviceId: String, val legacyEpoch: String,
        val legacyGeneration: String, val scopeJson: String, val deviceId: String, val bearer: String, val apiKey: String) {
        val accountId: String get() {
            if (!Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(userId)) SyncV2Codec.fail("authentication_required")
            return userId.replace("-", "").lowercase()
        }
        fun fence() = listOf(accountId, legacyDeviceId, legacyEpoch, legacyGeneration, scopeJson, deviceId)
    }
    private var capabilitiesChecked=false
    private val owner: List<String>
    init {
        // Claude must supply a separately reviewed isolated address before broadening this allowlist.
        if (base.host !in setOf("127.0.0.1", "localhost", "::1") || base.username.isNotEmpty() || base.password.isNotEmpty() || base.query != null || base.fragment != null || base.encodedPath != "/functions/v1/sync/v2/") SyncV2Codec.fail("isolated_backend_required")
        val s = session(); SyncV2Codec.scope(JSONObject(s.scopeJson)); SyncV2Codec.unhex(s.deviceId,16)
        if (s.legacyDeviceId.isEmpty() || s.legacyEpoch.isEmpty() || s.legacyGeneration.isEmpty()) SyncV2Codec.fail("authentication_required")
        owner = s.fence()
        if (http.interceptors.isNotEmpty() || http.networkInterceptors.isNotEmpty() || http.followRedirects || http.followSslRedirects) SyncV2Codec.fail("invalid_schema")
    }
    private fun active(): Session {
        if (!enabled) SyncV2Codec.fail("feature_disabled")
        serviceTrust.requireActive()
        val s = session(); if(s.accountId!=serviceTrust.accountId)SyncV2Codec.fail("authentication_required"); if (s.fence() != owner) SyncV2Codec.fail("scope_mismatch"); return s
    }
    val deviceId:String get()=active().deviceId
    val scope: JSONObject get() = JSONObject(active().scopeJson)
    private fun url(path: String, query: Map<String,String> = emptyMap()): HttpUrl {
        if (!Regex("[a-zA-Z0-9/_.:-]+").matches(path) || path.contains("..")) SyncV2Codec.fail("invalid_schema")
        val builder = base.newBuilder().encodedPath(base.encodedPath + path)
        val s = JSONObject(owner[4]); for (k in listOf("repoId", "libraryEpoch", "contentGeneration")) builder.addQueryParameter(k,s.getString(k))
        query.forEach { (k,v) -> builder.addQueryParameter(k,v) }; return builder.build()
    }
    private fun bytes(method: String, path: String, body: ByteArray? = null, headers: Map<String,String> = emptyMap(), query: Map<String,String> = emptyMap(), raw: Boolean = false): Pair<ByteArray,Map<String,String>> {
        val s = active()
        if(path!="capabilities" && !capabilitiesChecked)SyncV2Codec.fail("authentication_required")
        val builder = Request.Builder().url(url(path,query)).header("Authorization", "Bearer ${s.bearer}")
            .header("apikey",s.apiKey).header("x-watchrss-cloud-device-id",s.legacyDeviceId)
            .header("x-watchrss-library-epoch",s.legacyEpoch).header("x-watchrss-content-generation",s.legacyGeneration)
            .header("Accept",if(raw)"application/octet-stream" else "application/json")
        headers.forEach { (k,v) -> builder.header(k,v) }
        val request = builder.method(method, body?.toRequestBody((if(raw || method=="PUT")"application/octet-stream" else "application/json").toMediaType())).build()
        http.newCall(request).execute().use { response ->
            val limit = if(raw && response.isSuccessful) 266264 else 1048576
            val data = response.body?.byteStream()?.use { input ->
                val out=java.io.ByteArrayOutputStream();val block=ByteArray(8192)
                while(true) { val n=input.read(block);if(n<0)break;if(out.size()+n>limit)SyncV2Codec.fail("object_too_large");out.write(block,0,n) };out.toByteArray()
            } ?: SyncV2Codec.fail("invalid_encoding")
            active() // An auth refresh may keep ownership; account/old epoch changes invalidate this round.
            if (!response.isSuccessful) {
                if(path=="capabilities" && response.code in setOf(404,426))throw SyncV2CloudError("upgrade_required")
                val error = SyncV2Wire.parse(data).getJSONObject("error")
                SyncV2Codec.exact(error,setOf("code","retryable","retryAfterMillis","details"))
                val code=SyncV2Wire.string(error,"code")
                if(!Regex("[a-z][a-z0-9_]{0,63}").matches(code) || error.get("retryable") !is Boolean)SyncV2Codec.fail("invalid_schema")
                val delay=if(error.isNull("retryAfterMillis"))null else SyncV2Wire.uint(error,"retryAfterMillis",60000)
                throw SyncV2CloudError(code,error.getBoolean("retryable"),delay)
            }
            if(raw) {
                if(response.header("ETag")?.removeSurrounding("\"") != path.removePrefix("objects/"))SyncV2Codec.fail("hash_mismatch")
                if(response.header("Content-Length")?.toLongOrNull()!=data.size.toLong())SyncV2Codec.fail("invalid_encoding")
            }
            return data to response.headers.toMultimap().mapValues { it.value.singleOrNull() ?: "" }
        }
    }
    private fun get(path: String, query: Map<String,String> = emptyMap()): JSONObject = check(SyncV2Wire.parse(bytes("GET",path,query=query).first))
    private fun post(path: String, value: JSONObject): JSONObject {
        val request=JSONObject(value.toString())
        if(path!="devices/signing-key")request.put("version",2)
        if(!request.has("scope") && !request.has("body") && !request.has("descriptor"))request.put("scope",scope)
        return check(SyncV2Wire.parse(bytes("POST",path,SyncV2Codec.utf8(SyncV2Codec.canonical(request))).first))
    }
    private fun check(value: JSONObject): JSONObject {
        if(value.opt("version")!=2)throw SyncV2CloudError("upgrade_required")
        if(SyncV2Codec.scope(value.getJSONObject("scope"))!=owner[4])SyncV2Codec.fail("scope_mismatch")
        return value
    }
    fun capabilities() = get("capabilities").also {
        if(SyncV2Wire.uint(it,"minVersion",4294967295)>2 || SyncV2Wire.uint(it,"maxVersion",4294967295)<2)throw SyncV2CloudError("upgrade_required")
        SyncV2Wire.features(it.getJSONArray("features"))
        if(SyncV2Wire.uint(it,"maxPartBytes")!=65536L || SyncV2Wire.uint(it,"historyDays")!=7L)SyncV2Codec.fail("invalid_schema")
        serviceTrust.capabilities(it); capabilitiesChecked=true
    }
    fun authors(identity:SyncV2Identity,approvedLegacyDevice:(String)->Boolean):SyncV2Authors {
        if(identity.accountId!=session().accountId)SyncV2Codec.fail("authentication_required")
        return SyncV2Authors(identity,serviceTrust,approvedLegacyDevice)
    }
    fun registerSigningKey(binding:JSONObject)=post("devices/signing-key",binding)
    fun signingKeys(window:String)=get("devices/signing-keys",mapOf("windowId" to window))
    fun negotiate(known:JSONArray=JSONArray())=post("negotiate",JSONObject().put("knownRefs",known).put("features",JSONArray())).also(::window)
    fun refs(window:String,page:String?=null)=get("refs",mapOf("windowId" to window)+if(page==null)emptyMap()else mapOf("pageToken" to page))
    fun diff(window:String,root:String,known:JSONArray=JSONArray(),page:String?=null)=post("trees/diff",JSONObject().put("windowId",window).put("treeRoot",root).put("knownNodes",known).put("pageToken",page?:JSONObject.NULL))
    fun haveWant(window:String,oids:List<String>)=post("objects/have-want",JSONObject().put("windowId",window).put("oids",JSONArray(oids)))
    fun reserve(request:String,objects:List<Pair<String,Int>>)=post("objects/reserve",JSONObject().put("requestId",request).put("channel","control").put("leaseMillis",600000)
        .put("objects",JSONArray(objects.sortedBy{it.first}.map{JSONObject().put("oid",it.first).put("totalBytes",it.second)})))
    fun put(oid:String,offset:Int,reservation:String,request:String,part:ByteArray):JSONObject {
        SyncV2Wire.oid(oid);SyncV2Codec.unhex(reservation,16);SyncV2Codec.unhex(request,16)
        if(offset<0 || offset%65536!=0 || part.size !in 1..65536)SyncV2Codec.fail("invalid_schema")
        val s=scope
        val headers=mapOf("x-watchrss-sync-repo-id" to s.getString("repoId"),"x-watchrss-sync-library-epoch" to s.getString("libraryEpoch"),
            "x-watchrss-sync-content-generation" to s.getString("contentGeneration"),"x-watchrss-sync-reservation-id" to reservation,
            "x-watchrss-sync-request-id" to request,"x-watchrss-sync-part-sha256" to SyncV2Codec.hex(SyncV2Codec.sha(part)))
        return check(SyncV2Wire.parse(bytes("PUT","objects/$oid/parts/$offset",part,headers).first))
    }
    fun verifyReceipt(value:JSONObject,oid:String,total:Long,offset:Long,now:Long) = serviceTrust.receipt(value,scope,oid,total,offset,now)
    fun complete(oid:String,reservation:String,request:String,total:Int)=post("objects/$oid/complete",JSONObject().put("reservationId",reservation).put("requestId",request).put("totalBytes",total))
    fun stateRefs(value:JSONObject)=post("state-refs",value)
    fun reference(parent:String,window:String):JSONObject {
        val type=parent.substringBefore(':');if(type !in setOf("state","recipe"))SyncV2Codec.fail("invalid_oid")
        return get("$type-refs/$parent",mapOf("windowId" to window))
    }
    fun raw(oid:String):ByteArray { SyncV2Wire.oid(oid);return bytes("GET","objects/$oid",raw=true).first }
    fun batch(window:String,oids:List<String>)=post("objects/batch-get",JSONObject().put("windowId",window).put("oids",JSONArray(oids)))
    fun objects(window:String,oids:List<String>):Map<String,ByteArray> {
        val response=batch(window,oids);val out=linkedMapOf<String,ByteArray>();val a=response.getJSONArray("objects")
        for(i in 0 until a.length()) {val item=a.getJSONObject(i);SyncV2Codec.exact(item,setOf("oid","bytes"));val oid=item.getString("oid");if(oid !in oids || out.containsKey(oid))SyncV2Codec.fail("invalid_schema");out[oid]=SyncV2Codec.decode64(item.getString("bytes"))}
        for(oid in SyncV2Wire.strings(response.getJSONArray("nextOids"),512,false)) {if(oid !in oids || out.containsKey(oid))SyncV2Codec.fail("invalid_schema");out[oid]=raw(oid)}
        if(out.keys!=oids.toSet())SyncV2Codec.fail("object_missing")
        return out
    }
    /** Store this exact signed request before calling. Unknown timeout results never create a new requestId. */
    fun publish(value:JSONObject):JSONObject {
        try { return post("account/publish",value) } catch(e:SyncV2CloudError) { throw e } catch(e:IOException) {
            val id=value.getJSONObject("account").getJSONObject("body").getString("requestId")
            val prior=get("requests/$id")
            if(prior.getString("status")=="succeeded")return prior.getJSONObject("response")
            throw SyncV2CloudError("temporarily_unavailable",true,1000)
        }
    }
    fun stage(value:JSONObject)=post("refs/cas",value)
    fun events(after:String,waitMillis:Int=25000):JSONObject {
        SyncV2Codec.d64(after);require(waitMillis in 0..25000)
        return get("events",mapOf("after" to after,"waitMillis" to waitMillis.toString())).also(::window)
    }
    fun checkpoint()=get("checkpoint")
    fun account()=get("account").also(::window)
    fun ancestry(head:String,stop:String?)=get("account/ancestry",mapOf("head" to head,"limit" to "64")+if(stop==null)emptyMap()else mapOf("stop" to stop))
    fun history(page:String?=null)=get("history",if(page==null)emptyMap()else mapOf("pageToken" to page))
    fun repoInit(descriptor:JSONObject,recovery:JSONObject)=post("repo/init",JSONObject().put("descriptor",descriptor).put("recoveryCapsule",recovery))
    // A losing repo creator must authenticate the winning descriptor, whose scope can differ.
    fun descriptor():JSONObject {
        val value=SyncV2Wire.parse(bytes("GET","repo/descriptor").first)
        if(value.opt("version")!=2)throw SyncV2CloudError("upgrade_required")
        return value
    }
    fun recovery(oid:String):ByteArray {SyncV2Wire.oid(oid,"id-key-recovery");return bytes("GET","repo/id-key-recovery/$oid").first}
    companion object {
        fun window(v:JSONObject) {
            SyncV2Codec.unhex(SyncV2Wire.string(v,"windowId"),16);SyncV2Wire.uint(v,"expiresAtMillis")
            for(k in listOf("serverRevision","historyFloorRevision"))SyncV2Codec.d64(SyncV2Wire.string(v,k))
            val a=v.getJSONObject("account");SyncV2Codec.exact(a,setOf("commitOid","revision","rollbackGeneration"))
            if(!a.isNull("commitOid"))SyncV2Wire.oid(SyncV2Wire.string(a,"commitOid"),"commit")
            for(k in listOf("revision","rollbackGeneration"))SyncV2Codec.d64(SyncV2Wire.string(a,k))
            if(a.isNull("commitOid") && a.getString("revision")!="0")SyncV2Codec.fail("invalid_schema")
        }
        fun refUpdate(signer:SyncV2Signing,scope:JSONObject,ref:String,old:String?,revision:String,oid:String,requestId:String,now:Long):JSONObject {
            SyncV2Codec.scope(scope);SyncV2Codec.d64(revision);SyncV2Wire.oid(oid,"commit");if(old!=null)SyncV2Wire.oid(old,"commit");SyncV2Codec.unhex(requestId,16)
            if(ref!="refs/account/current" && ref!="refs/devices/${signer.deviceId}/index" || now !in 0..9007199254440991L)SyncV2Codec.fail("invalid_schema")
            return signer.signed("wrss.sync.v2/ref-update-sign",JSONObject().put("version",2).put("scope",scope).put("ref",ref)
                .put("expectedOid",old?:JSONObject.NULL).put("expectedRevision",revision).put("newCommitOid",oid).put("requestId",requestId).put("issuedAtMillis",now).put("expiresAtMillis",now+300000))
        }
    }
}
