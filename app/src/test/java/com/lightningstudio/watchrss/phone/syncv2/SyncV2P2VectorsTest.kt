package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class SyncV2P2VectorsTest {
    private fun v(n:String)=SyncV2Fixtures.vector(n)
    private fun same(a:Any?,b:Any?)=assertEquals(SyncV2Codec.canonical(a),SyncV2Codec.canonical(b))
    private fun error(code:String,action:()->Unit) {try{action();fail("accepted invalid input: $code")}catch(e:IllegalStateException){assertEquals(code,e.message)}}
    private fun cases(a:JSONArray,fn:(JSONObject)->Unit){for(i in 0 until a.length())fn(a.getJSONObject(i))}
    private fun hex(s:String)=SyncV2Codec.unhex(s,s.length/2)
    @Test fun bookFingerprintAndDerivedIdentityAreValidatedBeforeSealingOrAccepting() {
        val book=JSONObject().put("bookId","").put("fingerprint","ab".repeat(32)).put("title","").put("chapterCount",1).put("deletedChapters",JSONArray()).put("deletionClock",0).put("deleted",false).put("deletedAt",0).put("updatedAt",0).put("sourceDeviceId","")
        book.put("bookId",SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.lp(SyncV2Codec.utf8("watchrss/book/id/v1"),SyncV2Codec.utf8(book.getString("fingerprint"))))))
        SyncV2Business.validate("book",book)
        error("invalid_schema"){SyncV2Business.validate("book",JSONObject(book.toString()).put("fingerprint","synthetic-book-fingerprint"))}
        error("invalid_schema"){SyncV2Business.validate("book",JSONObject(book.toString()).put("bookId","cd".repeat(32)))}
        val instance=JSONObject(book.toString()).put("instanceId","ef".repeat(16))
        error("invalid_schema"){SyncV2Business.validate("book",instance)}
        instance.put("bookId",SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.lp(SyncV2Codec.utf8("watchrss/book/instance-id/v1"),SyncV2Codec.utf8(book.getString("fingerprint")),SyncV2Codec.utf8(instance.getString("instanceId"))))))
        SyncV2Business.validate("book",instance)
    }
    @Test fun absentNoteConflictChannelIsReadableUnknownAndCannotAuthorizeRewrite() {
        val meta=JSONObject().put("payload",JSONObject().put("noteId","old").put("folderId",JSONObject.NULL).put("title","").put("createdAt",0))
        val state=JSONObject().put("payload",JSONObject().put("markdown","").put("plainText","").put("contentHash","").put("baseContentHash","").put("baseMarkdown","").put("pinned",false).put("updatedAt",0).put("modifiedBy","").put("deleted",false).put("deletedAt",0))
        val read=SyncV2Business.join("note",meta,state)
        assertFalse(read.has("noteConflicts"))
        error("unsupported_feature"){SyncV2Business.split("note","old","aa".repeat(32),read,null)}
        assertFalse(state.getJSONObject("payload").has("noteConflicts"))
    }
    @Test fun frozenOptionalRecordFieldsRemainAbsentWithoutDefaultFilling() {
        for((kind,raw)in listOf("article" to JSONObject().put("articleId","old").put("url","https://example.invalid/old"),"source" to JSONObject().put("url","https://example.invalid/feed"))) {
            val pair=SyncV2Business.split(kind,"old","aa".repeat(32),raw,null)
            val restored=SyncV2Business.join(kind,pair.meta,pair.state)
            same(raw,restored);assertFalse(restored.has("title"));assertFalse(restored.has("updatedAt"));assertFalse(restored.has("sourceDeviceId"))
        }
    }
    private fun signer()=SyncV2Signing.fromPrivate(hex(v("commit").getString("publicFixtureSigningKeyHex")),"aa".repeat(16),"ab".repeat(16))
    private fun mutate(value:JSONObject,path:JSONArray,replacement:Any?,remove:Boolean=false) {
        var at:Any=value
        for(i in 0 until path.length()-1)at=if(at is JSONObject)at.get(path.getString(i))else(at as JSONArray).get(path.getInt(i))
        val k=path.get(path.length()-1)
        if(at is JSONObject) {if(remove)at.remove(k as String)else at.put(k as String,replacement)}else(at as JSONArray).put((k as Number).toInt(),replacement)
    }
    @Test fun c4BackendFixedMappingSignatureCanonicalBytesAndAllSixRejectCases() {
        val x=v("device-mapping");val expected=x.getJSONObject("expected");val mapping=x.getJSONObject("mapping");val body=mapping.getJSONObject("body")
        val trust=SyncV2ServiceTrust.fromBootstrap(SyncV2Fixtures.identity().accountId,x.getJSONObject("service"),null){}
        assertEquals(expected.getString("canonicalBody"),SyncV2Codec.canonical(body))
        assertEquals(expected.getString("canonicalBodyHex"),SyncV2Codec.hex(SyncV2Codec.utf8(SyncV2Codec.canonical(body))))
        assertEquals(expected.getString("lpHex"),SyncV2Codec.hex(SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/device-mapping"),SyncV2Codec.utf8(SyncV2Codec.canonical(body)))))
        same(body,trust.verify("wrss.sync.v2/device-mapping",mapping))
        val registration=SyncV2Fixtures.identity().signingBinding(signer(),100,"0").put("deviceMapping",mapping)
        SyncV2Authors(SyncV2Fixtures.identity(),trust){it=="synthetic-old-approved-device-1"}.accept(registration,"0")
        cases(x.getJSONArray("rejectCases")){c->error(c.getJSONObject("expected").getString("code")) {
            val checked=trust.verify(c.getString("domain"),c.getJSONObject("mapping"))
            if(SyncV2Codec.scope(checked.getJSONObject("scope"))!=SyncV2Codec.scope(c.getJSONObject("expectedScope")))SyncV2Codec.fail("scope_mismatch")
        }}
    }
    @Test fun repoDescriptorAndRecoveryCreationMatchFrozenKAT() {
        val x=v("identity-bootstrap");val body=x.getJSONObject("descriptor").getJSONObject("body")
        val result=SyncV2RepoBootstrap.build(body.getString("accountId"),body.getJSONObject("scope"),body.getString("creationId"),hex(x.getString("idKeyHex")),hex(x.getString("wrapKeyHex")),1,signer(),hex(x.getString("saltHex")),hex(x.getString("nonceHex")))
        same(x.get("descriptor"),result.descriptor);same(x.get("capsule"),result.recoveryCapsule)
    }
    @Test fun allAccountCasAndRollbackCommitsUseFullSignedBodyAndWideSequence() {
        val x=v("account-cas")
        cases(x.getJSONArray("cases")){c->val input=c.getJSONObject("input");val commit=input.getJSONObject("signedCommit")
            SyncV2HeadPins.validateBody(commit.getJSONObject("body"));assertEquals(commit.getString("oid"),SyncV2Codec.oid("commit",commit.getJSONObject("body")))
            assertEquals(commit.getString("signature"),signer().commit(commit.getJSONObject("body")).getString("signature"))
            assertTrue(SyncV2Codec.d64(commit.getJSONObject("body").getString("deviceSequence"))>java.math.BigInteger("9007199254740991"))
        }
        val rollback=v("rollback")
        for(name in listOf("targetCommit","currentCommit","rollbackCommit")){val c=rollback.getJSONObject(name);same(c.getJSONObject("expected").getString("signature"),signer().commit(c.getJSONObject("body")).getString("signature"))}
    }
    @Test fun strictNetworkRejectsDuplicateUnicodeNumbersBomAndUnknownControlFields() {
        for(raw in listOf("{\"a\":1,\"a\":2}","{\"a\":-0}","{\"a\":1.0}","{\"a\":1e0}","{\"a\":01}","{\"a\":9007199254740992}","{\"a\":\"\\ud800\"}","\ufeff{}","{a:1}","{}junk"))error("invalid_encoding"){SyncV2Wire.parse(raw.toByteArray())}
        same(JSONObject().put("汉",JSONArray().put(3)),SyncV2Wire.parse(" {\"汉\":[3]} \n".toByteArray()))
        error("invalid_encoding"){SyncV2Wire.parse(" {}".toByteArray(),canonical=true)}
    }
    @Test fun originalEnvelopeKatsIncludingStrictGzip() {
        cases(v("envelope").getJSONArray("cases")) { c ->
            val x=c.getJSONObject("input");val expected=c.getJSONObject("expected");val scope=x.getJSONObject("scope")
            val id=hex(x.getString("idKeyHex"));val key=hex(x.getString("contentKeyHex"));val plain=hex(x.getString("plainHex"))
            val opaque={t:String,p:ByteArray->SyncV2Codec.opaque(id,scope,t,p)}
            val bytes=hex(expected.getString("sealedHex"))
            assertArrayEquals(plain,SyncV2Envelope.open(scope,opaque,key,expected.getString("oid"),bytes))
            val h=expected.getJSONObject("header");val aad=hex(expected.getString("aadHex"))
            val derived=SyncV2Codec.hkdf(key,hex(x.getString("saltHex")),SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/aead"),SyncV2Codec.sha(aad)))
            val encoded=SyncV2Codec.decrypt(derived,hex(x.getString("nonceHex")),aad,bytes.copyOfRange(8+aad.size,bytes.size))
            val sealed=SyncV2Envelope.sealWithParameters(scope,opaque,key,x.getLong("keyVersion"),x.getString("type"),plain,hex(x.getString("saltHex")),hex(x.getString("nonceHex")),x.getString("encoding"),encoded)
            assertEquals(expected.getString("oid"),sealed.oid);assertArrayEquals(bytes,sealed.bytes);same(h,sealed.header)
            val wrong=JSONObject(scope.toString()).put("libraryEpoch","9")
            error("scope_mismatch"){SyncV2Envelope.open(wrong,opaque,key,sealed.oid,sealed.bytes)}
            val bad=bytes.copyOf().also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()}
            error("hash_mismatch"){SyncV2Envelope.open(scope,opaque,key,sealed.oid,bad)}
        }
    }
    @Test fun randomSealDoesNotReuseNonceAndBindsFinalHeader() {
        val id=SyncV2Fixtures.identity();val key=ByteArray(32){2};val plain="{}".toByteArray()
        val a=SyncV2Envelope.seal(id,key,1,"meta",plain);val b=SyncV2Envelope.seal(id,key,1,"meta",plain)
        assertNotEquals(a.oid,b.oid);assertNotEquals(a.header.getString("nonce"),b.header.getString("nonce"))
        assertArrayEquals(plain,SyncV2Envelope.open(id,key,a.oid,a.bytes))
    }
    @Test fun originalIndexSignatureCommitAndControlSignatureKats() {
        val index=v("index-signature");val row=index.getJSONObject("row");SyncV2Index.row(row)
        assertEquals(index.getJSONObject("expected").getString("canonicalRow"),SyncV2Codec.canonical(row))
        val tree=SyncV2Index.build(index.getJSONObject("scope"),listOf(row))
        assertEquals(index.getJSONObject("expected").getString("treeRoot"),tree.root)
        same(index.getJSONObject("expected").get("treeBody"),SyncV2Wire.parse(tree.objects.getValue(tree.root)))
        val message=SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/index-row-sign"),SyncV2Codec.utf8(SyncV2Codec.canonical(index.getJSONObject("scope"))),SyncV2Codec.utf8(SyncV2Codec.canonical(row)))
        assertEquals(index.getJSONObject("expected").getString("signature"),signer().sign(message))
        cases(v("commit").getJSONArray("cases")){c->same(JSONObject().put("body",c.get("body")).put("signature",c.getJSONObject("expected").getString("signature")),signer().commit(c.getJSONObject("body")))}
        cases(v("control-signatures").getJSONArray("cases")){c->assertEquals(c.getString("expected"),signer().signed(c.getString("domain"),c.getJSONObject("body")).getString("signature"))}
    }
    @Test fun signingBindingKATAndApprovalAndRevocation() {
        val id=SyncV2Fixtures.identity();val expected=v("control-signatures").getJSONObject("binding")
        val binding=id.signingBinding(signer(),100,"0")
        same(expected.get("body"),binding.get("body"));same(expected.getJSONObject("expected"),JSONObject().put("bindingProof",binding.get("bindingProof")).put("possessionSignature",binding.get("possessionSignature")))
        val authors=SyncV2Authors(id){true};authors.accept(binding,"0");same(SyncV2Fixtures.envelope().get("body"),authors.header(SyncV2Fixtures.envelope()))
        error("device_revoked"){SyncV2Authors(id){false}.accept(binding,"0")}
        error("authentication_required"){authors.accept(binding,"1")}
        val bad=JSONObject(binding.toString()).put("bindingProof",SyncV2Codec.b64(ByteArray(32)))
        error("authentication_required"){authors.accept(bad,"0")}
    }
    @Test fun canonicalMerkleRejectsForgedPrefixCountAndNoncanonicalSplit() {
        val x=v("index-signature");val rows=(0..600).map{JSONObject(x.getJSONObject("row").toString()).put("entityKey","%064x".format(it))}
        val scope=x.getJSONObject("scope");val a=SyncV2Index.build(scope,rows)
        same(JSONArray(rows),JSONArray(SyncV2Index.read(scope,a.root,a.objects::getValue)))
        val changed=rows.toMutableList();changed[200]=JSONObject(rows[200].toString()).put("rowSequence","9007199254740993")
        val b=SyncV2Index.build(scope,changed)
        assertTrue(a.objects.keys.intersect(b.objects.keys).isNotEmpty());assertNotEquals(a.root,b.root)
        val forged=SyncV2Wire.parse(a.objects.getValue(a.root));forged.getJSONArray("children").getJSONObject(0).put("count",1)
        val raw=SyncV2Codec.utf8(SyncV2Codec.canonical(forged));val oid=SyncV2Codec.oid("tree",forged)
        error("invalid_schema"){SyncV2Index.read(scope,oid){if(it==oid)raw else a.objects.getValue(it)}}
    }
    @Test fun conflictCanonicalIdentityUnionAndAllLegacyPhoneRows() {
        val x=v("notes-conflict");val id=x.getJSONObject("context").getString("noteId")
        assertEquals(x.getJSONObject("canonical").getString("expected"),SyncV2Codec.canonical(SyncV2NoteConflicts.validate(x.getJSONObject("canonical").getJSONObject("input"),id)))
        val sets=x.getJSONObject("sets")
        cases(x.getJSONArray("merges")){c->val a=sets.getJSONObject(c.getString("left"));val b=sets.getJSONObject(c.getString("right"));same(c.get("expected"),SyncV2NoteConflicts.merge(a,b,id));same(c.get("expected"),SyncV2NoteConflicts.merge(b,a,id))}
        cases(x.getJSONArray("legacy")){c->if(c.getString("client")=="phone")same(c.get("expected"),SyncV2NoteConflicts.phone(c.getJSONArray("rows"),id))}
        val collision=x.getJSONObject("identityCollision")
        error(collision.getJSONObject("expected").getString("error")){SyncV2NoteConflicts.merge(collision.getJSONObject("left"),collision.getJSONObject("right"),id)}
    }
    @Test fun conflictAllSeventeenInvalidWireCases() {
        val x=v("notes-conflict");val id=x.getJSONObject("context").getString("noteId")
        cases(x.getJSONArray("invalid")){c->val value=JSONObject(x.getJSONObject("sets").getJSONObject("all").toString());mutate(value,c.getJSONArray("path"),c.opt("value"),c.optBoolean("remove"));error(c.getJSONObject("expected").getString("error")){SyncV2NoteConflicts.validate(value,id)}}
    }
    @Test fun conflictEncryptedWirePlacementNoFeatureAndNoAuthorityToClear() {
        val x=v("notes-conflict");val context=x.getJSONObject("context");val note=context.getString("noteId");val entity=context.getString("entityKey")
        val root=SyncV2NoteConflicts.attach(x.getJSONObject("baseState"),note,entity,x.getJSONObject("sets").getJSONObject("all"))
        same(x.getJSONObject("wire").get("expected"),root);same(x.getJSONObject("sets").get("all"),SyncV2NoteConflicts.read(root,note,entity))
        assertNull(SyncV2NoteConflicts.read(x.getJSONObject("baseState"),note,entity))
        error("invalid_schema"){SyncV2NoteConflicts.attach(root,note,entity,x.getJSONObject("sets").getJSONObject("empty"))}
        cases(x.getJSONArray("crypto")){c->val i=c.getJSONObject("input");val scope=i.getJSONObject("scope");val id=hex(i.getString("idKeyHex"));val sealed=SyncV2Envelope.sealWithParameters(scope,{t,p->SyncV2Codec.opaque(id,scope,t,p)},hex(i.getString("contentKeyHex")),1,"state",hex(i.getString("plainHex")),hex(i.getString("saltHex")),hex(i.getString("nonceHex")));assertEquals(c.getJSONObject("expected").getString("oid"),sealed.oid)}
    }
    @Test fun noteResolutionsUseLiveBodyCloseAllRowsAndKeepAccountState() {
        val x=v("notes-conflict");val initial=x.getJSONObject("initial")
        cases(x.getJSONArray("resolutions")){c->val capture=SyncV2NoteResolution.capture(initial);val(next,_)=SyncV2NoteResolution.prepare(initial,capture,c.getString("choice"),if(c.isNull("manual"))null else c.getString("manual"),c.getLong("now"),c.getString("device"))
            val expected=c.getJSONObject("expected");val note=next.getJSONObject("note")
            for(k in listOf("markdown","baseMarkdown","updatedAt"))same(expected.get(k),note.get(k))
            for(k in listOf("contentHash","baseContentHash"))assertEquals(c.getJSONObject("expectedHashes").getString(k),note.getString(k))
            same(c.getJSONObject("expectedCounters"),JSONObject().put("stateVersion",next.get("stateVersion")).put("localChangeRevision",next.get("localChangeRevision")))
            val rows=next.getJSONObject("conflicts").getJSONArray("records");same(c.getJSONArray("expectedResolvedAt"),JSONArray((0 until rows.length()).map{rows.getJSONObject(it).getLong("resolvedAt")}))
            for(k in listOf("account","editBase","pendingChoice","inbox","applied","draft"))same(initial.get(k),next.get(k))
        }
    }
    @Test fun noteAllTwelveStaleAndFourRaceCasesPreserveLatest() {
        val x=v("notes-conflict");val initial=x.getJSONObject("initial");val c=SyncV2NoteResolution.capture(initial)
        for(group in listOf("stale","races"))cases(x.getJSONArray(group)){test->val changed=JSONObject(initial.toString());mutate(changed,test.getJSONArray("path"),test.get("value"));if(group=="races"||test.optBoolean("bumpVersion"))changed.put("stateVersion",SyncV2Codec.next(changed.getString("stateVersion")))
            val original=SyncV2Codec.canonical(changed);error(test.getJSONObject("expected").getString("error")){SyncV2NoteResolution.guard(changed,c)};assertEquals(original,SyncV2Codec.canonical(changed))}
    }
    @Test fun mandatoryFinalRootClosureAndJournalBudgetMatchesFixedExpected() {
        val x=v("rc6b-contracts").getJSONObject("budget");val before=x.getJSONObject("input")
        val(next,budget)=SyncV2NoteResolution.prepare(before,SyncV2NoteResolution.capture(before),"local",null,100,"new-source")
        same(x.get("expected"),budget.json())
        cases(v("rc6b-contracts").getJSONArray("resolutionFailures")){c->val st=JSONObject(before.toString()).put(c.getString("field"),c.get("value"));error(c.getString("expected")){SyncV2NoteResolution.prepare(st,SyncV2NoteResolution.capture(st),"local",null,100,"new-source")}}
    }
    @Test fun plannerScansAllNotesAndPreservesResolvedSetsAgainstSixteenFixedCases() {
        cases(v("rc6b-contracts").getJSONArray("planner")){c->same(c.get("expected"),SyncV2Planner.canMerge(c.getJSONObject("input")))}
    }
    @Test fun segmentedStructureRejectedBeforeAttachOrReadAgainstEightFixedCases() {
        val x=v("rc6b-contracts").getJSONObject("segmented");val note=x.getJSONObject("context").getString("noteId");val entity=x.getJSONObject("context").getString("entityKey")
        cases(x.getJSONArray("invalid")){c->for(attach in listOf(true,false)){val state=JSONObject(x.getJSONObject("state").toString());mutate(state,c.getJSONArray("path"),c.get("value"));error(c.getString("expected")){if(attach)SyncV2NoteConflicts.attach(state,note,entity,x.getJSONObject("conflicts"))else SyncV2NoteConflicts.read(state,note,entity)}}}
    }
    @Test fun legacyPrivateProofFixedHashesAndUnsafeBranchesAndReplay() {
        val x=v("rc6c-contracts");val st=x.getJSONObject("afterReceive");val p=SyncV2LegacyMerge();val proof=p.plan(st,x.getString("key"));val e=x.getJSONObject("proofExpected")
        assertEquals(e.getString("sourceHash"),proof.sourceHash);assertEquals(e.getString("snapshotsHash"),proof.snapshotsHash);assertEquals(e.getString("candidateHash"),proof.candidateHash);same(e.get("capture"),proof.capture.json())
        same(x.get("rowsExpected"),p.check(st,x.getString("key"),proof))
        error("choice_stale"){SyncV2LegacyMerge().check(st,x.getString("key"),proof)}
        cases(x.getJSONArray("stale")){c->val changed=JSONObject(st.toString());mutate(changed,c.getJSONArray("path"),c.get("value"));if(c.optBoolean("bump"))changed.put("stateVersion",SyncV2Codec.next(changed.getString("stateVersion")));error("choice_stale"){p.check(changed,x.getString("key"),proof)}}
        p.consume(proof);error("choice_stale"){p.check(st,x.getString("key"),proof)}
        cases(x.getJSONArray("unsupported")){c->val changed=JSONObject(st.toString());val entry=changed.getJSONObject("legacyInbox").getJSONObject(x.getString("key"));if(c.has("replaceBody"))entry.put("body",c.get("replaceBody"))else mutate(entry,c.getJSONArray("path"),c.get("value"));if(c.has("removed"))entry.put("removedEntityKeys",c.get("removed"));error(c.getJSONObject("expected").getString("error")){SyncV2LegacyMerge().plan(changed,x.getString("key"))}}
    }
}
