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
    private fun captureWindow(anchor: JSONObject): Long {
        val window = client.checkpoint(now()).getJSONObject("body")
        if (SyncV2Codec.canonical(window.getJSONObject("account")) != SyncV2Codec.canonical(anchor)) {
            SyncV2Codec.fail("choice_stale")
        }
        return window.getLong("expiresAtMillis").also { checkWindow(anchor, it) }
    }

    private fun checkWindow(anchor: JSONObject, expires: Long, st: SyncV2Projection.State = projection.state()) {
        life.check()
        if (now() >= expires) SyncV2Codec.fail("window_expired")
        val account = st.body.optJSONObject("account") ?: SyncV2Codec.fail("choice_stale")
        val metadata = st.body.optJSONObject("metadataAccount") ?: SyncV2Codec.fail("choice_stale")
        if (!st.body.isNull("pendingChoice") || !st.body.isNull("pendingPublish") ||
            SyncV2Codec.canonical(anchor(account)) != SyncV2Codec.canonical(anchor) ||
            SyncV2Codec.canonical(anchor(metadata)) != SyncV2Codec.canonical(anchor)) SyncV2Codec.fail("choice_stale")
    }

    /** Current accepted signed head -> Merkle path -> AEAD meta/state -> identity. */
    private fun current(entry: JSONObject, anchor: JSONObject, expires: Long, st: SyncV2Projection.State = projection.state()) {
        checkWindow(anchor, expires, st)
        val account = st.body.getJSONObject("account")
        val header=projection.accountCatalog.header(account.getString("oid"))?.getJSONObject("header")
            ?:SyncV2Wire.parse(projection.accountObject(account.getString("oid")),262144,true)
        val body=authors(st.body).header(header)
        if (SyncV2Codec.oid("commit",body)!=account.getString("oid") || body.getString("treeRoot")!=st.body.getString("metadataTreeRoot") ||
            body.getString("rollbackGeneration") != account.getString("generation")) SyncV2Codec.fail("hash_mismatch")
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
        if (SyncV2Business.logicalId(b.kind, value(entry)) != b.logicalId) SyncV2Codec.fail("identity_conflict")
    }
    private fun anchor(account:JSONObject)=JSONObject().put("commitOid",account.getString("oid")).put("revision",account.getString("revision")).put("rollbackGeneration",account.getString("generation"))
    private fun candidates():List<Pair<String,String>> = projection.accountDatabase.controlQuery("SELECT kind,logical_id FROM syncv2_candidates WHERE scope=? AND kind IN ('asset','book') ORDER BY entity_key",arrayOf(projection.scope)).use{c->buildList{while(c.moveToNext())add(c.getString(0) to c.getString(1))}}
    fun refreshReferences():List<String> {projection.migrateBodyAssetCandidates();return repository.refreshReferences(now(),life::check)}
    /** Random old placeholders with originals must be republished even without metadata edits. */
    fun needsPublication():Boolean {
        for((kind,id)in candidates())try {
            val c=projection.candidate(kind,id)?:continue;val v=SyncV2Business.join(kind,c.first,c.second);if(v.optBoolean("deleted"))continue
            val original=repository.original(kind,id,v)?:continue
            val entity=identity.entity(kind,id);val remote=projection.cloudRows(setOf(entity)).firstOrNull()?:return true
            val r=remote.getJSONObject("row");if(!r.isNull("recipeOid"))continue
            // A changed remote business binding requires the existing account-choice flow.
            if(SyncV2Codec.canonical(remote.getJSONObject("meta"))!=SyncV2Codec.canonical(c.first) || SyncV2Codec.canonical(remote.getJSONObject("state"))!=SyncV2Codec.canonical(c.second))continue
            if(original.hasRegisteredDigest && r.optString("bodyRevision")!=SyncV2BodyCodec.revision(kind,entity,original.sha256))return true
        } catch(e:Exception){life.check()}
        return false
    }
    /** Before account publication, bind actual original bytes without creating P3 recipes. */
    fun bindPrepared() {
        for((kind,id)in candidates())try {
            life.check();val c=projection.candidate(kind,id)?:continue;val v=SyncV2Business.join(kind,c.first,c.second);if(v.optBoolean("deleted"))continue
            val entity=identity.entity(kind,id);val row=projection.wireContentRow(entity)?:continue;if(!row.isNull("recipeOid"))continue
            val original=repository.original(kind,id,v)?:continue
            val revision = SyncV2BodyCodec.revision(kind, entity, original.sha256)
            val previous = if (original.hasRegisteredDigest) null else projection.cloudRows(setOf(entity))
                .firstOrNull()?.getJSONObject("row")
            if (previous != null && !previous.isNull("bodyRevision") && previous.getString("bodyRevision") != revision) continue
            projection.bindArticleRevision(row,revision,independent=true)
        } catch(e:Exception){life.check()}
    }
    fun sync(manual:Boolean=false, downloadOnly:Boolean=false):Result {
        life.check();val st=projection.state();val account=st.body.optJSONObject("account")?:return Result(0,emptyList())
        if(!st.body.isNull("pendingChoice") || !st.body.isNull("pendingPublish"))return Result(0,emptyList())
        val globalRetry=File(root,"retry.json")
        if(globalRetry.isFile) {val r=SyncV2Wire.parse(globalRetry.readBytes(),4096,true)
            if(SyncV2Wire.uint(r,"until")>now())return Result(1,listOf(r.getString("code")))}
        val anchor=anchor(account);var after="";var pending=0;val errors=linkedSetOf<String>()
        var windowExpiry: Long? = null
        while(true) {
            val page=projection.cloudRowsPage(after,16);if(page.isEmpty())break
            for(entry in page) {val m=entry.getJSONObject("meta");val row=entry.getJSONObject("row")
                if(m.getString("kind") !in setOf("book","asset") || row.getBoolean("deleted") || !row.isNull("recipeOid") || row.isNull("bodyRevision"))continue
                val b=binding(entry);val dir=File(root,"${b.entityKey}/${b.bodyRevision}")
                try {
                    val retry=File(dir,"retry.json");if(retry.isFile){val r=SyncV2Wire.parse(retry.readBytes(),4096,true);if(r.getLong("until")>now())throw SyncV2CloudError(r.getString("code"),true,r.getLong("until")-now())}
                    val route=signals();if(route.internet!=true || !manual&&route.wifi!=true)SyncV2Codec.fail("network_unavailable")
                    val expires = windowExpiry ?: captureWindow(anchor).also { windowExpiry = it }
                    syncEntry(entry,anchor,expires,dir,downloadOnly)
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
    private fun matchingOriginal(binding: SyncV2BodyCodec.Binding, selected: JSONObject): SyncV2BodyRepository.Original? {
        val original = repository.original(binding.kind, binding.logicalId, selected) ?: return null
        if (SyncV2BodyCodec.revision(binding.kind, binding.entityKey, original.sha256) != binding.bodyRevision) {
            if (original.hasRegisteredDigest) SyncV2Codec.fail("body_revision_changed")
            return null
        }
        return original
    }

    private fun carrierPublicKey(descriptor: JSONObject): String {
        val author = SyncV2BodyCodec.validate(descriptor, identity.scope).getString("authorDeviceId")
        return authors().publicKey(author)
    }

    private fun syncEntry(entry: JSONObject, anchor: JSONObject, expires: Long, dir: File, downloadOnly: Boolean) {
        current(entry, anchor, expires)
        val b = binding(entry)
        val selected = value(entry)
        var local = matchingOriginal(b, selected)
        val repair = if (local == null) repository.repairTarget(b.kind, b.logicalId, selected) else null
        var cursor = ""
        var last: Exception? = null
        do {
            checkWindow(anchor, expires)
            val page = http.list(b.entityKey, b.bodyRevision, cursor)
            checkWindow(anchor, expires)
            // The v1 receiver may have committed while the ready list was in flight.
            if (local == null) local = matchingOriginal(b, selected)
            val carriers = page.getJSONArray("carriers")
            for (i in 0 until carriers.length()) {
                val descriptor = carriers.getJSONObject(i).getJSONObject("descriptor")
                try {
                    if (local != null) {
                        // List and signature only confirm cloud availability, independent of local demand.
                        val signed = SyncV2BodyCodec.verifyDescriptor(descriptor, identity.scope, b, carrierPublicKey(descriptor))
                        keys.read(signed.getLong("keyVersion"))
                    } else {
                        receive(entry, anchor, descriptor, dir, expires, requireNotNull(repair))
                    }
                } catch (e: Exception) {
                    life.check()
                    if (code(e) in setOf("choice_stale", "window_expired", "authentication_required", "device_revoked")) throw e
                    last = e
                    continue
                }
                if (local != null) {
                    current(entry, anchor, expires)
                    requireCurrentOriginal(local, b, selected, anchor, expires)
                }
                return
            }
            cursor = if (page.isNull("nextAfter")) "" else page.getString("nextAfter")
        } while (cursor.isNotEmpty())

        // An invalid listed candidate does not authorize a competing publication.
        last?.let { throw it }
        val source = local ?: SyncV2Codec.fail("body_channel_pending")
        if (downloadOnly) return
        current(entry, anchor, expires)
        authors().publicKey(signer.deviceId, signer.keyId)
        val pending = File(dir, "pending.json")
        val version = keys.newestVersion
        var descriptor = if (pending.isFile) SyncV2Wire.parse(pending.readBytes(), 262144, true) else null
        descriptor?.let {
            val body = SyncV2BodyCodec.validate(it, identity.scope)
            if (SyncV2Codec.canonical(body.getJSONObject("anchor")) != SyncV2Codec.canonical(anchor) ||
                body.getLong("keyVersion") != version || body.getString("authorDeviceId") != signer.deviceId) descriptor = null
        }
        if (descriptor == null) {
            val prepared = SyncV2BodyCodec.prepare(identity.scope, anchor, b, keys.read(version), version, signer, source.raw)
            for ((hash, raw) in prepared.objects) SyncV2BodyHttp.atomic(File(dir, hash), raw)
            descriptor = prepared.descriptor
            SyncV2BodyHttp.atomic(pending, SyncV2Codec.utf8(SyncV2Codec.canonical(descriptor)))
        }
        // Authenticate restart state and the complete local ciphertext closure before reserve.
        val upload = requireNotNull(descriptor)
        SyncV2BodyCodec.open(upload, identity.scope, b, signer.publicHex, keys::read) { hash, _ -> File(dir, hash).readBytes() }
        current(entry, anchor, expires)
        val latest = requireCurrentOriginal(source, b, selected, anchor, expires)
        http.upload(upload, dir) { checkWindow(anchor, expires); latest.requireCurrent() }
        current(entry, anchor, expires)
    }

    private fun requireCurrentOriginal(original: SyncV2BodyRepository.Original, binding: SyncV2BodyCodec.Binding,
        selected: JSONObject, anchor: JSONObject, expires: Long): SyncV2BodyRepository.Original {
        val latest = matchingOriginal(binding, selected)
        if (latest == null || !original.matches(latest)) SyncV2Codec.fail("body_revision_changed")
        checkWindow(anchor, expires)
        latest.requireCurrent()
        return latest
    }

    internal fun receive(entry: JSONObject, anchor: JSONObject, descriptor: JSONObject, dir: File,
        expires: Long = captureWindow(anchor), capturedTarget: SyncV2BodyRepository.RepairTarget? = null) {
        current(entry, anchor, expires)
        val b = binding(entry)
        val selected = value(entry)
        val repair = capturedTarget ?: repository.repairTarget(b.kind, b.logicalId, selected)
        val raw = SyncV2BodyCodec.open(descriptor, identity.scope, b, carrierPublicKey(descriptor), keys::read) { hash, size ->
            http.download(descriptor.getJSONObject("body").getString("carrierId"), hash, size, dir) { checkWindow(anchor, expires) }
        }
        repository.validate(b.kind, b.logicalId, selected, raw)
        current(entry, anchor, expires)
        val committedDuringDownload = matchingOriginal(b, selected)
        if (committedDuringDownload != null) {
            checkWindow(anchor, expires)
            committedDuringDownload.requireCurrent()
            return
        }
        life.guard {
            val st = projection.state()
            projection.accountWriter(st.version) { state ->
                current(entry, anchor, expires, state)
                repository.install(b.kind, b.logicalId, raw, repair) { checkWindow(anchor, expires, state) }
                // Only cache columns changed inside this writer. Hydration is not a local business edit.
                state.body.put("businessStamp", SyncV2BusinessWitness.read(projection.accountDatabase).encoded())
            }
        }
    }

    fun status(kind:String,id:String):JSONObject {
        val ready = runCatching {
            val st = projection.state()
            val a = st.body.optJSONObject("account") ?: return@runCatching false
            val entry = projection.cloudRows(setOf(identity.entity(kind,id))).singleOrNull() ?: return@runCatching false
            current(entry, anchor(a), Long.MAX_VALUE, st)
            matchingOriginal(binding(entry), value(entry)) != null
        }.getOrDefault(false)
        return JSONObject().put("placeholder",!ready).put("reason",if(ready)JSONObject.NULL else"body_not_synced").put("keepLocalBody",true)
    }
    companion object {fun code(e:Exception)=(e as? SyncV2CloudError)?.code?:e.message?.takeIf{Regex("[a-z][a-z0-9_]{0,63}").matches(it)}?:"body_channel_pending"}
}
