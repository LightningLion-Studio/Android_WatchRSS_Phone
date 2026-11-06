package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

/** Pure verification outside the SQLite writer. Signed header pages are supplied by the caller. */
internal object SyncV2HeadPins {
    private val fields=setOf("version","scope","authorDeviceId","deviceSequence","previousOwnCommit","mergedParents","treeRoot","requiredFeatures","createdAtMillis","observedRefs","indexComplete","accountParent","rollbackGeneration","operation")
    fun validateBody(b: JSONObject) {
        SyncV2Codec.exact(b,fields)
        for(f in listOf("authorDeviceId","deviceSequence","rollbackGeneration","treeRoot")) SyncV2Wire.string(b,f)
        for(f in listOf("accountParent","previousOwnCommit")) if(!b.isNull(f)) SyncV2Wire.string(b,f)
        if(b.get("version")!=2 || b.get("indexComplete")!=true) SyncV2Codec.fail("invalid_schema")
        SyncV2Codec.scope(b.getJSONObject("scope")); SyncV2Codec.unhex(b.getString("authorDeviceId"),16)
        if(SyncV2Codec.d64(b.getString("deviceSequence"))<BigInteger.ONE) SyncV2Codec.fail("invalid_schema")
        SyncV2Codec.d64(b.getString("rollbackGeneration"))
        for(f in listOf("accountParent","previousOwnCommit"))if(!b.isNull(f))SyncV2Codec.commitOid(b.getString(f))
        if(!Regex("tree:sha256:[a-f0-9]{64}").matches(b.getString("treeRoot")))SyncV2Codec.fail("invalid_schema")
        val created=b.get("createdAtMillis"); if(created !is Int && created !is Long)SyncV2Codec.fail("invalid_schema")
        if((created as Number).toLong() !in 0..9007199254740991L)SyncV2Codec.fail("invalid_schema")
        val op=b.getJSONObject("operation"); SyncV2Codec.exact(op,setOf("kind","rollbackTarget","resolvesGeneration"))
        when(SyncV2Wire.string(op,"kind")) {
            "normal" -> if(!op.isNull("rollbackTarget") || !op.isNull("resolvesGeneration"))SyncV2Codec.fail("invalid_schema")
            "rollback" -> {SyncV2Codec.commitOid(SyncV2Wire.string(op,"rollbackTarget"));if(!op.isNull("resolvesGeneration"))SyncV2Codec.fail("invalid_schema")}
            "resolve-local","resolve-merge" -> if(!op.isNull("rollbackTarget") || SyncV2Wire.string(op,"resolvesGeneration")!=b.getString("rollbackGeneration"))SyncV2Codec.fail("invalid_schema")
            else -> SyncV2Codec.fail("invalid_schema")
        }
        val parents=b.getJSONArray("mergedParents"); val parentList=(0 until parents.length()).map{(parents.get(it) as? String ?: SyncV2Codec.fail("invalid_schema")).also(SyncV2Codec::commitOid)}
        if(parentList.size>64 || parentList!=parentList.distinct().sorted() || parentList.contains(b.optString("previousOwnCommit")))SyncV2Codec.fail("invalid_schema")
        val features=b.getJSONArray("requiredFeatures"); val featureList=(0 until features.length()).map { features.get(it) as? String ?: SyncV2Codec.fail("invalid_schema") }
        if(featureList.size>32 || featureList!=featureList.distinct().sorted() || featureList.any{!Regex("[a-z0-9][a-z0-9.-]{0,63}").matches(it)})SyncV2Codec.fail("invalid_schema")
        if(featureList.isNotEmpty())SyncV2Codec.fail("unsupported_feature")
        val refs=b.getJSONArray("observedRefs"); var prev=""
        if(refs.length()>64)SyncV2Codec.fail("invalid_schema")
        for(i in 0 until refs.length()) {
            val r=refs.getJSONObject(i);SyncV2Codec.exact(r,setOf("deviceId","commitOid","refRevision"))
            val id=SyncV2Wire.string(r,"deviceId");SyncV2Codec.unhex(id,16);if(id<=prev)SyncV2Codec.fail("invalid_schema");prev=id
            SyncV2Codec.commitOid(SyncV2Wire.string(r,"commitOid"));if(SyncV2Codec.d64(SyncV2Wire.string(r,"refRevision"))<BigInteger.ONE)SyncV2Codec.fail("invalid_schema")
        }
    }
    const val schema="rc6c-c5-path-v2"
    data class Proof(val path:JSONArray,val headers:List<JSONObject>)
    fun verify(identity:SyncV2Identity,window:JSONObject,headers:List<JSONObject>,pin:JSONObject?,
        verifyHeader:(JSONObject)->JSONObject=identity::verifyHeader,readHeader:((String)->JSONObject)?=null,
        knownAuthors:JSONObject=JSONObject(),knownHeaders:List<JSONObject> = emptyList()):JSONArray =
        authenticate(identity,window,headers,pin,verifyHeader,readHeader,knownAuthors,knownHeaders).path

