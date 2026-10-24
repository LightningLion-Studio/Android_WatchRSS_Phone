package com.lightningstudio.watchrss.phone.data.ble

import androidx.room.Room
import com.lightningstudio.watchrss.phone.data.db.*
import com.lightningstudio.watchrss.phone.data.book.*
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class BleProjectionUpgradeTest {
    private val context=RuntimeEnvironment.getApplication()
    private val directory=File(context.cacheDir,"projection-upgrade-${System.nanoTime()}").also{it.mkdirs()}
    private val db=Room.inMemoryDatabaseBuilder(context,PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
    private var time=1_000_000_000L
    private fun store(encoder:Int=1)=BleDomainStore(db,directory,BleDomainStore.DOMAINS.associateWith {if(it=="library")encoder else 1},{time})
    private fun repo(s:BleDomainStore)=PhoneCompanionRepository(db.phoneSavedItemDao(),db.phoneArticleDao(),db.phoneRssSourceDao(),"phone",db.syncChangeLogDao(),db.syncPeerStateDao(),appMetaDao=db.appMetaDao(),bleDomains=s)
    private fun row()=PhoneArticleEntity("a","phone","https://example.org/a","title","site","",null,"body",null,"hash",1,100,false,0,0,"https://example.org/rss","rss",true,100,1,false,0,0,false,0)
    @Before fun registered()=runBlocking<Unit>{db.bookReadingDao().putRegistration(BookCaptureMetaEntity(checkedRevision=0))}
    @After fun close(){db.close();directory.deleteRecursively()}

    @Test fun actualFiveLegacyDecisionsConsumeOldAliasTombstonesAcrossFullAndUpgrade()=runBlocking<Unit> {
        val fixture=org.json.JSONArray(requireNotNull(javaClass.getResourceAsStream("/ble/conflict5-state-chain.json")).bufferedReader().readText())
        val old=store();old.initialize()
        val remotes=mutableListOf<com.lightningstudio.watchrss.phone.connection.bluetooth.ArticleSyncManifestEntry>()
        for(i in 0 until fixture.length()) {
            val c=fixture.getJSONObject(i);val p=c.getJSONObject("phone");val w=c.getJSONObject("watchProjection")
            val id=p.getString("articleId");val alias=c.getJSONObject("phoneAlias").getString("old")
            val local=row().copy(articleId=id,sourceDeviceId="phone",url="https://redacted.invalid/$i",favoriteSaved=false,
                favoriteChangedAt=p.getLong("favoriteChangedAt"),updatedAt=p.getLong("updatedAt"),deleted=false,deletedAt=0)
            db.phoneArticleDao().upsert(local)
            db.appMetaDao().set(AppMetaEntity("rss_identity_alias:"+com.lightningstudio.watchrss.phone.cloud.CloudSnapshotCodec.sha256(byteArrayOf())+":"+alias,id))
            for(clock in c.getJSONArray("phoneOperations").let { a->(0 until a.length()).map(a::getJSONObject) }.filter{it.getString("reason")=="conflictResolution"}) {
                db.syncChangeLogDao().insert(SyncChangeLogEntity(kind="article",entityId=id,changedAt=clock.getLong("changedAt"),originDeviceId="phone",reason="conflictResolution",createdAt=clock.getLong("createdAt")))
            }
            remotes+=com.lightningstudio.watchrss.phone.connection.bluetooth.ArticleSyncManifestEntry(alias,"watch",local.contentHash,w.getLong("updatedAt"),w.getLong("independentChangedAt"),w.getLong("favoriteChangedAt"),w.getLong("watchLaterChangedAt"),w.getLong("deletedAt"),true)
        }
        val upgraded=repo(store(2))
        val projection=upgraded.bluetoothIdentityProjection(true)
        val localRemotes=remotes.map(projection::localManifest)
        assertEquals((0 until fixture.length()).map { fixture.getJSONObject(it).getJSONObject("phone").getString("articleId") },localRemotes.map{it.articleId})
        remotes.clear();remotes.addAll(localRemotes)
        assertTrue(upgraded.findDeleteConflicts(remotes).isEmpty())
        // A new tombstone after the decision still requires a real choice.
        assertEquals(1,upgraded.findDeleteConflicts(listOf(remotes.first().copy(updatedAt=9_000_000_000_000L,deletedAt=9_000_000_000_000L))).size)
        val plan=upgraded.prepareDeleteConflictResolutions(remotes,emptyMap())
        assertEquals(5,plan.outgoingArticleIds.size)
        val clocks=db.phoneArticleDao().getAllForSync().associate{it.articleId to it.updatedAt}
        for(encoder in listOf(2,3,4)) {
            val r=repo(store(encoder));r.prepareLibrarySyncWindow("watch",0,true)
            assertTrue(r.findDeleteConflicts(remotes).isEmpty())
            assertEquals(5,r.prepareDeleteConflictResolutions(remotes,emptyMap()).outgoingArticleIds.size)
            assertEquals(clocks,db.phoneArticleDao().getAllForSync().associate{it.articleId to it.updatedAt})
        }
    }

    @Test fun explicitConflictDecisionIsBusinessStateAcrossActualSqlCacheUpgradeAndFullSnapshot()=runBlocking<Unit> {
        val s=store();val r=repo(s);s.initialize();db.phoneArticleDao().upsert(row())
        val remote=r.getArticleManifestsForSync().single().copy(sourceDeviceId="watch",updatedAt=9_000_000_000_000L,deleted=true,deletedAt=9_000_000_000_000L)
        r.prepareDeleteConflictResolutions(listOf(remote),mapOf("a" to com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneSyncConflictResolution.KEEP_PHONE))
        val decided=db.phoneArticleDao().getById("a")!!
        assertTrue(decided.updatedAt>remote.deletedAt);assertFalse(decided.deleted)
        assertTrue(db.syncChangeLogDao().maxSeq()>0)
        val next=store(2);next.initialize()
        assertNotNull(db.appMetaDao().getString("article_conflict_decision_v1:a"))
        val upgraded=repo(next);val window=upgraded.prepareLibrarySyncWindow("watch",0,true)
        assertTrue(window.fullSnapshot);assertEquals(decided.updatedAt,window.articleManifest.single().updatedAt)
        assertTrue(upgraded.findDeleteConflicts(listOf(remote)).isEmpty())
        assertEquals(setOf("a"),upgraded.prepareDeleteConflictResolutions(listOf(remote),emptyMap()).outgoingArticleIds)
        assertEquals(decided.updatedAt,db.phoneArticleDao().getById("a")!!.updatedAt)
    }

    @Test fun encoderUpgradeRebuildsUnchangedManifestAndForcesFullOutsideFastPath()=runBlocking<Unit> {
        val old=store();old.initialize();db.phoneArticleDao().upsert(row())
        val r=repo(old);val first=r.prepareLibrarySyncWindow("watch",0,true)
        r.markLibrarySyncSuccess("watch",first.toSeqInclusive,0,14,true,true,first.projectionDefinition)
        val roots=old.roots("account");old.publish("scope","watch",mapOf("library" to (roots.getValue("library")to "b".repeat(64)),"notes" to (roots.getValue("notes")to "b".repeat(64))))
        db.openHelper.writableDatabase.execSQL("UPDATE ble_fast_manifest SET json=json_set(json,'$.metadataHash','stale-encoding')")
        val next=store(2);val changed=next.roots("account")
        assertNotEquals(roots["library"],changed["library"]);assertEquals(roots["notes"],changed["notes"])
        assertEquals(setOf("notes"),next.receipts("scope","watch").keys);assertTrue(next.manifests().isEmpty())
        assertEquals("",db.phoneArticleDao().getById("a")!!.syncMetadataHash)
        val window=repo(next).prepareLibrarySyncWindow("watch",first.toSeqInclusive,true)
        assertTrue(window.fullSnapshot);assertEquals("projectionChanged",window.fallbackReason)
        assertEquals(listOf("a"),window.articleManifest.map{it.articleId})
        assertNotEquals("stale-encoding",window.articleManifest.single().metadataHash)
        assertEquals("body",db.phoneArticleDao().getById("a")!!.contentText)
    }
    @Test fun addColumnReinstallsLiveTriggersRotatesAffectedDomainsAndSeedsUnchangedRows()=runBlocking<Unit> {
        val s=store();s.initialize();db.phoneArticleDao().upsert(row());repo(s).prepareLibrarySyncWindow("watch",0,true)
        val before=s.roots("account");val definition=s.libraryDefinition()
        db.openHelper.writableDatabase.execSQL("ALTER TABLE phone_articles ADD COLUMN futureProjection TEXT NOT NULL DEFAULT ''")
        val after=s.roots("account")
        assertNotEquals(definition,s.libraryDefinition());assertTrue(s.manifests().isEmpty());assertEquals("a",s.pending().single().first)
        for(d in listOf("library","books","bookReading"))assertNotEquals(d,before[d],after[d])
        assertEquals(before["notes"],after["notes"])
        db.openHelper.writableDatabase.execSQL("UPDATE phone_articles SET futureProjection='new' WHERE articleId='a'")
        assertNotEquals(after["library"],s.roots("account")["library"])
        db.bookDao().putChapters(listOf(BookChapterEntity("book",1,"a")))
        val mapped=s.roots("account")
        db.openHelper.writableDatabase.execSQL("UPDATE phone_articles SET futureProjection='newer' WHERE articleId='a'")
        assertNotEquals(mapped["bookReading"],s.roots("account")["bookReading"])
        assertNotEquals(mapped["books"],s.roots("account")["books"])
    }
    @Test fun schemaVersionAloneAlsoChangesProjectionAndRoots() {
        val s=store();val before=s.roots("account");db.openHelper.writableDatabase.version=19
        val after=s.roots("account");assertNotEquals(before,after)
        assertEquals(19,s.projectionVersions().getValue("library").schema)
    }
    @Test fun sevenDayReceiptsDoNotSlideOnSkipAndLegacyOrFutureTimesExpire() {
        val s=store();val pair=s.roots("account").getValue("library") to "b".repeat(64)
        s.publish("scope","watch",mapOf("library" to pair));time+=BleDomainStore.RECEIPT_MAX_AGE_MS-1
        assertEquals(pair,s.receipts("scope","watch")["library"])
        s.publish("scope","watch",mapOf("library" to pair),emptySet());time++
        assertTrue(s.receipts("scope","watch").isEmpty())
        time=999_999_999;assertTrue(s.receipts("scope","watch").isEmpty())
        db.openHelper.writableDatabase.execSQL("UPDATE ble_fast_receipts SET verifiedAt=0")
        assertTrue(s.receipts("scope","watch").isEmpty())
    }
    @Test fun peerProtocolAndPeriodicFullRemainAdmissionRulesEvenWithFreshProof()=runBlocking<Unit> {
        val s=store();val r=repo(s);s.acknowledgeLibraryDefinition("watch",s.libraryDefinition())
        r.markLibrarySyncSuccess("watch",0,0,14,true,true,s.libraryDefinition())
        val now=System.currentTimeMillis()
        db.syncPeerStateDao().upsert(SyncPeerStateEntity("watch",lastProtocolVersion=12,lastFullSyncAt=now))
        assertFalse(r.libraryFastPathAllowed("watch"));assertEquals("peerProtocol",r.prepareLibrarySyncWindow("watch",null,true).fallbackReason)
        db.syncPeerStateDao().upsert(SyncPeerStateEntity("watch",lastProtocolVersion=14,lastFullSyncAt=now-BleDomainStore.RECEIPT_MAX_AGE_MS))
        assertFalse(r.libraryFastPathAllowed("watch"));assertEquals("periodicFull",r.prepareLibrarySyncWindow("watch",null,true).fallbackReason)
        db.syncPeerStateDao().upsert(SyncPeerStateEntity("watch",lastProtocolVersion=14,lastFullSyncAt=now+100_000))
        assertFalse(r.libraryFastPathAllowed("watch"));assertEquals("periodicFull",r.prepareLibrarySyncWindow("watch",null,true).fallbackReason)
        db.syncPeerStateDao().upsert(SyncPeerStateEntity("watch",lastProtocolVersion=14,lastFullSyncAt=now))
        assertTrue(r.libraryFastPathAllowed("watch"))
    }
    @Test fun fullAckCannotAcknowledgeDefinitionAddedAfterFrozenWindow()=runBlocking<Unit> {
        val s=store();val r=repo(s);val window=r.prepareLibrarySyncWindow("watch",0,true)
        db.openHelper.writableDatabase.execSQL("ALTER TABLE phone_articles ADD COLUMN nextEncoding INTEGER NOT NULL DEFAULT 0")
        s.initialize();r.markLibrarySyncSuccess("watch",window.toSeqInclusive,0,14,true,true,window.projectionDefinition)
        assertFalse(r.libraryFastPathAllowed("watch"));assertEquals("projectionChanged",r.prepareLibrarySyncWindow("watch",null,true).fallbackReason)
    }
    @Test fun rootWindowTraversesDirectoriesOnceAndLateFileAppearanceCannotBeCertified() {
        val s=store();val folder=File(directory,"imported_text").also{it.mkdirs()};File(folder,"old.txt").writeText("old")
        val window=s.beginRootWindow("account");val traversals=s.inventoryTraversalsByDomain.toMap()
        assertEquals(window.initial,window.current("account"));assertEquals(window.initial,window.current("account"))
        assertEquals(1,traversals["library"]);assertEquals(traversals,s.inventoryTraversalsByDomain)
        File(folder,"late.txt").writeText("late")
        assertFalse("library" in window.current("account"));assertEquals(traversals,s.inventoryTraversalsByDomain)
    }
    @Test fun cachedRootGuardStillObservesSqlAndSameSizeFileWrites()=runBlocking<Unit> {
        val s=store();s.initialize();db.phoneArticleDao().upsert(row())
        val folder=File(directory,"imported_text").also{it.mkdirs()};val file=File(folder,"body.txt").also{it.writeText("old")}
        val window=s.beginRootWindow("account");val traversals=s.inventoryTraversalsByDomain.toMap()
        db.phoneArticleDao().upsert(row().copy(isRead=true))
        assertFalse("library" in window.current("account"));assertEquals(traversals,s.inventoryTraversalsByDomain)
        val other=s.beginRootWindow("account");file.writeText("new");file.setLastModified(file.lastModified()+3000)
        assertFalse("library" in other.current("account"));assertTrue("notes" in other.current("account"))
    }
    @Test fun readingEncoderUpgradeRotatesDerivedJournalButPreservesBusinessStates()=runBlocking<Unit> {
        val old=store();old.initialize()
        val books=ImportedBookStore(db,File(directory,"imported_books"),null,"phone")
        val state=org.json.JSONObject().put("bookId",BookSync.id("a".repeat(64))).put("chapterIndex",1).put("isRead",true).put("readingPositionChangedAt",100).put("sourceDeviceId","phone").put("readingPositionBytes",42).put("readingProgress",0.5).put("readingPositionContentHash","hash")
        books.mergeReading(state);val before=books.readingSummary();val business=db.bookDao().reading()
        val upgraded=BleDomainStore(db,directory,BleDomainStore.DOMAINS.associateWith{if(it=="bookReading")2 else 1},{time})
        upgraded.initialize();val after=books.readingSummary()
        assertNotEquals(before.epoch,after.epoch);assertEquals(before.root,after.root);assertEquals(business,db.bookDao().reading())
    }

    @Test fun schemaReseedCannotAcceptLateManifestPublicationWithReusedSeedSequence()=runBlocking<Unit> {
        db.phoneArticleDao().upsert(row());val s=store();s.initialize()
        val pending=s.pending().single();val definition=s.libraryDefinition()
        val entry=repo(s).getArticleManifestsForSync().single()
        db.openHelper.writableDatabase.execSQL("ALTER TABLE phone_articles ADD COLUMN afterExport INTEGER NOT NULL DEFAULT 0")
        s.initialize();assertEquals(pending.second,s.pending().single().second)
        assertFalse(s.publishManifest("a",pending.second,entry,expectedDefinition=definition))
        assertTrue(s.manifests().isEmpty())
    }

    @Test fun identicalDefinitionReinstallationCannotAcceptOldGenerationFullAck()=runBlocking<Unit> {
        val s=store();val r=repo(s);val window=r.prepareLibrarySyncWindow("watch",0,true)
        val descriptor=s.projectionVersions().getValue("library")
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER ble_fast_phone_articles_library_UPDATE")
        s.initialize()
        assertEquals(descriptor,s.projectionVersions().getValue("library"))
        assertNotEquals(window.projectionDefinition,s.libraryDefinition())
        r.markLibrarySyncSuccess("watch",window.toSeqInclusive,0,14,true,true,window.projectionDefinition)
        assertFalse(s.libraryBaselineMatches("watch"));assertFalse(r.libraryFastPathAllowed("watch"))
    }

    @Test fun nativeProtocolVersionUpgradeAlsoForcesReexportWithoutBusinessWrites()=runBlocking<Unit> {
        val s=store();s.initialize();db.phoneArticleDao().upsert(row());val r=repo(s)
        val old=r.prepareLibrarySyncWindow("watch",0,true)
        r.markLibrarySyncSuccess("watch",old.toSeqInclusive,0,14,true,true,old.projectionDefinition)
        val upgraded=BleDomainStore(db,directory,BleDomainStore.DOMAINS.associateWith{1},{time},15)
        val next=repo(upgraded).prepareLibrarySyncWindow("watch",old.toSeqInclusive,true)
        assertTrue(next.fullSnapshot);assertEquals("projectionChanged",next.fallbackReason)
        assertEquals(listOf("a"),next.articleManifest.map{it.articleId})
        assertNotEquals(old.projectionDefinition,next.projectionDefinition)
    }

}
