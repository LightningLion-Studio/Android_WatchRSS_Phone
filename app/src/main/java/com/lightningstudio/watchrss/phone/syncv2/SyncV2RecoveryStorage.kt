package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** N07 uses the existing recovery envelope and the SAME SQLite stateVersion commit.
 * This depot owns only local encrypted recovery bytes. It never uploads them or deletes originals. */
internal class SyncV2RecoveryStorage(private val projection:SyncV2Projection,private val identity:SyncV2Identity,
    private val files:File,private val quotaBytes:Long=32L*1024*1024) {
    init {if(quotaBytes !in 1L..64L*1024*1024)SyncV2Codec.fail("invalid_schema")}
    private val db get()=projection.accountDatabase
    private val scope get()=projection.scope
    data class Bundle(val copy:JSONObject,val pin:JSONObject,val objects:Map<String,ByteArray>)
    private interface Source {val size:Long;fun read(offset:Long,n:Int):ByteArray}
    private fun byteSource(bytes:ByteArray)=object:Source {override val size=bytes.size.toLong();override fun read(offset:Long,n:Int)=bytes.copyOfRange(offset.toInt(),offset.toInt()+n)}
    private fun fileSource(file:File,expected:String?=null,maxBytes:Long?=null):Source {
        if(!file.isFile)SyncV2Codec.fail("closure_incomplete")
        val size=file.length();val modified=file.lastModified();if(maxBytes!=null && size>maxBytes)SyncV2Codec.fail("quota_exceeded")
        if(expected!=null && file.inputStream().use{input->val d=MessageDigest.getInstance("SHA-256");val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;d.update(b,0,n)};SyncV2Codec.hex(d.digest())}!=expected)SyncV2Codec.fail("closure_incomplete")
        return object:Source {override val size=size;override fun read(offset:Long,n:Int):ByteArray {
            if(file.length()!=size || file.lastModified()!=modified)SyncV2Codec.fail("choice_stale")
            return ByteArray(n).also{out->RandomAccessFile(file,"r").use{it.seek(offset);it.readFully(out)}}
        }}
    }
    private fun sources(record:JSONObject,maxBytes:Long?=null):List<Pair<String,Source>> {
        val m=record.getJSONObject("meta");val id=m.getString("logicalId");val value=record.getJSONObject("value")
        if(SyncV2Business.logicalId(m.getString("kind"),value)!=id || identity.entity(m.getString("kind"),id)!=m.getString("entityKey"))SyncV2Codec.fail("identity_conflict")
        return when(m.getString("kind")) {
            "article"->{
                // CursorWindow must never hold a whole imported body. Read bounded BLOB slices,
                // then preserve both original text fields in the private recovery envelope.
                val lengths=db.query("SELECT length(CAST(contentText AS BLOB)),length(CAST(contentHtml AS BLOB)),contentHash FROM phone_articles WHERE articleId=?",arrayOf(id)).use{c->
                    if(!c.moveToFirst() || value.optString("contentHash")!=c.getString(2))SyncV2Codec.fail("closure_incomplete")
                    c.getLong(0) to if(c.isNull(1))null else c.getLong(1)
                }
                if(lengths.first+(lengths.second?:0)>quotaBytes)SyncV2Codec.fail("quota_exceeded")
                fun text(column:String,length:Long):String {
                    val out=java.io.ByteArrayOutputStream();var offset=0L
                    while(offset<length) {
                        val n=minOf(65536L,length-offset).toInt()
                        val bytes=db.query("SELECT substr(CAST($column AS BLOB),?,?) FROM phone_articles WHERE articleId=?",arrayOf<Any>(offset+1,n,id)).use{c->if(!c.moveToFirst() || c.isNull(0))SyncV2Codec.fail("choice_stale");c.getBlob(0)}
                        if(bytes.size!=n)SyncV2Codec.fail("choice_stale");out.write(bytes);offset+=n
                    }
                    return out.toByteArray().toString(Charsets.UTF_8)
                }
                if(lengths.first==0L && (lengths.second?:0)==0L && value.optString("contentHash").isNotEmpty())SyncV2Codec.fail("closure_incomplete")
                val bytes=SyncV2Codec.utf8(SyncV2Codec.canonical(JSONObject().put("contentText",text("contentText",lengths.first)).put("contentHtml",lengths.second?.let{text("contentHtml",it)}?:JSONObject.NULL)))
                listOf("article" to byteSource(bytes))
            }
            "book"->db.query("SELECT packageSha256,packageBytes FROM imported_books WHERE bookId=?",arrayOf(id)).use{c->
                if(!c.moveToFirst() || c.getString(0).isEmpty())SyncV2Codec.fail("closure_incomplete")
                val f=File(File(files,"imported_books"),"$id.jsonl")
                if(f.length()!=c.getLong(1))SyncV2Codec.fail("closure_incomplete")
                listOf("book" to fileSource(f,c.getString(0),maxBytes))
            }
            "asset"->db.query("SELECT storageKey,sha256 FROM note_assets WHERE assetId=?",arrayOf(id)).use{c->
                if(!c.moveToFirst())SyncV2Codec.fail("closure_incomplete")
                val name=c.getString(0);if(!com.lightningstudio.watchrss.phone.connection.bluetooth.NoteAssetSyncPayload.isSafeStorageKey(name))SyncV2Codec.fail("closure_incomplete")
                listOf("asset/$name" to fileSource(File(files,"notes/assets/$name"),c.getString(1),maxBytes))
            }
            "note"->{
                val texts=mutableListOf(value.getString("markdown"),value.getString("baseMarkdown"));val conflicts=value.getJSONObject("noteConflicts").getJSONArray("records")
                for(i in 0 until conflicts.length())for(field in listOf("baseMarkdown","localMarkdown","remoteMarkdown"))texts.add(conflicts.getJSONObject(i).getString(field))
                val keys=texts.flatMap{com.lightningstudio.watchrss.phone.connection.bluetooth.NoteAssetSyncPayload.referencedStorageKeys(it)}.toSortedSet()
                keys.map {name->
                    val hashes=db.query("SELECT sha256 FROM note_assets WHERE storageKey=?",arrayOf(name)).use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}
                    if(hashes.size!=1)SyncV2Codec.fail("closure_incomplete")
                    "asset/$name" to fileSource(File(files,"notes/assets/$name"),hashes.single(),maxBytes)
                }
            }
            else->emptyList()
        }
    }
    /** This is a conservative loss fence, not a remote-copy or complete-closure proof. */
    fun hasOriginalSource(record:JSONObject):Boolean {
        val m=record.getJSONObject("meta");val id=m.getString("logicalId")
        return when(m.getString("kind")) {
            "article"->db.query("SELECT length(CAST(contentText AS BLOB))+COALESCE(length(CAST(contentHtml AS BLOB)),0) FROM phone_articles WHERE articleId=?",arrayOf(id)).use{it.moveToFirst() && it.getLong(0)>0}
            "book"->File(files,"imported_books/$id.jsonl").isFile
            "asset"->db.query("SELECT storageKey FROM note_assets WHERE assetId=?",arrayOf(id)).use{c->c.moveToFirst() && com.lightningstudio.watchrss.phone.connection.bluetooth.NoteAssetSyncPayload.isSafeStorageKey(c.getString(0)) && File(files,"notes/assets/${c.getString(0)}").isFile}
            else->false
        }
    }
    fun contentReady(record:JSONObject):Boolean=try {sources(record);true}catch(_:Exception){false}
    fun prepare(local:List<JSONObject>,cloud:List<JSONObject>,base:JSONObject,captured:Set<String>,revision:String,accountOid:String,now:Long):Bundle {
        val localValues=JSONObject();val cloudValues=JSONObject();val refs=JSONObject();val records=local.associateBy{it.getJSONObject("meta").getString("entityKey")}
        for((id,r)in records)localValues.put(id,JSONObject().put("meta",r.getJSONObject("meta")).put("state",r.getJSONObject("state")))
        for(r in cloud)cloudValues.put(r.getJSONObject("meta").getString("entityKey"),JSONObject().put("meta",r.getJSONObject("meta")).put("state",r.getJSONObject("state")))
        val objects=linkedMapOf<String,ByteArray>();var size=0L
        val key=projection.recoveryKey()
        fun add(oid:String,bytes:ByteArray){if(oid !in objects){size=Math.addExact(size,bytes.size.toLong());if(size>quotaBytes)SyncV2Codec.fail("quota_exceeded");objects[oid]=bytes}}
        try {
            for(id in captured.sorted()) {
                val record=records[id]?:continue
                if(SyncV2Codec.canonical(localValues.opt(id))==SyncV2Codec.canonical(cloudValues.opt(id)))continue
                val oids=sortedSetOf<String>()
                for((part,source)in sources(record,quotaBytes-size)) {
                    if(source.size>quotaBytes-size)SyncV2Codec.fail("quota_exceeded")
                    val blocks=JSONArray();val digest=MessageDigest.getInstance("SHA-256");var offset=0L
                    while(offset<source.size) {
                        val bytes=source.read(offset,minOf(262144L,source.size-offset).toInt());digest.update(bytes)
                        val sealed=SyncV2Envelope.seal(identity,key,1,"blob",bytes);add(sealed.oid,sealed.bytes);oids.add(sealed.oid)
                        blocks.put(JSONObject().put("index",blocks.length()).put("oid",sealed.oid).put("plainBytes",bytes.size).put("opaqueContentId",identity.opaque("blob",bytes)));offset+=bytes.size
                    }
                    val hash=digest.digest();val recipe=JSONObject().put("version",2).put("entityKey",id).put("bodyRevision",SyncV2Codec.hex(SyncV2Codec.random(16)))
                        .put("opaqueContentId",identity.opaqueDigest("recipe",hash)).put("plaintextSha256",SyncV2Codec.hex(hash)).put("totalPlainBytes",source.size).put("blocks",blocks)
                        .put("ranges",JSONArray().put(JSONObject().put("logicalPart",part).put("offset",0).put("length",source.size)))
                    SyncV2ContentCodec.recipe(recipe)
                    val sealed=SyncV2Envelope.seal(identity,key,1,"recipe",SyncV2Codec.utf8(SyncV2Codec.canonical(recipe)));add(sealed.oid,sealed.bytes);oids.add(sealed.oid)
                }
                refs.put(id,JSONArray(oids.toList()))
            }
            val delta=SyncV2Recovery.delta(localValues,cloudValues,base,captured,refs)
            val existing=descriptors().map{it.getString("copyId")}.toSet()
            val copy=SyncV2Recovery.prepare(delta,key,now,revision,accountOid,existing)
            val pin=SyncV2Recovery.pin(copy,delta)
            if(size+SyncV2Codec.utf8(SyncV2Codec.canonical(copy)).size>quotaBytes)SyncV2Codec.fail("quota_exceeded")
            SyncV2Recovery.open(copy,key) // Authenticate prepared ciphertext before it becomes a reference.
            return Bundle(copy,pin,objects).also{checkQuota(it)}
        } finally {key.fill(0)}
    }
    private fun descriptors():List<JSONObject> = db.query("SELECT body FROM syncv2_recovery_copies WHERE scope=? ORDER BY copy_id",arrayOf(scope)).use{c->buildList{while(c.moveToNext())add(projection.accountOpen("recovery-copy-head",c.getBlob(0)))}}
    fun headers():List<JSONObject> = descriptors().map{it.getJSONObject("header")}
    private fun descriptor(bytes:ByteArray)=JSONObject().put("bytes",bytes.size).put("sha256",SyncV2Codec.hex(SyncV2Codec.sha(bytes)))
    private fun chunks(copyId:String,kind:String,expected:JSONObject):ByteArray {
        val out=java.io.ByteArrayOutputStream();var index=0
        db.query("SELECT ordinal,bytes FROM syncv2_recovery_chunks WHERE scope=? AND copy_id=? AND kind=? ORDER BY ordinal",arrayOf(scope,copyId,kind)).use{c->while(c.moveToNext()) {
            if(c.getInt(0)!=index++)SyncV2Codec.fail("closure_incomplete")
            val bytes=c.getBlob(1);if(bytes.size !in 1..65536 || out.size().toLong()+bytes.size>expected.getLong("bytes"))SyncV2Codec.fail("closure_incomplete");out.write(bytes)
        }}
        val bytes=out.toByteArray();if(bytes.size!=expected.getInt("bytes") || SyncV2Codec.hex(SyncV2Codec.sha(bytes))!=expected.getString("sha256"))SyncV2Codec.fail("hash_mismatch")
        return bytes
    }
    fun pins():List<JSONObject> = db.query("SELECT copy_id,pin FROM syncv2_recovery_copies WHERE scope=? ORDER BY copy_id",arrayOf(scope)).use{c->buildList{while(c.moveToNext()) {
        val id=c.getString(0);val head=projection.accountOpen("recovery-pin-head",c.getBlob(1));add(projection.accountOpen("recovery-pin",chunks(id,"pin",head.getJSONObject("payload"))))
    }}}
    private fun heads(copyId:String):Pair<ByteArray,ByteArray> = db.query("SELECT body,pin FROM syncv2_recovery_copies WHERE scope=? AND copy_id=?",arrayOf(scope,copyId)).use{c->
        if(!c.moveToFirst())SyncV2Codec.fail("snapshot_expired");c.getBlob(0) to c.getBlob(1)
    }
    fun open(copyId:String):JSONObject {
        SyncV2Codec.unhex(copyId,16);val head=projection.accountOpen("recovery-copy-head",heads(copyId).first)
        if(head.getString("copyId")!=copyId)SyncV2Codec.fail("closure_incomplete")
        val copy=JSONObject().put("copyId",copyId).put("header",head.getJSONObject("header")).put("nonce",head.getString("nonce")).put("ciphertext",SyncV2Codec.b64(chunks(copyId,"ciphertext",head.getJSONObject("payload"))))
        val key=projection.recoveryKey();try{return SyncV2Recovery.open(copy,key)}finally{key.fill(0)}
    }
    /** No file IO here. SQLite's commit protects the ciphertext, pin and active business state. */
    private fun checkQuota(bundle:Bundle)=checkBundles(listOf(bundle))
    fun checkBundles(bundles:List<Bundle>) {
        if(bundles.map{it.copy.getString("copyId")}.distinct().size!=bundles.size)SyncV2Codec.fail("identity_conflict")
        val used=db.query("SELECT COALESCE(SUM(length(body)+length(pin)),0) FROM syncv2_recovery_copies WHERE scope=?",arrayOf(scope)).use{it.moveToFirst();it.getLong(0)}+
            db.query("SELECT COALESCE(SUM(length(body)),0) FROM syncv2_recovery_objects WHERE scope=?",arrayOf(scope)).use{it.moveToFirst();it.getLong(0)}+
            db.query("SELECT COALESCE(SUM(length(bytes)),0) FROM syncv2_recovery_chunks WHERE scope=?",arrayOf(scope)).use{it.moveToFirst();it.getLong(0)}
        var required=used;val unique=linkedMapOf<String,ByteArray>()
        for(bundle in bundles) {
            for((oid,bytes)in bundle.objects) {
                if(unique[oid]?.contentEquals(bytes)==false)SyncV2Codec.fail("idempotency_conflict")
                unique[oid]=bytes
            }
            required=Math.addExact(required,SyncV2Codec.decode64(bundle.copy.getString("ciphertext")).size.toLong()+projection.accountSeal("recovery-pin",bundle.pin).size+4096)
        }
        for((oid,bytes)in unique)if(!db.query("SELECT 1 FROM syncv2_recovery_objects WHERE scope=? AND oid=?",arrayOf(scope,oid)).use{it.moveToFirst()})required=Math.addExact(required,bytes.size.toLong())
        if(required>quotaBytes)SyncV2Codec.fail("quota_exceeded")
    }
    fun commit(bundle:Bundle) {
        check(db.inTransaction());checkQuota(bundle)
        val cipher=SyncV2Codec.decode64(bundle.copy.getString("ciphertext"));val pinBytes=projection.accountSeal("recovery-pin",bundle.pin);val copyId=bundle.copy.getString("copyId")
        val body=projection.accountSeal("recovery-copy-head",JSONObject().put("copyId",copyId).put("header",bundle.copy.getJSONObject("header")).put("nonce",bundle.copy.getString("nonce")).put("payload",descriptor(cipher)))
        val pin=projection.accountSeal("recovery-pin-head",JSONObject().put("copyId",copyId).put("payload",descriptor(pinBytes)))
        val key=projection.recoveryKey();try {
            val delta=SyncV2Recovery.open(bundle.copy,key)
            if(SyncV2Codec.canonical(SyncV2Recovery.pin(bundle.copy,delta))!=SyncV2Codec.canonical(bundle.pin))SyncV2Codec.fail("closure_incomplete")
            for(oid in SyncV2Wire.strings(delta.getJSONArray("bodyRefs"),100000,false)) {
                val bytes=bundle.objects[oid]?:SyncV2Codec.fail("closure_incomplete")
                SyncV2Envelope.open(identity,key,oid,bytes,1)
                db.execSQL("INSERT OR IGNORE INTO syncv2_recovery_objects VALUES(?,?,?)",arrayOf(scope,oid,bytes))
            }
        } finally {key.fill(0)}
        for((kind,bytes)in listOf("ciphertext" to cipher,"pin" to pinBytes))for(offset in bytes.indices step 65536)
            db.execSQL("INSERT INTO syncv2_recovery_chunks VALUES(?,?,?,?,?)",arrayOf(scope,copyId,kind,offset/65536,bytes.copyOfRange(offset,minOf(offset+65536,bytes.size))))
        db.execSQL("INSERT INTO syncv2_recovery_copies VALUES(?,?,?,?)",arrayOf(scope,copyId,body,pin))
    }
    /** Explicit recovery reads yield necessary original bytes; callers create NEW local edits.
     * These bytes never update a cloud ref, and a recipe from another copy cannot be substituted. */
    private data class ReadSession(val headHash:String,val recipe:JSONObject,val refs:Set<String>)
    private val readSessions=object:LinkedHashMap<String,ReadSession>(8,0.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,ReadSession>?)=size>8
    }
    fun readPart(copyId:String,recipeOid:String,offset:Long,maxBytes:Int):ByteArray {
        if(offset<0 || maxBytes !in 1..65536)SyncV2Codec.fail("invalid_range")
        SyncV2Codec.unhex(copyId,16);SyncV2Wire.oid(recipeOid,"recipe")
        // Durable ownership is checked on EVERY slice. A session cannot survive deletion,
        // replacement, another copy's pin or a modified authenticated descriptor.
        val heads=heads(copyId);val head=projection.accountOpen("recovery-copy-head",heads.first)
        val pinHead=projection.accountOpen("recovery-pin-head",heads.second)
        if(head.getString("copyId")!=copyId)SyncV2Codec.fail("closure_incomplete")
        val headHash=SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.lp(heads.first,heads.second)))
        val sessionId="$copyId/$recipeOid"
        fun raw(oid:String)=db.query("SELECT body FROM syncv2_recovery_objects WHERE scope=? AND oid=?",arrayOf(scope,oid)).use{if(it.moveToFirst())it.getBlob(0)else SyncV2Codec.fail("closure_incomplete")}
        val key=projection.recoveryKey()
        try {
            fun block(recipeBlock:JSONObject,refs:Set<String>):ByteArray {
                val oid=recipeBlock.getString("oid");if(oid !in refs)SyncV2Codec.fail("closure_incomplete")
                val bytes=SyncV2Envelope.open(identity,key,oid,raw(oid),1)
                if(bytes.size!=recipeBlock.getInt("plainBytes") || identity.opaque("blob",bytes)!=recipeBlock.getString("opaqueContentId"))SyncV2Codec.fail("fingerprint_mismatch")
                return bytes
            }
            val cached=synchronized(readSessions){readSessions[sessionId]}
            val session=if(cached?.headHash==headHash)cached else {
                val delta=open(copyId);val refs=SyncV2Wire.strings(delta.getJSONArray("bodyRefs"),100000,false).toSet()
                val pin=projection.accountOpen("recovery-pin",chunks(copyId,"pin",pinHead.getJSONObject("payload")))
                SyncV2Codec.exact(pin,setOf("copyId","bodyRefs","expiresAtMillis"))
                if(pin.getString("copyId")!=copyId || pin.getLong("expiresAtMillis")!=head.getJSONObject("header").getLong("expiresAtMillis") ||
                    SyncV2Codec.canonical(pin.getJSONArray("bodyRefs"))!=SyncV2Codec.canonical(delta.getJSONArray("bodyRefs")) || recipeOid !in refs)SyncV2Codec.fail("closure_incomplete")
                val recipe=SyncV2Wire.parse(SyncV2Envelope.open(identity,key,recipeOid,raw(recipeOid),1),262144,true)
                SyncV2ContentCodec.verifyBody(identity,recipe,{block(it,refs)})
                ReadSession(headHash,recipe,refs).also{synchronized(readSessions){readSessions[sessionId]=it}}
            }
            val recipe=session.recipe;val total=recipe.getLong("totalPlainBytes")
            if(offset>total)SyncV2Codec.fail("invalid_range")
            val out=java.io.ByteArrayOutputStream(maxBytes);var position=0L;val blocks=recipe.getJSONArray("blocks")
            for(i in 0 until blocks.length()) {
                val entry=blocks.getJSONObject(i);val end=position+entry.getLong("plainBytes")
                if(end>offset && position<offset+maxBytes) {
                    // Previously verified full-body hashes authorize the immutable recipe;
                    // each returned block still passes its own AEAD and opaque-ID checks.
                    val bytes=block(entry,session.refs);val begin=maxOf(offset-position,0).toInt()
                    val count=minOf(bytes.size-begin,maxBytes-out.size());if(count>0)out.write(bytes,begin,count)
                }
                position=end;if(out.size()==maxBytes)break
            }
            return out.toByteArray()
        } finally {key.fill(0)}
    }
    fun purge(trustedNow:Long?) {
        if(trustedNow==null)return
        val st=projection.state();val old=st.body.optLong("recoveryTrustedNow",0)
        if(trustedNow<old || trustedNow<0)return
        val copies=descriptors();val pins=pins();val expired=SyncV2Recovery.expiredPlan(JSONArray(copies),JSONArray(pins),trustedNow,old)
        if(expired.isEmpty() && trustedNow==old)return
        projection.accountWriter(st.version){current->
            for(id in expired) {
                db.execSQL("DELETE FROM syncv2_recovery_copies WHERE scope=? AND copy_id=?",arrayOf(scope,id))
                db.execSQL("DELETE FROM syncv2_recovery_chunks WHERE scope=? AND copy_id=?",arrayOf(scope,id))
            }
            val live=this.pins().flatMap{SyncV2Wire.strings(it.getJSONArray("bodyRefs"),100000,false)}.toSet()
            val candidates=pins.filter{it.getString("copyId") in expired}.flatMap{SyncV2Wire.strings(it.getJSONArray("bodyRefs"),100000,false)}.toSet()
            // The depot contains private recovery envelopes, never shared cloud/cache/original files.
            for(oid in candidates-live)db.execSQL("DELETE FROM syncv2_recovery_objects WHERE scope=? AND oid=?",arrayOf(scope,oid))
            current.body.put("recoveryTrustedNow",trustedNow)
        }
    }
}
