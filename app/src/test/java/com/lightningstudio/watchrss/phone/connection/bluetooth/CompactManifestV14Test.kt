package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.cloud.PhoneCloudRecordCodec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompactManifestV14Test {
    private val vector=JSONObject(requireNotNull(javaClass.getResource("/ble/compact-manifest-v14.json")).readText())
    private fun parse(row:JSONObject)=LibrarySyncPayload.parseArticleManifest(JSONArray().put(row)).single()
    private fun canonical(row:JSONObject)=PhoneCloudRecordCodec.canonical(row)

    @Test fun allTenSharedCasesEncodeExactlyAndReadBackWithUnchangedClocksAndHashes() {
        val cases=vector.getJSONArray("cases");assertEquals(10,cases.length())
        var verboseBytes=0;var compactBytes=0
        for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i);val verbose=c.getJSONObject("verbose");val expected=c.getJSONObject("compact")
            val original=parse(verbose);val compact=LibrarySyncPayload.encodeManifestEntry(original,compactDefaults=true)
            assertEquals(c.getString("name"),canonical(expected),canonical(compact))
            assertEquals(original,parse(compact));assertEquals(parse(c.getJSONObject("normalized")),parse(compact))
            for(key in listOf("bodyHash","metadataHash","bodyByteCount","chunkSize","chunkHashes"))assertEquals(verbose.get(key).toString(),compact.get(key).toString())
            for(key in listOf("independentChangedAt","favoriteChangedAt","watchLaterChangedAt","deletedAt","readingPositionChangedAt")) {
                if(verbose.optLong(key)!=0L)assertEquals(verbose.getLong(key),compact.getLong(key))
            }
            assertEquals(c.getInt("compactBytes"),canonical(compact).toByteArray().size)
            assertEquals(c.getInt("verboseBytes"),canonical(verbose).toByteArray().size)
            verboseBytes+=c.getInt("verboseBytes");compactBytes+=c.getInt("compactBytes")
        }
        println("compact manifest vectors: $verboseBytes -> $compactBytes bytes")
    }

    @Test fun onlyV2PersistentSessionsOptInAndLegacyBuilderAndDiskEncodingStayVerbose() {
        val source=vector.getJSONArray("cases").getJSONObject(0).getJSONObject("verbose");val entry=parse(source)
        for(flag in listOf<Any?>(null,false,1,"true",true))for(persistent in listOf(false,true)) {
            val declarations=JSONObject().apply{if(flag!=null)put("supportsDomainFastPathV2",flag)}
            val enabled=LibrarySyncPayload.compactManifestEnabled(BleSyncCapabilities.read(declarations),persistent)
            assertEquals(flag==true && persistent,enabled)
            val request=LibrarySyncPayload.buildManifestRequestFromEntries("phone",listOf(entry),compactDefaults=enabled)
            val row=request.getJSONArray("articleManifest").getJSONObject(0)
            assertEquals(14,request.getInt("version"));assertEquals(entry,parse(row))
            assertEquals(!enabled,row.has("bodyAvailable"))
        }
        val fallback=LibrarySyncPayload.buildManifestRequestFromEntries("phone",listOf(entry)).getJSONArray("articleManifest").getJSONObject(0)
        assertEquals(canonical(source),canonical(fallback))
        assertEquals(canonical(source),canonical(LibrarySyncPayload.encodeManifestEntry(entry)))
    }

    @Test fun explicitKeepUnreadAndNondefaultValuesNeverBecomeADeletionOrStaleRead() {
        val sample=parse(vector.getJSONArray("cases").getJSONObject(9).getJSONObject("verbose"))
        val compact=LibrarySyncPayload.encodeManifestEntry(sample,true)
        assertFalse(compact.getBoolean("deleted"));assertTrue(compact.getLong("deletedAt")>0)
        assertFalse(parse(compact).isRead);assertEquals(1234L,compact.getLong("readingPositionChangedAt"))
        val unusual=sample.copy(deleted=true,deletedAt=0,bodyAvailable=false,bodySyncMode="saved",isRead=true,legacyArticleIds=listOf("old-id"))
        val encoded=LibrarySyncPayload.encodeManifestEntry(unusual,true)
        assertTrue(encoded.getBoolean("deleted"));assertFalse(encoded.getBoolean("bodyAvailable"));assertTrue(encoded.getBoolean("isRead"))
        assertEquals(unusual,parse(encoded));assertEquals("old-id",encoded.getJSONArray("legacyArticleIds").getString(0))
    }
}
