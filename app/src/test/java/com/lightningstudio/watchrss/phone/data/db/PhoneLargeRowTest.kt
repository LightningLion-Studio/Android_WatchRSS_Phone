package com.lightningstudio.watchrss.phone.data.db

import androidx.room.Room
import com.lightningstudio.watchrss.phone.data.ble.BleDomainStore
import org.json.JSONArray
import com.lightningstudio.watchrss.phone.account.*
import com.lightningstudio.watchrss.phone.testing.MemoryPreferences
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import com.lightningstudio.watchrss.phone.connection.bluetooth.*
import com.lightningstudio.watchrss.phone.data.backup.WatchRssBackupService
import com.lightningstudio.watchrss.phone.data.backup.BackupImportMode
import com.lightningstudio.watchrss.phone.data.importer.ImportedWebArticle
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.*

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PhoneLargeRowTest {
    private val context=RuntimeEnvironment.getApplication()
    private val root=File(context.cacheDir,"large-row-${System.nanoTime()}").apply{mkdirs()}
    private val queries=java.util.Collections.synchronizedList(mutableListOf<String>())
    private fun openDatabase()=Room.databaseBuilder(context,PhoneCompanionDatabase::class.java,File(root,"phone.db").absolutePath)
        .setQueryCallback({sql,_->queries.add(sql)},java.util.concurrent.Executor { it.run() }).allowMainThreadQueries().build()
    private var db=openDatabase()
    private val dao get()=db.phoneArticleDao()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val repo get()=PhoneCompanionRepository(db.phoneSavedItemDao(),dao,db.phoneRssSourceDao(),"phone",db.syncChangeLogDao(),db.syncPeerStateDao(),appMetaDao=db.appMetaDao())
    // Each column exceeds the device CursorWindow. Emoji cross SQLite substr boundaries.
    private fun body(size:Int)="a".repeat(65_530)+"🙂漢字"+"b".repeat(size-65_540)+"🙂END"
    private fun row()=PhoneArticleEntity(articleId="large",sourceDeviceId="watch",url="https://fixture.invalid/591",title="big",siteName="fixture",excerpt="excerpt",
        contentHtml=body(2_135_000),contentText=body(2_123_000),imageUrl=null,contentHash="body",importedAt=1,updatedAt=10,
        independentSaved=true,independentChangedAt=10,independentSortOrder=10,rssSourceUrl="https://fixture.invalid/feed",rssSourceTitle="feed",
        favoriteSaved=true,favoriteChangedAt=10,favoriteSortOrder=10,watchLaterSaved=false,watchLaterChangedAt=0,watchLaterSortOrder=0,deleted=false,deletedAt=0)
    private fun same(expected:PhoneArticleEntity,actual:PhoneArticleEntity?){assertNotNull(actual);assertEquals(expected.contentHtml,actual!!.contentHtml);assertEquals(expected.contentText,actual.contentText)}
    @After fun close(){scope.cancel();db.close();root.deleteRecursively()}
    @Test fun oldInlineRowsReadExactlyAcrossEveryFullEntitySelector()=runBlocking<Unit>{
        val row=row();dao.upsert(row)
        same(row,dao.getById(row.articleId));same(row,dao.observeById(row.articleId).first())
        same(row,dao.getAllForSync().single());same(row,dao.getByRssSourceUrl(row.rssSourceUrl!!).single())
        same(row,dao.privateCloudBodyPage("",25).single())
        dao.upsert(row.copy(excerpt="x".repeat(281)));same(row,dao.longCloudExcerpts().single())
        same(row,repo.getArticle(row.articleId))
    }
    @Test fun fullManifestComparisonAndExportKeepTheEntireBody()=runBlocking<Unit>{
        val row=row();dao.upsert(row)
        val manifest=repo.getArticleManifestsForSync()
        assertEquals(1,manifest.size)
        queries.clear()
        assertTrue(repo.findDeleteConflicts(manifest).isEmpty())
        repo.prepareDeleteConflictResolutions(manifest,emptyMap())
        assertFalse("No-conflict comparison must not hydrate bodies",queries.any { "substr(" in it.lowercase() })
        same(row,repo.getArticlesForSync().single())
        assertEquals(ArticleSyncBody.metadataFor(row).bodyHash,manifest.single().bodyHash)
    }
    @Test fun privateCloudAndBackupExportRestoreWithoutTruncation()=runBlocking<Unit>{
        val row=row();dao.upsert(row)
        same(row,repo.getPrivateArticlesForCloudArchive().single())
        val backup=WatchRssBackupService(context,db,repo,ReaderPresetRepository(context,db,db.readerPresetDao(),"phone",scope),"phone")
        val archive=backup.createCloudPrivateArchive()
        val exported=ByteArrayOutputStream();backup.exportTo(exported);assertTrue(exported.size()>0)
        repo.clearLibrary();assertNull(dao.getById(row.articleId))
        backup.restorePrivateLibrary(archive);same(row,dao.getById(row.articleId))
        repo.clearLibrary();backup.importFrom(exported.toByteArray().inputStream(),BackupImportMode.REPLACE)
        same(row,dao.getById(row.articleId))
    }
    @Test fun sameUrlReimportReadsLegacyRowAndKeepsNewFullContent()=runBlocking<Unit>{
        val original=row();dao.upsert(original)
        val changedHtml=original.contentHtml+"REVISION2";val changedText=original.contentText+"REVISION2"
        val importer:suspend(String)->ImportedWebArticle={ImportedWebArticle(original.articleId,original.url,"revision2",original.siteName,original.excerpt,
            changedHtml,changedText,null,"new-hash")}
        val repository=PhoneCompanionRepository(db.phoneSavedItemDao(),dao,db.phoneRssSourceDao(),"phone",db.syncChangeLogDao(),db.syncPeerStateDao(),
            webArticleImporter=importer,appMetaDao=db.appMetaDao())
        repository.importWebArticle(original.url)
        assertEquals(changedHtml,dao.getById(original.articleId)!!.contentHtml)
        assertEquals(changedText,dao.getById(original.articleId)!!.contentText)
        assertEquals("revision2",dao.getById(original.articleId)!!.title)
    }
    @Test fun coldReopenAndNullVersusEmptyBodyRemainLossless()=runBlocking<Unit>{
        val row=row();dao.upsert(row);db.close();db=openDatabase();same(row,dao.getById(row.articleId))
        dao.upsert(row.copy(contentHtml=null,contentText=""));same(row.copy(contentHtml=null,contentText=""),dao.getById(row.articleId))
        dao.upsert(row.copy(contentHtml="",contentText=""));same(row.copy(contentHtml="",contentText=""),dao.getById(row.articleId))
        assertNull(dao.getById("missing"))
        assertEquals(19,db.openHelper.readableDatabase.version)
    }
    @Test fun embeddedNulAndUtf8CharactersAcrossByteBoundariesRemainExact()=runBlocking<Unit>{
        val original=row()
        val html="a".repeat(65_535)+"🙂\u0000tail"+original.contentHtml
        val text="b".repeat(65_534)+"漢🙂\u0000tail"+original.contentText
        val row=original.copy(contentHtml=html,contentText=text)
        dao.upsert(row);same(row,dao.getById(row.articleId))
        same(row,repo.getArticlesForSync().single())
        repo.deleteArticle(row.articleId);same(row,dao.getById(row.articleId))
    }
    @Test fun upgradeInvalidatesOldLargeBodyProjectionWithoutChangingBusinessState()=runBlocking<Unit>{
        val old=BleDomainStore(db,root,BleDomainStore.DOMAINS.associateWith { if(it=="library")5 else 1 })
        old.initialize()
        val row=row();val stale=ArticleSyncBody.metadataFor(row.copy(contentHtml="old incomplete",contentText="old incomplete"))
        dao.upsert(row.copy(syncBodyHash=stale.bodyHash,syncBodyByteCount=stale.bodyByteCount,syncChunkSize=stale.chunkSize,
            syncChunkHashesJson=JSONArray(stale.chunkHashes).toString(),syncMetadataHash=ArticleSyncBody.metadataHashFor(row)))
        val before=old.roots("account")
        old.publish("scope","watch",mapOf("library" to(before.getValue("library") to "b".repeat(64))))
        val upgraded=BleDomainStore(db,root);val after=upgraded.roots("account")
        assertNotEquals(before.getValue("library"),after.getValue("library"))
        assertFalse(upgraded.receipts("scope","watch").containsKey("library"))
        assertEquals("",dao.getById(row.articleId)!!.syncMetadataHash)
        val repository=PhoneCompanionRepository(db.phoneSavedItemDao(),dao,db.phoneRssSourceDao(),"phone",db.syncChangeLogDao(),db.syncPeerStateDao(),
            appMetaDao=db.appMetaDao(),bleDomains=upgraded)
        assertEquals(ArticleSyncBody.metadataFor(row).bodyHash,repository.getArticleManifestsForSync().single().bodyHash)
        val restored=dao.getById(row.articleId)!!;same(row,restored)
        assertEquals(row.updatedAt,restored.updatedAt);assertEquals(row.favoriteChangedAt,restored.favoriteChangedAt)
    }
    @Test fun logoutReleasesIdentityAndKeepsLocalLargeArticle()=runBlocking<Unit>{
        val row=row();dao.upsert(row)
        val store=EncryptedAccountSessionStore(context).also {
            EncryptedAccountSessionStore::class.java.getDeclaredField("prefsRef").apply{isAccessible=true}.set(it,MemoryPreferences())
        }
        // Expired session exercises actual logout without a remote request.
        store.save(PhoneAccountSession("fixture-owner","***","session","refresh",1))
        val env=AccountEnvironment(backendBaseUrl="https://fixture.invalid")
        val account=PhoneAccountRepository(env,store,PhoneInstallationIdentity(context),PhoneAccountClient(env,LicenseDeviceIdentity(context)),"phone")
        var cleared=false
        account.onReleaseCloudAuthorization={same(row,repo.getArticle(row.articleId))}
        account.onClearCloudState={cleared=true}
        account.logout()
        assertTrue(cleared);assertNull(account.session.value);same(row,dao.getById(row.articleId))
    }
    @Test fun reimportUpdateAndNormalDeleteThenOwnerClearAreSafe()=runBlocking<Unit>{
        val row=row();dao.upsert(row)
        repo.mergeArticlesFromSync(listOf(row.copy(title="revision2",updatedAt=20)))
        assertEquals("revision2",repo.getArticle(row.articleId)!!.title)
        same(row,repo.getArticle(row.articleId))
        repo.deleteArticle(row.articleId);assertTrue(dao.getById(row.articleId)!!.deleted)
        repo.clearLibrary();assertTrue(dao.getAllIds().isEmpty())
    }
}
