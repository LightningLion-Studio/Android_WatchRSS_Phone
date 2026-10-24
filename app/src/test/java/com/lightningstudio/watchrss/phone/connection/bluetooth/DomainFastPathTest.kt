package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.data.ble.BleDomainStore
import org.json.*
import org.junit.Test
import org.junit.Assert.*

class DomainFastPathTest {
    private val a="a".repeat(64);private val b="b".repeat(64);private val c="c".repeat(64)
    private val versions=BleDomainStore.DOMAINS.associateWith { BleDomainStore.Projection(1,18,a) }
    private fun window(known:Boolean=true)=DomainFastPath("phone","watch",mapOf("library" to a,"reader" to a),if(known)mapOf("library" to(a to b),"reader" to(a to b))else emptyMap(),versions)
    private fun response(phase:String,roots:Map<String,String> =mapOf("library" to b,"reader" to b))=JSONObject().put("success",true).put("action","syncDomains").put("version",2).put("phase",phase).put("deviceId","watch").put("windowId","window").put("roots",JSONObject(roots)).put("projectionVersions",JSONObject().apply { versions.forEach{(d,p)->put(d,p.json())} })
    private fun summary(skip:List<String> =listOf("library","reader"))=response("summary").put("skipDomains",JSONArray(skip))
    private fun commit(saved:List<String> =listOf("library","reader"),retry:List<String> =emptyList())=response("commit").put("committedDomains",JSONArray(saved)).put("retryDomains",JSONArray(retry))
    private fun rejects(block:()->Unit){assertNotNull(runCatching(block).exceptionOrNull())}
    @Test fun sharedVersionVectors() {
        val vectors=JSONObject(javaClass.getResource("/ble/domain-fast-path-v2.json")!!.readText()).getJSONArray("cases")
        for(i in 0 until vectors.length()) {
            val v=vectors.getJSONObject(i)
            val p=BleDomainStore.Projection(v.getInt("encoder"),v.getInt("schema"),v.getString("definition"))
            assertEquals(v.getString("root"),BleDomainStore.versionRoot(v.getString("domain"),v.getString("epoch"),v.getLong("revision"),p))
            assertEquals(v.getString("projectionText"),p.text(v.getString("domain")))
            assertEquals(v.getString("projectionHash"),BleDomainStore.hash(p.text(v.getString("domain"))))
        }

    }
    @Test fun bothMirroredReceiptsAndCurrentRootsRequired() {
        val w=window();w.acceptSummary(summary());assertEquals(setOf("library","reader"),w.skipped)
        rejects{window(false).acceptSummary(summary())}
        rejects{window().acceptSummary(summary().put("roots",JSONObject(mapOf("library" to c,"reader" to b))))}
        val changed=DomainFastPath("phone","watch",mapOf("library" to c,"reader" to a),mapOf("library" to(a to b),"reader" to(a to b)),versions)
        changed.acceptSummary(summary(listOf("reader")));assertEquals(setOf("reader"),changed.skipped)
    }
    @Test fun missingRootFallsBackOnlyThatDomain() {
        val w=DomainFastPath("phone","watch",mapOf("library" to a),mapOf("library" to(a to b)),versions)
        w.acceptSummary(response("summary",mapOf("library" to b)).put("skipDomains",JSONArray(listOf("library"))))
        assertEquals(setOf("library"),w.skipped)
        assertEquals(1,w.remoteRootCount);assertTrue("reader" in w.missingRemote)
    }
    @Test fun capabilityPersistentProfileForcedAndLegacyFallback() {
        val cap=BleSyncCapabilities(canonicalRssIds=true,books=true,bookReadingDelta=true,domainFastPath=true)
        assertTrue(DomainFastPath.enabled(cap,true,false,LibrarySyncMode.MERGE))
        assertFalse(DomainFastPath.enabled(cap.copy(domainFastPath=false),true,false,LibrarySyncMode.MERGE))
        assertFalse(DomainFastPath.enabled(cap.copy(bookReadingDelta=false),true,false,LibrarySyncMode.MERGE))
        assertFalse(DomainFastPath.enabled(cap,false,false,LibrarySyncMode.MERGE))
        assertFalse(DomainFastPath.enabled(cap,true,true,LibrarySyncMode.MERGE))
        assertFalse(DomainFastPath.enabled(cap,true,false,LibrarySyncMode.PHONE_TO_WATCH_REPLACE))
    }
    @Test fun rejectsWrongPeerPhaseVersionWindowUnknownAndDuplicateDomains() {
        for(bad in listOf(summary().put("deviceId","other"),summary().put("phase","commit"),summary().put("version",1),summary().put("windowId",""),summary().put("windowId","\n"),summary(listOf("library","library")),summary(listOf("unknown")),summary().put("roots",JSONObject().put("bogus",a))))rejects{window().acceptSummary(bad)}
    }
    @Test fun stageOnlyPublishesAfterCompleteAppliedAckAndFinalRootCheck() {
        val w=window();w.acceptSummary(summary());val roots=mapOf("library" to a,"reader" to a);val done=roots.keys
        w.commitRequest(roots,done);w.acceptCommit(commit(),roots,done)
        assertTrue(w.publishable(roots,false).isEmpty())
        assertEquals(2,w.publishable(roots,true).size)
        assertEquals(setOf("reader"),w.publishable(roots+mapOf("library" to c),true).keys)
        assertTrue(window().publishable(roots,true).isEmpty()) // cancelled/new physical connection
    }
    @Test fun editsAfterSummaryCannotBeCertifiedAndNormalRetryIsAccepted() {
        val w=window();w.acceptSummary(summary());val roots=mapOf("library" to c,"reader" to a);val done=roots.keys
        rejects{w.acceptCommit(commit(),roots,done)}
        w.acceptCommit(commit(listOf("reader"),listOf("library")),roots,done)
        assertEquals(setOf("reader"),w.publishable(roots,true).keys)
        assertEquals("library",w.diagnostics(roots)["localChanged"])
    }
    @Test fun partialSuccessCanOnlyCommitCompletedDomains() {
        val w=window(false);w.acceptSummary(summary(emptyList()));val roots=mapOf("library" to a,"reader" to a)
        rejects{w.acceptCommit(commit(),roots,setOf("library"))}
        w.acceptCommit(commit(listOf("library")),roots,setOf("library"));assertEquals(setOf("library"),w.publishable(roots,true).keys)
    }
    @Test fun malformedCommitCannotCertifyUnknownOrChangedRemoteOrDuplicateDomain() {
        for(bad in listOf(commit().put("windowId","other"),commit(listOf("library","library")),commit().put("roots",JSONObject(mapOf("library" to c,"reader" to b))),commit(listOf("library"),listOf("library","reader")))) {
            val w=window();w.acceptSummary(summary());rejects{w.acceptCommit(bad,mapOf("library" to a,"reader" to a),setOf("library","reader"))}
        }
    }
    @Test fun v1PeersNeverSelectUnsafeFastPathAndV2ProjectionFieldsAreMandatory() {
        val legacy=JSONObject().put("supportsDomainFastPathV1",true).put("supportsRssCanonicalIds",true).put("supportsBookSyncV1",true).put("supportsBookReadingDeltaV1",true)
        assertFalse(DomainFastPath.enabled(BleSyncCapabilities.read(legacy),true,false,LibrarySyncMode.MERGE))
        assertTrue(DomainFastPath.enabled(BleSyncCapabilities.read(legacy.put("supportsDomainFastPathV2",true)),true,false,LibrarySyncMode.MERGE))
        rejects {window().acceptSummary(summary().apply{remove("projectionVersions")})}
        rejects {window().acceptSummary(summary().put("projectionVersions",JSONObject()))}
        rejects {window().acceptSummary(summary().put("projectionVersions",JSONObject().put("library",versions.getValue("library").json().put("encoder",0))))}
        val w=window();w.acceptSummary(summary())
        rejects {w.acceptCommit(commit().put("projectionVersions",JSONObject().apply { versions.forEach{(d,p)->put(d,p.copy(schema=p.schema+1).json())} }),mapOf("library" to a,"reader" to a),setOf("library","reader"))}
    }
    @Test fun remoteEncoderOrSchemaUpgradeCannotReuseOldRootPair() {
        val cases=JSONObject(javaClass.getResource("/ble/domain-fast-path-v2.json")!!.readText()).getJSONArray("cases")
        val library=(0 until cases.length()).map{cases.getJSONObject(it)}.filter{it.getString("domain")=="library"}
        val old=library.first().getString("root")
        for(upgraded in library.drop(1)) {
            val root=upgraded.getString("root");assertNotEquals(old,root)
            val w=DomainFastPath("phone","watch",mapOf("library" to a),mapOf("library" to (a to old)),versions)
            rejects {w.acceptSummary(response("summary",mapOf("library" to root)).put("skipDomains",JSONArray(listOf("library"))))}
            w.acceptSummary(response("summary",mapOf("library" to root)).put("skipDomains",JSONArray()))
            assertTrue(w.skipped.isEmpty())
        }
    }
    @Test fun sharedExpiryBoundaryVectors() {
        val cases=JSONObject(javaClass.getResource("/ble/domain-fast-path-v2.json")!!.readText()).getJSONArray("expiryCases")
        for(i in 0 until cases.length()) {val c=cases.getJSONObject(i);assertEquals(c.getBoolean("valid"),BleDomainStore.receiptFresh(c.getLong("verifiedAt"),c.getLong("now")))}
    }

    @Test fun accountGrantReuseWitnessIsBoundToLocalProjectionUpgrade() {
        val current=DomainFastPath("phone","watch",mapOf("account" to a),emptyMap(),versions)
        val changed=DomainFastPath("phone","watch",mapOf("account" to a),emptyMap(),versions+mapOf("account" to versions.getValue("account").copy(encoder=2)))
        assertNotEquals(current.accountGrantWitness(b),changed.accountGrantWitness(b))
        assertNull(current.accountGrantWitness(null))
    }

}
