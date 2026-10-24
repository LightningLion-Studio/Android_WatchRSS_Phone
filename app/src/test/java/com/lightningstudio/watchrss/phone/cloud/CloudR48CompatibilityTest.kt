package com.lightningstudio.watchrss.phone.cloud

import org.json.*
import org.junit.Test
import org.junit.Assert.*
import java.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CloudR48CompatibilityTest {
    private val key=ByteArray(32){it.toByte()}
    private val codec=CloudSnapshotCodec()
    private fun fixture(name:String)=JSONObject(javaClass.getResource("/cloud/r48/r48-$name.json")!!.readText())
    private fun hex(s:String)=s.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
    private fun envelope(raw:String,user:String,id:String):JSONObject {
        val nonce=ByteArray(12){3};val k=CloudSnapshotCodec.hkdfSha256(key,user.toByteArray(),"watchrss/cloud/record/v1".toByteArray(),32)
        val bytes=CloudSnapshotCodec.encryptAesGcm(k,nonce,raw.toByteArray(),"watchrss-cloud-record-v1:$user:$id:1".toByteArray())
        return JSONObject().put("algorithm","A256GCM").put("keyVersion",1).put("nonceBase64",Base64.getEncoder().encodeToString(nonce)).put("ciphertextBase64",Base64.getEncoder().encodeToString(bytes))
    }
    @Test fun allSharedFeatureCasesUseProductionPolicy() {
        val cases=fixture("extensions").getJSONArray("cases")
        for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i);val raw=CloudRawDocument.parse(JSONObject().put("features",c.getJSONObject("features")).toString())
            val names=c.getJSONArray("known").let{a->(0 until a.length()).map(a::getString).toSet()}
            val access=CloudFormatR48.access(raw,known=names,unknownBytes=if(c.opt("withinLimits")==false)16385 else 0)
            val expected=c.getJSONObject("expected").getString("read")
            assertEquals(c.getString("id"),when(expected){"opaque"->CloudFormatAccess.OPAQUE;"isolated"->CloudFormatAccess.ISOLATED;else->if(c.getJSONObject("expected").getString("write")=="pending-upgrade")CloudFormatAccess.READ_ONLY else CloudFormatAccess.WRITABLE},access)
        }
    }
    @Test fun authenticatedRecordEditsPreserveTheSharedRawTokensExactly() {
        val c=fixture("extensions").getJSONObject("implicitCompat");val raw=c.getString("originalUtf8")
        val id=PhoneCloudRecordCodec.id(key,"user","article",JSONObject(raw).getJSONObject("value"))
        val decoded=PhoneCloudRecordCodec.decryptDocument(key,"user",id,envelope(raw,"user",id))
        val changed=JSONObject(decoded.value.toString()).put("title","new")
        val encoded=PhoneCloudRecordCodec.encrypt(key,"user",id,1,"article",changed,baseline=decoded.document)
        val again=PhoneCloudRecordCodec.decryptDocument(key,"user",id,encoded)
        assertEquals(c.getString("expectedUtf8"),again.document.source)
        assertTrue(again.document.source.contains(c.getString("unknownPropertyRawUtf8")))
    }
    @Test fun knownTitleDependencyIsUpdatedAtomicallyWithoutChangingUnknownTokens() {
        val c=fixture("extensions");val v=c.getJSONObject("implicitCompat")
        var doc=CloudRawDocument.parse(v.getString("originalUtf8"))
        doc=doc.patch(listOf("features"),"{\"incompat\":[],\"roCompat\":[\"r48.title-bound.v1\"]}")
            .patch(listOf("value","titleBinding"),JSONObject().put("sha256",c.getJSONObject("knownTitleBindingPatch").getString("oldBindingSha256")).toString())
        val id=PhoneCloudRecordCodec.id(key,"user","article",doc.json().getJSONObject("value"))
        val decoded=PhoneCloudRecordCodec.decryptDocument(key,"user",id,envelope(doc.source,"user",id))
        val out=PhoneCloudRecordCodec.encrypt(key,"user",id,1,"article",JSONObject(decoded.value.toString()).put("title","new"),baseline=decoded.document)
        val value=PhoneCloudRecordCodec.decryptDocument(key,"user",id,out)
        assertEquals(c.getJSONObject("knownTitleBindingPatch").getString("newBindingSha256"),value.value.getJSONObject("titleBinding").getString("sha256"))
        assertTrue(value.document.source.contains(v.getString("unknownPropertyRawUtf8")))
    }
    @Test fun malformedFeaturesDuplicateKeysAndInvalidUnicodeNeverBecomeWritable() {
        val a=fixture("extensions").getJSONArray("invalidFeatures")
        for(i in 0 until a.length())assertEquals(CloudFormatAccess.ISOLATED,CloudFormatR48.access(CloudRawDocument.parse(JSONObject().put("features",a.get(i)).toString())))
        for(raw in listOf("{\"x\":1,\"x\":2}","{\"x\":\"\\ud800\"}","{\"x\":01}","{\"x\":NaN}"))assertTrue(runCatching{CloudRawDocument.parse(raw)}.isFailure)
        for(raw in listOf("{\"features\":null}","{\"features\":{\"compat\":[]}}","{\"features\":{\"incompat\":[\"a\"],\"roCompat\":[\"a\"]}}"))assertEquals(CloudFormatAccess.ISOLATED,CloudFormatR48.access(CloudRawDocument.parse(raw)))
    }
    @Test fun exactUnknownByteAndDepthBoundariesAreEnforced() {
        for(limit in listOf(4096,16384,1048576))for(extra in listOf(0,1)) {
            val doc=CloudRawDocument.parse("{\"x\":\""+"a".repeat(limit+extra-6)+"\"}")
            val bytes=doc.unknownBytes(emptyList(),emptySet());assertEquals(limit+extra,bytes)
            assertEquals(if(extra==0)CloudFormatAccess.WRITABLE else CloudFormatAccess.ISOLATED,CloudFormatR48.access(doc,unknownBytes=bytes,limit=limit))
        }
        CloudRawDocument.parse("{\"x\":"+"[".repeat(31)+"0"+"]".repeat(31)+"}")
        assertTrue(runCatching{CloudRawDocument.parse("{\"x\":"+"[".repeat(32)+"0"+"]".repeat(32)+"}")}.exceptionOrNull() is CloudFormatIsolationException)
    }
    @Test fun sharedObjectKeyEnvelopeDecryptsRealWrsscc2AndCannotSeedAccountReuse() {
        val v=fixture("object-key-envelope");val account=hex(v.getString("accountKeyHex"));val descriptor=CloudObjectDescriptor.fromRaw(v.getJSONObject("descriptor").toString())
        val manifest=CloudSnapshotManifest("id","device",1,1,0,emptyMap(),emptyMap(),listOf(descriptor))
        val blob=hex(v.getString("outputChunkBlobHex"))
        assertArrayEquals(hex(v.getString("outputPlaintextHex")),codec.restoreObjects(account,manifest){blob}.getValue(descriptor.name))
        val next=codec.create(account,1,"phone",2,listOf(CloudLogicalObject(descriptor.name,hex(v.getString("outputPlaintextHex")),false)),reusableManifests=listOf(manifest))
        assertEquals(1,next.newCiphertextChunks.size)
        assertFalse(next.manifest.objects.single().objectKeyEnvelope!=null)
        assertFalse(next.newCiphertextChunks.containsKey(descriptor.chunks.single().ciphertextSha256))
        val carried=codec.create(account,1,"phone",3,emptyList(),carryForwardObjects=listOf(descriptor))
        assertEquals(0,carried.newCiphertextChunks.size)
        assertTrue(runCatching{codec.restoreObjects(ByteArray(32),manifest){blob}}.isFailure)
        val wrong=JSONObject(v.getJSONObject("descriptor").toString());wrong.getJSONObject("objectKeyEnvelope").put("scope","migration:22222222-2222-4222-8222-222222222222")
        assertTrue(runCatching{codec.restoreObjects(account,manifest.copy(objects=listOf(CloudObjectDescriptor.fromRaw(wrong.toString())))){blob}}.isFailure)
        val both=descriptor.copy(readerKeyEnvelope=JSONObject())
        assertTrue(runCatching{codec.restoreObjects(account,manifest.copy(objects=listOf(both))){blob}}.isFailure)
    }
    @Test fun entryReferenceReadsVerifiedArchiveAndRejectsUnsafeOrMissingEntries() {
        val plain="entry plaintext".toByteArray();val zip=ByteArrayOutputStream().also {out->ZipOutputStream(out).use {z->z.putNextEntry(ZipEntry("bodies/synthetic.txt"));z.write(plain);z.closeEntry()}}.toByteArray()
        val archive=codec.create(key,1,"phone",1,listOf(CloudLogicalObject("private-library.wrss",zip,false)))
        fun ref(path:String="bodies/synthetic.txt",target:String="private-library.wrss")=CloudObjectDescriptor.fromRaw(JSONObject().put("name","private/article.txt").put("encoding","identity").put("originalBytes",plain.size).put("encodedBytes",plain.size).put("features",JSONObject().put("incompat",JSONArray(listOf("r48.entry-ref.v1")))).put("entryRef",JSONObject().put("object",target).put("entryPath",path).put("encoding","identity")).toString())
        val manifest=archive.manifest.copy(objects=archive.manifest.objects+ref())
        assertArrayEquals(plain,codec.restoreObjects(key,manifest){archive.newCiphertextChunks.getValue(it)}.getValue("private/article.txt"))
        for(path in listOf("../secret","/secret","a//b","a/./b","a\\b","not-present"))assertTrue(path,runCatching{codec.restoreObjects(key,archive.manifest.copy(objects=archive.manifest.objects+ref(path))){archive.newCiphertextChunks.getValue(it)}}.isFailure)
        assertTrue(runCatching{codec.restoreObjects(key,archive.manifest.copy(objects=listOf(ref(target="private/article.txt")))){byteArrayOf()}}.isFailure)
        assertTrue(runCatching{codec.restoreObjects(key,manifest.copy(objects=archive.manifest.objects+ref().copy(chunks=archive.manifest.objects.single().chunks))){archive.newCiphertextChunks.getValue(it)}}.isFailure)
    }
    @Test fun manifestDescriptorAndChunkUnknownTokensSurviveRealManifestEncryptionAndCarry() {
        val original=codec.create(key,1,"phone",1,listOf(CloudLogicalObject("notes-state.json","body".toByteArray(),false)))
        var d=CloudRawDocument.parse(original.manifest.toRawString()).patch(listOf("x-manifest"),"{\"wide\":9007199254740993,\"minus\":-0}")
        d=d.patch(listOf("objects"),"["+CloudRawDocument.parse(original.manifest.objects.single().toRawString()).patch(listOf("x-object"),"{ \"escaped\":\"\\u4e2d\" }").source+"]")
        val bytes=codec.encryptManifestBytes(key,original.manifest.snapshotId,d.source.toByteArray(),ByteArray(16),ByteArray(12))
        val read=codec.decryptManifest(key,original.manifest.snapshotId,bytes)
        val next=codec.create(key,1,"phone",2,emptyList(),previousManifest=read,carryForwardObjects=read.objects)
        val decoded=codec.decryptManifest(key,next.manifest.snapshotId,next.encryptedManifest)
        assertTrue(decoded.toRawString().contains("{\"wide\":9007199254740993,\"minus\":-0}"))
        assertTrue(decoded.toRawString().contains("{ \"escaped\":\"\\u4e2d\" }"))
        assertEquals(0,next.newCiphertextChunks.size)
        val opaque=read.copy(rawDocument=CloudRawDocument.parse(read.toRawString()).patch(listOf("features"),"{\"incompat\":[\"future.manifest.v1\"]}"))
        assertTrue(runCatching{codec.create(key,1,"phone",3,emptyList(),previousManifest=opaque)}.exceptionOrNull() is CloudFormatUpgradeRequired)
    }
    @Test fun sharedSixteenVersionHistoryUsesProductionCodecWithoutNewBodyBytes() {
        val fixture=fixture("object-reuse");val root=hex(fixture.getString("baselineRootHex"))
        val descriptor=CloudObjectDescriptor.fromRaw(fixture.getJSONObject("inputDescriptor").toString())
        val fixed=listOf(descriptor.chunks.single().saltBase64,descriptor.chunks.single().nonceBase64).map{Base64.getDecoder().decode(it)}
        val codec=CloudSnapshotCodec(object:java.security.SecureRandom(){var calls=0;override fun nextBytes(bytes:ByteArray) {
            val index=calls++;if(index<fixed.size){assertEquals(fixed[index].size,bytes.size);fixed[index].copyInto(bytes)}else java.security.SecureRandom().nextBytes(bytes)
        }})
        val original=CloudSnapshotManifest("old","phone",1,1,0,emptyMap(),emptyMap(),listOf(descriptor))
        val plaintext=codec.restoreObjects(root,original){hex(fixture.getString("inputChunkBlobHex"))}.getValue(descriptor.name)
        val blobs=mutableMapOf<String,ByteArray>();var previous:CloudSnapshotManifest?=null;var total=0L
        val history=fixture.getJSONArray("legacyHistory")
        for(i in 0 until history.length()) {
            val proof=previous?.let {m->CloudSnapshotCodec.sha256(codec.restoreObjects(root,m){blobs.getValue(it)}.getValue(descriptor.name))}
            val created=codec.create(root,1,"phone",(i+1).toLong(),listOf(CloudLogicalObject(descriptor.name,plaintext,i%2==1)),previousManifest=previous,
                snapshotSettings=JSONObject().put("metadataOnlyEvent",i),authenticatedObjectHashes=if(proof==null)emptyMap()else mapOf(descriptor.name to proof))
            val count=created.newCiphertextChunks.values.sumOf{it.size.toLong()};total+=count
            assertEquals(history.getJSONObject(i).getLong("r48NewBodyCiphertextBytes"),count)
            val golden=history.getJSONObject(i).getString("unchangedBodyCiphertextSha256")
            assertEquals(golden,created.manifest.objects.single().chunks.single().ciphertextSha256)
            if(i==0)assertArrayEquals(hex(fixture.getString("inputChunkBlobHex")),created.newCiphertextChunks.getValue(golden))
            blobs.putAll(created.newCiphertextChunks);previous=codec.decryptManifest(root,created.manifest.snapshotId,created.encryptedManifest)
        }
        assertEquals(fixture.getLong("r48TotalNewBodyCiphertextBytes"),total)
        val lazy=fixture("lazy-migration");assertTrue(lazy.getBoolean("editedObjectsOnly"))
        val events=fixture.getJSONArray("zeroBodyWriteCases")
        val lazyEvents=lazy.getJSONArray("zeroMigrationEvents")
        for(i in 0 until events.length()+lazyEvents.length()) {
            val m=previous!!;val hash=CloudSnapshotCodec.sha256(codec.restoreObjects(root,m){blobs.getValue(it)}.getValue(descriptor.name))
            val event=if(i<events.length())events.getJSONObject(i).getString("id")else lazyEvents.getString(i-events.length())
            val next=codec.create(root,1,"phone",(100+i).toLong(),listOf(CloudLogicalObject(descriptor.name,plaintext,true)),previousManifest=m,
                authenticatedObjectHashes=mapOf(descriptor.name to hash),snapshotSettings=JSONObject().put("event",event))
            assertTrue(event,next.newCiphertextChunks.isEmpty())
        }
    }
    @Test fun actualScopeAndProfileMismatchesCannotSeedNewCiphertextReuse() {
        val settings=JSONObject().put("backendScope","backend").put("ownerUserId","owner").put("libraryEpoch","epoch").put("contentGeneration","generation")
        val body="scope-sensitive".toByteArray()
        val first=codec.create(key,1,"phone",1,listOf(CloudLogicalObject("body.txt",body,false)),snapshotSettings=settings)
        for(field in listOf("backendScope","ownerUserId","libraryEpoch","contentGeneration")) {
            val other=JSONObject(settings.toString()).put(field,"other")
            val next=codec.create(key,1,"phone",2,listOf(CloudLogicalObject("body.txt",body,false)),previousManifest=first.manifest,snapshotSettings=other,
                authenticatedObjectHashes=mapOf("body.txt" to CloudSnapshotCodec.sha256(body)))
            assertEquals(field,1,next.newCiphertextChunks.size)
        }
        assertEquals(1,codec.create(key,2,"phone",2,listOf(CloudLogicalObject("body.txt",body,false)),previousManifest=first.manifest,snapshotSettings=settings).newCiphertextChunks.size)
        assertEquals(1,codec.create(key,1,"phone",2,listOf(CloudLogicalObject("body.txt",body,true)),previousManifest=first.manifest,snapshotSettings=settings).newCiphertextChunks.size)
    }
    @Test fun unknownObjectsAndChunksAreCarriedWhileOnlyAnEditedObjectGetsNewChunks() {
        val first=codec.create(key,1,"phone",1,listOf(CloudLogicalObject("known.txt","old".toByteArray(),false),CloudLogicalObject("future.bin","future".toByteArray(),false)))
        val changed=CloudRawDocument.parse(first.manifest.objects[1].toRawString()).patch(listOf("x-unknown"),"{ \"wide\":9007199254740993 }".replace("\\\"","\""))
        val unknown=CloudObjectDescriptor.fromRaw(changed.source)
        val manifest=first.manifest.copy(objects=listOf(first.manifest.objects[0],unknown))
        val carried=CloudSnapshotReuse.preserveObjects(listOf(manifest),setOf("known.txt"),emptyList())
        val next=codec.create(key,1,"phone",2,listOf(CloudLogicalObject("known.txt","new".toByteArray(),false)),previousManifest=manifest,carryForwardObjects=carried)
        assertEquals(1,next.newCiphertextChunks.size)
        assertEquals(unknown.toRawString(),next.manifest.objects.single{it.name=="future.bin"}.toRawString())
    }

    @Test fun unchangedAcceptedRootMayBeCarriedButOpaqueBodiesNeverProveCompleteRestoration() {
        val v=fixture("object-key-envelope")
        val d=CloudObjectDescriptor.fromRaw(v.getJSONObject("descriptor").toString()).copy(name="private-library.wrss")
        val settings=JSONObject().put("snapshotContentLayout",3).put("ownerUserId","owner").put("libraryEpoch","epoch").put("privateBodyFingerprintV2","fingerprint")
            .put("privateBodyArticleIds",JSONArray(listOf("a")))
        val manifest=CloudSnapshotManifest("id","phone",1,1,0,emptyMap(),emptyMap(),listOf(d),settings)
        assertTrue(CloudSnapshotReuse.canCarry(manifest,"owner","epoch",1,"fingerprint"))
        val opaque=d.copy(rawDocument=CloudRawDocument.parse(d.toRawString()).patch(listOf("features"),"{\"incompat\":[\"future.body.v1\"]}"))
        assertTrue(CloudSnapshotReuse.completedArticleIds(listOf(manifest.copy(objects=listOf(opaque))),"owner","epoch",1,"fingerprint").isEmpty())
    }
    @Test fun nestedPrivateMetadataAndChunkExtensionsRemainRawOnCarryAndAccountRootEdit() {
        val original=codec.create(key,1,"phone",1,listOf(CloudLogicalObject("body.txt","old".toByteArray(),false)))
        var document=CloudRawDocument.parse(original.manifest.objects.single().toRawString())
            .patch(listOf("privateArticle"),"{\"articleId\":\"a\",\"title\":\"title\",\"x-private\":{ \"wide\":9007199254740993 }}")
        val chunk=CloudRawDocument.parse(original.manifest.objects.single().chunks.single().toRawString()).patch(listOf("x-chunk"),"{ \"minus\":-0 }")
        document=document.patch(listOf("chunks"),"["+chunk.source+"]")
        val descriptor=CloudObjectDescriptor.fromRaw(document.source)
        assertTrue(descriptor.toRawString().contains("{ \"wide\":9007199254740993 }"))
        val next=codec.create(key,1,"phone",2,emptyList(),carryForwardObjects=listOf(descriptor))
        val read=codec.decryptManifest(key,next.manifest.snapshotId,next.encryptedManifest)
        assertTrue(read.toRawString().contains("{ \"minus\":-0 }"));assertTrue(read.toRawString().contains("{ \"wide\":9007199254740993 }"))
        val v=fixture("object-key-envelope");val root=hex(v.getString("accountKeyHex"))
        val output=CloudObjectDescriptor.fromRaw(CloudRawDocument.parse(v.getJSONObject("descriptor").toString()).patch(listOf("x-extension"),"{ \"wide\":9007199254740993 }").source)
        val changed=codec.create(root,1,"phone",2,listOf(CloudLogicalObject(output.name,"changed".toByteArray(),false)),previousManifest=original.manifest.copy(objects=listOf(output)))
        val edited=codec.decryptManifest(root,changed.manifest.snapshotId,changed.encryptedManifest).objects.single()
        assertTrue(edited.accountRoot);assertTrue(edited.toRawString().contains("{ \"wide\":9007199254740993 }"))
        assertFalse(edited.toRawString().contains("r48.object-key.v1"));assertArrayEquals("changed".toByteArray(),codec.restoreObjects(root,changed.manifest){changed.newCiphertextChunks.getValue(it)}.getValue(output.name))
    }
    @Test fun opaqueManifestCanRetainFutureObjectShapeButCannotApplyOrPublishSubset() {
        val source=codec.create(key,1,"phone",1,emptyList()).manifest
        val doc=CloudRawDocument.parse(source.toRawString()).patch(listOf("features"),"{\"incompat\":[\"future.manifest.v1\"]}").patch(listOf("objects"),"[{\"future-shape\":[1,2,3]}]")
        val bytes=codec.encryptManifestBytes(key,source.snapshotId,doc.source.toByteArray(),ByteArray(16),ByteArray(12))
        val manifest=codec.decryptManifest(key,source.snapshotId,bytes)
        assertEquals(CloudFormatAccess.OPAQUE,manifest.access);assertEquals(doc.source,manifest.toRawString())
        assertTrue(runCatching{codec.restoreObjects(key,manifest){error("must not download")}}.exceptionOrNull() is CloudFormatUpgradeRequired)
        assertTrue(runCatching{codec.create(key,1,"phone",2,emptyList(),previousManifest=manifest)}.exceptionOrNull() is CloudFormatUpgradeRequired)
    }
    @Test fun malformedAndTruncatedZipReferencesNeverBecomeEmptyOrComplete() {
        val zip=ByteArrayOutputStream().also {out->ZipOutputStream(out).use{z->z.putNextEntry(ZipEntry("a.txt"));z.write("data".toByteArray());z.closeEntry()}}.toByteArray()
        for(bytes in listOf(zip.copyOf(zip.size-22),zip.copyOf().also{a->val central=(0..a.size-4).first{a[it]==0x50.toByte()&&a[it+1]==0x4b.toByte()&&a[it+2]==0x01.toByte()&&a[it+3]==0x02.toByte()};a[central+16]=(a[central+16].toInt() xor 1).toByte()})) {
            val snapshot=codec.create(key,1,"phone",1,listOf(CloudLogicalObject("archive.zip",bytes,false)))
            val descriptor=CloudObjectDescriptor.fromRaw("{\"name\":\"body.txt\",\"encoding\":\"identity\",\"originalBytes\":4,\"encodedBytes\":4,\"features\":{\"incompat\":[\"r48.entry-ref.v1\"]},\"entryRef\":{\"object\":\"archive.zip\",\"entryPath\":\"a.txt\",\"encoding\":\"identity\"}}")
            assertTrue(runCatching{codec.restoreObjects(key,snapshot.manifest.copy(objects=snapshot.manifest.objects+descriptor)){snapshot.newCiphertextChunks.getValue(it)}}.isFailure)
        }
    }

    @Test fun unsupportedEncodingKeepsTheEntireOriginalDescriptorToken() {
        val raw="{ \"name\":\"future.bin\",\"encoding\":\"future-encoding\",\"originalBytes\":3,\"encodedBytes\":4,\"chunks\":[{ \"future\":9007199254740993 }] }"
        val descriptor=CloudObjectDescriptor.fromRaw(raw)
        assertEquals(CloudFormatAccess.OPAQUE,descriptor.access);assertEquals(raw,descriptor.toRawString())
        assertTrue(runCatching{codec.create(key,1,"phone",2,emptyList(),carryForwardObjects=listOf(descriptor))}.exceptionOrNull() is CloudFormatUpgradeRequired)
    }
    @Test fun onlyAcceptedReadsAreEnabledAndNewWritersRemainDisabled() {
        assertFalse(CloudFormatR48.writeV1);assertFalse(CloudFormatR48.migrationV1)
    }
}
