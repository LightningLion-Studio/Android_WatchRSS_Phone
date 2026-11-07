package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/** Bounded local adapter over P3's durable sealed-byte store. No encryption on transport changes.
 * A window is bound to this authenticated socket and expires within five minutes. */
internal class SyncV2LocalObjects(private val session:SyncV2LocalSession,private val store:SyncV2ContentStore,
    private val now:()->Long={System.currentTimeMillis()},private val verifyCommit:(JSONObject)->Unit={SyncV2Codec.fail("authentication_required")},
    private val contentKey:((Long)->ByteArray)?=null,
    private val snapshot:()->JSONObject={store.load("local-snapshot")?:SyncV2Codec.fail("snapshot_unavailable")}) {
    private val windows=linkedMapOf<String,Long>()
    private val transfers=mutableMapOf<String,Pair<String,Int>>()
    private data class Page(val window:String,val root:String,val known:List<String>,val todo:List<String>)
    private val pages=mutableMapOf<String,Page>()
    fun openWindow(body:JSONObject=JSONObject().put("scope",session.scope).put("knownRefs",JSONArray()).put("features",JSONArray())):JSONObject {
        session.requireProof();SyncV2Codec.exact(body,setOf("scope","knownRefs","features"))
        if(SyncV2Codec.scope(body.getJSONObject("scope"))!=SyncV2Codec.scope(session.scope))SyncV2Codec.fail("scope_mismatch")
        if(body.getJSONArray("knownRefs").length()>64)SyncV2Codec.fail("invalid_schema")
        session.requireNegotiatedFeatures(SyncV2Wire.features(body.getJSONArray("features")))
        windows.entries.removeAll{it.value<=now()}
        if(windows.size>=4)SyncV2Codec.fail("window_limit")
        val id=SyncV2Codec.hex(SyncV2Codec.random(16));val expiry=now()+300000;windows[id]=expiry
        val captured=SyncV2Wire.parse(SyncV2LocalProtocol.bytes(snapshot()),262144,true)
        SyncV2Codec.exact(captured,setOf("refs","serverRevision","historyFloorRevision","checkpointOid","account"))
        val result=captured.put("version",2).put("scope",session.scope).put("windowId",id).put("expiresAtMillis",expiry)
        SyncV2CloudClient.window(result)
        if(!result.isNull("checkpointOid"))SyncV2Wire.oid(result.getString("checkpointOid"),"commit")
        val refs=result.getJSONArray("refs");if(refs.length()>64)SyncV2Codec.fail("invalid_schema")
        var prior=""
        for(i in 0 until refs.length()) {
            val row=refs.getJSONObject(i);SyncV2Codec.exact(row,setOf("deviceId","commitOid","refRevision"))
            val device=SyncV2LocalProtocol.id(row.getString("deviceId"));if(device<=prior)SyncV2Codec.fail("invalid_schema");prior=device
            SyncV2Wire.oid(row.getString("commitOid"),"commit");if(SyncV2Codec.d64(row.getString("refRevision")).signum()==0)SyncV2Codec.fail("invalid_schema")
        }
        return result
    }
    private fun window(body:JSONObject) {
        session.requireFraming()
        if(SyncV2Codec.scope(body.getJSONObject("scope"))!=SyncV2Codec.scope(session.scope))SyncV2Codec.fail("scope_mismatch")
        val id=body.opt("windowId") as? String?:SyncV2Codec.fail("window_expired")
        if(!Regex("[0-9a-f]{32}").matches(id))SyncV2Codec.fail("window_expired")
        if((windows[id]?:0)<=now())SyncV2Codec.fail("window_expired")
    }
    private fun scope()=JSONObject().put("scope",session.scope)
    fun localReceipt(body:JSONObject) {
        session.requireFraming();SyncV2Codec.exact(body,setOf("kind","oid","durableOffset","totalBytes"))
        if(body.get("kind")!="local")SyncV2Codec.fail("invalid_schema")
        val oid=SyncV2Wire.oid(body.getString("oid"));val total=SyncV2Wire.uint(body,"totalBytes",266264)
        if(total==0L || SyncV2Wire.uint(body,"durableOffset",total)>total)SyncV2Codec.fail("invalid_schema")
        store.save("peer-local-receipt/${session.peerDeviceId}/$oid",boundReceipt(body))
    }
    fun complete(body:JSONObject):JSONObject {
        session.requireFraming();SyncV2Codec.exact(body,setOf("scope","transferId","oid","totalBytes"))
        if(SyncV2Codec.scope(body.getJSONObject("scope"))!=SyncV2Codec.scope(session.scope))SyncV2Codec.fail("scope_mismatch")
        val id=SyncV2LocalProtocol.id(body.getString("transferId"));val oid=SyncV2Wire.oid(body.getString("oid"));val total=SyncV2Wire.uint(body,"totalBytes",266264).toInt()
        if(transfers[id]!=(oid to total))SyncV2Codec.fail("invalid_schema")
        val existing=try{store.objectSize(oid)}catch(e:IllegalStateException){if(e.message!="object_missing")throw e;null}
        if(existing!=null && existing!=total)SyncV2Codec.fail("part_conflict")
        if(existing==null)store.finish(oid,total,verifyCommit)
        receipt(oid,total,total,false)
        return JSONObject().put("kind","local").put("oid",oid).put("durableOffset",total).put("totalBytes",total)
    }
    /** Server-owned page tokens bind window/root/known set. No unbounded full-tree walk. */
    fun treeDiff(body:JSONObject):JSONObject {
        window(body);SyncV2Codec.exact(body,setOf("scope","windowId","treeRoot","knownNodes","pageToken"))
        val root=SyncV2Wire.oid(body.getString("treeRoot"),"tree");val known=SyncV2Wire.strings(body.getJSONArray("knownNodes"),512).also{it.forEach{oid->SyncV2Wire.oid(oid,"tree")}}
        val window=body.getString("windowId")
        val token=if(body.isNull("pageToken"))null else body.getString("pageToken")
        val page=token?.let{pages[it]?:SyncV2Codec.fail("invalid_schema")}
        if(page!=null && (page.window!=window || page.root!=root || page.known!=known))SyncV2Codec.fail("invalid_schema")
        val todo=java.util.ArrayDeque(page?.todo?:listOf(root));val nodes=JSONArray();val seen=mutableSetOf<String>()
        var visits=0
        while(todo.isNotEmpty() && visits++<64) {
            val oid=todo.removeFirst();if(!seen.add(oid))SyncV2Codec.fail("invalid_schema")
            if(oid in known)continue
            val raw=store.objectBytes(oid)
            if(oid!="tree:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(raw))}")SyncV2Codec.fail("hash_mismatch")
            val tree=SyncV2Wire.parse(raw,262144,true)
            if(SyncV2Codec.scope(tree.getJSONObject("scope"))!=SyncV2Codec.scope(session.scope))SyncV2Codec.fail("scope_mismatch")
            nodes.put(oid)
            if(tree.getString("form")=="node") {
                val children=tree.getJSONArray("children");if(children.length() !in 1..16)SyncV2Codec.fail("invalid_schema")
                for(i in 0 until children.length())todo.addLast(SyncV2Wire.oid(children.getJSONObject(i).getString("oid"),"tree"))
            }else if(tree.getString("form")!="leaf")SyncV2Codec.fail("invalid_schema")
        }
        val next=if(todo.isEmpty())null else SyncV2Codec.hex(SyncV2Codec.random(16)).also{id->
            if(pages.size>=32)SyncV2Codec.fail("window_limit");pages[id]=Page(window,root,known,todo.toList())}
        return JSONObject().put("windowId",window).put("nodes",JSONArray((0 until nodes.length()).map{nodes.getString(it)}.sorted())).put("pageToken",next?:JSONObject.NULL)
    }
    /** Local receive receipts are not SignedAvailability or a custody authorization. */
    private fun receipt(oid:String,total:Int,offset:Int,persist:Boolean=true):JSONObject {
        val value=JSONObject().put("kind","local").put("oid",oid).put("totalBytes",total).put("durableOffset",offset)
        if(persist)store.save("local-receipt/${session.peerDeviceId}/$oid",boundReceipt(value))
        return value
    }
    /** C30: context is durable internal metadata, never part of the local wire receipt. */
    private fun boundReceipt(value:JSONObject)=scope().put("sessionId",session.sessionId)
        .put("holderDeviceId",session.selfDeviceId).put("peerDeviceId",session.peerDeviceId).put("receipt",value)
    fun haveWant(body:JSONObject):JSONObject {
        window(body);SyncV2Codec.exact(body,setOf("scope","windowId","oids"))
        val rows=JSONArray();for(oid in SyncV2Wire.strings(body.getJSONArray("oids"),512)) {
            SyncV2Wire.oid(oid)
            val size=try{store.objectSize(oid)}catch(e:IllegalStateException){if(e.message!="object_missing")throw e;null}
            val partial=store.localTotal(oid)
            if(size!=null)rows.put(JSONObject().put("oid",oid).put("status","present").put("totalBytes",size).put("durableOffset",size).put("receipt",receipt(oid,size,size)))
            else if(partial!=null) {
                val total=partial;val offset=store.prefix(oid,total)
                // C8: complete final durable prefix before reporting present after ACK loss.
                if(offset==total)store.finish(oid,total,verifyCommit)
                rows.put(JSONObject().put("oid",oid).put("status",if(offset==total)"present" else "partial").put("totalBytes",total).put("durableOffset",offset).put("receipt",receipt(oid,total,offset)))
            }else rows.put(JSONObject().put("oid",oid).put("status","missing").put("totalBytes",JSONObject.NULL).put("durableOffset",0).put("receipt",JSONObject.NULL))
        }
        return scope().put("windowId",body.get("windowId")).put("objects",rows)
    }
    /** Immutable mappings are registered only after the parent has been decrypted and matched. */
    @Synchronized fun registerRefs(identity:SyncV2Identity,authors:SyncV2Authors,parent:String,oids:JSONArray,mapping:JSONObject,raw:ByteArray) {
        session.requireFraming();SyncV2ContentCodec.verifyRefs(identity,authors,parent,oids,mapping,raw)
        val plain=SyncV2Wire.parse(SyncV2ContentCodec.openStored(identity,store,parent,contentKey?:SyncV2Codec.fail("authentication_required")),262144,true)
        val actual=JSONArray()
        if(parent.startsWith("recipe:")) {
            val blocks=SyncV2ContentCodec.recipe(plain).getJSONArray("blocks")
            for(i in 0 until blocks.length())actual.put(blocks.getJSONObject(i).getString("oid"))
        }else {
            // C7 keeps note-segments disabled; a state must contain the authenticated empty table.
            val segments=plain.getJSONArray("noteSegments")
            if(segments.length()!=0)SyncV2Codec.fail("unsupported_feature")
        }
        if(SyncV2Codec.canonical(actual)!=SyncV2Codec.canonical(oids))SyncV2Codec.fail("invalid_schema")
        val key="local-refs/$parent";val old=store.load(key)
        if(old!=null && SyncV2Codec.canonical(old)!=SyncV2Codec.canonical(mapping))SyncV2Codec.fail("idempotency_conflict")
        store.putObject(mapping.getString("refsOid"),raw)
        if(old==null)store.save(key,mapping)
    }
    fun reference(action:String,body:JSONObject):JSONObject {
        window(body);SyncV2Codec.exact(body,setOf("scope","windowId","parentOid"))
        val type=when(action){"syncObjectsV2.stateRefsGet"->"state";"syncObjectsV2.recipeRefsGet"->"recipe";else->SyncV2Codec.fail("unknown_action")}
        val parent=SyncV2Wire.oid(SyncV2Wire.string(body,"parentOid"),type)
        val mapping=store.load("local-refs/$parent")?:SyncV2Codec.fail("object_missing")
        store.objectSize(mapping.getString("refsOid"));return mapping
    }
    data class Read(val body:JSONObject,val oid:String,val total:Int,val start:Int,val end:Int,val transferId:String)
    fun objectGet(body:JSONObject):Read {
        window(body);SyncV2Codec.exact(body,setOf("scope","windowId","oid","range"))
        val oid=SyncV2Wire.oid(SyncV2Wire.string(body,"oid"));val total=store.objectSize(oid)
        val range=body.optJSONObject("range");var start=0;var end=total-1
        if(!body.isNull("range")) {
            if(range==null)SyncV2Codec.fail("invalid_range")
            if(range.keys().asSequence().toSet()!=setOf("start","end"))SyncV2Codec.fail("invalid_range")
            try {start=SyncV2Wire.uint(range,"start",266264).toInt();end=SyncV2Wire.uint(range,"end",266264).toInt()}catch(_:IllegalStateException){SyncV2Codec.fail("invalid_range")}
        }
        if(start>end || end>=total)SyncV2Codec.fail("invalid_range")
        val transfer=SyncV2Codec.hex(SyncV2Codec.random(16))
        return Read(scope().put("oid",oid).put("totalBytes",total).put("start",start).put("end",end).put("transferId",transfer),oid,total,start,end,transfer)
    }
    fun send(read:Read,out:OutputStream) {
        var at=read.start
        while(at<=read.end){session.requireFraming();val part=store.objectPart(read.oid,at,minOf(65536,read.end-at+1));SyncV2Wrp2.write(out,session,read.oid,read.total,at,read.transferId,part);at+=part.size;Thread.yield()}
    }
    /** The expected transfer comes from this session's objectGet response, never its header. */
    fun receive(input:InputStream,expected:Read):Int {
        session.requireFraming();val frame=SyncV2Wrp2.read(input,session);return receive(frame,expected)
    }
    fun receive(frame:SyncV2Wrp2.Frame,expected:Read):Int {
        session.requireFraming();val h=frame.header
        // Public Frame constructors are not authentication evidence. Revalidate the complete wire.
        SyncV2Wrp2.validate(session,h,frame.payload)
        val offset=h.getInt("offset")
        if(h.get("oid")!=expected.oid || h.getInt("totalBytes")!=expected.total || h.get("transferId")!=expected.transferId || offset<expected.start || offset+frame.payload.size-1>expected.end)SyncV2Codec.fail("invalid_range")
        store.append(expected.oid,expected.total,offset,frame.payload)
        if(offset+frame.payload.size==expected.total)store.finish(expected.oid,expected.total,verifyCommit)
        receipt(expected.oid,expected.total,offset+frame.payload.size);return frame.payload.size
    }
    /** putPart transfers exactly one fine frame. Duplicate bytes must match the durable prefix. */
    @Synchronized fun putPart(frame:SyncV2Wrp2.Frame):JSONObject {
        session.requireFraming();val h=frame.header;val oid=SyncV2Wire.oid(h.getString("oid"));val total=h.getInt("totalBytes");val offset=h.getInt("offset")
        if(h.get("sessionId")!=session.sessionId || SyncV2Codec.scope(h.getJSONObject("scope"))!=SyncV2Codec.scope(session.scope))SyncV2Codec.fail("proof_invalid")
        SyncV2Wrp2.validate(session,h,frame.payload)
        val transfer=h.getString("transferId")
        if(transfers[transfer]?.let{it!=(oid to total)}==true)SyncV2Codec.fail("part_conflict")
        if(offset%65536!=0 || frame.payload.size!=minOf(65536,total-offset))SyncV2Codec.fail("offset_conflict")
        if(transfers.size>=512 && transfer !in transfers)SyncV2Codec.fail("window_limit")
        transfers[transfer]=oid to total
        val prior=store.localTotal(oid)
        if(prior!=null && prior!=total)SyncV2Codec.fail("part_conflict")
        val size=try{store.objectSize(oid)}catch(e:IllegalStateException){if(e.message!="object_missing")throw e;null}
        val prefix=if(size!=null)size else store.prefix(oid,total)
        if(offset<prefix) {
            if(offset+frame.payload.size>prefix)SyncV2Codec.fail("part_conflict")
            val bytes=if(size!=null)store.objectPart(oid,offset,frame.payload.size) else store.partialBytes(oid,offset,frame.payload.size)
            if(!bytes.contentEquals(frame.payload))SyncV2Codec.fail("part_conflict")
        }else store.appendLocal(oid,total,offset,frame.payload,boundReceipt(receipt(oid,total,offset+frame.payload.size,false)))
        val end=maxOf(prefix,offset+frame.payload.size)
        if(end==total && size==null)store.finish(oid,total,verifyCommit)
        return receipt(oid,total,end,offset<prefix)
    }
}
