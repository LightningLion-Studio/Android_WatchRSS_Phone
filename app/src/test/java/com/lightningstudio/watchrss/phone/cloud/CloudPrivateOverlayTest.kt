package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.data.backup.CloudPrivateOverlayArchive
import com.lightningstudio.watchrss.phone.data.backup.WatchRssBackupArchive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class CloudPrivateOverlayTest {
    private fun fixture(name:String)=JSONObject(requireNotNull(javaClass.getResource("/r48-write-v1/$name.json")).readText())
    private fun hex(s:String)=CloudMigrationCrypto.hex(s)
    private val codec=CloudSnapshotCodec()
    @Test fun sharedMixedManifestAndBothOriginalFormatsDecodeThroughProductionCodecs() {
        val v=fixture("mixed-manifest");val key=hex(v.getString("accountKeyHex"))
        val manifest=codec.decryptManifest(key,v.getString("snapshotId"),hex(v.getString("encryptedManifestHex")))
        assertEquals(CloudFormatAccess.WRITABLE,manifest.access)
        assertEquals(CloudFormatAccess.OPAQUE,CloudFormatR48.access(CloudRawDocument.parse(manifest.toRawString()),known=emptySet()))
        val blobs=v.getJSONArray("blobs").let{a->(0 until a.length()).associate { i -> val b=a.getJSONObject(i);b.getJSONObject("descriptor").getJSONArray("chunks").getJSONObject(0).getString("ciphertextSha256") to hex(b.getString("blobHex")) }}
        val selected=manifest.copy(objects=manifest.objects.filter{it.access==CloudFormatAccess.WRITABLE})
        val restored=codec.restoreObjects(key,selected){blobs.getValue(it)}
        val index=CloudPrivateOverlay.decode(restored.getValue(CloudPrivateOverlay.NAME))
        val expected=v.getJSONObject("expected")
        val legacy=WatchRssBackupArchive.read(restored.getValue("private-library.wrss").inputStream(),tolerateCloudEntries=true)
        val active=legacy.articles.mapTo(mutableSetOf()){it.articleId}
        index.forEach{if(it.objectName==null)active.remove(it.id)else active+=it.id}
        assertEquals(expected.getJSONArray("activeArticleIds").let{a->(0 until a.length()).map{a.getString(it)}.toSet()},active)
        val edit=index.single{it.id=="article-edit"}
        val row=CloudPrivateOverlayArchive.validate(restored.getValue(requireNotNull(edit.objectName)),edit.id).articles.single()
        assertEquals(expected.getString("editedTextUtf8"),row.contentText)
        assertEquals(expected.getString("editedHtmlUtf8"),row.contentHtml)
        val original=manifest.objects.single{it.name=="private-library.wrss"}
        assertEquals(CloudMigrationJcs.canonical(expected.getJSONObject("unchangedDescriptor").toString()),CloudMigrationJcs.canonical(original.toRawString()))
        val unknown=manifest.objects.first{it.name=="future/object.bin"}
        assertEquals(expected.getString("unknownDescriptorRawUtf8"),unknown.toRawString())
        val next=codec.create(key,1,"phone",3,emptyList(),previousManifest=manifest,carryForwardObjects=manifest.objects)
        assertEquals(0,next.newCiphertextChunks.size)
        assertEquals(unknown.toRawString(),next.manifest.objects.first{it.name==unknown.name}.toRawString())
        assertArrayEquals(hex(v.getString("encryptedManifestHex")),codec.encryptManifestBytes(key,manifest.snapshotId,v.getString("manifestUtf8").toByteArray(),hex(v.getString("manifestSaltHex")),hex(v.getString("manifestNonceHex"))))
    }
    @Test fun entryReferenceWriterUsesRealArchiveAndRejectsAllTraversalAndChains() {
        val v=fixture("entry-ref");val source=CloudObjectDescriptor.fromJson(v.getJSONObject("archiveDescriptor"));val template=CloudObjectDescriptor.fromJson(v.getJSONObject("descriptor"))
        val plain=hex(v.getString("expectedEntryHex"));val ref=template.entryRef!!
        val written=CloudObjectWriter.entryReference(template.name,source,ref.getString("entryPath"),plain,template)
        val manifest=CloudSnapshotManifest("00000000-0000-4000-8000-000000000001","phone",1,1,1,emptyMap(),emptyMap(),listOf(source,written))
        assertArrayEquals(plain,codec.restoreObjects(hex(v.getString("accountKeyHex")),manifest){hex(v.getString("archiveBlobHex"))}.getValue(written.name))
        val invalid=v.getJSONArray("invalid")
        for(i in 0 until invalid.length()) {
            val r=invalid.getJSONObject(i)
            if(r.has("entryRef"))assertThrows(Exception::class.java){CloudObjectWriter.entryReference(written.name,source,r.getJSONObject("entryRef").getString("entryPath"),plain)}
        }
        assertThrows(Exception::class.java){CloudObjectWriter.entryReference("chain.txt",written,"bodies/x.txt",plain)}
    }
    @Test fun overlayDeleteIsDurableAndMissingReplacementDoesNotInferDelete() {
        val v=fixture("overlay-cases");val entries=CloudPrivateOverlay.decode(v.getJSONObject("index").toString().toByteArray())
        assertNull(entries.first{it.id=="article-delete"}.objectName)
        assertNotNull(entries.first{it.id=="article-edit"}.objectName)
        assertEquals(entries,CloudPrivateOverlay.decode(CloudPrivateOverlay.encode(entries)))
        val live=entries.last();val older=live.copy(changedAt=live.changedAt-1,objectName=null)
        assertEquals(live,CloudPrivateOverlay.merge(listOf(live,older)).single())
        assertNull(CloudPrivateOverlay.merge(listOf(live,live.copy(objectName=null))).single().objectName)
        assertTrue(CloudPrivateOverlay.merge(emptyList()).isEmpty())
        val extra=JSONObject(v.getJSONObject("index").toString());extra.getJSONArray("entries").getJSONObject(0).put("extra",1)
        assertThrows(Exception::class.java){CloudPrivateOverlay.decode(extra.toString().toByteArray())}
        extra.getJSONArray("entries").getJSONObject(0).remove("extra");extra.getJSONArray("entries").getJSONObject(0).put("changedAt","1")
        assertThrows(Exception::class.java){CloudPrivateOverlay.decode(extra.toString().toByteArray())}
    }
    @Test fun overlayAndManifestLimitsFailBeforePublishingAndTypedFieldsDoNotCoerce() {
        val entries=(0..8192).map { CloudPrivateOverlayEntry("article","a$it",null,it.toLong(),"phone") }
        assertThrows(CloudFormatUpgradeRequired::class.java){CloudPrivateOverlay.encode(entries)}
        val tooMany=(0..1024).map { CloudLogicalObject("objects/$it",byteArrayOf(1),false) }
        assertThrows(CloudFormatUpgradeRequired::class.java){codec.create(ByteArray(32),1,"phone",1,tooMany)}
        val valid=JSONObject(CloudPrivateOverlay.encode(entries.take(1)).toString(Charsets.UTF_8))
        for(field in listOf("id","kind","sourceDeviceId","object")) {
            val bad=JSONObject(valid.toString());bad.getJSONArray("entries").getJSONObject(0).put(field,123)
            assertThrows(Exception::class.java){CloudPrivateOverlay.decode(bad.toString().toByteArray())}
        }
        valid.put("version","1")
        assertThrows(Exception::class.java){CloudPrivateOverlay.decode(valid.toString().toByteArray())}
    }
    @Test fun microWrssPreservesHtmlAndUnknownMetadataButRejectsMixedLibraryOrCredentials() {
        val v=fixture("mixed-manifest");val original=Base64.getDecoder().decode(v.getString("editedArchiveBase64"))
        val row=CloudPrivateOverlayArchive.validate(original,"article-edit").articles.single()
        val raw=CloudPrivateOverlayArchive.rawArticles(original).getValue(row.articleId)
        val extended=CloudRawDocument.parse(raw).patch(listOf("future"),"{ \"number\":9007199254740993,\"escaped\":\"\\u4e2d\" }")
        val encoded=CloudPrivateOverlayArchive.encode(row.copy(title="local edit"),extended.source)
        val restored=CloudPrivateOverlayArchive.validate(encoded,row.articleId).articles.single()
        assertEquals(row.contentHtml,restored.contentHtml);assertEquals(row.contentText,restored.contentText)
        assertTrue(CloudPrivateOverlayArchive.rawArticles(encoded).getValue(row.articleId).contains("{ \"number\":9007199254740993,\"escaped\":\"\\u4e2d\" }"))
        assertEquals(CloudPrivateOverlayArchive.signature(row),CloudPrivateOverlayArchive.signature(row.copy(favoriteSaved=!row.favoriteSaved,favoriteChangedAt=300,favoriteSortOrder=900,readingProgress=.4f)))
        assertThrows(Exception::class.java){CloudPrivateOverlayArchive.validate(Base64.getDecoder().decode(v.getString("legacyArchiveBase64")),row.articleId)}
        assertThrows(Exception::class.java){CloudPrivateOverlayArchive.validate(encoded,"another-id")}
    }
    @Test fun newOutputKeyIsReadonlyAndUserEditReturnsToAccountRoot() {
        val v=fixture("object-key");val account=hex(v.getString("accountKeyHex"));val d=CloudObjectDescriptor.fromJson(v.getJSONObject("descriptor"))
        val plain=hex(v.getString("outputPlaintextHex"));val blob=hex(v.getString("outputChunkBlobHex"))
        val old=CloudSnapshotManifest("11111111-1111-4111-8111-111111111111","phone",1,1,1,emptyMap(),emptyMap(),listOf(d))
        assertArrayEquals(plain,codec.restoreObjects(account,old){blob}.getValue(d.name))
        val newer=codec.create(account,1,"phone",2,listOf(CloudLogicalObject(d.name,"changed".toByteArray(),false)),previousManifest=old)
        assertTrue(newer.manifest.objects.single().accountRoot)
        assertArrayEquals("changed".toByteArray(),codec.restoreObjects(account,newer.manifest){newer.newCiphertextChunks.getValue(it)}.getValue(d.name))
    }
    @Test fun writerAndDelegationGatesFailClosedIndependently() {
        val caps=JSONObject().put("phaseA",JSONObject().put("cloudFormatR48WriteV1",true)).put("phaseB",JSONObject().put("cloudFormatMigrationR48V1",true))
        assertFalse(CloudFormatWritePolicy().canWrite(caps));assertFalse(CloudFormatWritePolicy(true,false).canMigrate(caps))
        assertFalse(CloudFormatWritePolicy(true,true).canMigrate(null));assertTrue(CloudFormatWritePolicy(true,true).canMigrate(caps))
        caps.getJSONObject("phaseA").put("cloudFormatR48WriteV1",false)
        assertFalse(CloudFormatWritePolicy(true,true).canMigrate(caps))
    }
}
