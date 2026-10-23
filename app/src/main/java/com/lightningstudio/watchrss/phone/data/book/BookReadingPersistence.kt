package com.lightningstudio.watchrss.phone.data.book

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName="book_reading_meta")
data class BookReadingMetaEntity(@PrimaryKey val id:Int=1,val epoch:String,val seq:Long=0,val root:String="",val count:Long=0,val initialized:Boolean=false)
@Entity(tableName="book_reading_versions",primaryKeys=["bookId","chapterIndex"])
data class BookReadingVersionEntity(val bookId:String,val chapterIndex:Int,val seq:Long,val hash:String)
@Entity(tableName="book_reading_journal",indices=[Index(value=["bookId","chapterIndex","seq"])])
data class BookReadingJournalEntity(@PrimaryKey val seq:Long,val bookId:String,val chapterIndex:Int,val stateJson:String)
@Entity(tableName="book_reading_roots")
data class BookReadingRootEntity(@PrimaryKey val bookId:String,val root:String,val count:Long)
@Entity(tableName="book_reading_hash_dirty")
data class BookReadingHashDirtyEntity(@PrimaryKey val bookId:String)
@Entity(tableName="book_reading_dirty")
data class BookReadingDirtyEntity(@PrimaryKey val articleId:String,val revision:Long)
@Entity(tableName="book_reading_peers")
data class BookReadingPeerEntity(@PrimaryKey val peer:String,val localEpoch:String,val localSeq:Long,val remoteEpoch:String,val remoteSeq:Long,val root:String)
@Entity(tableName="book_capture_meta")
data class BookCaptureMetaEntity(@PrimaryKey val id:Int=1,val registrationRevision:Long=0,val checkedRevision:Long=-1,val sourceStateJson:String="")

@Dao interface BookReadingDao {
    @Query("SELECT * FROM book_reading_meta WHERE id=1") suspend fun meta():BookReadingMetaEntity?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putMeta(row:BookReadingMetaEntity)
    @Query("SELECT * FROM book_reading_versions WHERE bookId=:id AND chapterIndex=:index") suspend fun version(id:String,index:Int):BookReadingVersionEntity?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putVersion(row:BookReadingVersionEntity)
    @Insert suspend fun append(row:BookReadingJournalEntity)
    @Query("""SELECT * FROM book_reading_journal WHERE seq IN
        (SELECT MAX(seq) FROM book_reading_journal WHERE seq>:from AND seq<=:to GROUP BY bookId,chapterIndex) ORDER BY seq""")
    suspend fun entries(from:Long,to:Long):List<BookReadingJournalEntity>
    @Query("SELECT * FROM book_reading_versions WHERE bookId=:id ORDER BY chapterIndex") suspend fun leaves(id:String):List<BookReadingVersionEntity>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putRoot(row:BookReadingRootEntity)
    @Query("SELECT * FROM book_reading_roots ORDER BY bookId") suspend fun roots():List<BookReadingRootEntity>
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun dirtyHash(row:BookReadingHashDirtyEntity)
    @Query("SELECT bookId FROM book_reading_hash_dirty ORDER BY bookId") suspend fun dirtyHashes():List<String>
    @Query("DELETE FROM book_reading_hash_dirty WHERE bookId=:id") suspend fun cleanHash(id:String)
    @Query("SELECT * FROM book_reading_dirty ORDER BY articleId LIMIT 100") suspend fun dirty():List<BookReadingDirtyEntity>
    @Query("DELETE FROM book_reading_dirty WHERE articleId=:id AND revision=:revision") suspend fun clean(id:String,revision:Long)
    @Query("DELETE FROM book_reading_dirty WHERE articleId=:id") suspend fun cleanProjection(id:String)
    @Query("SELECT * FROM book_reading_peers WHERE peer=:peer") suspend fun peer(peer:String):BookReadingPeerEntity?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putPeer(row:BookReadingPeerEntity)
    @Query("SELECT * FROM book_capture_meta WHERE id=1") suspend fun registration():BookCaptureMetaEntity?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putRegistration(row:BookCaptureMetaEntity)
}

