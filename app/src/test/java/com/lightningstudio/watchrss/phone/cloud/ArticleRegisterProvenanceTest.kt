package com.lightningstudio.watchrss.phone.cloud

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArticleRegisterProvenanceTest {
    private fun resource(name:String)=requireNotNull(javaClass.getResource("/article-provenance-v1/$name")).readBytes()
    private fun fixture()=JSONObject(resource("vectors.json").toString(Charsets.UTF_8))
    private fun record(v:JSONObject)=ArticleRegisterProvenance.decode(v.toString().toByteArray())
    private fun checkExpected(expected:JSONObject,actual:ArticleRegisterProvenance.Record) {
        val text=actual.canonicalUtf8();assertEquals(expected.getString("canonicalUtf8"),text)
        assertEquals(expected.getString("sha256"),actual.sha256())
        assertEquals(expected.getString("utf8Hex"),text.toByteArray(Charsets.UTF_8).joinToString(""){"%02x".format(it)})
        assertEquals(expected.getString("positionStatus"),actual.positionStatus())
        for(p in listOf("independent","favorite","watchLater"))assertEquals(expected.getJSONObject("effectiveSaved").getBoolean(p),actual.value.getBoolean(p+"Saved")&&!actual.value.getBoolean("deleted"))
    }
    @Test fun manifestAndAll159MergesUseProductionRegisterCodec() {
        assertEquals("b3cf35fa707d3b45d3b703d44e265e77260e18a1a6de2b7b510362423b499830",CloudSnapshotCodec.sha256(resource("manifest.json")))
        val cases=fixture().getJSONArray("mergeCases");assertEquals(159,cases.length())
        var properties=0
        for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i);val source=c.getJSONArray("inputs");val values=(0..2).map{
                val decoded=ArticleProvenanceFixtures.decode(source.getJSONObject(it).toString())
                assertEquals(CloudFormatAccess.READ_ONLY,decoded.access)
                (decoded.value as ArticleProvenanceValue).record
            }
            val merge=ArticleRegisterProvenance::merge
            checkExpected(c.getJSONObject("expected"),merge(merge(values[0],values[1]),values[2]))
            val encryptedInput=ArticleProvenanceFixtures.decode(c.getJSONObject("expected").getString("canonicalUtf8"))
            assertEquals(CloudFormatAccess.READ_ONLY,encryptedInput.access)
            val production=(encryptedInput.value as ArticleProvenanceValue).record
            checkExpected(c.getJSONObject("expected"),production)
            assertSame(encryptedInput.value,PhoneCloudRecordCodec.knownValue("article",encryptedInput.value))
            assertEquals(merge(merge(values[0],values[1]),values[2]).canonicalUtf8(),merge(values[0],merge(values[1],values[2])).canonicalUtf8())
            for(a in values)for(b in values){assertEquals(merge(a,b).canonicalUtf8(),merge(b,a).canonicalUtf8());properties++}
            for(a in values){assertEquals(a.canonicalUtf8(),merge(a,a).canonicalUtf8());properties++}
            val orders=c.getJSONArray("orders")
            for(j in 0 until orders.length()) {
                val o=orders.getJSONObject(j);val order=o.getJSONArray("order");val indices=(0..2).map{order.getInt(it)}
                var current=values[indices[0]];val hashes=o.getJSONArray("replayPrefixSha256")
                for((n,k) in (indices.drop(1)+indices).withIndex()){current=merge(current,values[k]);assertEquals(c.getString("id"),hashes.getString(n),current.sha256())}
                checkExpected(c.getJSONObject("expected"),current);properties++
            }
        }
        assertEquals(2862,properties)
    }
    @Test fun all13NormalizationAndOneTimeLegacySeedVectorsMatchExactBytes() {
        val cases=fixture().getJSONArray("normalizationCases");assertEquals(13,cases.length())
        for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i);val raw=c.getString("raw")
            val actual=if(c.getString("mode")=="legacySeed")ArticleRegisterProvenance.seedLegacy(raw) else ArticleRegisterProvenance.decode(raw.toByteArray())
            checkExpected(c.getJSONObject("expected"),actual)
            if(c.getString("mode")!="legacySeed")checkExpected(c.getJSONObject("expected"),(ArticleProvenanceFixtures.decode(raw).value as ArticleProvenanceValue).record)
        }
    }
    @Test fun all42RejectVectorsFailBeforeLossyJsonOrFloatConversion() {
        val cases=JSONObject(resource("reject.json").toString(Charsets.UTF_8)).getJSONArray("cases");assertEquals(42,cases.length())
        for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i)
            // Resolve fixture fields outside assertThrows: a missing fixture field must fail the test.
            val operation:()->Unit=when(c.getString("mode")) {
                "merge"->{val a=record(c.getJSONObject("left"));val b=record(c.getJSONObject("right"));{ArticleRegisterProvenance.merge(a,b);Unit}}
                "legacySeed"->{val raw=c.getString("raw");{ArticleRegisterProvenance.seedLegacy(raw);Unit}}
                else->{val bytes=if(c.has("rawHex"))c.getString("rawHex").chunked(2).map{it.toInt(16).toByte()}.toByteArray() else c.getString("raw").toByteArray();{ArticleRegisterProvenance.decode(bytes);Unit}}
            }
            try {
                operation()
                fail("accepted reject vector ${c.getString("id")}")
            } catch(e:Exception) { assertFalse(e is java.util.concurrent.CancellationException) }
            if(c.getString("mode") !in setOf("merge","legacySeed")) {
                val id=PhoneCloudRecordCodec.id(ArticleProvenanceFixtures.key,ArticleProvenanceFixtures.user,"article",JSONObject().put("articleId","synthetic-article"))
                val bytes=if(c.has("rawHex"))c.getString("rawHex").chunked(2).map{it.toInt(16).toByte()}.toByteArray() else c.getString("raw").toByteArray()
                val env=ArticleProvenanceFixtures.envelope(bytes,id)
                val decode={PhoneCloudRecordCodec.decryptDocument(ArticleProvenanceFixtures.key,ArticleProvenanceFixtures.user,id,env)}
                when(c.getString("error")) {
                    "opaque_feature"->assertEquals(CloudFormatAccess.OPAQUE,decode().access)
                    "read_only_feature"->assertEquals(CloudFormatAccess.READ_ONLY,decode().access)
                    else->{
                        val result=try{decode()}catch(_:IllegalArgumentException){null}
                        // R48 may quarantine malformed feature envelopes rather than expose a projection.
                        if(result!=null)assertTrue("production decoder ${c.getString("id")}",result.access in setOf(CloudFormatAccess.OPAQUE,CloudFormatAccess.ISOLATED))
                    }
                }
            }
        }
    }
    @Test fun unknownTokensArePreservedAndEqualMetadataExtensionConflictsCannotBePublished() {
        val raw=fixture().getJSONArray("mergeCases").getJSONObject(0).getJSONArray("inputs").getJSONObject(0).toString()
        val base=CloudRawDocument.parse(raw).patch(listOf("value","future"),"{\"n\":1e+03,\"s\":\"\\u0061\"}")
        val original=ArticleRegisterProvenance.decode(base)
        val same=ArticleRegisterProvenance.merge(original,original)
        assertEquals(base.raw(listOf("value","future")),same.original.raw(listOf("value","future")))
        val changed=ArticleRegisterProvenance.decode(base.patch(listOf("value","future"),"{\"n\":1001}"))
        assertThrows(CloudFormatUpgradeRequired::class.java){ArticleRegisterProvenance.merge(original,changed)}
        assertFalse(ArticleRegisterProvenance.writeV1)
    }
}
