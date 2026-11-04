package com.lightningstudio.watchrss.phone.data.book

import androidx.room.withTransaction
import com.lightningstudio.watchrss.phone.data.db.*
import com.lightningstudio.watchrss.phone.data.local.ArticleContentStore
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import com.lightningstudio.watchrss.phone.cloud.CloudSnapshotCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.*
import java.io.*
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A complete peer item was received, but its package is invalid. Never wrap IO/owner/cancellation failures. */
internal class InvalidBookPackageException : IllegalArgumentException("book_package_invalid")
internal inline fun <T> validateBookPackage(block:()->T):T = try {block()}
    catch(_:org.json.JSONException){throw InvalidBookPackageException()}
    catch(_:IllegalArgumentException){throw InvalidBookPackageException()}

/** Immutable original packages survive chapter deletion. State/reading are independent small rows. */
class ImportedBookStore(private val db:PhoneCompanionDatabase,private val directory:File,
    private val contentStore:ArticleContentStore?,private val deviceId:String) {
    private val registrationLock=Mutex()
    private val dao get()=db.bookDao()
    private val articles get()=db.phoneArticleDao()
    private val readingJournal=BookReadingJournal(db)
    internal val readingHashCounts get()=listOf(readingJournal.leafHashes,readingJournal.bookHashes,readingJournal.rootHashes)
    var onDiagnostic:(String)->Unit={}
    private fun diagnostic(message:String){runCatching{onDiagnostic(message)}}
    private class IncompleteOriginal(message:String):IllegalArgumentException(message)
    val migrationWarnings=linkedSetOf<String>()
    private fun file(id:String)=File(directory,"$id.jsonl")
    // Failed registration/verification is not a change to the immutable package domain.
    private fun temporary(prefix:String):File {
        val staging=File(directory.absoluteFile.parentFile,".${directory.name}-staging").apply { mkdirs() }
        return File.createTempFile(prefix,".part",staging)
    }
    private fun hydrate(row:PhoneArticleEntity):PhoneArticleEntity {
        fun load(value:String):String=if(com.lightningstudio.watchrss.phone.data.local.isArticleContentMarker(value) || contentStore?.isMarker(value)==true)contentStore?.loadText(value)?:error("导入书正文暂不可用") else value
        return row.copy(contentText=load(row.contentText),contentHtml=row.contentHtml?.let(::load))
    }
    companion object {
        fun isBookChapter(row:PhoneArticleEntity)=ImportedContentIds.isImportedContentUrl(row.url)
        fun group(row:PhoneArticleEntity)=if(ImportedContentIds.isImportedTextArticleUrl(row.url))row.url else row.rssSourceUrl?.takeIf {ImportedContentIds.isImportedContentUrl(it)}?:row.url.substringBefore("/chapter/")
        fun ordinal(row:PhoneArticleEntity)=Regex("/chapter/(\\d+)").find(row.url)?.groupValues?.get(1)?.toIntOrNull()?:0
    }
    suspend fun ensureRegistered()=withContext(Dispatchers.IO) { registrationLock.withLock {
        db.withTransaction {readingJournal.initialize()}
        val revision=requireNotNull(db.bookReadingDao().registration())
        if(revision.checkedRevision!=revision.registrationRevision) {
            val started=System.nanoTime()
            val rows=articles.unregisteredBookMetadata().filter(::isBookChapter)
            val existing=dao.all().map{JSONObject(it.stateJson).optString("sourceUrl")}.toSet()
            var transientFailure=false
            for((source,group) in rows.groupBy(::group)) {
                if(source in existing)continue
                try { register(source,group.sortedWith(compareBy(::ordinal,{it.url})));migrationWarnings.remove(source) }
                catch(e:kotlinx.coroutines.CancellationException){throw e}
                catch(_:IncompleteOriginal){migrationWarnings+=source}
                catch(_:Exception){migrationWarnings+=source;transientFailure=true}
            }
            if(!transientFailure)db.withTransaction {
                val current=requireNotNull(db.bookReadingDao().registration())
                db.bookReadingDao().putRegistration(current.copy(checkedRevision=revision.registrationRevision))
            }
            diagnostic("registration:unmapped=${rows.size}:ms=${(System.nanoTime()-started)/1_000_000}")
        }
        for(book in dao.all().filter { !available(it) && !JSONObject(it.stateJson).getBoolean("deleted") })try {
            val mappings=dao.chapters(book.bookId);val state=JSONObject(book.stateJson)
            if(mappings.size!=state.getInt("chapterCount"))continue
            val original=mappings.map {requireNotNull(articles.getById(it.articleId))}
            register(state.optString("sourceUrl"),original,book.bookId);migrationWarnings.remove(book.bookId)
        }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){migrationWarnings+=book.bookId}
        captureLocalChanges()
    }}
    private suspend fun register(source:String,metadata:List<PhoneArticleEntity>,expectedId:String?=null) {
        require(metadata.isNotEmpty())
        val registrationRevision=db.bookReadingDao().registration()?.registrationRevision
        if(!ImportedContentIds.isImportedTextArticleUrl(metadata.first().url)) {
            val ordinals=metadata.map(::ordinal)
            if(!(ordinals.first() in 0..1 && ordinals.zipWithNext().all{(a,b)->b==a+1}))throw IncompleteOriginal("存量书原始目录不完整")
        }
        val sourceRow=db.phoneRssSourceDao().getByUrl(metadata.first().rssSourceUrl.orEmpty())
        val title=sourceRow?.takeUnless {it.url==ImportedContentIds.ROOT_SOURCE_URL}?.title?:metadata.first().title
        val temporary=temporary("book-")
        val hashes=mutableListOf<String>()
        try {
            temporary.bufferedWriter(Charsets.UTF_8).use {out->
                out.append(BookSync.canonical(JSONObject().put("format","watchrss-book").put("version",1).put("title",title).put("chapterCount",metadata.size).put("sourceUrl",source).put("kind",if(source.contains("epub"))"epub" else "txt"))).append('\n')
                for((index,item) in metadata.withIndex()) {
                    val row=hydrate(requireNotNull(articles.getById(item.articleId)))
                    if(!BodyAuthority.accepts(row))throw IncompleteOriginal("导入书缺少权威正文，等待补回正文")
                    hashes+=BookSync.chapterHash(index+1,row.title,row.contentText)
                    out.append(BookSync.canonical(JSONObject().put("chapterIndex",index+1).put("title",BookSync.normalized(row.title))
                        .put("contentText",BookSync.normalized(row.contentText)).put("contentHtml",row.contentHtml?.let(BookSync::normalized)?:JSONObject.NULL).put("url",row.url))).append('\n')
                }
            }
            val fingerprint=BookSync.fingerprint(hashes);val id=BookSync.id(fingerprint)
            if(expectedId!=null && id!=expectedId)throw IncompleteOriginal("存量书原始目录不完整")
            val clock=metadata.maxOf{maxOf(it.updatedAt,it.deletedAt)}
            val state=JSONObject().put("bookId",id).put("fingerprint",fingerprint).put("title",title).put("chapterCount",metadata.size)
                .put("deletedChapters",JSONArray(metadata.mapIndexedNotNull{index,row->(index+1).takeIf{row.deleted}}))
                .put("deletionClock",metadata.maxOf{it.deletedAt}).put("deleted",sourceRow?.deleted==true)
                .put("deletedAt",if(sourceRow?.deleted==true)sourceRow.deletedAt else 0)
                .put("updatedAt",clock).put("sourceDeviceId",deviceId).put("sourceUrl",source).put("kind",if(source.contains("epub"))"epub" else "txt")
            check(db.bookReadingDao().registration()?.registrationRevision==registrationRevision){"书籍在登记期间发生变化，请重试"}
            val existing=dao.get(id)
            if(existing==null) {
                directory.mkdirs();com.lightningstudio.watchrss.phone.data.local.CloudExportFileWitness.changing(file(id)){Files.move(temporary.toPath(),file(id).toPath(),StandardCopyOption.REPLACE_EXISTING)}
                db.withTransaction {dao.put(BookEntity(id,BookSync.canonical(state),sha(file(id)),file(id).length()));dao.putChapters(metadata.mapIndexed {i,row->BookChapterEntity(id,i+1,row.articleId)})}
            } else {
                // Same immutable sequence imported under a new filename: one identity, merge deletion state.
                if(!available(existing)){directory.mkdirs();com.lightningstudio.watchrss.phone.data.local.CloudExportFileWitness.changing(file(id)){Files.move(temporary.toPath(),file(id).toPath(),StandardCopyOption.REPLACE_EXISTING)};dao.put(existing.copy(packageSha256=sha(file(id)),packageBytes=file(id).length()))}
                merge(state)
                val mappings=dao.chapters(id)
                if(mappings.isEmpty())dao.putChapters(metadata.mapIndexed{i,row->BookChapterEntity(id,i+1,row.articleId)})
                else for((index,row) in metadata.withIndex()) {
                    val mapping=mappings.firstOrNull{it.chapterIndex==index+1}?:continue
                    if(mapping.articleId!=row.articleId) {
                        if(interacted(row))mergeReading(readingValue(mapping,row))
                        db.appMetaDao().set(AppMetaEntity("book_identity_alias:"+row.articleId,JSONObject().put("bookId",id).put("chapterIndex",index+1).toString()))
                        articles.deleteByIds(listOf(row.articleId))
                    }
                }
                if(source!=ImportedContentIds.ROOT_SOURCE_URL && articles.getByRssSourceUrl(source).isEmpty())db.phoneRssSourceDao().deleteByUrls(listOf(source))
            }
        } finally {temporary.delete()}
    }
    suspend fun manifests():List<JSONObject> {ensureRegistered();return dao.all().map{JSONObject(it.stateJson).put("contentAvailable",available(it))}}
    suspend fun states():List<JSONObject> {ensureRegistered();return dao.all().map{JSONObject(it.stateJson)}}
    /** Caller has already captured the R46 dirty journal for this window. Do not prepare it twice. */
    internal suspend fun bluetoothExclusions():Pair<Set<String>,Set<String>> =
        dao.chapters().mapTo(hashSetOf()){it.articleId} to
            (dao.all().map{JSONObject(it.stateJson).optString("sourceUrl")}+dao.sourceUrls()).filter{it.isNotBlank()}.toHashSet()
    suspend fun chapterIds():Set<String> {ensureRegistered();return dao.chapters().mapTo(hashSetOf()){it.articleId}}
    suspend fun bookForArticle(id:String,url:String?=null):BookChapterEntity? {
        dao.chapterByArticle(id)?.let{return it}
        db.appMetaDao().getString("book_identity_alias:"+id)?.let {raw->
            val alias=JSONObject(raw);dao.chapterAt(alias.getString("bookId"),alias.getInt("chapterIndex"))?.let{return it}
        }
        if(url!=null)dao.chapterByUrl(url)?.let{return it}
        return null
    }
    suspend fun canonicalArticle(row:PhoneArticleEntity)=if(!isBookChapter(row))row else bookForArticle(row.articleId,row.url)?.let{row.copy(articleId=it.articleId)}?:row
    private fun interacted(row:PhoneArticleEntity)=row.readingPositionChangedAt>0||row.isRead||row.readingProgress>0||row.favoriteSaved||row.favoriteChangedAt>0||row.watchLaterSaved||row.watchLaterChangedAt>0
    suspend fun missingBookIds():Set<String> {ensureRegistered();return dao.all().filter{!JSONObject(it.stateJson).getBoolean("deleted")&&!available(it)}.mapTo(hashSetOf()){it.bookId}}
    suspend fun sourceUrls():Set<String> {
        ensureRegistered()
        return (dao.all().map{JSONObject(it.stateJson).optString("sourceUrl")}+dao.sourceUrls()).filter{it.isNotBlank()}.toHashSet()
    }
    suspend fun availableBooks():List<Pair<JSONObject,File>> {ensureRegistered();return dao.all().filter{available(it)&&!JSONObject(it.stateJson).getBoolean("deleted")}.map{JSONObject(it.stateJson) to file(it.bookId)}}
    private fun available(row:BookEntity)=row.packageSha256.isNotBlank()&&file(row.bookId).isFile&&file(row.bookId).length()==row.packageBytes
    suspend fun packageFile(id:String):File?=dao.get(id)?.takeIf(::available)?.let{file(id)}
    suspend fun packageHash(id:String)=requireNotNull(dao.get(id)).packageSha256
    suspend fun merge(remote:JSONObject,checkOwner:()->Unit={},forceProjection:Boolean=false):Int=db.withTransaction {
        checkOwner()
        val id=remote.getString("bookId");var previous=dao.get(id)
        val before=previous?.let{JSONObject(it.stateJson).also(BookSync::validate)}
        var state=validateBookPackage{BookSync.merge(before,remote)};var json=BookSync.canonical(state)
        if(previous?.stateJson==json && !forceProjection)return@withTransaction 0
        readingJournal.initialize()
        captureDirtyReading()
        // Dirty capture can add a local deletion. Rebase before writing remote metadata.
        previous=dao.get(id)
        val captured=previous?.let{JSONObject(it.stateJson).also(BookSync::validate)}
        state=validateBookPackage{BookSync.merge(captured,remote)};json=BookSync.canonical(state)
        var changed=0
        if(previous?.stateJson!=json){
            val before=previous?.stateJson?.let(::JSONObject)
            val fields=(state.keys().asSequence().toSet()+before?.keys()?.asSequence().orEmpty().toSet()).filter {
                com.lightningstudio.watchrss.phone.cloud.PhoneCloudRecordCodec.canonical(before?.opt(it))!=com.lightningstudio.watchrss.phone.cloud.PhoneCloudRecordCodec.canonical(state.opt(it))
            }
            diagnostic("book-merge:id=${id.take(8)}:fields=${fields.sorted().joinToString(",")}")
            dao.put(previous?.copy(stateJson=json)?:BookEntity(id,json));changed++
        }
        changed+applyDeletions(state)
    }
    private suspend fun applyDeletions(state:JSONObject):Int {
        var changed=0;val deleted=BookSync.deletions(state);val all=state.getBoolean("deleted")
        val id=state.getString("bookId")
        val affected=if(all)dao.chapters(id)else deleted.toList().chunked(100).flatMap{dao.chapterBatch(id,it)}
        val metadata=affected.chunked(100).flatMap{articles.bookMetadata(it.map{m->m.articleId})}.associateBy{it.articleId}
        val readings=if(all)dao.readingForBook(id)else deleted.toList().chunked(100).flatMap{dao.readingBatch(id,it)}
        for(reading in readings) {
            val value=JSONObject(reading.stateJson)
            for(prefix in listOf("favorite","watchLater"))if(value.has("${prefix}Saved"))value.put("${prefix}Saved",false)
            if(readingJournal.record(value))changed++
        }
        for(mapping in affected) {
            val metadataRow=metadata[mapping.articleId]?:continue
            val clock=maxOf(state.getLong("deletionClock"),state.getLong("deletedAt"))
            if(!metadataRow.deleted || metadataRow.deletedAt<clock){
                val row=articles.getById(mapping.articleId)?:continue
                writeArticle(row.copy(deleted=true,deletedAt=maxOf(row.deletedAt,clock),favoriteSaved=false,watchLaterSaved=false,independentSaved=false));changed++}
        }
        val sourceUrl=state.optString("sourceUrl")
        val source=db.phoneRssSourceDao().getByUrl(sourceUrl) ?: dao.chapters(state.getString("bookId")).firstOrNull()?.let{articles.getById(it.articleId)?.rssSourceUrl}?.let{db.phoneRssSourceDao().getByUrl(it)}
        if(source!=null && source.url!=ImportedContentIds.ROOT_SOURCE_URL) {
            val next=if(state.getLong("updatedAt")>source.updatedAt)source.copy(title=state.getString("title"),updatedAt=state.getLong("updatedAt"),sourceDeviceId=state.getString("sourceDeviceId"))else source
            val deletedSource=if(all)next.copy(deleted=true,deletedAt=maxOf(source.deletedAt,state.getLong("deletedAt")))else next
            if(source!=deletedSource){db.phoneRssSourceDao().upsert(deletedSource);changed++}
        }
        return changed
    }
    /** Old clients may replay live chapter rows after the permanent book deletion. */
    suspend fun constrainDeletion(row:PhoneArticleEntity):PhoneArticleEntity {
        if(!isBookChapter(row))return row
        val mapping=bookForArticle(row.articleId,row.url)
        val state=mapping?.let{dao.get(it.bookId)}?.let{JSONObject(it.stateJson)} ?: dao.all().map{JSONObject(it.stateJson)}.firstOrNull{
            it.getBoolean("deleted") && (it.optString("sourceUrl")==row.url || it.optString("sourceUrl")==row.rssSourceUrl)
        } ?: return row
        val current=mapping?.let{articles.getById(it.articleId)}
        if(!state.getBoolean("deleted") && mapping?.chapterIndex !in BookSync.deletions(state) && current?.deleted!=true)return row
        return row.copy(deleted=true,deletedAt=maxOf(row.deletedAt,state.getLong("deletionClock"),state.getLong("deletedAt"),current?.deletedAt?:0),
            independentSaved=false,favoriteSaved=false,watchLaterSaved=false)
    }
    suspend fun markDeleted(id:String,clock:Long) {
        val previous=dao.get(id)?:return
        merge(JSONObject(previous.stateJson).put("deleted",true).put("deletedAt",clock).put("deletionClock",clock))
    }
    suspend fun captureLocalChanges()=db.withTransaction {
        readingJournal.initialize()
        val started=System.nanoTime()
        val changedRows=captureDirtyReading()
        val sources=db.phoneRssSourceDao().getAllForSync().associateBy{it.url}
        val sourceState=JSONArray(sources.values.sortedBy{it.url}.map {
            JSONArray(listOf(it.url,it.title,it.updatedAt,it.sourceDeviceId,it.deleted,it.deletedAt))
        }).toString()
        val registration=requireNotNull(db.bookReadingDao().registration())
        val changedSources=sourceState!=registration.sourceStateJson
        val aliases=if(changedSources)dao.mappedSources().groupBy{it.bookId}else emptyMap()
        val changedByBook=changedRows.groupBy{it.first.bookId}
        for(book in dao.all()) {
            val state=JSONObject(book.stateJson)
            val rows=changedByBook[book.bookId].orEmpty()
            val source=sources[state.optString("sourceUrl")] ?: rows.firstNotNullOfOrNull{sources[it.second.rssSourceUrl]}
                ?: aliases[book.bookId]?.firstNotNullOfOrNull{sources[it.sourceUrl]}
            val deleted=BookSync.deletions(state)+rows.filter{it.second.deleted}.map{it.first.chapterIndex}
            val clock=maxOf(state.getLong("deletionClock"),rows.maxOfOrNull{it.second.deletedAt}?:0L,source?.deletedAt?:0L)
            if(source!=null && source.url!=ImportedContentIds.ROOT_SOURCE_URL && source.updatedAt>state.getLong("updatedAt"))state.put("title",source.title).put("updatedAt",source.updatedAt).put("sourceDeviceId",source.sourceDeviceId)
            state.put("deletedChapters",JSONArray(deleted.sorted())).put("deletionClock",clock)
            if(source?.deleted==true)state.put("deleted",true).put("deletedAt",maxOf(clock,state.getLong("deletedAt")))
            if(BookSync.canonical(state)!=book.stateJson)merge(state)
        }
        if(changedSources)db.bookReadingDao().putRegistration(requireNotNull(db.bookReadingDao().registration()).copy(sourceStateJson=sourceState))
        diagnostic("capture:items=${changedRows.size}:ms=${(System.nanoTime()-started)/1_000_000}")
    }
    /** The transaction is the dirty revision witness: local writers cannot race projection cleanup. */
    private suspend fun captureDirtyReading():List<Pair<BookChapterEntity,PhoneArticleEntity>> {
        val changed=mutableListOf<Pair<BookChapterEntity,PhoneArticleEntity>>()
        while(true) {
            val dirty=db.bookReadingDao().dirty();if(dirty.isEmpty())break
            val ids=dirty.map{it.articleId}
            val mappings=dao.chaptersForArticles(ids).associateBy{it.articleId}
            val oldRows=dao.readingForArticles(ids).associateBy{it.bookId to it.chapterIndex}
            val rows=articles.bookMetadata(ids).associateBy{it.articleId}
            for(item in dirty) {
                val mapping=mappings[item.articleId];val row=rows[item.articleId]
                if(mapping!=null && row!=null) {
                    changed+=mapping to row
                    if(row.deleted)dao.get(mapping.bookId)?.let { book ->
                        val state=JSONObject(book.stateJson)
                        val next=JSONObject(state.toString()).put("deletedChapters",JSONArray((BookSync.deletions(state)+mapping.chapterIndex).sorted()))
                            .put("deletionClock",maxOf(state.getLong("deletionClock"),row.deletedAt))
                        val json=BookSync.canonical(next)
                        if(json!=book.stateJson)dao.put(book.copy(stateJson=json))
                    }
                    if(interacted(row)) {
                        val value=BookSync.mergeReading(null,readingValue(mapping,row))
                        val old=oldRows[mapping.bookId to mapping.chapterIndex]?.let{JSONObject(it.stateJson)}
                        if(old!=null && old.getBoolean("isRead")==value.getBoolean("isRead") && old.getDouble("readingProgress")==value.getDouble("readingProgress") && old.getLong("readingPositionBytes")==value.getLong("readingPositionBytes") && old.getString("readingPositionContentHash")==value.getString("readingPositionContentHash") && old.getLong("readingPositionChangedAt")==value.getLong("readingPositionChangedAt"))value.put("sourceDeviceId",old.getString("sourceDeviceId"))
                        for(prefix in listOf("favorite","watchLater")) {
                            if(old?.has("${prefix}Saved")!=true && !value.getBoolean("${prefix}Saved") && value.getLong("${prefix}ChangedAt")==0L && value.getLong("${prefix}SortOrder")==0L) {
                                listOf("Saved","ChangedAt","SortOrder","SourceDeviceId").forEach{value.remove(prefix+it)}
                                continue
                            }
                            val same=old!=null && old.has("${prefix}Saved") && old.getBoolean("${prefix}Saved")==value.getBoolean("${prefix}Saved") && old.getLong("${prefix}ChangedAt")==value.getLong("${prefix}ChangedAt") && old.getLong("${prefix}SortOrder")==value.getLong("${prefix}SortOrder")
                            value.put("${prefix}SourceDeviceId",if(same)old!!.optString("${prefix}SourceDeviceId",old.getString("sourceDeviceId"))else row.sourceDeviceId)
                        }
                        val merged=BookSync.mergeReading(old,value)
                        if(row.deleted)for(prefix in listOf("favorite","watchLater"))if(merged.has("${prefix}Saved"))merged.put("${prefix}Saved",false)
                        readingJournal.record(merged)
                    }
                }
                db.bookReadingDao().clean(item.articleId,item.revision)
            }
        }
        return changed
    }
    private fun readingValue(m:BookChapterEntity,row:PhoneArticleEntity)=JSONObject().put("bookId",m.bookId).put("chapterIndex",m.chapterIndex)
        .put("isRead",row.isRead).put("readingProgress",row.readingProgress.toDouble()).put("readingPositionBytes",row.readingPositionBytes)
        .put("readingPositionContentHash",row.readingPositionContentHash).put("readingPositionChangedAt",row.readingPositionChangedAt.takeIf{it>0}?:if(row.isRead||row.readingProgress>0)row.updatedAt else 0).put("sourceDeviceId",row.sourceDeviceId)
        .put("favoriteSaved",row.favoriteSaved).put("favoriteChangedAt",row.favoriteChangedAt).put("favoriteSortOrder",row.favoriteSortOrder)
        .put("watchLaterSaved",row.watchLaterSaved).put("watchLaterChangedAt",row.watchLaterChangedAt).put("watchLaterSortOrder",row.watchLaterSortOrder)
    suspend fun readingStates():List<JSONObject> {captureLocalChanges();return dao.reading().map{JSONObject(it.stateJson)}}
    suspend fun readingSummary():BookReadingSummary=db.withTransaction {
        readingJournal.initialize();captureDirtyReading();readingJournal.summary()
    }
    suspend fun readingEntries(from:Long,to:Long):List<BookReadingEntry> = db.withTransaction {
        readingJournal.initialize();readingJournal.entries(from,to)
    }
    suspend fun readingPeer(peer:String)=db.withTransaction {readingJournal.initialize();db.bookReadingDao().peer(peer)}
    suspend fun acknowledgeReading(peer:String,local:BookReadingSummary,remote:BookReadingSummary,root:String)=db.withTransaction {
        readingJournal.initialize();readingJournal.acknowledge(peer,local,remote,root)
    }
    suspend fun mergeReading(remote:JSONObject,checkOwner:()->Unit={}):Int=mergeReadingBatch(listOf(remote),checkOwner)
    suspend fun mergeReadingBatch(values:List<JSONObject>,checkOwner:()->Unit={}):Int=db.withTransaction {
        require(values.size<=100)
        checkOwner();values.forEach(BookSync::validateReading)
        readingJournal.initialize();captureDirtyReading()
        var changed=0
        val books=dao.all().associateBy{it.bookId}
        val mappings=mutableMapOf<Pair<String,Int>,BookChapterEntity>()
        val previous=mutableMapOf<Pair<String,Int>,JSONObject>()
        for((id,batch) in values.groupBy{it.getString("bookId")}) {
            val indices=batch.map{it.getInt("chapterIndex")}.distinct()
            dao.chapterBatch(id,indices).forEach{mappings[id to it.chapterIndex]=it}
            dao.readingBatch(id,indices).forEach{previous[id to it.chapterIndex]=JSONObject(it.stateJson)}
        }
        val metadata=articles.bookMetadata(mappings.values.map{it.articleId}).associateBy{it.articleId}.toMutableMap()
        for(remote in values) {
            checkOwner();val id=remote.getString("bookId");val index=remote.getInt("chapterIndex")
            books[id]?.let{require(index<=JSONObject(it.stateJson).getInt("chapterCount"))}
            val merged=BookSync.mergeReading(previous[id to index],remote)
            books[id]?.let { book ->
                val state=JSONObject(book.stateJson)
                if(state.getBoolean("deleted") || index in BookSync.deletions(state))
                    for(prefix in listOf("favorite","watchLater"))if(merged.has("${prefix}Saved"))merged.put("${prefix}Saved",false)
            }
            previous[id to index]=merged
            if(readingJournal.record(merged))changed++
            val m=mappings[id to index]
            val row=m?.let{metadata[it.articleId]}
            if(row!=null) {
                val next=row.copy(sourceDeviceId=merged.getString("sourceDeviceId"),isRead=merged.getBoolean("isRead"),readingProgress=merged.getDouble("readingProgress").toFloat(),readingPositionBytes=merged.getLong("readingPositionBytes"),readingPositionContentHash=merged.getString("readingPositionContentHash"),readingPositionChangedAt=merged.getLong("readingPositionChangedAt"),
                    favoriteSaved=!row.deleted && (if(merged.has("favoriteSaved"))merged.getBoolean("favoriteSaved")else row.favoriteSaved),
                    favoriteChangedAt=merged.optLong("favoriteChangedAt",row.favoriteChangedAt),favoriteSortOrder=merged.optLong("favoriteSortOrder",row.favoriteSortOrder),
                    watchLaterSaved=!row.deleted && (if(merged.has("watchLaterSaved"))merged.getBoolean("watchLaterSaved")else row.watchLaterSaved),
                    watchLaterChangedAt=merged.optLong("watchLaterChangedAt",row.watchLaterChangedAt),watchLaterSortOrder=merged.optLong("watchLaterSortOrder",row.watchLaterSortOrder))
                if(next!=row) {
                    articles.projectBookReading(next.articleId,next.sourceDeviceId,next.isRead,next.readingProgress,next.readingPositionBytes,next.readingPositionContentHash,next.readingPositionChangedAt,
                        next.favoriteSaved,next.favoriteChangedAt,next.favoriteSortOrder,next.watchLaterSaved,next.watchLaterChangedAt,next.watchLaterSortOrder)
                    metadata[next.articleId]=next
                    logArticle(next);db.bookReadingDao().cleanProjection(next.articleId);changed++
                }
            }
        }
        changed
    }
    /** Validate the entire package before any row becomes visible, then atomically publish file+mapping. */
    suspend fun receivePackage(id:String,input:File,checkOwner:()->Unit={}):Int=withContext(Dispatchers.IO) {
        checkOwner()
        var header:JSONObject?=null;val hashes=mutableListOf<String>()
        val fingerprint=validateBookPackage {
            require(id.matches(Regex("[0-9a-f]{64}")))
            input.bufferedReader(Charsets.UTF_8).use { r ->
                val h=JSONObject(requireNotNull(r.readLine()));header=h
                require(h.getString("format")=="watchrss-book" && BookSync.integer(h,"version")==1L)
                val count=BookSync.integer(h,"chapterCount");require(count in 1..100_000)
                require(h.get("title") is String && h.get("sourceUrl") is String && h.get("kind") in listOf("txt","epub"))
                val source=h.getString("sourceUrl");require(ImportedContentIds.isImportedContentUrl(source))
                val cli=ImportedContentIds.isCliSingleTextUrl(source.trim())
                if(cli)require(count==1L && h.getString("kind")=="txt")
                repeat(count.toInt()) { i ->
                    val c=JSONObject(requireNotNull(r.readLine()));require(BookSync.integer(c,"chapterIndex")==i+1L)
                    require(c.get("title") is String && c.get("contentText") is String && c.get("url") is String)
                    require(!c.has("contentHtml") || c.isNull("contentHtml") || c.get("contentHtml") is String)
                    val url=c.getString("url");require(ImportedContentIds.isImportedContentUrl(url))
                    if(ImportedContentIds.isCliSingleTextUrl(url.trim()))require(cli && source.equals(url,ignoreCase=true))
                    if(cli)require(ImportedContentIds.isCliSingleTextUrl(url.trim()))
                    hashes+=BookSync.chapterHash(i+1,c.getString("title"),c.getString("contentText"))
                }
                require(r.readLine()==null)
            }
            BookSync.fingerprint(hashes).also { require(BookSync.id(it)==id) }
        }
        val existing=dao.get(id);if(existing!=null)validateBookPackage {require(JSONObject(existing.stateJson).getString("fingerprint")==fingerprint && JSONObject(existing.stateJson).getInt("chapterCount")==hashes.size)}
        checkOwner()
        if(existing!=null && available(existing)) {
            val mappings=dao.chapters(id);val present=articles.getAllIds().toHashSet()
            if(mappings.size==hashes.size && mappings.all{it.articleId in present})return@withContext merge(JSONObject(existing.stateJson),checkOwner,forceProjection=true)
        }
        val h=requireNotNull(header);val state=existing?.let{JSONObject(it.stateJson)}?:JSONObject().put("bookId",id).put("fingerprint",fingerprint).put("title",h.getString("title")).put("chapterCount",hashes.size).put("sourceUrl",h.getString("sourceUrl")).put("kind",h.getString("kind")).put("deletedChapters",JSONArray()).put("deletionClock",0).put("deleted",false).put("deletedAt",0).put("updatedAt",0).put("sourceDeviceId",deviceId)
        require(ImportedContentIds.isImportedContentUrl(h.getString("sourceUrl")))
        val tmp=temporary("received-");input.copyTo(tmp,true)
        val packageHash=sha(tmp);val bytes=tmp.length();directory.mkdirs();com.lightningstudio.watchrss.phone.data.local.CloudExportFileWitness.changing(file(id)){Files.move(tmp.toPath(),file(id).toPath(),StandardCopyOption.REPLACE_EXISTING)}
        var changed=0
        db.withTransaction {
            checkOwner()
            val current=dao.get(id);val merged=BookSync.merge(current?.let{JSONObject(it.stateJson)},state)
            if(merged.optString("sourceUrl").isBlank())merged.put("sourceUrl",h.getString("sourceUrl"))
            val mappings=dao.chapters(id).associateBy{it.chapterIndex};val allOld=articles.getAllMetadataForSync().associateBy{it.url}
            input.bufferedReader(Charsets.UTF_8).use {r->r.readLine();repeat(hashes.size){i->checkOwner();val c=JSONObject(requireNotNull(r.readLine()));val index=i+1
                val oldId=mappings[index]?.articleId?:allOld[c.getString("url")]?.articleId
                val articleId=oldId?:BookSync.articleId(id,index);val previous=articles.getById(articleId)
                fun missing(value:String?)=value.isNullOrBlank() || contentStore?.isMarker(value)==true && contentStore.loadText(value).isNullOrBlank()
                val repaired=previous?.let { row ->
                    val text=if(missing(row.contentText))c.getString("contentText") else row.contentText
                    val html=if(missing(row.contentHtml) && !c.isNull("contentHtml"))c.getString("contentHtml") else row.contentHtml
                    if(text==row.contentText && html==row.contentHtml)row else row.copy(contentText=text,contentHtml=html,
                        contentHash=CloudSnapshotCodec.sha256(c.getString("contentText").toByteArray()),syncBodyHash="",syncBodyByteCount=0,syncChunkSize=0,syncChunkHashesJson="",syncMetadataHash="")
                }
                val tombstone=merged.getBoolean("deleted")||index in BookSync.deletions(merged)
                val next=repaired?.copy(deleted=previous!!.deleted||tombstone,deletedAt=maxOf(previous.deletedAt,if(tombstone)maxOf(merged.getLong("deletedAt"),merged.getLong("deletionClock"))else 0))?:PhoneArticleEntity(articleId,merged.getString("sourceDeviceId"),c.getString("url"),c.getString("title"),h.getString("title"),"",if(c.isNull("contentHtml"))null else c.getString("contentHtml"),c.getString("contentText"),null,CloudSnapshotCodec.sha256(c.getString("contentText").toByteArray()),1,1,false,0,0,if(ImportedContentIds.isImportedTextArticleUrl(c.getString("url")))ImportedContentIds.ROOT_SOURCE_URL else h.getString("sourceUrl"),if(ImportedContentIds.isImportedTextArticleUrl(c.getString("url")))ImportedContentIds.ROOT_SOURCE_TITLE else h.getString("title"),false,0,0,false,0,0,tombstone,if(tombstone)maxOf(merged.getLong("deletedAt"),merged.getLong("deletionClock"))else 0)
                if(next!=previous){writeArticle(next);changed++};dao.putChapters(listOf(BookChapterEntity(id,index,articleId)))
            }}
            val next=BookEntity(id,BookSync.canonical(merged),packageHash,bytes);if(current!=next){dao.put(next);changed++}
            val singleText=ImportedContentIds.isImportedTextArticleUrl(h.getString("sourceUrl"))
            val source=if(singleText)ImportedContentIds.ROOT_SOURCE_URL else h.getString("sourceUrl")
            if(db.phoneRssSourceDao().getByUrl(source)==null)db.phoneRssSourceDao().upsert(PhoneRssSourceEntity(source,merged.getString("sourceDeviceId"),if(singleText)ImportedContentIds.ROOT_SOURCE_TITLE else merged.getString("title"),"",null,null,1,merged.getLong("updatedAt"),1,false,!singleText&&merged.getBoolean("deleted"),if(singleText)0 else merged.getLong("deletedAt")))
            for(reading in dao.reading().filter{it.bookId==id})mergeReading(JSONObject(reading.stateJson))
        };changed
    }
    private suspend fun writeArticle(row:PhoneArticleEntity) {
        val text=if(contentStore!=null && ImportedContentIds.isImportedTextArticleUrl(row.url) &&
            !contentStore.isMarker(row.contentText) && row.contentText.length>50_000)
            contentStore.storeText("${row.articleId}-text",row.contentText) else row.contentText
        articles.upsert(row.copy(contentText=text,syncMetadataHash=""))
        db.bookReadingDao().cleanProjection(row.articleId)
        logArticle(row)
    }
    private suspend fun logArticle(row:PhoneArticleEntity) {
        db.syncChangeLogDao().insert(SyncChangeLogEntity(kind="article",entityId=row.articleId,changedAt=maxOf(row.updatedAt,row.deletedAt,row.readingPositionChangedAt),
            originDeviceId=row.sourceDeviceId,reason="bookState",createdAt=System.currentTimeMillis()))
    }
    suspend fun clear(){db.withTransaction {dao.clear();readingJournal.clear()};directory.deleteRecursively();migrationWarnings.clear()}
    private fun sha(file:File):String {val digest=MessageDigest.getInstance("SHA-256");file.inputStream().buffered().use{input->val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)}};return digest.digest().joinToString(""){"%02x".format(it)}}
}