    /** Auxiliary author/target headers authenticate edges; only the account path advances pins. */
    fun authenticate(identity:SyncV2Identity,window:JSONObject,headers:List<JSONObject>,pin:JSONObject?,
        verifyHeader:(JSONObject)->JSONObject=identity::verifyHeader,readHeader:((String)->JSONObject)?=null,
        knownAuthors:JSONObject=JSONObject(),knownHeaders:List<JSONObject> = emptyList()):Proof {
        if(headers.size>4096)SyncV2Codec.fail("object_too_large")
        val supplied=mutableMapOf<String,JSONObject>()
        for(envelope in knownHeaders+headers) {
            val oid=SyncV2Codec.oid("commit",envelope.getJSONObject("body"))
            supplied[oid]=envelope
        }
        val checked=linkedMapOf<String,Pair<JSONObject,JSONObject>>()
        fun get(oid:String):JSONObject {
            SyncV2Codec.commitOid(oid)
            return checked[oid]?.first?:run {
                if(checked.size>=4096)SyncV2Codec.fail("object_too_large")
                val envelope=supplied[oid]?:readHeader?.invoke(oid)?:SyncV2Codec.fail("object_missing")
                val body=verifyHeader(envelope)
                if(SyncV2Codec.oid("commit",body)!=oid)SyncV2Codec.fail("hash_mismatch")
                if(SyncV2Codec.scope(body.getJSONObject("scope"))!=identity.scopeJson)SyncV2Codec.fail("scope_mismatch")
                checked[oid]=body to envelope;body
            }
        }
        val head=SyncV2Wire.string(window,"oid");SyncV2Codec.commitOid(head)
        val stop=pin?.getString("oid")
        if(head==stop && window.getString("generation")!=pin!!.getString("generation"))SyncV2Codec.fail("replay_detected")
        val path=mutableListOf<Pair<String,JSONObject>>();val seen=mutableSetOf<String>();var at:String?=head
        while(at!=stop) {
            if(at==null || !seen.add(at))SyncV2Codec.fail("ref_continuity_unavailable")
            val b=get(at);path.add(at to b)
            at=if(b.isNull("accountParent"))null else b.getString("accountParent")
        }
        val current=get(head)
        if(current.getString("rollbackGeneration")!=window.getString("generation"))SyncV2Codec.fail("replay_detected")
        if(pin!=null && get(stop!!).getString("rollbackGeneration")!=pin.getString("generation"))SyncV2Codec.fail("replay_detected")
        val authors=JSONObject(knownAuthors.toString())
        fun transition(b:JSONObject) {
            val parent=if(b.isNull("accountParent"))null else get(b.getString("accountParent"))
            val previous=parent?.getString("rollbackGeneration")?:"0"
            val operation=b.getJSONObject("operation")
            if(operation.getString("kind")=="rollback") {
                if(parent==null || b.getString("rollbackGeneration")!=SyncV2Codec.next(previous))SyncV2Codec.fail("replay_detected")
                val target=operation.getString("rollbackTarget");val targetBody=get(target)
                val merged=b.getJSONArray("mergedParents")
                if(targetBody.getString("treeRoot")!=b.getString("treeRoot") ||
                    target!=b.optString("previousOwnCommit") && (0 until merged.length()).none{merged.getString(it)==target})SyncV2Codec.fail("invalid_schema")
            }else {
                if(b.getString("rollbackGeneration")!=previous || parent==null && operation.getString("kind")!="normal")SyncV2Codec.fail("replay_detected")
            }
        }
        for((oid,b)in path.asReversed()) {
            transition(b)
            val author=b.getString("authorDeviceId");val prior=authors.optJSONObject(author)
            val seq=SyncV2Codec.d64(b.getString("deviceSequence"))
            val priorOid=prior?.getString("oid");val priorSequence=prior?.let{SyncV2Codec.d64(it.getString("sequence"))}
            if(priorSequence!=null && seq<=priorSequence)SyncV2Codec.fail("replay_detected")
            var own=if(b.isNull("previousOwnCommit"))null else b.getString("previousOwnCommit")
            var newer=seq;val ownSeen=mutableSetOf<String>()
            while(own!=priorOid) {
                if(own==null || !ownSeen.add(own))SyncV2Codec.fail("replay_detected")
                val previous=get(own);val sequence=SyncV2Codec.d64(previous.getString("deviceSequence"))
                if(previous.getString("authorDeviceId")!=author || sequence>=newer || priorSequence!=null && sequence<=priorSequence)SyncV2Codec.fail("replay_detected")
                newer=sequence;own=if(previous.isNull("previousOwnCommit"))null else previous.getString("previousOwnCommit")
            }
            if(priorOid!=null) {
                val pinned=get(priorOid)
                if(pinned.getString("authorDeviceId")!=author || SyncV2Codec.d64(pinned.getString("deviceSequence"))!=priorSequence || newer<=priorSequence!!)SyncV2Codec.fail("replay_detected")
            }
            authors.put(author,JSONObject().put("oid",oid).put("sequence",b.getString("deviceSequence")))
        }
        val archive=JSONArray()
        for((oid,_)in path)archive.put(JSONObject().put("oid",oid).put("header",checked.getValue(oid).second))
        return Proof(archive,checked.values.map{it.second})
    }
}
