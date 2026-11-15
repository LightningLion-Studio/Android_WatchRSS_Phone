package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONObject
import java.io.File

/** Runs after metadata. Originals and exact signed ciphertext form a restart-safe offline queue. */
internal class SyncV2BodyChannel(private val projection:SyncV2Projection,private val identity:SyncV2Identity,
    private val keys:SyncV2ContentKeys,private val signer:SyncV2Signing,private val client:SyncV2CloudClient,
    private val repository:SyncV2BodyRepository,private val root:File,private val life:SyncV2Lifetime,
    private val approved:(String)->Boolean,private val signals:()->SyncV2TransportSignals,
    private val now:()->Long=System::currentTimeMillis) {
    data class Result(val pending:Int,val errors:List<String>)
    private val http=SyncV2BodyHttp(client,life::check)
    private fun authors(state:JSONObject=projection.state().body):SyncV2Authors=client.authors(identity,approved).also{a->
        val bindings=state.optJSONArray("signingBindings")?:SyncV2Codec.fail("authentication_required")
        for(i in 0 until bindings.length())a.restorePinned(bindings.getJSONObject(i))
    }
    private fun binding(entry:JSONObject):SyncV2BodyCodec.Binding {
        val m=entry.getJSONObject("meta");val r=entry.getJSONObject("row");val kind=m.getString("kind");val id=m.getString("logicalId")
        if(identity.entity(kind,id)!=r.getString("entityKey") || m.getString("entityKey")!=r.getString("entityKey"))SyncV2Codec.fail("identity_conflict")
        return SyncV2BodyCodec.Binding(kind,id,r.getString("entityKey"),r.getString("bodyRevision"))
    }
    private fun value(entry:JSONObject)=SyncV2Business.join(entry.getJSONObject("meta").getString("kind"),entry.getJSONObject("meta"),entry.getJSONObject("state"))
    /** Current accepted signed head -> Merkle path -> AEAD meta/state -> identity. */
    private fun current(entry:JSONObject,anchor:JSONObject,captured:SyncV2Projection.State?=null) {
        life.check();val st=captured?:projection.state();val account=st.body.optJSONObject("account")?:SyncV2Codec.fail("choice_stale")
        if(SyncV2Codec.canonical(anchor(account))!=SyncV2Codec.canonical(anchor) || st.body.optJSONObject("metadataAccount")?.getString("oid")!=account.getString("oid"))SyncV2Codec.fail("choice_stale")
        val header=projection.accountCatalog.header(account.getString("oid"))?.getJSONObject("header")
            ?:SyncV2Wire.parse(projection.accountObject(account.getString("oid")),262144,true)
        val body=authors(st.body).header(header)
        if(SyncV2Codec.oid("commit",body)!=account.getString("oid") || body.getString("treeRoot")!=st.body.getString("metadataTreeRoot"))SyncV2Codec.fail("hash_mismatch")
        val b=binding(entry);val row=SyncV2Index.find(identity.scope,body.getString("treeRoot"),b.entityKey,projection::accountObject)?:SyncV2Codec.fail("body_revision_changed")
        if(row.getBoolean("deleted") || SyncV2Codec.canonical(row)!=SyncV2Codec.canonical(entry.getJSONObject("row")))SyncV2Codec.fail("body_revision_changed")
        for((field,type)in listOf("metaOid" to "meta","stateOid" to "state")) {
            val oid=row.getString(field);val blob=projection.accountObject(oid)
            val length=java.nio.ByteBuffer.wrap(blob,4,4).int
            if(length !in 1..4096 || length+24>blob.size)SyncV2Codec.fail("invalid_encoding")
            val h=SyncV2Wire.parse(blob.copyOfRange(8,8+length),4096,true)
            val plain=SyncV2Envelope.open(identity,keys.read(h.getLong("keyVersion")),oid,blob)
            val decoded=try{SyncV2Wire.parse(plain,262144,true)}finally{plain.fill(0)}
            if(SyncV2Codec.canonical(decoded)!=SyncV2Codec.canonical(entry.getJSONObject(type)))SyncV2Codec.fail("hash_mismatch")
        }
        value(entry)
    }
    private fun anchor(account:JSONObject)=JSONObject().put("commitOid",account.getString("oid")).put("revision",account.getString("revision")).put("rollbackGeneration",account.getString("generation"))
    private fun candidates():List<Pair<String,String>> = projection.accountDatabase.controlQuery("SELECT kind,logical_id FROM syncv2_candidates WHERE scope=? AND kind IN ('asset','book') ORDER BY entity_key",arrayOf(projection.scope)).use{c->buildList{while(c.moveToNext())add(c.getString(0) to c.getString(1))}}
    fun refreshReferences():List<String> {projection.migrateBodyAssetCandidates();return repository.refreshReferences(now(),life::check)}
    /** Random old placeholders with originals must be republished even without metadata edits. */
    fun needsPublication():Boolean {
        for((kind,id)in candidates())try {
            val c=projection.candidate(kind,id)?:continue;val v=SyncV2Business.join(kind,c.first,c.second);if(v.optBoolean("deleted"))continue
            val raw=repository.read(kind,id)?:continue;repository.validate(kind,id,v,raw)
            val entity=identity.entity(kind,id);val remote=projection.cloudRows(setOf(entity)).firstOrNull()?:return true
            val r=remote.getJSONObject("row");if(!r.isNull("recipeOid"))continue
            // A changed remote business binding requires the existing account-choice flow.
            if(SyncV2Codec.canonical(remote.getJSONObject("meta"))!=SyncV2Codec.canonical(c.first) || SyncV2Codec.canonical(remote.getJSONObject("state"))!=SyncV2Codec.canonical(c.second))continue
            if(r.optString("bodyRevision")!=SyncV2BodyCodec.revision(kind,entity,SyncV2BodyCodec.hash(raw)))return true
        } catch(e:Exception){life.check()}
        return false
    }
    /** Before account publication, bind actual original bytes without creating P3 recipes. */
    fun bindPrepared() {
        for((kind,id)in candidates())try {
            life.check();val c=projection.candidate(kind,id)?:continue;val v=SyncV2Business.join(kind,c.first,c.second);if(v.optBoolean("deleted"))continue
            val entity=identity.entity(kind,id);val row=projection.wireContentRow(entity)?:continue;if(!row.isNull("recipeOid"))continue
            val raw=repository.read(kind,id)?:continue;repository.validate(kind,id,v,raw)
            projection.bindArticleRevision(row,SyncV2BodyCodec.revision(kind,entity,SyncV2BodyCodec.hash(raw)),independent=true)
        } catch(e:Exception){life.check()}
    }
    fun sync(manual:Boolean=false):Result {
        life.check();val st=projection.state();val account=st.body.optJSONObject("account")?:return Result(0,emptyList())
        if(!st.body.isNull("pendingChoice") || !st.body.isNull("pendingPublish"))return Result(0,emptyList())
        val globalRetry=File(root,"retry.json")
        if(globalRetry.isFile) {val r=SyncV2Wire.parse(globalRetry.readBytes(),4096,true)
            if(SyncV2Wire.uint(r,"until")>now())return Result(1,listOf(r.getString("code")))}
        val anchor=anchor(account);var after="";var pending=0;val errors=linkedSetOf<String>()
        while(true) {
            val page=projection.cloudRowsPage(after,16);if(page.isEmpty())break
            for(entry in page) {val m=entry.getJSONObject("meta");val row=entry.getJSONObject("row")
                if(m.getString("kind") !in setOf("book","asset") || row.getBoolean("deleted") || !row.isNull("recipeOid"))continue
                val b=binding(entry);val dir=File(root,"${b.entityKey}/${b.bodyRevision}")
                try {
                    val retry=File(dir,"retry.json");if(retry.isFile){val r=SyncV2Wire.parse(retry.readBytes(),4096,true);if(r.getLong("until")>now())throw SyncV2CloudError(r.getString("code"),true,r.getLong("until")-now())}
                    val route=signals();if(route.internet!=true || !manual&&route.wifi!=true)SyncV2Codec.fail("network_unavailable")
                    syncEntry(entry,anchor,dir)
                }catch(e:Exception) {
                    life.check();pending++;val code=code(e);errors.add(code)
                    val delay=(e as? SyncV2CloudError)?.retryAfterMillis
                    if(delay!=null&&delay>0) {
                        val retry=SyncV2Codec.utf8(SyncV2Codec.canonical(JSONObject().put("code",code).put("until",Math.addExact(now(),delay))))
                        SyncV2BodyHttp.atomic(File(dir,"retry.json"),retry)
                        SyncV2BodyHttp.atomic(globalRetry,retry)
                        return Result(pending,errors.toList())
                    }
                }
            }
            after=page.last().getJSONObject("row").getString("entityKey")
        }
        return Result(pending,errors.toList())
    }
    private fun syncEntry(entry:JSONObject,anchor:JSONObject,dir:File) {
        current(entry,anchor);val b=binding(entry);val v=value(entry);val local=repository.read(b.kind,b.logicalId)
        if(local!=null){repository.validate(b.kind,b.logicalId,v,local);if(SyncV2BodyCodec.revision(b.kind,b.entityKey,SyncV2BodyCodec.hash(local))!=b.bodyRevision)SyncV2Codec.fail("body_revision_changed")}
        var cursor="";var last:Exception?=null
        do {val page=http.list(b.entityKey,b.bodyRevision,cursor);val carriers=page.getJSONArray("carriers")
            for(i in 0 until carriers.length())try {
                receive(entry,anchor,carriers.getJSONObject(i).getJSONObject("descriptor"),dir,true);return
            }catch(e:Exception){life.check();if(code(e) in setOf("choice_stale","authentication_required","device_revoked"))throw e;last=e}
            cursor=if(page.isNull("nextAfter"))""else page.getString("nextAfter")
        }while(cursor.isNotEmpty())
        if(local==null)throw last?:IllegalStateException("body_channel_pending")
        current(entry,anchor)
        val pending=File(dir,"pending.json");val version=keys.newestVersion
        var d=if(pending.isFile)SyncV2Wire.parse(pending.readBytes(),262144,true)else null
        d?.let{val body=SyncV2BodyCodec.validate(it,identity.scope);if(SyncV2Codec.canonical(body.getJSONObject("anchor"))!=SyncV2Codec.canonical(anchor) || body.getLong("keyVersion")!=version || body.getString("authorDeviceId")!=signer.deviceId)d=null}
        if(d==null) {
            val prepared=SyncV2BodyCodec.prepare(identity.scope,anchor,b,keys.read(version),version,signer,local)
            for((hash,raw)in prepared.objects)SyncV2BodyHttp.atomic(File(dir,hash),raw)
            d=prepared.descriptor;SyncV2BodyHttp.atomic(pending,SyncV2Codec.utf8(SyncV2Codec.canonical(d)))
        }
        // Authenticate restart state and the complete local ciphertext closure before reserve.
        SyncV2BodyCodec.open(d!!,identity.scope,b,signer.publicHex,keys::read){h,_->File(dir,h).readBytes()}
        current(entry,anchor);if(repository.read(b.kind,b.logicalId)?.let{SyncV2BodyCodec.hash(it)}!=SyncV2BodyCodec.hash(local))SyncV2Codec.fail("body_revision_changed")
        http.upload(d!!,dir)
    }
    internal fun receive(entry:JSONObject,anchor:JSONObject,d:JSONObject,dir:File,install:Boolean) {
        current(entry,anchor);val b=binding(entry);val author=SyncV2BodyCodec.validate(d,identity.scope).getString("authorDeviceId")
        val raw=SyncV2BodyCodec.open(d,identity.scope,b,authors().publicKey(author),keys::read){h,n->http.download(d.getJSONObject("body").getString("carrierId"),h,n,dir)}
        repository.validate(b.kind,b.logicalId,value(entry),raw)
        current(entry,anchor)
        if(install)life.guard {
            val st=projection.state();projection.accountWriter(st.version){state->
                current(entry,anchor,state);repository.install(b.kind,b.logicalId,raw)
                // Only cache columns changed inside this writer. Hydration is not a local business edit.
                state.body.put("businessStamp",SyncV2BusinessWitness.read(projection.accountDatabase).encoded())
            }
        }
    }
    fun status(kind:String,id:String):JSONObject {
        val raw=runCatching{repository.read(kind,id)}.getOrNull();val entry=runCatching{projection.cloudRows(setOf(identity.entity(kind,id))).firstOrNull()}.getOrNull()
        val ready=raw!=null&&entry!=null&&!entry.getJSONObject("row").getBoolean("deleted")&&runCatching{repository.validate(kind,id,value(entry),raw);SyncV2BodyCodec.revision(kind,identity.entity(kind,id),SyncV2BodyCodec.hash(raw))==entry.getJSONObject("row").optString("bodyRevision")}.getOrDefault(false)
        return JSONObject().put("placeholder",!ready).put("reason",if(ready)JSONObject.NULL else"body_not_synced").put("keepLocalBody",true)
    }
    companion object {fun code(e:Exception)=(e as? SyncV2CloudError)?.code?:e.message?.takeIf{Regex("[a-z][a-z0-9_]{0,63}").matches(it)}?:"body_channel_pending"}
}
