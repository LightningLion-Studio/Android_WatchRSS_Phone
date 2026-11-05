package com.lightningstudio.watchrss.phone.syncv2

import androidx.room.Room
import androidx.room.RoomDatabase
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.data.db.SyncPeerStateEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

/** Deliberately kills its isolated Gradle test worker. Never runs without the explicit harness env. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2CrashChildTest {
    @Test fun haltInsideProductTransaction()=runBlocking<Unit> {
        val stage=System.getenv("WRSS_P1_CRASH_AT");assumeTrue(!stage.isNullOrEmpty())
        val root=File(requireNotNull(System.getenv("WRSS_P1_CRASH_DIR"))).canonicalFile
        require(root.name.startsWith("wrss-p1-crash-"))
        root.mkdirs()
        val mode=if(System.getenv("WRSS_P1_CRASH_JOURNAL")=="DELETE")RoomDatabase.JournalMode.TRUNCATE else RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING
        val db=Room.databaseBuilder(RuntimeEnvironment.getApplication(),PhoneCompanionDatabase::class.java,File(root,"phone.db").absolutePath).setJournalMode(mode).allowMainThreadQueries().build()
        fun row(id:String)=PhoneArticleEntity(id,"legacy","https://example.invalid/$id","synthetic","site","excerpt",null,"body",null,"hash",1,1,true,1,1,null,null,true,1,1,false,0,0,false,0)
        val a=SyncV2Projection(db.openHelper.writableDatabase,SyncV2Fixtures.identity(),SyncV2Fixtures.localKey,true)
        if(System.getenv("WRSS_P1_CRASH_JOURNAL")=="DELETE")db.openHelper.writableDatabase.query("PRAGMA journal_mode=DELETE").close()
        a.install();db.phoneArticleDao().upsert(row("a-prefix"));db.phoneArticleDao().upsert(row("b-suffix"))
        db.syncPeerStateDao().upsert(SyncPeerStateEntity("peer",8,7,0,14,0))
        a.scanPage("phone_articles",limit=1) // a real persisted prefix must survive every later crash.
        File(root,"ready.json").writeText(JSONObject().put("prefix",1).put("oldCursor",7).put("journal",System.getenv("WRSS_P1_CRASH_JOURNAL")).toString())
        val crashing=SyncV2Projection(db.openHelper.writableDatabase,SyncV2Fixtures.identity(),SyncV2Fixtures.localKey,true){point->
            if(point==stage)Runtime.getRuntime().halt(73)
        }
        if(stage=="after-tail-entry")crashing.catchUp(1)else crashing.scanPage("phone_articles",limit=1)
        error("crash boundary did not execute")
    }
}
