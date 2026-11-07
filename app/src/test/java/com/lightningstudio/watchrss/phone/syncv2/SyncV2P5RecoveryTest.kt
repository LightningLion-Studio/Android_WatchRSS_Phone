package com.lightningstudio.watchrss.phone.syncv2

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2P5RecoveryTest {
    private fun article(id:String)=PhoneArticleEntity(id,"synthetic","https://example.invalid/$id","title","site","summary",null,"necessary original body",null,"body-hash",0,0,true,0,0,null,null,true,0,0,false,0,0,false,0)
    private fun code(expected:String,block:()->Unit){val e=runCatching(block).exceptionOrNull();assertNotNull(e);assertEquals(expected,if(e is SyncV2CloudError)e.code else e!!.message)}
    private fun addArticle(w:SyncV2P5Fixture,id:String="local-article"){runBlocking{w.room.phoneArticleDao().upsert(article(id))};w.drain()}
    @Test fun originalArticleBodyIsEncryptedPinnedAndReadableAfterSourceRowIsDiscarded():Unit=SyncV2P5Fixture().use{w->
        w.pending();addArticle(w);val a=w.actions();a.choose(a.preview(),"cloud")
        assertEquals(0,w.sql.query("SELECT COUNT(*) FROM phone_articles").use{it.moveToFirst();it.getInt(0)})
        val copyId=a.recoveryCopies().getJSONObject(0).getString("copyId");val delta=a.recoveryDelta(copyId)
        assertEquals(2,delta.getJSONArray("changes").length());val refs=SyncV2Wire.strings(delta.getJSONArray("bodyRefs"),100000,false)
        val recipe=refs.single{it.startsWith("recipe:")};assertEquals(2,refs.size)
        val original=JSONObject(a.recoveryBodyPart(copyId,recipe,0,65536).toString(Charsets.UTF_8));assertEquals("necessary original body",original.getString("contentText"))
        w.sql.query("SELECT body FROM syncv2_recovery_objects").use{c->while(c.moveToNext())assertFalse(c.getBlob(0).toString(Charsets.UTF_8).contains("necessary original body"))}
        w.reopen();assertEquals("necessary original body",JSONObject(w.actions().recoveryBodyPart(copyId,recipe,0,65536).toString(Charsets.UTF_8)).getString("contentText"))
    }
    @Test fun multiMegabyteRecoveryManifestIsChunkedWithoutGrowingTheAtomicStateRow():Unit=SyncV2P5Fixture().use{w->
        w.pending();val text="n".repeat(60000)
        repeat(19){i->w.sql.execSQL("INSERT INTO notes VALUES(?,NULL,'large note',?,?,'hash','base','base',0,0,0,'synthetic',0,0)",arrayOf("large-$i",text,text))};w.drain()
        val a=w.actions();a.choose(a.preview(),"cloud")
        assertTrue(w.sql.query("SELECT COALESCE(SUM(length(bytes)),0) FROM syncv2_recovery_chunks WHERE kind='ciphertext'").use{it.moveToFirst();it.getLong(0)}>2*1024*1024)
        assertTrue(w.sql.query("SELECT MAX(length(bytes)) FROM syncv2_recovery_chunks").use{it.moveToFirst();it.getLong(0)}<=65536)
        assertTrue(w.sql.query("SELECT length(body) FROM syncv2_state").use{it.moveToFirst();it.getLong(0)}<1024*1024)
        val id=a.recoveryCopies().getJSONObject(0).getString("copyId");w.reopen();assertEquals(20,w.actions().recoveryDelta(id).getJSONArray("changes").length())
    }
    @Test fun localChoicePreservesP3BodyBindingAndNormalC10CanPublishItsRecipe():Unit=SyncV2P5Fixture().use{w->
        w.pending();addArticle(w);val a=w.actions();a.choose(a.preview(),"local");w.drain()
        val header=w.headers.getValue(w.current.getString("commitOid")).getJSONObject("body");val prepared=w.projection.prepareWireIndex(w.f.signer,w.key,1)
        assertEquals(header.getString("treeRoot"),prepared.tree.root)
        val row=w.projection.wireContentRow(w.identity.entity("article","local-article"))!!
        val store=SyncV2ContentStore(File(w.root,"body-export"),w.identity.scopeJson,SyncV2Fixtures.localKey);val export=SyncV2ContentExport(w.identity,w.key,1,store)
        val binding=SyncV2ContentExport.Binding(row.getString("entityKey"),row.getString("bodyRevision"),export.authenticationBinding);val bytes="necessary original body".toByteArray()
        val source=object:SyncV2ContentExport.Source{override val size=bytes.size.toLong();override val version="synthetic-v1";override fun read(offset:Long,maxBytes:Int)=bytes.copyOfRange(offset.toInt(),offset.toInt()+maxBytes)}
        while(!export.step(binding,source)){};val descriptor=export.descriptor(binding)!!;val recipe=descriptor.getJSONObject("recipe");val recipeOid=descriptor.getString("recipeOid")
        // The synthetic backend begins this assertion with the exact complete content objects.
        val oids=SyncV2Wire.strings(JSONArray().put(recipeOid).also{for(i in 0 until recipe.getJSONArray("blocks").length())it.put(recipe.getJSONArray("blocks").getJSONObject(i).getString("oid"))},100,false)
        for(oid in oids)w.f.objects[oid]=store.objectBytes(oid)
        w.projection.attachContentRecipe(row,recipeOid,recipe)
        val sync=SyncV2CloudSync(w.f.client(),w.identity,w.projection,{it=="synthetic-phone"},{w.key},now={w.f.now});sync.readCurrent();sync.publishIndex(w.f.signer,w.key,1);sync.readCurrent()
        val current=w.projection.cloudRows().single{it.getJSONObject("row").getString("entityKey")==row.getString("entityKey")}.getJSONObject("row")
        assertEquals(recipeOid,current.getString("recipeOid"));assertEquals(row.getString("bodyRevision"),current.getString("bodyRevision"))
    }
    @Test fun laterRollbackCannotSilentlyDiscardAJustPublishedPromiseBody():Unit=SyncV2P5Fixture().use{w->
        w.pending();addArticle(w);val a=w.actions();a.choose(a.preview(),"local");w.drain()
        w.rollbackRemote();a.refresh();w.drain()
        assertEquals("awaiting-user",w.projection.status());assertNotNull(a.pendingChoice())
        assertEquals(1,w.sql.query("SELECT COUNT(*) FROM phone_articles").use{it.moveToFirst();it.getInt(0)})
        a.choose(a.preview(),"cloud")
        val copy=a.recoveryCopies().getJSONObject(0).getString("copyId")
        val recipe=SyncV2Wire.strings(a.recoveryDelta(copy).getJSONArray("bodyRefs"),100000,false).single{it.startsWith("recipe:")}
        assertEquals("necessary original body",JSONObject(a.recoveryBodyPart(copy,recipe,0,65536).toString(Charsets.UTF_8)).getString("contentText"))
    }
    @Test fun initiatingRollbackAlsoKeepsAnUnuploadedOriginalAndOffersRecoveryChoice():Unit=SyncV2P5Fixture().use{w->
        w.pending();addArticle(w);val a=w.actions();a.choose(a.preview(),"local");w.drain()
        a.rollback(a.captureRollback(),w.target);w.drain()
        assertNotNull(a.pendingChoice());assertEquals(1,w.sql.query("SELECT COUNT(*) FROM phone_articles").use{it.moveToFirst();it.getInt(0)})
        a.choose(a.preview(),"cloud");assertEquals(1,a.recoveryCopies().length())
    }
    @Test fun multiMegabyteArticleAvoidsCursorWindowDuringApplyAndRecovery():Unit=SyncV2P5Fixture().use{w->
        w.pending();val text="正文".repeat(600000)
        runBlocking{w.room.phoneArticleDao().upsert(article("large-article").copy(contentText=text))};w.drain()
        val a=w.actions();a.choose(a.preview(),"local");w.drain()
        assertEquals(text.toByteArray().size.toLong(),w.sql.query("SELECT length(CAST(contentText AS BLOB)) FROM phone_articles").use{it.moveToFirst();it.getLong(0)})
        w.rollbackRemote();a.refresh();w.drain();a.choose(a.preview(),"cloud")
        val id=a.recoveryCopies().getJSONObject(0).getString("copyId");val recipe=SyncV2Wire.strings(a.recoveryDelta(id).getJSONArray("bodyRefs"),100000,false).single{it.startsWith("recipe:")}
        val out=java.io.ByteArrayOutputStream();var offset=0L
        while(true){val b=a.recoveryBodyPart(id,recipe,offset,65536);if(b.isEmpty())break;out.write(b);offset+=b.size}
        assertEquals(text,JSONObject(out.toString("UTF-8")).getString("contentText"))
    }
    @Test fun exactSevenDayBoundaryPurgesCiphertextAndPinsTogetherAndClockRollbackKeepsThem():Unit=SyncV2P5Fixture().use{w->
        w.pending();addArticle(w);val a=w.actions();a.choose(a.preview(),"cloud");val header=a.recoveryCopies().getJSONObject(0);val expires=header.getLong("expiresAtMillis")
        w.f.now=expires-1;a.purgeRecovery();assertEquals(1,a.recoveryCopies().length())
        w.f.now-=100;a.purgeRecovery();assertEquals(1,a.recoveryCopies().length())
        w.f.now=expires;a.purgeRecovery();assertEquals(0,a.recoveryCopies().length())
        assertEquals(0,w.sql.query("SELECT COUNT(*) FROM syncv2_recovery_objects").use{it.moveToFirst();it.getInt(0)})
    }
    @Test fun stableIdsSurviveCompactionAppendAndRestartAndOnlyExpiredBodiesAreRemoved():Unit=SyncV2P5Fixture().use{w->
        w.pending();addArticle(w,"first");var a=w.actions();a.choose(a.preview(),"cloud");val first=a.recoveryCopies().getJSONObject(0);val firstId=first.getString("copyId")
        w.f.now+=100;w.drain();w.edit("second");addArticle(w,"second");w.rollbackRemote();a.refresh();w.drain();a.choose(a.preview(),"cloud")
        val second=a.recoveryCopies().let{v->(0 until v.length()).map{v.getJSONObject(it)}.single{it.getString("copyId")!=firstId}};val secondId=second.getString("copyId");val secondBody=a.recoveryDelta(secondId).toString()
        w.f.now=first.getLong("expiresAtMillis");a.purgeRecovery();assertEquals(secondId,a.recoveryCopies().getJSONObject(0).getString("copyId"));assertEquals(secondBody,a.recoveryDelta(secondId).toString())
        w.reopen();a=w.actions();assertEquals(secondId,a.recoveryCopies().getJSONObject(0).getString("copyId"))
        w.drain();w.edit("third");w.rollbackRemote();a.refresh();w.drain();a.choose(a.preview(),"cloud");assertEquals(2,a.recoveryCopies().length())
        w.f.now=second.getLong("expiresAtMillis");a.purgeRecovery();val remaining=a.recoveryCopies().getJSONObject(0);assertNotEquals(secondId,remaining.getString("copyId"));assertNotEquals(firstId,remaining.getString("copyId"))
        w.f.now=remaining.getLong("expiresAtMillis");a.purgeRecovery();assertEquals(0,a.recoveryCopies().length())
    }
    @Test fun purgeFaultRollsBackCopiesPinsAndPrivateObjectsAsOneSQLiteCommit():Unit=SyncV2P5Fixture().use{w->
        w.pending();addArticle(w);val a=w.actions();a.choose(a.preview(),"cloud");val header=a.recoveryCopies().getJSONObject(0);val expires=header.getLong("expiresAtMillis")
        val faulty=SyncV2Projection(w.sql,w.identity,SyncV2Fixtures.localKey,true){if(it=="before-commit")throw java.io.IOException("purge crash")}
        val storage=SyncV2RecoveryStorage(faulty,w.identity,w.root)
        assertNotNull(runCatching{storage.purge(expires)}.exceptionOrNull());assertEquals(1,a.recoveryCopies().length())
        assertEquals(2,w.sql.query("SELECT COUNT(*) FROM syncv2_recovery_objects").use{it.moveToFirst();it.getInt(0)})
    }
    @Test fun lostAttachmentAndRecoveryQuotaStopBeforeCloudAuditAndKeepOriginalEdits():Unit=SyncV2P5Fixture().use{w->
        w.pending();w.edit("![image](assets/missing.jpg)\n");val a=w.actions();val p=a.preview();assertFalse(p.canMerge);assertEquals("missing_content",p.reason)
        code("closure_incomplete"){a.choose(p,"cloud")};assertEquals(0,w.choiceCalls);assertTrue(w.text().contains("missing.jpg"));assertEquals("awaiting-user",w.projection.status())
    }
    @Test fun unregisteredAttachmentBytesCannotBecomeAnAuthenticatedRecoveryClosure():Unit=SyncV2P5Fixture().use{w->
        w.pending();val file=File(w.root,"notes/assets/unregistered.jpg");requireNotNull(file.parentFile).mkdirs();file.writeBytes(byteArrayOf(1,2,3))
        w.edit("![image](assets/unregistered.jpg)");val a=w.actions();val p=a.preview();assertFalse(p.canMerge)
        code("closure_incomplete"){a.choose(p,"cloud")};assertEquals(0,w.choiceCalls);assertTrue(w.text().contains("unregistered.jpg"))
    }
    @Test fun registeredNoteAttachmentClosureStaysRecoverableAfterCloudChoice():Unit=SyncV2P5Fixture().use{w->
        w.pending();val bytes=byteArrayOf(3,4,5,6);val file=File(w.root,"notes/assets/asset.jpg");requireNotNull(file.parentFile).mkdirs()
        com.lightningstudio.watchrss.phone.data.local.CloudExportFileWitness.changing(file){file.writeBytes(bytes)}
        w.sql.execSQL("INSERT INTO note_assets VALUES('asset','note',?,'asset.jpg','image/jpeg',4,'asset.jpg',0,0,0,0)",arrayOf(SyncV2Codec.hex(SyncV2Codec.sha(bytes))))
        w.edit("![image](assets/asset.jpg)");val a=w.actions();a.choose(a.preview(),"cloud")
        val id=a.recoveryCopies().getJSONObject(0).getString("copyId");val refs=SyncV2Wire.strings(a.recoveryDelta(id).getJSONArray("bodyRefs"),100000,false).filter{it.startsWith("recipe:")}
        assertTrue(refs.isNotEmpty());for(recipe in refs)assertArrayEquals(bytes,a.recoveryBodyPart(id,recipe,0,65536))
        assertEquals(0,w.sql.query("SELECT COUNT(*) FROM note_assets").use{it.moveToFirst();it.getInt(0)})
    }
    @Test fun quotaCountsExistingCopiesAndDisabledSettingNeverDeletesPromises():Unit=SyncV2P5Fixture().use{w->
        w.pending();val a=w.actions();a.choose(a.preview(),"cloud");val first=a.recoveryCopies().getJSONObject(0).getString("copyId")
        a.setKeepDiscardedLocalChanges7Days(false);assertEquals(first,a.recoveryCopies().getJSONObject(0).getString("copyId"))
        w.drain();w.edit("second");w.rollbackRemote();a.refresh();w.drain();a.setKeepDiscardedLocalChanges7Days(true)
        val used=w.sql.query("SELECT SUM(length(body)+length(pin)) FROM syncv2_recovery_copies").use{it.moveToFirst();it.getLong(0)}
        val small=w.actions(quota=used+10);code("quota_exceeded"){small.choose(small.preview(),"cloud")};assertEquals(first,a.recoveryCopies().getJSONObject(0).getString("copyId"));assertEquals("second",w.text())
    }
    @Test fun productFileMutationInvalidatesCaptureEvenBeforeItsRoomMetadataWrite():Unit=SyncV2P5Fixture().use{w->
        w.pending();val a=w.actions();val p=a.preview();val file=File(w.root,"notes/assets/new.jpg");requireNotNull(file.parentFile).mkdirs()
        com.lightningstudio.watchrss.phone.data.local.CloudExportFileWitness.changing(file){file.writeText("new attachment")}
        code("choice_stale"){a.choose(p,"cloud")};assertEquals(0,w.choiceCalls);assertEquals("offline\nline\n",w.text())
    }
    @Test fun recoveryBodyCannotBeReadThroughAnotherCopyOrTamperedCiphertext():Unit=SyncV2P5Fixture().use{w->
        w.pending();addArticle(w);val a=w.actions();a.choose(a.preview(),"cloud");val id=a.recoveryCopies().getJSONObject(0).getString("copyId")
        code("closure_incomplete"){a.recoveryBodyPart(id,"recipe:sha256:"+"0".repeat(64),0,100)}
        val recipe=SyncV2Wire.strings(a.recoveryDelta(id).getJSONArray("bodyRefs"),100000,false).single{it.startsWith("recipe:")}
        w.sql.execSQL("UPDATE syncv2_recovery_objects SET body=? WHERE oid=?",arrayOf(byteArrayOf(1,2,3),recipe))
        assertNotNull(runCatching{a.recoveryBodyPart(id,recipe,0,100)}.exceptionOrNull());assertEquals(1,a.recoveryCopies().length())
    }
}
