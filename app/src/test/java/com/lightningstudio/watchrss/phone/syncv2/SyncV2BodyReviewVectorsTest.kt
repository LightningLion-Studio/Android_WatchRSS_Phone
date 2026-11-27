package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2BodyReviewVectorsTest {
    private val key=ByteArray(32){7}
    private val scope=JSONObject().put("repoId","ab".repeat(16)).put("libraryEpoch","1").put("contentGeneration","1")
    private val anchor=JSONObject().put("commitOid","commit:sha256:"+"aa".repeat(32)).put("revision","1").put("rollbackGeneration","0")
    private fun b64(raw:ByteArray)=Base64.getEncoder().encodeToString(raw)
    private fun un64(s:String)=Base64.getDecoder().decode(s)
    private fun b(kind:String,id:String,raw:ByteArray)=SyncV2BodyCodec.Binding(kind,id,"cc".repeat(32),SyncV2BodyCodec.revision(kind,"cc".repeat(32),SyncV2BodyCodec.hash(raw)))
    private fun book():Pair<JSONObject,ByteArray> {
        val text="review 正文\r\nAé😀".repeat(30_000);val fp=com.lightningstudio.watchrss.phone.data.book.BookSync.fingerprint(listOf(com.lightningstudio.watchrss.phone.data.book.BookSync.chapterHash(1,"Ch",text)))
        val id=com.lightningstudio.watchrss.phone.data.book.BookSync.id(fp)
        val raw=(JSONObject().put("format","watchrss-book").put("version",1).put("title","Review").put("chapterCount",1).put("sourceUrl","https://watchrss.local/import-txt-novel/review").put("kind","txt").toString()+"\n"+JSONObject().put("chapterIndex",1).put("title","Ch").put("contentText",text).put("contentHtml",JSONObject.NULL).put("url","https://watchrss.local/import-txt-novel/review/chapter/1").toString()+"\n").toByteArray()
        return JSONObject().put("bookId",id).put("fingerprint",fp).put("chapterCount",1).put("deleted",false) to raw
    }
    @Test fun d1BookAndAssetRevisionMatchSharedContract() {
        val entity = "ab".repeat(32)
        val book = "book\n".toByteArray()
        val image = byteArrayOf(0, 127, -128, -1)
        val bookSha = SyncV2BodyCodec.hash(book)
        val imageSha = SyncV2BodyCodec.hash(image)
        assertEquals("3c4dbff9eeda9410a0b9eb423472981db8997d666388b4cd2424700c3974d64b", bookSha)
        assertEquals("89273d2f70b93285bb7ddb4bcee86a5347ca7159352e3cbdd20c23e9d1e507d3", imageSha)
        assertEquals("cef3283dd3fe09a140ca6c55c1d59c29", SyncV2BodyCodec.revision("book", entity, bookSha))
        assertEquals("32bc4651766c6ea75898f871cbd42d20", SyncV2BodyCodec.revision("asset", entity, imageSha))
    }

    @Test fun independentThreeKindCrossLanguageAndBoundaryVectors() {
        val watchSource = File(System.getenv("WATCHRSS_PODJS_BODY_SOURCE")
            ?: "/home/wilf/data/OPPO_Develop/PodJS_WatchRSS.wt-notes").absoluteFile
        val miniSource = File(System.getenv("WATCHRSS_MINI_BODY_SOURCE")
            ?: "/home/wilf/data/OPPO_Develop/WatchRSS_WeChat.wt-body").absoluteFile
        val missing = listOf(
            File(watchSource, "src/sync-v2/body-channel-codec.ts"),
            File(miniSource, "miniprogram/lib/sync-v2/body-channel-codec.js")
        ).filterNot { it.isFile }
        val reason = "Missing cross-language source: ${missing.joinToString()}. " +
            "Set WATCHRSS_PODJS_BODY_SOURCE and WATCHRSS_MINI_BODY_SOURCE to compatible checkouts."
        if (missing.isNotEmpty()) println(reason)
        assumeTrue(reason, missing.isEmpty())

        SyncV2P5Fixture().use {f->
            val (bookValue,bookRaw)=book();val items=JSONArray()
            val samples=(listOf(0,1,262144,262145,5*1024*1024).map{Triple("asset","n$it.bin",ByteArray(it){n->(n%251).toByte()})}+Triple("book",bookValue.getString("bookId"),bookRaw))
            for((kind,id,raw)in samples){val bind=b(kind,id,raw);val prepared=SyncV2BodyCodec.prepare(scope,anchor,bind,key,1,f.f.signer,raw)
                if (kind == "book") assertTrue(prepared.objects.values.any { it.size == 524_288 })
                items.put(JSONObject().put("binding",JSONObject().put("kind",kind).put("logicalId",id).put("entityKey",bind.entityKey).put("bodyRevision",bind.bodyRevision)).put("raw",b64(raw)).put("value",if(kind=="book")bookValue else JSONObject()).put("descriptor",prepared.descriptor).put("objects",JSONObject(prepared.objects.mapValues{b64(it.value)})))
            }
            val idKey=ByteArray(32){11};val entity="cc".repeat(32);val articleRaw="review 正文\r\nAé😀".repeat(20000).toByteArray()
            fun opaque(t:String,p:ByteArray)=SyncV2Codec.opaque(idKey,scope,t,p)
            val articleObjects=JSONArray();val blocks=JSONArray()
            for(at in articleRaw.indices step 262144){val part=articleRaw.copyOfRange(at,minOf(at+262144,articleRaw.size));val sealed=SyncV2Envelope.sealWithParameters(scope,::opaque,key,1,"blob",part,SyncV2Codec.random(32),SyncV2Codec.random(12))
                articleObjects.put(JSONObject().put("oid",sealed.oid).put("bytes",b64(sealed.bytes)));blocks.put(JSONObject().put("index",blocks.length()).put("oid",sealed.oid).put("plainBytes",part.size).put("opaqueContentId",sealed.header.get("opaqueContentId")))}
            val revision=SyncV2ArticleBody.revision(entity,SyncV2BodyCodec.hash(articleRaw))
            val recipe=JSONObject().put("version",2).put("entityKey",entity).put("bodyRevision",revision).put("opaqueContentId",opaque("recipe",articleRaw)).put("totalPlainBytes",articleRaw.size).put("plaintextSha256",SyncV2BodyCodec.hash(articleRaw)).put("blocks",blocks).put("ranges",JSONArray())
            SyncV2ContentCodec.recipe(recipe)
            val sealed=SyncV2Envelope.sealWithParameters(scope,::opaque,key,1,"recipe",SyncV2Codec.utf8(SyncV2Codec.canonical(recipe)),SyncV2Codec.random(32),SyncV2Codec.random(12))
            articleObjects.put(JSONObject().put("oid",sealed.oid).put("bytes",b64(sealed.bytes)))
            val input=JSONObject().put("scope",scope).put("anchor",anchor).put("publicHex",f.f.signer.publicHex).put("items",items).put("article",JSONObject().put("entityKey",entity).put("raw",b64(articleRaw)).put("bodyRevision",revision).put("objects",articleObjects))
            val inputFile=File(f.root,"vectors-input.json");val outputFile=File(f.root,"vectors-output.json");inputFile.writeText(input.toString())
            val script=File("src/test/body/review-body-oracle.ts").absoluteFile.takeIf{it.isFile}?:File("app/src/test/body/review-body-oracle.ts").absoluteFile
            val process = ProcessBuilder("bun", script.path, inputFile.path, outputFile.path)
                .redirectErrorStream(true)
                .apply {
                    environment()["WATCHRSS_PODJS_BODY_SOURCE"] = watchSource.path
                    environment()["WATCHRSS_MINI_BODY_SOURCE"] = miniSource.path
                }
                .start()
            val log = process.inputStream.bufferedReader().readText()
            assertEquals(log, 0, process.waitFor())
            val output=JSONObject(outputFile.readText());val publicHex=output.getString("publicHex")
            for(i in samples.indices){val item=output.getJSONArray("items").getJSONObject(i);val sample=samples[i];val bind=b(sample.first,sample.second,sample.third)
                assertEquals("PASS",item.getString("phoneToPodjs"));assertEquals("PASS",item.getString("phoneToMini"))
                for(field in listOf("podjs","mini")){val pack=item.getJSONObject(field);assertArrayEquals(sample.third,SyncV2BodyCodec.open(pack.getJSONObject("descriptor"),scope,bind,publicHex,{key}){h,_->un64(pack.getJSONObject("objects").getString(h))})}
            }
            val article=output.getJSONObject("article");assertEquals(revision,article.getString("bodyRevision"));val aObjects=article.getJSONArray("objects");val plains=linkedMapOf<String,ByteArray>()
            for(i in 0 until aObjects.length()){val o=aObjects.getJSONObject(i);plains[o.getString("oid")]=SyncV2Envelope.open(scope,::opaque,key,o.getString("oid"),un64(o.getString("bytes")))}
            val restoredRecipe=plains.entries.single{it.key.startsWith("recipe:")}.value.let{SyncV2Wire.parse(it,262144,true)};SyncV2ContentCodec.recipe(restoredRecipe)
            val restored=java.io.ByteArrayOutputStream();val bks=restoredRecipe.getJSONArray("blocks");for(i in 0 until bks.length())restored.write(plains.getValue(bks.getJSONObject(i).getString("oid")));assertArrayEquals(articleRaw,restored.toByteArray())
            val legacy=output.getJSONObject("legacyBook");val bind=b("book",bookValue.getString("bookId"),bookRaw)
            val restoredLegacy=SyncV2BodyCodec.open(legacy.getJSONObject("descriptor"),scope,bind,publicHex,{key}){h,_->un64(legacy.getJSONObject("objects").getString(h))}
            assertArrayEquals(bookRaw,restoredLegacy)
            // Locator metadata deliberately differs from authenticated current business metadata.
            assertEquals("Review",output.getString("legacyLocatorTitle"))
            val current=JSONObject(bookValue.toString()).put("title","Current account title").put("sourceUrl","https://current.invalid/account")
                .put("kind","txt").put("sourceDeviceId","synthetic-phone").put("updatedAt",3).put("deletedAt",0).put("deletionClock",0).put("deletedChapters",JSONArray())
            SyncV2PhoneApply(f.sql).upsert("imported_books",listOf("bookId"),JSONObject().put("bookId",bind.logicalId).put("stateJson",current.toString()))
            f.sql.beginTransaction();try{SyncV2BodyRepository(f.sql,f.root).install("book",bind.logicalId,restoredLegacy);f.sql.setTransactionSuccessful()}finally{f.sql.endTransaction()}
            val after=f.sql.query("SELECT stateJson FROM imported_books WHERE bookId=?",arrayOf(bind.logicalId)).use{it.moveToFirst();it.getString(0)}
            assertEquals(SyncV2Codec.canonical(current),SyncV2Codec.canonical(JSONObject(after)))
            val legacyCodec=com.lightningstudio.watchrss.phone.cloud.CloudSnapshotCodec()
            val legacyBody=legacy.getJSONObject("descriptor").getJSONObject("body")
            val legacyObjects=legacy.getJSONObject("objects")
            val encrypted=un64(legacyObjects.getString(legacyBody.getJSONObject("manifest").getString("sha256")))
            val manifest=legacyCodec.decryptManifest(key,legacyBody.getString("sourceSnapshotId"),encrypted).toJson()
            fun changedLegacy(change:(JSONObject)->Unit):Pair<JSONObject,ByteArray> {
                val document=JSONObject(manifest.toString());change(document.getJSONArray("objects").getJSONObject(0))
                val blob=legacyCodec.encryptManifestBytes(key,legacyBody.getString("sourceSnapshotId"),document.toString().toByteArray(),ByteArray(16){3},ByteArray(12){4})
                val carrier=f.f.signer.signed(SyncV2BodyCodec.DOMAIN,JSONObject(legacyBody.toString()).put("manifest",JSONObject().put("sha256",SyncV2BodyCodec.hash(blob)).put("sizeBytes",blob.size)))
                return carrier to blob
            }
            val salt=ByteArray(16){5};val nonce=ByteArray(12){6};val wrapping=SyncV2Codec.hkdf(key,salt,"watchrss/cloud/reader-key/v1".toByteArray())
            val envelope=JSONObject().put("version",1).put("saltBase64",b64(salt)).put("nonceBase64",b64(nonce))
                .put("ciphertextBase64",b64(SyncV2Codec.encrypt(wrapping,nonce,("watchrss-reader-key-v1:"+SyncV2BodyCodec.name("book",bind.logicalId)).toByteArray(),key)))
            wrapping.fill(0)
            val validWrapped=changedLegacy{it.put("readerKeyEnvelope",envelope)}
            fun openChanged(p:Pair<JSONObject,ByteArray>,onChunk:()->Unit={}):ByteArray=SyncV2BodyCodec.open(p.first,scope,bind,f.f.signer.publicHex,{key}){h,_->
                if(h==SyncV2BodyCodec.hash(p.second))p.second else {onChunk();un64(legacyObjects.getString(h))}}
            assertArrayEquals(bookRaw,openChanged(validWrapped))
            for(bad in listOf(changedLegacy{it.put("readerKeyEnvelope",JSONObject(envelope.toString()).put("version",99))},
                changedLegacy{it.put("features",JSONObject().put("incompat",JSONArray().put("future.unsupported")))})) {
                var chunks=0;assertNotNull(runCatching{openChanged(bad){chunks++}}.exceptionOrNull());assertEquals(0,chunks)
                assertArrayEquals(bookRaw,SyncV2BodyRepository(f.sql,f.root).file("book",bind.logicalId).readBytes())
            }

        }
    }
    @Test fun rejectsOversizeBeforeSignatureOrCiphertextRead() {
        SyncV2P5Fixture().use{f->val raw=ByteArray(5*1024*1024+1);val binding=b("asset","too-large.bin",raw)
            assertEquals("object_too_large",runCatching{SyncV2BodyCodec.prepare(scope,anchor,binding,key,1,f.f.signer,raw)}.exceptionOrNull()?.message)
            val d=SyncV2BodyCodec.prepare(scope,anchor,b("asset","ok.bin",byteArrayOf(1)),key,1,f.f.signer,byteArrayOf(1)).descriptor
            d.getJSONObject("body").put("chunks",JSONArray((0..4).map{JSONObject().put("sha256",("%02x".format(it)).repeat(32)).put("sizeBytes",16777216)}))
            var reads=0;assertEquals("object_too_large",runCatching{SyncV2BodyCodec.open(d,scope,b("asset","ok.bin",byteArrayOf(1)),f.f.signer.publicHex,{key}){_,_->reads++;byteArrayOf()}}.exceptionOrNull()?.message);assertEquals(0,reads)
        }
    }
}
