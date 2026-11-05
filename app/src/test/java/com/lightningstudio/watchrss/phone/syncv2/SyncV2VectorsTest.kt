package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SyncV2VectorsTest {
    private fun vector(n:String)=SyncV2Fixtures.vector(n)
    private fun same(a:Any?,b:Any?)=assertEquals(SyncV2Codec.canonical(a),SyncV2Codec.canonical(b))
    private fun rejected(code:String?=null,action:()->Unit) {
        try { action(); fail("accepted invalid input") } catch(e:IllegalStateException) { if(code!=null) assertEquals(code,e.message) }
    }
    @Test fun originalSharedVectorBytesArePinned() {
        val manifest=vector("manifest").getJSONObject("sha256")
        for(name in manifest.keys()) {
            val raw=javaClass.classLoader!!.getResourceAsStream("syncv2/$name")!!.use{it.readBytes()}
            assertEquals(name,manifest.getString(name),SyncV2Codec.hex(SyncV2Codec.sha(raw)))
        }
    }
    @Test fun sharedEntityAndOpaqueDerivations() {
        val v=vector("derivation"); val key=SyncV2Codec.unhex(v.getString("idKeyHex"),32)
        val cases=v.getJSONArray("cases")
        for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i);val x=c.getJSONObject("input")
            assertEquals(c.getString("expected"),SyncV2Codec.entity(key,x.getString("repoId"),x.getString("kind"),x.getString("logicalId")))
        }
        val opaque=v.getJSONArray("opaqueCases")
        for(i in 0 until opaque.length()) {
            val c=opaque.getJSONObject(i);val x=c.getJSONObject("input");val bytes=x.getString("plainHex")
            assertEquals(c.getString("expected"),SyncV2Codec.opaque(key,v.getJSONObject("scope"),x.getString("type"),SyncV2Codec.unhex(bytes,bytes.length/2)))
        }
    }
    @Test fun sharedFrozenBookLifecycleAndCopies() {
        val cases=vector("book-merge").getJSONArray("cases")
        for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i)
            if(c.has("expectedError"))rejected { SyncV2Business.mergeBook(c.getJSONObject("left"),c.getJSONObject("right")) }
            else {
                same(c.get("expected"),SyncV2Business.mergeBook(c.getJSONObject("left"),c.getJSONObject("right")))
                same(c.get("expected"),SyncV2Business.mergeBook(c.getJSONObject("right"),c.getJSONObject("left")))
            }
        }
    }
    @Test fun sharedFrozenNotesAncestorsAndConflicts() {
        val cases=vector("notes-merge").getJSONArray("cases")
        for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i)
            same(c.get("expected"),SyncV2Business.mergeNote(c.getString("base"),c.getString("local"),c.getString("remote")))
        }
    }
    @Test fun identityRecoveryAuthenticatesOriginalKAT() {
        val v=vector("identity-bootstrap");val id=SyncV2Fixtures.identity()
        assertEquals(v.getString("entityKey"),id.entity("article","article-1"))
        assertEquals(v.getJSONObject("request").getString("descriptorOid"),id.descriptorOid)
    }
    @Test fun identityRejectsWrongRootAccountScopePinCiphertextAndMissingCapsule() {
        val v=vector("identity-bootstrap");val c=v.getJSONObject("context");val root=SyncV2Codec.unhex(v.getString("wrapKeyHex"),32);val pin=v.getJSONObject("request").getString("descriptorOid")
        fun recover(capsule:JSONObject=v.getJSONObject("capsule"),descriptor:JSONObject=v.getJSONObject("descriptor"),wrap:ByteArray=root,account:String=c.getString("accountId"),scope:JSONObject=c.getJSONObject("scope"),p:String=pin) = SyncV2Identity.recover(capsule,descriptor,wrap,account,scope,p)
        rejected {recover(wrap=ByteArray(32))};rejected {recover(account="ff".repeat(16))}
        rejected {recover(scope=JSONObject(c.getJSONObject("scope").toString()).put("libraryEpoch","2"))}
        rejected {recover(p="repo-descriptor:sha256:"+"f".repeat(64))}
        rejected {recover(capsule=JSONObject())}
        val tampered=JSONObject(v.getJSONObject("capsule").toString()).apply{put("ciphertext","A"+getString("ciphertext").drop(1))}
        rejected {recover(capsule=tampered)}
        val d=JSONObject(v.getJSONObject("descriptor").toString());d.getJSONObject("body").put("creatorPlatform","cli")
        rejected {recover(descriptor=d)}
    }
    @Test fun deliveryRejectsWrongRecipientAccountChallengeDescriptorAndRoot() {
        val v=vector("identity-bootstrap");val req=v.getJSONObject("request");val descriptor=v.getJSONObject("descriptor")
        val root=SyncV2Codec.unhex(v.getString("wrapKeyHex"),32)
        val proof=SyncV2Codec.b64(SyncV2Codec.hmac(root,SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/id-key-request"),SyncV2Codec.utf8(SyncV2Codec.canonical(req)))))
        val request=JSONObject().put("body",req).put("proof",proof)
        val h=JSONObject(req.toString()).put("domain","wrss.sync.v2/id-key-delivery").put("salt",SyncV2Codec.b64(SyncV2Codec.unhex(v.getString("saltHex"),32))).put("nonce",SyncV2Codec.b64(SyncV2Codec.unhex(v.getString("nonceHex"),12)))
        val aad=SyncV2Codec.utf8(SyncV2Codec.canonical(h))
        val encryption=SyncV2Codec.hkdf(root,SyncV2Codec.decode64(h.getString("salt")),SyncV2Codec.lp(SyncV2Codec.utf8(h.getString("domain")),SyncV2Codec.sha(aad)))
        val response=JSONObject().put("header",h).put("ciphertext",SyncV2Codec.b64(SyncV2Codec.encrypt(encryption,SyncV2Codec.decode64(h.getString("nonce")),aad,SyncV2Codec.utf8(SyncV2Codec.canonical(JSONObject().put("idKeyHex",v.getString("idKeyHex")))))))
        val member=SyncV2Identity.Companion.Member(req.getString("recipientDeviceId"),req.getString("recipientPlatform"),true)
        fun receive(value:JSONObject=response,r:JSONObject=request,k:ByteArray=root,m:SyncV2Identity.Companion.Member=member)=SyncV2Identity.receive(value,r,descriptor,k,1000,m)
        assertEquals(v.getString("entityKey"),receive().entity("article","article-1"))
        val reject=v.getJSONArray("rejectDelivery")
        for(i in 0 until reject.length()) {
            val c=reject.getJSONObject(i);val changed=JSONObject(response.toString());changed.getJSONObject("header").put(c.getString("field"),c.get("value"))
            rejected{receive(value=changed)}
        }
        rejected{receive(k=ByteArray(32))};rejected{receive(m=member.copy(authorized=false))}
        val forged=JSONObject(request.toString());forged.getJSONObject("body").put("expiresAtMillis","301000")
        rejected("invalid_schema"){receive(r=forged)}
    }
    @Test fun sharedA03A04A18A20FixedExpectedAgainstProductGuards() {
        val cases=vector("adversarial-review").getJSONArray("cases")
        fun expected(id:String)=(0 until cases.length()).map(cases::getJSONObject).single{it.getString("id")==id}.getJSONObject("expected")
        val e=SyncV2Fixtures.envelope();val sig=SyncV2Codec.decode64(e.getString("signature"),64)
        val n=java.math.BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551",16)
        val high=(n-java.math.BigInteger(1,sig.copyOfRange(32,64))).toByteArray().takeLast(32).toByteArray()
        val message=SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/commit-sign"),SyncV2Codec.utf8(SyncV2Codec.oid("commit",e.getJSONObject("body"))))
        val pub=vector("commit").getString("publicKeyHex")
        same(expected("A03"),JSONObject().put("returned",SyncV2Identity.verifySignature(pub,message,SyncV2Codec.b64(sig.copyOfRange(0,32)+high))))
        same(expected("A04"),JSONObject().put("returned",SyncV2Identity.verifySignature("00"+pub.drop(2),message,e.getString("signature"))))
        val b=SyncV2Fixtures.identity().verifyHeader(SyncV2Fixtures.observationHeaders()[0]);val kind=b.getJSONObject("operation").getString("kind");val generation=b.getString("rollbackGeneration")
        same(expected("A18"),JSONObject().put("returned",JSONObject().put("modelKind",kind).put("signedKind",kind).put("modelGeneration",generation).put("signedGeneration",generation)))
        val failure=try{SyncV2Codec.next("18446744073709551615");"accepted"}catch(t:IllegalStateException){t.message}
        same(expected("A20"),JSONObject().put("returned",failure))
    }
    @Test fun signedHeaderKATAndUnknownFields() {
        val e=SyncV2Fixtures.envelope();val id=SyncV2Fixtures.identity()
        same(e.get("body"),id.verifyHeader(e))
        for(field in listOf("indexComplete","treeRoot","operation","deviceSequence")) {
            val changed=JSONObject(e.toString()); changed.getJSONObject("body").remove(field)
            rejected {id.verifyHeader(changed)}
        }
        val changed=JSONObject(e.toString());changed.getJSONObject("body").put("extra",true)
        rejected("invalid_schema") {id.verifyHeader(changed)}
    }
    @Test fun affectedRecursiveClosureMatchesC01C06() {
        val cases=vector("rc4-review").getJSONArray("cases")
        for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i);if(c.getString("id") !in setOf("C01","C06"))continue
            val x=c.getJSONObject("input")
            same(c.getJSONObject("expected").get("affected"),JSONArray(SyncV2Business.affected(x.getJSONObject("before"),x.getJSONObject("target"))))
        }
    }
    @Test fun graphUnknownAndEmptyEdgesMatchE12E13() {
        val fixed=vector("rc6-review").getJSONArray("cases")
        val e12=(0 until fixed.length()).map{fixed.getJSONObject(it)}.single{it.getString("id")=="E12"}.getJSONObject("expected")
        val e13=(0 until fixed.length()).map{fixed.getJSONObject(it)}.single{it.getString("id")=="E13"}.getJSONObject("expected")
        fun affected(raw:String)=JSONObject(raw).let{SyncV2Business.affected(it,it)}
        same(e12.get("unchangedNull"),affected("{a:{dependencies:null}}"))
        same(e13.get("wrongType"),affected("{a:{dependencies:42}}"))
        same(e13.get("invalidElement"),affected("{a:{dependencies:[null]}}"))
        same(e12.get("absent"),JSONArray(SyncV2Business.affected(JSONObject("{a:{v:2}}"),JSONObject("{a:{v:1}}"))))
        same(e12.get("empty"),JSONArray(SyncV2Business.affected(JSONObject("{a:{v:2,dependencies:[]}}"),JSONObject("{a:{v:1,dependencies:[]}}"))))
    }
    @Test fun allExplicitAdjacencyCheckedBeforeSeedsW03() {
        val values=vector("rc6-contracts").getJSONArray("malformedDependencies")
        for(i in 0 until values.length())for(side in 0..1) {
            val bad=JSONObject().put("a",JSONObject().put("dependencies",values.get(i)));val good=JSONObject("{a:{dependencies:[]}}")
            assertNull(SyncV2Business.affected(if(side==0)bad else good,if(side==0)good else bad))
        }
        val cyclic=JSONObject("{a:{v:2,dependencies:['b']},b:{dependencies:['a']}}")
        assertEquals(listOf("a","b"),SyncV2Business.affected(cyclic,JSONObject("{a:{v:1,dependencies:['b']},b:{dependencies:['a']}}")))
        assertNull(SyncV2Business.affected(JSONObject("{a:{dependencies:['missing']}}"),JSONObject()))
    }
    @Test fun d64AndImmutableCaptureQ01() {
        assertEquals("18446744073709551615",SyncV2Codec.next("18446744073709551614"))
        rejected("counter_exhausted") {SyncV2Codec.next("18446744073709551615")}
        listOf("00","-1","18446744073709551616").forEach {rejected("invalid_schema"){SyncV2Codec.d64(it)}}
        val full=ChoiceCapture("0","commit:sha256:"+"a".repeat(64),"1","0","0")
        assertEquals(full,ChoiceCapture.restore(full.json()))
        // A final Kotlin value type has no symbols/non-enumerable properties. Serialized extras fail exact keys.
        rejected("choice_stale") {ChoiceCapture.restore(full.json().put("nonEnumerableEquivalent",true))}
        rejected("choice_stale") {ChoiceCapture.restore(full.json().put("symbolEquivalent",true))}
        rejected("choice_stale") {ChoiceCapture.restore(JSONObject().put("oid",full.accountOid).put("revision","1").put("generation","0"))}
    }
    @Test fun wireRetainsNegativeSortUnknownTimesAndSixDigitProgress() {
        val raw=JSONObject("{articleId:'old',sourceDeviceId:'legacy',url:'https://example.invalid',title:'中😀',updatedAt:0,favoriteSortOrder:-9,readingProgress:0.1234567}")
        val p=SyncV2Business.split("article","old","a".repeat(64),raw,null)
        assertEquals("unknown",p.meta.getString("addedAtSource")); assertFalse(p.meta.getJSONObject("payload").has("importedAt"))
        assertEquals(123457L,p.state.getJSONObject("payload").getLong("readingProgressMicros"))
        assertEquals(-9L,SyncV2Business.join("article",p.meta,p.state).getLong("favoriteSortOrder"))
        rejected("unsupported_feature") {SyncV2Business.split("article","old","a".repeat(64),JSONObject(raw.toString()).put("unknown",1),null)}
        rejected("invalid_encoding") {SyncV2Codec.canonical(JSONObject().put("bad","\uD800"))}
        rejected("invalid_encoding") {SyncV2Codec.canonical(1.0)}
    }
}
