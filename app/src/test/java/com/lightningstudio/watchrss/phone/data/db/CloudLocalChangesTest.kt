package com.lightningstudio.watchrss.phone.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.lightningstudio.watchrss.phone.cloud.CloudCycleCheckpoint
import com.lightningstudio.watchrss.phone.cloud.PhoneCloudStateStore
import com.lightningstudio.watchrss.phone.testing.MemoryPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class CloudLocalChangesTest {
    private val context:Context=RuntimeEnvironment.getApplication()
    private val dbs=mutableListOf<PhoneCompanionDatabase>()
    private val names=mutableListOf<String>()
    private fun open(name:String?=null,callback:Boolean=true)=
        (if(name==null)Room.inMemoryDatabaseBuilder(context,PhoneCompanionDatabase::class.java)
        else Room.databaseBuilder(context,PhoneCompanionDatabase::class.java,name))
            .addMigrations(PhoneCompanionDatabase.MIGRATION_18_19)
            .apply{if(callback)addCallback(PhoneCompanionDatabase.ARTICLE_PROVENANCE_CALLBACK)}
            .allowMainThreadQueries().build().also{dbs+=it}
    private fun name()="fix5-cloud-counter-${System.nanoTime()}".also{names+=it}
    private fun checkpoint(changes:CloudLocalChanges):CloudCycleCheckpoint {
        val state=PhoneCloudStateStore(MemoryPreferences()).also{it.bindScope("backend","user")}
        val cp=CloudCycleCheckpoint(changes.schemaIdentity(),emptyMap(),changes.sequence(),"0",1)
        assertTrue(state.completeCycle(cp,state.admitCycle()))
        return requireNotNull(state.cycleCheckpoint())
    }
    @After fun close(){dbs.forEach{if(it.isOpen)it.close()};names.forEach(context::deleteDatabase)}

    @Test fun historyDeletionRoomClearAndReopenCannotRepeatAcknowledgedSequence()=runBlocking<Unit> {
        val name=name();val db=open(name);val changes=CloudLocalChanges(db)
        db.appMetaDao().set(AppMetaEntity("counter","acknowledged"))
        com.lightningstudio.watchrss.phone.data.ble.BleDomainStore(db,context.filesDir).initialize()
        val cp=checkpoint(changes)
        db.appMetaDao().set(AppMetaEntity("counter","unpublished"))
        val dirty=changes.sequence();assertTrue(dirty>cp.localMaxSeq)
        db.syncChangeLogDao().deleteAll();db.syncPeerStateDao().deleteAll()
        assertEquals(dirty,changes.sequence())
        // Real BLE projection/encoder upgrades clear receipts and reset domain revisions.
        com.lightningstudio.watchrss.phone.data.ble.BleDomainStore(db,context.filesDir,
            encoderVersions=com.lightningstudio.watchrss.phone.data.ble.BleDomainStore.DOMAINS.associateWith{99}).initialize()
        com.lightningstudio.watchrss.phone.data.book.BookReadingJournal(db).clear()
        assertTrue(changes.sequence()>=dirty)
        withContext(Dispatchers.IO){db.clearAllTables()}
        val cleared=changes.sequence();assertTrue(cleared>=dirty)
        db.close()
        val reopened=open(name);val after=CloudLocalChanges(reopened)
        assertEquals(cleared,after.sequence())
        reopened.appMetaDao().set(AppMetaEntity("counter","next"))
        assertTrue(after.sequence()>cleared)
        assertTrue(after.sequence()>cp.localMaxSeq)
    }

    @Test fun compactCounterHasAutoincrementAndRollbackCannotAcknowledgeUncommittedChanges()=runBlocking<Unit> {
        val db=open();val changes=CloudLocalChanges(db);val cp=checkpoint(changes)
        repeat(500){db.appMetaDao().set(AppMetaEntity("one","value-$it"))}
        val dirty=changes.sequence();assertTrue(dirty>=cp.localMaxSeq+500)
        val sql=db.openHelper.writableDatabase
        sql.query("SELECT COUNT(*),MAX(seq) FROM cloud_local_change_counter").use{assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0));assertEquals(dirty,it.getLong(1))}
        try { db.withTransaction {db.appMetaDao().set(AppMetaEntity("one","rolled back"));error("rollback")} } catch(_:IllegalStateException){}
        assertEquals(dirty,changes.sequence())
        // Even pruning the last payload row cannot erase SQLite's allocated high-water mark.
        sql.execSQL("DELETE FROM cloud_local_change_counter")
        assertEquals(dirty,changes.sequence())
        db.appMetaDao().set(AppMetaEntity("one","after prune"))
        assertTrue(changes.sequence()>dirty)
    }

    @Test fun legacyUpgradeStartsAboveDeletedHistoryAndReinstallationDoesNotResetIt()=runBlocking<Unit> {
        val name=name();val db=open(name,callback=false)
        db.syncChangeLogDao().insert(SyncChangeLogEntity(900,"article","old",1,"phone","old",1))
        db.syncChangeLogDao().deleteAll()
        val sql=db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER cloud_local_change_app_meta_insert AFTER INSERT ON app_meta BEGIN INSERT INTO sync_change_log(kind,entityId,changedAt,originDeviceId,reason,createdAt) VALUES('cloud-change','app_meta',0,'','cloud-local-change-v1',0); END")
        val changes=CloudLocalChanges(db);assertTrue(changes.sequence()>900)
        val cp=checkpoint(changes)
        db.appMetaDao().set(AppMetaEntity("after-upgrade","changed"))
        db.syncChangeLogDao().deleteAll()
        assertTrue(changes.sequence()>cp.localMaxSeq)
        assertEquals(0,db.syncChangeLogDao().maxSeq())
        val dirty=changes.sequence();installCloudLocalChanges(sql)
        assertEquals(dirty,changes.sequence())
        db.close();val reopened=open(name)
        assertEquals(dirty,CloudLocalChanges(reopened).sequence())
    }

    @Test fun actual18To19MigrationPreservesExistingCloudHighWater()=runBlocking<Unit> {
        val name=name();val db=open(name);val changes=CloudLocalChanges(db)
        db.appMetaDao().set(AppMetaEntity("before-migration","changed"))
        val cp=checkpoint(changes)
        val sql=db.openHelper.writableDatabase
        for(table in listOf("article_register_versions","article_register_originals","article_register_provenance"))sql.execSQL("DROP TABLE $table")
        sql.version=18;db.close()
        val migrated=open(name);val after=CloudLocalChanges(migrated)
        assertTrue(after.sequence()>=cp.localMaxSeq)
        migrated.appMetaDao().set(AppMetaEntity("after-migration","changed"))
        migrated.syncChangeLogDao().deleteAll()
        assertTrue(after.sequence()>cp.localMaxSeq)
        assertEquals(19,migrated.openHelper.readableDatabase.version)
    }

    @Test fun cloudFlowObservesChangeEvenWhenSameTransactionDeletesBluetoothHistory()=runBlocking<Unit> {
        val db=open();val changes=CloudLocalChanges(db);val channel=Channel<Long>(Channel.UNLIMITED)
        val job=launch(Dispatchers.Default){changes.observeSequence().collect{channel.send(it)}}
        try {
            val initial=withTimeout(5000){channel.receive()}
            db.withTransaction {db.appMetaDao().set(AppMetaEntity("flow","new"));db.syncChangeLogDao().deleteAll()}
            val changed=withTimeout(5000){channel.receive()}
            assertTrue(changed>initial);assertEquals(0,db.syncChangeLogDao().maxSeq())
        } finally {job.cancelAndJoin();channel.close()}
    }
    @Test fun exhaustedAutoincrementFailsTheBusinessWriteInsteadOfRepeatingAValue()=runBlocking<Unit> {
        val db=open();val changes=CloudLocalChanges(db)
        changes.sequence()
        db.appMetaDao().set(AppMetaEntity("overflow","original"))
        db.openHelper.writableDatabase.execSQL("INSERT INTO cloud_local_change_counter(seq) VALUES(?)",arrayOf(Long.MAX_VALUE))
        val cp=checkpoint(changes)
        assertEquals(Long.MAX_VALUE,cp.localMaxSeq)
        assertTrue(runCatching{db.appMetaDao().set(AppMetaEntity("overflow","unwitnessed"))}.isFailure)
        assertEquals("original",db.appMetaDao().getString("overflow"))
        assertEquals(Long.MAX_VALUE,changes.sequence())
    }

}
