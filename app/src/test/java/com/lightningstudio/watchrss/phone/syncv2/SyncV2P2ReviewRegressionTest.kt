package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** P2R A/B/C counterexamples, checked through the product cryptographic boundaries. */
class SyncV2P2ReviewRegressionTest {
    private val identity=SyncV2Fixtures.identity()
    private val a=SyncV2Signing.fromPrivate(ByteArray(31)+byteArrayOf(1),"aa".repeat(16),"ab".repeat(16))
    private val b=SyncV2Signing.fromPrivate(ByteArray(31)+byteArrayOf(2),"bb".repeat(16),"bc".repeat(16))
    private fun authors()=SyncV2Authors(identity){true}.also{for(s in listOf(a,b))it.accept(identity.signingBinding(s,100,"0"),"0")}
    private fun oid(e:JSONObject)=SyncV2Codec.oid("commit",e.getJSONObject("body"))
    private fun code(expected:String,fn:()->Unit) {try{fn();fail("accepted $expected")}catch(e:IllegalStateException){assertEquals(expected,e.message)}}
    private fun commit(s:SyncV2Signing=a,sequence:String="1",parent:JSONObject?=null,previous:JSONObject?=null,generation:String="0",operation:String="normal",target:JSONObject?=null,tree:String?=null):JSONObject {
        val body=JSONObject(SyncV2Fixtures.envelope().getJSONObject("body").toString())
            .put("authorDeviceId",s.deviceId).put("deviceSequence",sequence).put("accountParent",parent?.let(::oid)?:JSONObject.NULL)
            .put("previousOwnCommit",previous?.let(::oid)?:JSONObject.NULL).put("rollbackGeneration",generation)
            .put("mergedParents",JSONArray(if(target!=null && (previous==null || oid(previous)!=oid(target)))listOf(oid(target))else emptyList<String>()))
            .put("operation",JSONObject().put("kind",operation).put("rollbackTarget",target?.let(::oid)?:JSONObject.NULL)
                .put("resolvesGeneration",if(operation.startsWith("resolve-"))generation else JSONObject.NULL))
        if(tree!=null)body.put("treeRoot",tree)
        return s.commit(body)
    }
    private fun window(e:JSONObject)=JSONObject().put("oid",oid(e)).put("revision","3").put("generation",e.getJSONObject("body").getString("rollbackGeneration"))
    private fun proof(head:JSONObject,all:List<JSONObject>,pin:JSONObject?=null,pins:JSONObject=JSONObject()):SyncV2HeadPins.Proof {
        val byId=all.associateBy(::oid)
        return SyncV2HeadPins.authenticate(identity,window(head),listOf(head),pin,authors()::header,
            {byId[it]?:SyncV2Codec.fail("object_missing")},pins)
    }
    @Test fun r02SignedNormalGenesisCannotSkipGenerationOrHideMissingAuthorPredecessor() {
        code("replay_detected"){proof(commit(generation="9"),emptyList())}
        val body=commit().getJSONObject("body").put("previousOwnCommit","commit:sha256:"+"f".repeat(64))
        code("object_missing"){proof(a.commit(body),emptyList())}
        val combined=a.commit(JSONObject(body.toString()).put("rollbackGeneration","9").put("deviceSequence","9007199254740993"))
        code("replay_detected"){proof(combined,emptyList())}
    }
    @Test fun r02AuthorPredecessorsMustMatchAuthorScopeSignatureOidAndDecreasingSequence() {
        val wrongAuthor=commit(s=b)
        code("replay_detected"){proof(commit(sequence="2",previous=wrongAuthor),listOf(wrongAuthor))}
        val sameSequence=commit(sequence="2")
        code("replay_detected"){proof(commit(sequence="2",previous=sameSequence),listOf(sameSequence))}
        val prior=commit();val head=commit(sequence="2",previous=prior)
        val tampered=JSONObject(prior.toString()).put("signature",SyncV2Codec.b64(ByteArray(64)))
        code("signature_invalid"){proof(head,listOf(tampered))}
        val foreignBody=commit().getJSONObject("body");foreignBody.getJSONObject("scope").put("repoId","dd".repeat(16))
        val foreign=a.commit(foreignBody)
        code("scope_mismatch"){proof(commit(sequence="2",previous=foreign),listOf(foreign))}
        code("hash_mismatch") {SyncV2HeadPins.verify(identity,window(head),listOf(head),null,authors()::header,{commit(s=b)})}
    }
    @Test fun r02StagedAuthorChainReachesExactAcceptedPinWithoutPromotingAuxiliaryHeads() {
        val root=commit();val staged=commit(sequence="2",parent=root,previous=root)
        val head=commit(sequence="3",parent=root,previous=staged)
        val pins=JSONObject().put(a.deviceId,JSONObject().put("sequence","1").put("oid",oid(root)))
        val good=proof(head,listOf(root,staged),window(root),pins)
        assertEquals(1,good.path.length());assertEquals(oid(head),good.path.getJSONObject(0).getString("oid"));assertEquals(3,good.headers.size)
        val disconnected=commit(sequence="2")
        code("replay_detected"){proof(commit(sequence="3",parent=root,previous=disconnected),listOf(root,disconnected),window(root),pins)}
        assertEquals("1",pins.getJSONObject(a.deviceId).getString("sequence"))
    }
    @Test fun r02AllInitialNormalRollbackAndResolveGenerationTransitionsAreChecked() {
        val root=commit();val normal=commit(sequence="2",parent=root,previous=root)
        assertEquals(2,proof(normal,listOf(root)).path.length())
        code("replay_detected"){proof(commit(sequence="2",parent=root,previous=root,generation="1"),listOf(root))}
        for(kind in listOf("resolve-local","resolve-merge"))code("replay_detected"){proof(commit(operation=kind),emptyList())}
        code("replay_detected"){proof(commit(generation="1",operation="rollback",target=root),listOf(root))}
        for(generation in listOf("0","2",SyncV2Codec.maxD64.toString()))code("replay_detected") {
            proof(commit(sequence="3",parent=normal,previous=normal,generation=generation,operation="rollback",target=root),listOf(root,normal))
        }
        val rollback=commit(sequence="3",parent=normal,previous=normal,generation="1",operation="rollback",target=root)
        assertEquals(3,proof(rollback,listOf(root,normal)).path.length())
        for(kind in listOf("normal","resolve-local","resolve-merge")) {
            val resolved=commit(sequence="4",parent=rollback,previous=rollback,generation="1",operation=kind)
            assertEquals(4,proof(resolved,listOf(root,normal,rollback)).path.length())
            code("replay_detected"){proof(commit(sequence="4",parent=rollback,previous=rollback,generation="2",operation=kind),listOf(root,normal,rollback))}
        }
        val newAuthor=commit(s=b,parent=rollback,generation="1")
        assertEquals(4,proof(newAuthor,listOf(root,normal,rollback)).path.length())
        val maxParent=commit(generation=SyncV2Codec.maxD64.toString())
        // The initial normal parent is itself invalid; it must never become a generation anchor.
        code("replay_detected"){proof(commit(sequence="2",parent=maxParent,previous=maxParent,generation="0",operation="rollback",target=maxParent),listOf(maxParent))}
    }
    @Test fun r02RollbackTargetTreeAndDeclaredSourceAreAuthenticated() {
        val root=commit();val current=commit(sequence="2",parent=root,previous=root)
        code("invalid_schema") {proof(commit(sequence="3",parent=current,previous=current,generation="1",operation="rollback",target=root,tree="tree:sha256:"+"e".repeat(64)),listOf(root,current))}
        val body=commit(sequence="3",parent=current,previous=current,generation="1",operation="rollback",target=root).getJSONObject("body").put("mergedParents",JSONArray())
        code("invalid_schema"){proof(a.commit(body),listOf(root,current))}
    }
    private fun business():List<Triple<String,String,JSONObject>> {
        val book=JSONObject(SyncV2Fixtures.vector("book-merge").getJSONArray("cases").getJSONObject(0).getJSONObject("left").toString())
        val id=book.getString("bookId")
        return listOf(
            Triple("article","a",JSONObject().put("articleId","a").put("url","https://synthetic.invalid/a")),
            Triple("source","https://synthetic.invalid/feed",JSONObject().put("url","https://synthetic.invalid/feed")),
            Triple("book",id,book),
            Triple("book-reading","$id:1",JSONObject().put("bookId",id).put("chapterIndex",1).put("isRead",false).put("readingProgress",0).put("readingPositionBytes",0).put("readingPositionContentHash","").put("readingPositionChangedAt",0).put("sourceDeviceId","")),
            Triple("note","n",JSONObject().put("noteId","n").put("folderId",JSONObject.NULL).put("title","").put("markdown","").put("plainText","").put("contentHash","").put("baseContentHash","").put("baseMarkdown","").put("pinned",false).put("createdAt",0).put("updatedAt",0).put("modifiedBy","").put("deleted",false).put("deletedAt",0).put("noteConflicts",JSONObject().put("version",1).put("records",JSONArray()))),
            Triple("folder","f",JSONObject().put("folderId","f").put("name","").put("sortOrder",0).put("updatedAt",0).put("modifiedBy","").put("deleted",false).put("deletedAt",0)),
            Triple("asset","x",JSONObject().put("assetId","x").put("mime","image/png").put("originalFilename","").put("updatedAt",0).put("sourceDeviceId","").put("deleted",false).put("deletedAt",0)),
            Triple("rss-state","s:"+"1".repeat(64),JSONObject().put("sourceId","s").put("articleIdHash","1".repeat(64)))
        )
    }
    private fun closure(kind:String,logical:String,value:JSONObject):SyncV2ControlClosure.Verified {
        val entity=identity.entity(kind,logical);val pair=SyncV2Business.split(kind,logical,entity,value,null);val key=ByteArray(32){34}
        val m=SyncV2Envelope.seal(identity,key,1,"meta",SyncV2Codec.utf8(SyncV2Codec.canonical(pair.meta)))
        val s=SyncV2Envelope.seal(identity,key,1,"state",SyncV2Codec.utf8(SyncV2Codec.canonical(pair.state)))
        val row=JSONObject().put("entityKey",entity).put("metaOid",m.oid).put("stateOid",s.oid).put("addedAtMillis",0).put("rowSequence","1").put("deleted",value.optBoolean("deleted",false)).put("requiredFeatures",JSONArray())
        for(k in listOf("bodyRevision","promiseId","recipeOid","opaqueContentId","contentBytes"))row.put(k,JSONObject.NULL)
        val tree=SyncV2Index.build(identity.scope,listOf(row));val refs=SyncV2Index.refs(identity,a,s.oid)
        val refsOid=SyncV2Codec.oid("state-refs",refs);val raw=SyncV2Codec.utf8(SyncV2Codec.canonical(refs))
        val objects=tree.objects.toMutableMap().apply{put(m.oid,m.bytes);put(s.oid,s.bytes);put(refsOid,raw)}
        return SyncV2ControlClosure(identity,authors()){key}.verify(commit(tree=tree.root),objects::getValue) {
            JSONObject().put("scope",identity.scope).put("parentOid",s.oid).put("refsOid",refsOid).put("authorDeviceId",a.deviceId).put("keyId",a.keyId).put("totalBytes",raw.size)
        }
    }
    @Test fun r01EverySupportedBusinessIdentityMustEqualAuthenticatedMetaLogicalId() {
        code("invalid_schema"){closure("article","a",JSONObject().put("articleId","b").put("url","https://synthetic.invalid/item"))}
        for((kind,id,value)in business()) {
            assertEquals(1,closure(kind,id,value).rows.size)
            code("invalid_schema"){closure(kind,"outer-identity",value)}
        }
    }
}
