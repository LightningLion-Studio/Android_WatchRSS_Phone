package com.lightningstudio.watchrss.phone.data.book

import androidx.room.withTransaction
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import org.json.JSONObject
import java.security.SecureRandom

/** Called inside the same Room transaction as capture/merge/projection. History freezes window bytes. */
internal class BookReadingJournal(private val db:PhoneCompanionDatabase) {
    private val dao get()=db.bookReadingDao()
    private var installed=false
    var leafHashes=0L; private set
    var bookHashes=0L; private set
    var rootHashes=0L; private set
    fun install() {
        if (!installed) { BookReadingSchema.install(db.openHelper.writableDatabase);installed=true }
    }
    suspend fun initialize() {
        install()
        var meta=dao.meta()
        if(meta==null) {
            meta=BookReadingMetaEntity(epoch=ByteArray(16).also{SecureRandom().nextBytes(it)}.joinToString(""){"%02x".format(it)})
            dao.putMeta(meta)
        }
        if(!meta.initialized) {
            for(row in db.bookDao().reading())record(JSONObject(row.stateJson))
            db.openHelper.writableDatabase.execSQL("INSERT OR IGNORE INTO book_reading_dirty SELECT articleId,1 FROM imported_book_chapters")
            dao.putMeta(requireNotNull(dao.meta()).copy(initialized=true))
        }
        if(dao.registration()==null)dao.putRegistration(BookCaptureMetaEntity())
    }
    suspend fun record(value:JSONObject):Boolean {
        val normalized=BookSync.mergeReading(null,value)
        val id=normalized.getString("bookId");val index=normalized.getInt("chapterIndex")
        val canonical=BookSync.canonical(normalized)
        val previous=db.bookDao().reading(id,index)
        // Identical semantic JSON needs no hash at all.
        if(previous?.stateJson==canonical && dao.version(id,index)!=null)return false
        val leaf=BookReadingHash.leaf(normalized);leafHashes++
        val old=dao.version(id,index)
        if(old?.hash==leaf) {
            if(previous?.stateJson!=canonical)db.bookDao().putReading(BookReadingEntity(id,index,canonical))
            return false
        }
        val meta=requireNotNull(dao.meta());check(meta.seq<BookReadingHash.MAX){"阅读增量日志已满"}
        val seq=meta.seq+1
        db.bookDao().putReading(BookReadingEntity(id,index,canonical))
        dao.putVersion(BookReadingVersionEntity(id,index,seq,leaf))
        dao.append(BookReadingJournalEntity(seq,id,index,canonical))
        dao.dirtyHash(BookReadingHashDirtyEntity(id))
        dao.putMeta(meta.copy(seq=seq,root=""))
        return true
    }
    suspend fun summary():BookReadingSummary {
        var meta=requireNotNull(dao.meta())
        if(meta.root.isEmpty()) {
            for(id in dao.dirtyHashes()) {
                val leaves=dao.leaves(id)
                dao.putRoot(BookReadingRootEntity(id,BookReadingHash.book(id,leaves.map{it.chapterIndex to it.hash}),leaves.size.toLong()));bookHashes++
                dao.cleanHash(id)
            }
            val roots=dao.roots();val root=BookReadingHash.root(roots);rootHashes++
            meta=meta.copy(root=root,count=roots.sumOf{it.count});dao.putMeta(meta)
        }
        return BookReadingSummary(meta.epoch,meta.seq,meta.root,meta.count)
    }
    suspend fun entries(from:Long,to:Long):List<BookReadingEntry> {
        require(from in 0..to && to<=requireNotNull(dao.meta()).seq)
        return dao.entries(from,to).map{BookReadingEntry(it.seq,JSONObject(it.stateJson))}
    }
    suspend fun acknowledge(peer:String,local:BookReadingSummary,remote:BookReadingSummary,root:String) {
        require(peer.isNotBlank() && root.matches(Regex("[0-9a-f]{64}")))
        check(requireNotNull(dao.meta()).epoch==local.epoch){"阅读增量命名空间已改变，请重试"}
        val old=dao.peer(peer)
        dao.putPeer(BookReadingPeerEntity(peer,local.epoch,
            if(old?.localEpoch==local.epoch)maxOf(old.localSeq,local.seq)else local.seq,
            remote.epoch,if(old?.remoteEpoch==remote.epoch)maxOf(old.remoteSeq,remote.seq)else remote.seq,root))
    }
    suspend fun clear() {
        for(table in listOf("book_reading_meta","book_reading_versions","book_reading_journal","book_reading_roots","book_reading_hash_dirty","book_reading_dirty","book_reading_peers","book_capture_meta"))
            db.openHelper.writableDatabase.execSQL("DELETE FROM $table")
    }
}
