package com.lightningstudio.watchrss.phone.syncv2

import android.content.Context
import com.lightningstudio.watchrss.phone.cloud.CloudKeyManager
import com.lightningstudio.watchrss.phone.data.book.BookEntity
import com.lightningstudio.watchrss.phone.data.book.BookSync
import com.lightningstudio.watchrss.phone.data.book.ImportedBookStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2BodyChannelTest {
    private val anchor=JSONObject().put("commitOid","commit:sha256:"+"aa".repeat(32)).put("revision","1").put("rollbackGeneration","0")
    private val key=ByteArray(32){7}
    private fun binding(kind:String,id:String,raw:ByteArray,entity:String="cc".repeat(32))=SyncV2BodyCodec.Binding(kind,id,entity,SyncV2BodyCodec.revision(kind,entity,SyncV2BodyCodec.hash(raw)))
    private fun json(b:SyncV2BodyCodec.Binding)=JSONObject().put("kind",b.kind).put("logicalId",b.logicalId).put("entityKey",b.entityKey).put("bodyRevision",b.bodyRevision)
    private fun bytes(p:JSONObject):Map<String,ByteArray> = p.getJSONObject("objects").let{o->o.keys().asSequence().associateWith{Base64.getDecoder().decode(o.getString(it))}}
    private fun expect(code:String,run:()->Unit) {try{run();fail("accepted $code")}catch(e:Exception){assertEquals(code,e.message)}}
    private fun book(text:String="正文\r\né😀"):Pair<JSONObject,ByteArray> {
        val fingerprint=BookSync.fingerprint(listOf(BookSync.chapterHash(1,"chapter",text)))
        val id=BookSync.id(fingerprint)
        val raw=(JSONObject().put("format","watchrss-book").put("version",1).put("title","Book").put("chapterCount",1).put("sourceUrl","https://watchrss.local/import-txt-novel/test").put("kind","txt").toString()+"\n"+
            JSONObject().put("chapterIndex",1).put("title","chapter").put("contentText",text).put("contentHtml",JSONObject.NULL).put("url","https://watchrss.local/import-txt-novel/test/chapter/1").toString()+"\n").toByteArray()
        return JSONObject().put("bookId",id).put("fingerprint",fingerprint).put("chapterCount",1).put("deleted",false) to raw
    }
    @Test fun realPodjsBidirectionalAssetBookGzipAndFrozenLegacyVectors() {
        SyncV2P5Fixture().use{f->
            val (book,bookRaw)=book();val samples=listOf(Triple("asset","image.png",ByteArray(180000){(it%239).toByte()}),Triple("asset","large.png",ByteArray(700000){(it%239).toByte()}),Triple("asset","empty.bin",byteArrayOf()),Triple("book",book.getString("bookId"),bookRaw))
            val items=JSONArray();val bindings=mutableListOf<SyncV2BodyCodec.Binding>()
            for((kind,id,raw)in samples){val b=binding(kind,id,raw);bindings.add(b);val p=SyncV2BodyCodec.prepare(f.identity.scope,anchor,b,key,1,f.f.signer,raw)
                assertArrayEquals(raw,SyncV2BodyCodec.open(p.descriptor,f.identity.scope,b,f.f.signer.publicHex,{key}){h,_->p.objects.getValue(h)})
                items.put(JSONObject().put("binding",json(b)).put("descriptor",p.descriptor).put("objects",JSONObject(p.objects.mapValues{Base64.getEncoder().encodeToString(it.value)})).put("raw",Base64.getEncoder().encodeToString(raw)).put("value",if(kind=="book")book else JSONObject()))}
            val input=File(f.root,"input.json");val output=File(f.root,"output.json");input.writeText(JSONObject().put("scope",f.identity.scope).put("publicHex",f.f.signer.publicHex).put("items",items).toString())
            org.junit.Assume.assumeTrue("real PodJS source is required",File(System.getenv("WATCHRSS_PODJS_BODY_SOURCE")?:"/home/wilf/data/OPPO_Develop/PodJS_WatchRSS.wt-notes","src/sync-v2/body-channel-codec.ts").isFile)
            val script=File("src/test/body/podjs-body-oracle.ts").absoluteFile.takeIf{it.isFile}?:File("app/src/test/body/podjs-body-oracle.ts").absoluteFile
            val process=ProcessBuilder("bun",script.path,input.path,output.path).redirectErrorStream(true).start();val log=process.inputStream.bufferedReader().readText();assertEquals(log,0,process.waitFor())
            val result=JSONObject(output.readText());val pub=result.getString("publicHex");assertTrue(result.getJSONArray("items").getJSONObject(1).isNull("phoneToPodjsError"))
            for((i,sample)in samples.withIndex())for(p in listOf(result.getJSONArray("items").getJSONObject(i),result.getJSONArray("items").getJSONObject(i).getJSONObject("legacy"))) {
                val decoded=SyncV2BodyCodec.open(p.getJSONObject("descriptor"),f.identity.scope,bindings[i],pub,{key}){h,_->bytes(p).getValue(h)}
                assertArrayEquals(sample.third,decoded)
            }
        }
    }
    @Test fun authenticatedArticleReusedDuringAnIndependentBodyPublicationRemainsReadableByArticleWorker() {
        SyncV2P5Fixture().use { fixture ->
            val records = javaClass.getResourceAsStream("/syncv2-business-schema.json")!!
                .bufferedReader().use { JSONObject(it.readText()).getJSONArray("records") }
            val article = (0 until records.length()).asSequence().map(records::getJSONObject)
                .first { it.getString("kind") == "article" }.getJSONObject("value")
            val entity = fixture.identity.entity("article", article.getString("articleId"))
            val oid = fixture.commit(listOf(fixture.note(), article), fixture.initial, "0", "normal", null)
            fixture.setCurrent(oid, "3")
            fixture.start()

            val authenticated = fixture.projection.cloudRows().single { it.getJSONObject("row").getString("entityKey") == entity }
                .getJSONObject("row")
            assertNull(fixture.projection.wireContentRow(entity))
            fixture.projection.prepareWireIndex(fixture.f.signer, fixture.key, 1).use { prepared ->
                val reusable = fixture.projection.wireContentRow(entity)
                assertNotNull("The article publisher needs the prepared authenticated wire row", reusable)
                assertEquals(SyncV2Codec.canonical(authenticated), SyncV2Codec.canonical(reusable))
                assertFalse(prepared.refs.any { it.getJSONObject("body").getString("stateOid") == authenticated.getString("stateOid") })
            }
        }
    }

    @Test fun signatureClosureCiphertextAndRevisionCorruptionKeepOriginal() {
        SyncV2P5Fixture().use{f->val raw="original".toByteArray();val b=binding("asset","original.png",raw);val p=SyncV2BodyCodec.prepare(f.identity.scope,anchor,b,key,1,f.f.signer,raw)
            val repo=SyncV2BodyRepository(f.sql,f.root);val original=repo.file("asset",b.logicalId);original.parentFile!!.mkdirs();original.writeBytes(raw)
            fun open(d:JSONObject,read:(String,Int)->ByteArray={h,_->p.objects.getValue(h)})=SyncV2BodyCodec.open(d,f.identity.scope,b,f.f.signer.publicHex,{key},read)
            val bad=JSONObject(p.descriptor.toString());bad.put("signature",SyncV2Codec.b64(ByteArray(64){1}));expect("signature_invalid"){open(bad)}
            val body=JSONObject(p.descriptor.getJSONObject("body").toString()).put("chunks",JSONArray());expect("closure_incomplete"){open(f.f.signer.signed(SyncV2BodyCodec.DOMAIN,body))}
            expect("hash_mismatch"){open(p.descriptor){h,_->p.objects.getValue(h).copyOf().also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()}}}
            expect("body_revision_changed"){SyncV2BodyCodec.open(p.descriptor,f.identity.scope,b.copy(bodyRevision="ff".repeat(16)),f.f.signer.publicHex,{key}){h,_->p.objects.getValue(h)}}
            expect("key_unavailable"){SyncV2BodyCodec.open(p.descriptor,f.identity.scope,b,f.f.signer.publicHex,{error("key_unavailable")}){h,_->p.objects.getValue(h)}}
            // Valid signature, manifest AEAD and ciphertext SHA still cannot replace the original hash binding.
            val encrypted=p.objects.getValue(p.descriptor.getJSONObject("body").getJSONObject("manifest").getString("sha256"))
            val uuid=SyncV2BodyCodec.uuid(p.descriptor.getJSONObject("body").getString("carrierId"))
            val derived=SyncV2Codec.hkdf(key,encrypted.copyOfRange(7,23),SyncV2Codec.utf8("watchrss/cloud/manifest/v2/$uuid"))
            val m=SyncV2Wire.parse(SyncV2Codec.decrypt(derived,encrypted.copyOfRange(23,35),SyncV2Codec.utf8("watchrss-cloud-manifest-v2:$uuid"),encrypted.copyOfRange(35,encrypted.size)),1048576,true).put("plaintextSha256","00".repeat(32))
            val changed=encrypted.copyOfRange(0,35)+SyncV2Codec.encrypt(derived,encrypted.copyOfRange(23,35),SyncV2Codec.utf8("watchrss-cloud-manifest-v2:$uuid"),SyncV2Codec.utf8(SyncV2Codec.canonical(m)))
            derived.fill(0)
            val changedHash=SyncV2BodyCodec.hash(changed)
            val signed=f.f.signer.signed(SyncV2BodyCodec.DOMAIN,JSONObject(p.descriptor.getJSONObject("body").toString()).put("manifest",JSONObject().put("sha256",changedHash).put("sizeBytes",changed.size)))
            expect("hash_mismatch"){open(signed){h,_->if(h==changedHash)changed else p.objects.getValue(h)}}
            assertArrayEquals(raw,original.readBytes())
            f.sql.beginTransaction();try{expect("body_revision_changed"){repo.install("asset",b.logicalId,"foreign".toByteArray())}}finally{f.sql.endTransaction()}
            assertArrayEquals(raw,original.readBytes())
        }
    }
    @Test fun literalStorageKeyAndRetainedConflictReferencesUseNoPrivateUuid() {
        SyncV2P5Fixture().use{f->
            SyncV2PhoneApply(f.sql).upsert("note_assets",listOf("assetId"),JSONObject().put("assetId","private-uuid").put("storageKey","image.png").put("mimeType","image/png").put("displayName","image.png").put("createdAt",1).put("deleted",false).put("deletedAt",0))
            f.drain();assertNotNull(f.projection.candidate("asset","image.png"));assertNull(f.projection.candidate("asset","private-uuid"))
            assertEquals("image.png",f.projection.candidate("asset","image.png")!!.first.getJSONObject("payload").getString("assetId"))
            val v=JSONObject().put("markdown","![a](assets/a.png)").put("baseMarkdown","[b](assets/b.png)").put("noteConflicts",JSONObject().put("records",JSONArray().put(JSONObject().put("baseMarkdown","[c](assets/c.png)").put("localMarkdown","[d](assets/d.png)").put("remoteMarkdown","[e](assets/e.png)<img src=\"assets/html.png\"><a href=\"assets/link.bin\">x</a>"))))
            assertEquals(setOf("a.png","b.png","c.png","d.png","e.png","html.png","link.bin"),SyncV2BodyRepository.noteReferences(v))
        }
    }
    @Test fun oldPrivateUuidCandidatesMigrateWithoutTombstonesOrOriginalDeletion() {
        SyncV2P5Fixture().use{f->
            SyncV2PhoneApply(f.sql).upsert("note_assets",listOf("assetId"),JSONObject().put("assetId","private-uuid").put("storageKey","migrated.png").put("mimeType","image/png").put("displayName","migrated.png").put("createdAt",1).put("deleted",false).put("deletedAt",0))
            f.drain()
            val entity=f.identity.entity("asset","private-uuid")
            val value=JSONObject().put("assetId","private-uuid").put("mime","image/png").put("originalFilename","migrated.png").put("updatedAt",1).put("sourceDeviceId","").put("deleted",false).put("deletedAt",0)
            val pair=SyncV2Business.split("asset","private-uuid",entity,value,null)
            f.sql.execSQL("INSERT INTO syncv2_candidates VALUES(?,?,?,?,?,?,?,?)",arrayOf<Any>(f.projection.scope,entity,"asset","private-uuid","1",SyncV2Codec.sealLocal(SyncV2Fixtures.localKey,f.projection.scope,"meta",pair.meta),SyncV2Codec.sealLocal(SyncV2Fixtures.localKey,f.projection.scope,"candidate-state",pair.state),SyncV2Codec.hash(value)))
            val original=SyncV2BodyRepository(f.sql,f.root).file("asset","migrated.png");original.parentFile!!.mkdirs();original.writeText("original")
            f.projection.migrateBodyAssetCandidates();f.drain();f.projection.prepareWireIndex(f.f.signer,f.key,1).close()
            assertNull(f.projection.candidate("asset","private-uuid"));assertNotNull(f.projection.candidate("asset","migrated.png"));assertEquals("original",original.readText())
            assertEquals("private-uuid",f.sql.query("SELECT assetId FROM note_assets WHERE storageKey='migrated.png'").use{it.moveToFirst();it.getString(0)})
        }
    }
    @Test fun bookConsumersUseTheCurrentDigestAcrossBothLocalPaths() = runBlocking<Unit> {
        SyncV2P5Fixture().use { f ->
            val (value, raw) = book()
            value.put("title", "Book").put("kind", "txt").put("updatedAt", 1)
                .put("sourceUrl", "https://watchrss.local/import-txt-novel/test")
                .put("sourceDeviceId", "fixture").put("deletedAt", 0)
                .put("deletionClock", 0).put("deletedChapters", JSONArray())
            val id = value.getString("bookId")
            val hash = SyncV2BodyCodec.hash(raw)
            f.room.bookDao().put(BookEntity(id, BookSync.canonical(value), hash, raw.size.toLong()))
            val directory = File(f.root, "imported_books").apply { mkdirs() }
            val books = ImportedBookStore(f.room, directory, null, "fixture")
            val cloud = SyncV2BodyRepository(f.sql, f.root)
            val recovery = SyncV2RecoveryStorage(f.projection, f.identity, f.root)
            val state = SyncV2Business.split("book", id, f.identity.entity("book", id), value, null)
            val record = JSONObject().put("value", value).put("meta", state.meta).put("state", state.state)
            val legacy = File(directory, "$id.jsonl")
            val binary = File(directory, "b2-$id-$hash.jsonl")
            val old = raw.toString(Charsets.UTF_8).replace("\"title\":\"Book\"", "\"title\":\"Past\"").toByteArray()
            assertEquals(raw.size, old.size)
            assertFalse(raw.contentEquals(old))
            val short = raw.copyOf(raw.size - 1)
            val results = linkedMapOf<String, List<Boolean>>()
            suspend fun sample(label: String, v1: ByteArray?, b2: ByteArray?) {
                if (v1 == null) legacy.delete() else legacy.writeBytes(v1)
                if (b2 == null) binary.delete() else binary.writeBytes(b2)
                val announced = books.manifests().single().getBoolean("contentAvailable")
                val original = books.packageFile(id)?.readBytes()
                val cloudBytes = cloud.read("book", id)
                results[label] = listOf(announced, original?.contentEquals(raw) == true,
                    cloudBytes?.contentEquals(raw) == true, recovery.contentReady(record), recovery.hasOriginalSource(record),
                    original == null, cloudBytes == null)
            }
            sample("v1 only", raw, null)
            sample("B2 only", null, raw)
            sample("both valid", raw, raw)
            sample("old v1, current B2", old, raw)
            sample("valid v1, short B2", raw, short)
            sample("valid v1, same-length corrupt B2", raw, old)
            sample("same-length corrupt v1, short B2", old, short)
            val ready = listOf(true, true, true, true, true, false, false)
            val unavailable = listOf(false, false, false, false, false, true, true)
            println("book read matrix: $results")
            assertEquals(results.keys.associateWith { if (it.startsWith("same-length corrupt v1")) unavailable else ready }, results)

            legacy.delete()
            binary.writeBytes(raw)
            val entity = f.identity.entity("book", id)
            val base = JSONObject().put("accountOid", f.initial).put("generation", "0")
            val copy = recovery.prepare(listOf(record), emptyList(), base, setOf(entity), "1", f.initial, f.f.now)
            val blob = copy.objects.entries.single { it.key.startsWith("blob:") }
            val recoveryKey = f.projection.recoveryKey()
            try {
                assertArrayEquals(raw, SyncV2Envelope.open(f.identity, recoveryKey, blob.key, blob.value))
            } finally {
                recoveryKey.fill(0)
            }
        }
    }

    @Test fun verifiedBookInstallsOriginalAndChaptersWithoutChangingCurrentBookOrReadingState() {
        SyncV2P5Fixture().use{f->val (value,raw)=book();val id=value.getString("bookId");val repo=SyncV2BodyRepository(f.sql,f.root)
            SyncV2PhoneApply(f.sql).upsert("imported_books",listOf("bookId"),JSONObject().put("bookId",id).put("stateJson",value.toString()))
            val reading=JSONObject().put("isRead",true).put("favoriteSaved",true).put("favoriteChangedAt",123).put("favoriteSortOrder",9).put("readingPositionBytes",17)
            SyncV2PhoneApply(f.sql).upsert("imported_book_reading",listOf("bookId","chapterIndex"),JSONObject().put("bookId",id).put("chapterIndex",1).put("stateJson",reading.toString()))
            repo.validate("book",id,value,raw)
            f.sql.beginTransaction();try{repo.install("book",id,raw);f.sql.setTransactionSuccessful()}finally{f.sql.endTransaction()}
            assertArrayEquals(raw,repo.file("book",id).readBytes())
            assertEquals(value.toString(),f.sql.query("SELECT stateJson FROM imported_books WHERE bookId=?",arrayOf(id)).use{it.moveToFirst();it.getString(0)})
            f.sql.query("SELECT contentText,isRead,favoriteSaved,favoriteChangedAt FROM phone_articles WHERE articleId=?",arrayOf(BookSync.articleId(id,1))).use{assertTrue(it.moveToFirst());assertEquals("正文\r\né😀",it.getString(0));assertEquals(1,it.getInt(1));assertEquals(1,it.getInt(2));assertEquals(123,it.getInt(3))}
            assertEquals(reading.toString(),f.sql.query("SELECT stateJson FROM imported_book_reading WHERE bookId=?",arrayOf(id)).use{it.moveToFirst();it.getString(0)})
        }
    }
    @Test fun largeBookChapterUsesPrivateBodyCacheWithoutAdvancingFileEditWitness() {
        SyncV2P5Fixture().use{f->val text="长".repeat(50001);val (value,raw)=book(text);val id=value.getString("bookId");val repo=SyncV2BodyRepository(f.sql,f.root)
            SyncV2PhoneApply(f.sql).upsert("imported_books",listOf("bookId"),JSONObject().put("bookId",id).put("stateJson",value.toString()))
            val before=com.lightningstudio.watchrss.phone.data.local.CloudExportFileWitness.captureForProjection(f.root)
            f.sql.beginTransaction();try{repo.install("book",id,raw);f.sql.setTransactionSuccessful()}finally{f.sql.endTransaction()}
            val marker=f.sql.query("SELECT contentText FROM phone_articles WHERE articleId=?",arrayOf(BookSync.articleId(id,1))).use{it.moveToFirst();it.getString(0)}
            val store=com.lightningstudio.watchrss.phone.data.local.FileArticleContentStore(File(f.root,"imported_text"))
            assertTrue(store.isMarker(marker));assertEquals(text,store.loadText(marker))
            assertEquals(before,com.lightningstudio.watchrss.phone.data.local.CloudExportFileWitness.captureForProjection(f.root))
        }
    }
    @Test fun frozenBookFingerprintCountAndInstanceBindingRejectWrongPackage() {
        SyncV2P5Fixture().use{f->val (value,raw)=book();val repo=SyncV2BodyRepository(f.sql,f.root);repo.validate("book",value.getString("bookId"),value,raw)
            expect("book_package_invalid"){repo.validate("book",value.getString("bookId"),JSONObject(value.toString()).put("fingerprint","00".repeat(32)),raw)}
            expect("book_package_invalid"){repo.validate("book",value.getString("bookId"),JSONObject(value.toString()).put("instanceId","00".repeat(16)),raw)}
            expect("object_too_large"){repo.validate("asset","huge.bin",JSONObject().put("assetId","huge.bin").put("deleted",false),ByteArray(5*1024*1024+1))}
        }
    }
}
