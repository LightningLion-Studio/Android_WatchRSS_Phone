package com.lightningstudio.watchrss.phone.syncv2

import android.content.Context
import androidx.room.Room
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.RuntimeEnvironment
import java.io.File

/** Native Room + actual product HTTP/crypto/actions. All identifiers and keys are synthetic. */
internal class SyncV2P5Fixture:AutoCloseable {
    val f=SyncV2P3Fixture();val identity=f.identity;val key=ByteArray(32){7}
    val root=File((RuntimeEnvironment.getApplication() as Context).cacheDir,"p5-${System.nanoTime()}").also{it.mkdirs()}
    private val path=File(root,"phone.db")
    var room:PhoneCompanionDatabase=room();private set
    private fun room()=Room.databaseBuilder(RuntimeEnvironment.getApplication(),PhoneCompanionDatabase::class.java,path.absolutePath).allowMainThreadQueries().build()
    val sql get()=room.openHelper.writableDatabase
    var projection=projection();private set
    private fun projection()=SyncV2Projection(sql,identity,SyncV2Fixtures.localKey,true).also{it.install()}
    val headers=linkedMapOf<String,JSONObject>();val refs=linkedMapOf<String,JSONObject>()
    val results=mutableMapOf<String,JSONObject>();val requests=mutableListOf<String>()
    var current=JSONObject();var deviceOid:String?=null;var deviceRevision="0"
    var selectedLocalRevision:String?=null;var choiceCalls=0;var publishCalls=0;var ackCalls=0;var historyCalls=0;var flags=true;var drop=false;var ackFailure=false
    var onConfirm:(()->Unit)?=null;var afterCloudConfirmation:((JSONObject)->Unit)?=null;var historyExpired=false;var snapshotExpired=false
    private val totals=mutableMapOf<String,Int>()
    val target:String;val initial:String
    init {
        val targetRow=note("old title","base\nline\n")
        target=commit(listOf(targetRow),null,"0","normal",null)
        val currentRow=note("new title","base\nline\n")
        initial=commit(listOf(currentRow),target,"0","normal",null)
        setCurrent(initial,"2")
        f.route={request->
            val route=request.requestUrl!!.encodedPath.substringAfter("/functions/v1/sync/v2/");requests.add(route)
            fun body()=SyncV2Wire.parse(request.body.readByteArray())
            when {
                route=="capabilities"->f.response(f.dto().put("minVersion",2).put("maxVersion",2).put("maxPartBytes",65536).put("historyDays",7).put("features",JSONArray())
                    .put("receiptKeyId",f.bootstrap.get("receiptKeyId")).put("receiptSigningPublicKey",f.bootstrap.get("receiptSigningPublicKey"))
                    .put("flags",JSONObject().put("read",true).put("indexWrite",true).put("contentWrite",true).put("historyRestore",flags)))
                route=="negotiate" || route=="account"->f.response(window())
                route=="checkpoint"->f.response(f.signer.signed("wrss.sync.v2/checkpoint-sign",window().apply{remove("version")}.put("checkpointId","33".repeat(16)).put("issuedAtMillis",f.now).put("historyFloorMillis",0)).put("version",2).put("scope",identity.scope))
                route=="devices/signing-keys"->f.response(f.dto().put("bindings",JSONArray().put(binding())).put("revocationRevision","0"))
                route=="account/ancestry"->{
                    var at=request.requestUrl!!.queryParameter("head");val stop=request.requestUrl!!.queryParameter("stop");val entries=JSONArray();var n=0
                    while(at!=stop && at!=null && n++<64) {
                        val h=headers.getValue(at);entries.put(JSONObject().put("commitOid",at).put("headerOid",SyncV2Codec.oid("commit-header",h)).put("body",h.getJSONObject("body")).put("signature",h.getString("signature")))
                        at=h.getJSONObject("body").optString("accountParent").takeIf{it.isNotEmpty() && it!="null"}
                    }
                    f.response(f.dto().put("head",request.requestUrl!!.queryParameter("head")).put("stop",stop?:JSONObject.NULL).put("headers",entries).put("nextOid",at?:JSONObject.NULL).put("complete",at==stop))
                }
                route=="history"->{historyCalls++;f.response(f.dto().put("items",JSONArray().put(JSONObject().put("commitOid",target).put("retiredAtMillis",100).put("expiresAtMillis",604800100).put("authorDeviceId",f.signer.deviceId))).put("pageToken",JSONObject.NULL))}
                route=="history/prepare"->{val b=body();if(snapshotExpired)error("snapshot_expired",410)else f.response(f.dto().put("targetCommitOid",b.get("targetCommitOid")).put("treeRoot",headers.getValue(b.getString("targetCommitOid")).getJSONObject("body").get("treeRoot")).put("leaseId","66".repeat(16)).put("expiresAtMillis",f.now+600000))}
                route=="choices/cloud"->{
                    val b=body();SyncV2Codec.exact(b,setOf("scope","requestId","choiceId","expectedAccountRevision","expectedRollbackGeneration","selectedLocalChangeRevision"));selectedLocalRevision=b.getString("selectedLocalChangeRevision");SyncV2Codec.d64(selectedLocalRevision!!);choiceCalls++;onConfirm?.invoke()
                    val old=results[b.getString("requestId")]
                    if(old!=null)f.response(old)else if(current.getString("revision")!=b.getString("expectedAccountRevision") || current.getString("rollbackGeneration")!=b.getString("expectedRollbackGeneration"))error("choice_stale",409)
                    else {val r=f.dto().put("choiceId",b.get("choiceId")).put("account",JSONObject(current.toString())).put("confirmedAccount",JSONObject(current.toString())).put("auditRevision","9");results[b.getString("requestId")]=r;afterCloudConfirmation?.invoke(r)
                        if(drop){drop=false;MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)}else f.response(r)}
                }
                route.startsWith("requests/")->{val r=results[route.substringAfter("requests/")];f.response(f.dto().put("status",if(r==null)"unknown"else"succeeded").put("response",r?:JSONObject.NULL))}
                route=="acks/applied"->{ackCalls++;if(ackFailure)error("temporarily_unavailable",503)else f.response(f.dto().put("accepted",true).put("serverRevision",current.get("revision")))}
                route=="account/publish"->{
                    val b=body();publishCalls++;onConfirm?.invoke();val a=b.getJSONObject("account").getJSONObject("body");val d=b.getJSONObject("device").getJSONObject("body");val requestId=a.getString("requestId");val old=results[requestId]
                    if(old!=null)f.response(old)else if(a.getString("expectedOid")!=current.getString("commitOid") || a.getString("expectedRevision")!=current.getString("revision"))error("ref_conflict",409)
                    else {
                        val oid=a.getString("newCommitOid");val h=SyncV2Wire.parse(f.objects.getValue(oid),262144,true);SyncV2HeadPins.validateBody(h.getJSONObject("body"));headers[oid]=h
                        setCurrent(oid,SyncV2Codec.next(current.getString("revision")));deviceRevision=SyncV2Codec.next(d.getString("expectedRevision"));deviceOid=oid
                        val r=f.dto().put("account",JSONObject(current.toString())).put("device",JSONObject().put("ref",d.get("ref")).put("commitOid",oid).put("revision",deviceRevision)).put("serverRevision",current.get("revision"));results[requestId]=r
                        if(drop){drop=false;MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)}else f.response(r)
                    }
                }
                route=="objects/reserve"->{val b=body();val list=b.getJSONArray("objects");for(i in 0 until list.length())totals[list.getJSONObject(i).getString("oid")]=list.getJSONObject(i).getInt("totalBytes")
                    f.response(f.dto().put("reservationId","44".repeat(16)).put("expiresAtMillis",f.now+600000).put("reservedBytes",0).put("objects",list.also{for(i in 0 until it.length())it.getJSONObject(i).put("durableOffset",0)}))}
                route=="objects/have-want"->{val b=body();val list=b.getJSONArray("oids");val entries=JSONArray()
                    for(i in 0 until list.length()) {
                        val oid=list.getString(i);val bytes=f.objects[oid];val partial=f.prefix[oid];val offset=bytes?.size?:partial?.size?:0;val total=bytes?.size?:totals[oid]
                        entries.put(JSONObject().put("oid",oid).put("status",if(bytes!=null)"present"else if(offset>0)"partial"else"missing").put("totalBytes",if(offset==0)JSONObject.NULL else total).put("durableOffset",offset).put("receipt",if(offset==0)JSONObject.NULL else f.receipt(oid,total!!,offset)))
                    }
                    f.response(f.dto().put("windowId",b.get("windowId")).put("objects",entries))}
                route.startsWith("objects/") && "/parts/" in route->{val oid=route.substringAfter("objects/").substringBefore("/parts/");val off=route.substringAfter("/parts/").toInt();val raw=request.body.readByteArray();f.putOids.add(oid);f.putOffsets.add(off);check(off==(f.prefix[oid]?.size?:0));f.prefix[oid]=(f.prefix[oid]?:byteArrayOf())+raw
                    f.response(f.dto().put("oid",oid).put("durableOffset",off+raw.size).put("receipt",f.receipt(oid,totals.getValue(oid),off+raw.size)))}
                route.startsWith("objects/") && route.endsWith("/complete")->{val oid=route.substringAfter("objects/").substringBefore("/complete");f.objects[oid]=f.prefix.getValue(oid);f.response(f.dto().put("oid",oid).put("receipt",f.receipt(oid,f.objects.getValue(oid).size,f.objects.getValue(oid).size)))}
                route=="state-refs"->{val b=body();val value=b.getJSONObject("body");val oid=SyncV2Codec.oid("state-refs",b);f.objects[oid]=SyncV2Codec.utf8(SyncV2Codec.canonical(b));refs[value.getString("stateOid")]=mapping(value.getString("stateOid"),oid,f.objects.getValue(oid).size);f.response(f.dto().put("accepted",true))}
                route.startsWith("state-refs/")->f.response(JSONObject(refs.getValue(route.substringAfter("state-refs/")).toString()).put("version",2))
                route.startsWith("objects/") && request.method=="GET"->{val bytes=f.objects[route.substringAfter("objects/")];if(bytes==null)error("object_missing",404)else MockResponse().setHeader("ETag",route.substringAfter("objects/")).setBody(Buffer().write(bytes))}
                route=="events"->if(historyExpired)error("history_expired",410)else f.response(window().put("events",JSONArray()).put("cursor",current.get("revision")).put("hasMore",false))
                else->null
            }
        }
    }
    fun binding():JSONObject {
        val mapping=JSONObject().put("scope",identity.scope).put("deviceId",f.signer.deviceId).put("keyId",f.signer.keyId).put("legacyDeviceId","synthetic-phone").put("approvalRevision","1")
        return identity.signingBinding(f.signer,f.now,"0").put("deviceMapping",f.signer.signed("wrss.sync.v2/device-mapping",mapping))
    }
    fun window()=f.dto().put("windowId","12".repeat(16)).put("expiresAtMillis",f.now+300000).put("issuedAtMillis",f.now).put("serverRevision",current.getString("revision")).put("historyFloorRevision","0")
        .put("refs",JSONArray().also{if(deviceOid!=null)it.put(JSONObject().put("deviceId",f.signer.deviceId).put("commitOid",deviceOid).put("refRevision",deviceRevision))}).put("account",JSONObject(current.toString()))
    private fun mapping(parent:String,oid:String,size:Int)=JSONObject().put("scope",identity.scope).put("parentOid",parent).put("refsOid",oid).put("authorDeviceId",f.signer.deviceId).put("keyId",f.signer.keyId).put("totalBytes",size)
    fun note(title:String="title",markdown:String="base\nline\n")=JSONObject().put("noteId","note").put("folderId",JSONObject.NULL).put("title",title).put("markdown",markdown).put("plainText",markdown)
        .put("contentHash","hash").put("baseContentHash","hash").put("baseMarkdown","base\nline\n").put("pinned",false).put("createdAt",0).put("updatedAt",0).put("modifiedBy","synthetic").put("deleted",false).put("deletedAt",0).put("noteConflicts",JSONObject().put("version",1).put("records",JSONArray()))
    fun commit(values:List<JSONObject>,parent:String?,generation:String,kind:String,target:String?):String {
        val rows=values.map{v->val kind=if(v.has("noteId"))"note"else if(v.has("folderId"))"folder"else"article";val id=SyncV2Business.logicalId(kind,v);val entity=identity.entity(kind,id)
            val p=SyncV2Business.split(kind,id,entity,v,null);val meta=SyncV2Envelope.seal(identity,key,1,"meta",SyncV2Codec.utf8(SyncV2Codec.canonical(p.meta)));val state=SyncV2Envelope.seal(identity,key,1,"state",SyncV2Codec.utf8(SyncV2Codec.canonical(p.state)))
            f.objects[meta.oid]=meta.bytes;f.objects[state.oid]=state.bytes
            val reference=SyncV2Index.refs(identity,f.signer,state.oid);val refOid=SyncV2Codec.oid("state-refs",reference);f.objects[refOid]=SyncV2Codec.utf8(SyncV2Codec.canonical(reference));refs[state.oid]=mapping(state.oid,refOid,f.objects.getValue(refOid).size)
            JSONObject().put("entityKey",entity).put("metaOid",meta.oid).put("stateOid",state.oid).put("addedAtMillis",0).put("rowSequence","1").put("deleted",v.optBoolean("deleted",false)).put("bodyRevision",JSONObject.NULL).put("recipeOid",JSONObject.NULL).put("promiseId",JSONObject.NULL).put("opaqueContentId",JSONObject.NULL).put("contentBytes",JSONObject.NULL).put("requiredFeatures",JSONArray())}
        val tree=SyncV2Index.build(identity.scope,rows);f.objects.putAll(tree.objects)
        val previous=headers.keys.lastOrNull();val sequence=SyncV2Codec.next(previous?.let{headers.getValue(it).getJSONObject("body").getString("deviceSequence")}?:"0")
        val b=JSONObject().put("version",2).put("scope",identity.scope).put("authorDeviceId",f.signer.deviceId).put("deviceSequence",sequence).put("previousOwnCommit",previous?:JSONObject.NULL)
            .put("mergedParents",JSONArray(if(target!=null && target!=previous)listOf(target)else emptyList<String>())).put("treeRoot",if(kind=="rollback")headers.getValue(target!!).getJSONObject("body").getString("treeRoot")else tree.root).put("requiredFeatures",JSONArray()).put("createdAtMillis",f.now).put("observedRefs",JSONArray()).put("indexComplete",true)
            .put("accountParent",parent?:JSONObject.NULL).put("rollbackGeneration",generation).put("operation",JSONObject().put("kind",kind).put("rollbackTarget",target?:JSONObject.NULL).put("resolvesGeneration",JSONObject.NULL))
        val h=f.signer.commit(b);val oid=SyncV2Codec.oid("commit",b);headers[oid]=h;f.objects[oid]=SyncV2Codec.utf8(SyncV2Codec.canonical(h));deviceOid=oid;deviceRevision=sequence;return oid
    }
    fun setCurrent(oid:String,revision:String){current=JSONObject().put("commitOid",oid).put("revision",revision).put("rollbackGeneration",headers.getValue(oid).getJSONObject("body").getString("rollbackGeneration"))}
    fun rollbackRemote(values:List<JSONObject> = listOf(note("old title"))):String {
        val oid=commit(values,current.getString("commitOid"),SyncV2Codec.next(current.getString("rollbackGeneration")),"rollback",target);setCurrent(oid,SyncV2Codec.next(current.getString("revision")));return oid
    }
    fun actions(enabled:Boolean=true,quota:Long=32L*1024*1024,fault:(String)->Unit={})=SyncV2AccountActions(f.client(),identity,projection,f.signer,{key},{it=="synthetic-phone"},root,enabled,{f.now},quota,fault)
    fun start() {actions().refresh();drain()}
    fun drain(){repeat(20){if(!projection.runWindow().paused)return};error("scan did not finish")}
    fun edit(text:String="offline\nline\n") {sql.execSQL("UPDATE notes SET markdown=?,plainText=?,updatedAt=updatedAt+1 WHERE noteId='note'",arrayOf(text,text));drain()}
    fun pending(){start();edit();rollbackRemote();actions().refresh();drain()}
    fun reopen(){room.close();room=room();projection=projection()}
    fun text()=sql.query("SELECT markdown FROM notes WHERE noteId='note'").use{check(it.moveToFirst());it.getString(0)}
    fun error(code:String,status:Int)=f.response(JSONObject().put("error",JSONObject().put("code",code).put("retryable",status==503).put("retryAfterMillis",if(status==503)1000 else JSONObject.NULL).put("details",JSONObject()))).setResponseCode(status)
    override fun close(){room.close();f.close();root.deleteRecursively()}
}