/** Triggers cover every DAO writer, including SQL-only read/unread and list updates. */
object BookReadingSchema {
    fun create(db:SupportSQLiteDatabase) {
        listOf(
            "CREATE TABLE IF NOT EXISTS book_reading_meta(id INTEGER NOT NULL PRIMARY KEY,epoch TEXT NOT NULL,seq INTEGER NOT NULL,root TEXT NOT NULL,count INTEGER NOT NULL,initialized INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS book_reading_versions(bookId TEXT NOT NULL,chapterIndex INTEGER NOT NULL,seq INTEGER NOT NULL,hash TEXT NOT NULL,PRIMARY KEY(bookId,chapterIndex))",
            "CREATE TABLE IF NOT EXISTS book_reading_journal(seq INTEGER NOT NULL PRIMARY KEY,bookId TEXT NOT NULL,chapterIndex INTEGER NOT NULL,stateJson TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS index_book_reading_journal_bookId_chapterIndex_seq ON book_reading_journal(bookId,chapterIndex,seq)",
            "CREATE TABLE IF NOT EXISTS book_reading_roots(bookId TEXT NOT NULL PRIMARY KEY,root TEXT NOT NULL,count INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS book_reading_hash_dirty(bookId TEXT NOT NULL PRIMARY KEY)",
            "CREATE TABLE IF NOT EXISTS book_reading_dirty(articleId TEXT NOT NULL PRIMARY KEY,revision INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS book_reading_peers(peer TEXT NOT NULL PRIMARY KEY,localEpoch TEXT NOT NULL,localSeq INTEGER NOT NULL,remoteEpoch TEXT NOT NULL,remoteSeq INTEGER NOT NULL,root TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS book_capture_meta(id INTEGER NOT NULL PRIMARY KEY,registrationRevision INTEGER NOT NULL,checkedRevision INTEGER NOT NULL,sourceStateJson TEXT NOT NULL)"
        ).forEach(db::execSQL)
        install(db)
    }
    fun install(db:SupportSQLiteDatabase) {
        val fields=listOf("sourceDeviceId","isRead","readingProgress","readingPositionBytes","readingPositionContentHash","readingPositionChangedAt",
            "favoriteSaved","favoriteChangedAt","favoriteSortOrder","watchLaterSaved","watchLaterChangedAt","watchLaterSortOrder","deleted","deletedAt")
        val mark="INSERT INTO book_reading_dirty(articleId,revision) VALUES(NEW.articleId,1) ON CONFLICT(articleId) DO UPDATE SET revision=revision+1;"
        val mapped="EXISTS(SELECT 1 FROM imported_book_chapters WHERE articleId=NEW.articleId)"
        db.execSQL("CREATE TRIGGER IF NOT EXISTS book_reading_article_insert AFTER INSERT ON phone_articles WHEN $mapped BEGIN $mark END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS book_reading_article_update AFTER UPDATE ON phone_articles WHEN $mapped AND (${fields.joinToString(" OR "){"NEW.$it IS NOT OLD.$it"}}) BEGIN $mark END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS book_reading_mapping_insert AFTER INSERT ON imported_book_chapters BEGIN $mark END")
        val imported="lower(trim(NEW.url)) LIKE 'https://watchrss.local/import-%'"
        val revision="INSERT INTO book_capture_meta(id,registrationRevision,checkedRevision,sourceStateJson) VALUES(1,1,-1,'') ON CONFLICT(id) DO UPDATE SET registrationRevision=registrationRevision+1;"
        db.execSQL("CREATE TRIGGER IF NOT EXISTS book_registration_insert AFTER INSERT ON phone_articles WHEN $imported AND NOT $mapped BEGIN $revision END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS book_registration_update AFTER UPDATE ON phone_articles WHEN $imported AND NOT $mapped AND (NEW.url IS NOT OLD.url OR NEW.title IS NOT OLD.title OR NEW.contentHash IS NOT OLD.contentHash OR NEW.contentText IS NOT OLD.contentText OR NEW.contentHtml IS NOT OLD.contentHtml OR NEW.rssSourceUrl IS NOT OLD.rssSourceUrl) BEGIN $revision END")
    }
}
