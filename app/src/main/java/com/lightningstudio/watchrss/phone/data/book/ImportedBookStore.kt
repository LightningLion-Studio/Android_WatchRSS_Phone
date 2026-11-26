package com.lightningstudio.watchrss.phone.data.book

import androidx.room.withTransaction
import com.lightningstudio.watchrss.phone.BuildConfig
import com.lightningstudio.watchrss.phone.data.db.*
import com.lightningstudio.watchrss.phone.data.local.ArticleContentStore
import com.lightningstudio.watchrss.phone.data.local.ConditionalBodyFile
import com.lightningstudio.watchrss.phone.data.local.FileArticleContentStore
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Exception
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Frames
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Scratch
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import com.lightningstudio.watchrss.phone.cloud.CloudSnapshotCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.*
import java.io.*
import java.security.MessageDigest
import java.nio.file.Files

/** A complete peer item was received, but its package is invalid. Never wrap IO/owner/cancellation failures. */
internal class InvalidBookPackageException(val reason:String="package_invalid") : IllegalArgumentException("book_package_invalid")
internal inline fun <T> validateBookPackage(block:()->T):T = try {block()}
    catch(e:InvalidBookPackageException){throw e}
    catch(_:org.json.JSONException){throw InvalidBookPackageException()}
    catch(_:IllegalArgumentException){throw InvalidBookPackageException()}

/** Immutable original packages survive chapter deletion. State/reading are independent small rows. */
class ImportedBookStore(private val db:PhoneCompanionDatabase,private val directory:File,
    private val contentStore:ArticleContentStore?,private val deviceId:String) {
    private val registrationLock=Mutex()
    private val verifiedReceiveLock = Mutex()
    private val dao get()=db.bookDao()
    private val articles get()=db.phoneArticleDao()
    private val readingJournal=BookReadingJournal(db)
    internal val readingHashCounts get()=listOf(readingJournal.leafHashes,readingJournal.bookHashes,readingJournal.rootHashes)
    var onDiagnostic:(String)->Unit={}
    private fun diagnostic(message:String){runCatching{onDiagnostic(message)}}
    private class IncompleteOriginal(message:String):IllegalArgumentException(message)
    val migrationWarnings=linkedSetOf<String>()
    private fun file(id: String) = BookPackageFiles.legacy(directory, id)
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
        for(book in dao.all().filter { availablePackage(it) == null && !JSONObject(it.stateJson).getBoolean("deleted") })try {
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
                directory.mkdirs();com.lightningstudio.watchrss.phone.data.local.CloudExportFileWitness.changing(file(id)){com.lightningstudio.watchrss.phone.data.local.ConditionalBodyFile.installPrivateBook(file(id),temporary)}
                db.withTransaction {
                    val installed = BookEntity(id, BookSync.canonical(state), sha(file(id)), file(id).length())
                    dao.put(installed)
                    dao.putChapters(metadata.mapIndexed {i,row->BookChapterEntity(id,i+1,row.articleId)})
                }
            } else {
                // Same immutable sequence imported under a new filename: one identity, merge deletion state.
                if(availablePackage(existing) == null){directory.mkdirs();com.lightningstudio.watchrss.phone.data.local.CloudExportFileWitness.changing(file(id)){com.lightningstudio.watchrss.phone.data.local.ConditionalBodyFile.installPrivateBook(file(id),temporary)};dao.put(existing.copy(packageSha256=sha(file(id)),packageBytes=file(id).length()))}
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
    suspend fun manifests():List<JSONObject> {ensureRegistered();return dao.all().map{JSONObject(it.stateJson).put("contentAvailable", availablePackage(it) != null)}}
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
    suspend fun missingBookIds():Set<String> {ensureRegistered();return dao.all().filter{!JSONObject(it.stateJson).getBoolean("deleted")&&availablePackage(it) == null}.mapTo(hashSetOf()){it.bookId}}
    suspend fun sourceUrls():Set<String> {
        ensureRegistered()
        return (dao.all().map{JSONObject(it.stateJson).optString("sourceUrl")}+dao.sourceUrls()).filter{it.isNotBlank()}.toHashSet()
    }
    internal suspend fun binaryReady(): Boolean =
        contentStore is FileArticleContentStore && !dao.hasUnregisteredBookArticles()

    internal suspend fun freezeBinaryBooks(work: () -> Unit = {}, visit: suspend (BookEntity, File?) -> Unit) {
        captureLocalChanges()
        val sql = db.openHelper.writableDatabase
        val (generation, reading) = db.withTransaction {
            BookReadingSchema.installBinaryScanWitness(sql)
            if (!binaryReady()) throw B2Exception("SOURCE_CHANGED")
            BookReadingSchema.binaryScanRevision(sql) to db.bookReadingDao().meta()
        }
        var after = ""
        while (true) {
            // DAO reads each row (including large JSON slices) in one short transaction.
            // Neither hashing nor the visitor may hold Room while acquiring the B2 journal.
            val row = dao.nextBinaryBook(after) ?: break
            val original = verifiedPackage(row, work)
            visit(row, original?.file)
            if (dao.get(row.bookId) != row || original?.isCurrent() == false) throw B2Exception("SOURCE_CHANGED")
            after = row.bookId
        }
        db.withTransaction {
            val currentReading = db.bookReadingDao().meta()
            if (BookReadingSchema.binaryScanRevision(sql) != generation ||
                currentReading?.epoch != reading?.epoch || currentReading?.seq != reading?.seq || !binaryReady()) {
                throw B2Exception("SOURCE_CHANGED")
            }
        }
    }
    internal suspend fun visitCommittedBinaryPackages(limit: Int, work: () -> Unit, visit: (BookEntity) -> Unit) {
        var after = ""
        repeat(limit) {
            work()
            val row = dao.nextBinaryBook(after) ?: return
            after = row.bookId
            if (!JSONObject(row.stateJson).getBoolean("deleted")) {
                val original = verifiedPackage(row, work)
                if (original != null && dao.get(row.bookId) == row && original.isCurrent()) visit(row)
            }
        }
    }

    internal suspend fun binaryReadingPage(id: String, after: Int) = dao.binaryReadingPage(id, after)
    internal suspend fun binaryState(id: String) = dao.get(id)?.stateJson?.let(::JSONObject)
    internal suspend fun binaryPackageRevision(id: String) = dao.get(id)?.packageRevision()
    internal suspend fun binaryReadingWitness(id: String, indices: List<Int>): List<String> =
        dao.readingBatch(id, indices).sortedBy { it.chapterIndex }.map { it.stateJson }

    suspend fun availableBooks(): List<Pair<JSONObject, File>> {
        ensureRegistered()
        return dao.all().mapNotNull { row ->
            val file = availablePackage(row) ?: return@mapNotNull null
            JSONObject(row.stateJson).takeUnless { it.getBoolean("deleted") }?.let { it to file }
        }
    }
    internal fun binaryPackage(id: String, hash: String): File = BookPackageFiles.binary(directory, id, hash)
    internal suspend fun resolvePackage(
        id: String, revision: BookPackageFiles.Revision, work: () -> Unit
    ): BookPackageFiles.Original? {
        val sql = db.openHelper.readableDatabase
        val transaction = sql.inTransaction()
        val publication = if (transaction) BookPackageFiles.publication(sql, id) else null
        return withContext(Dispatchers.IO) {
            val selected = if (transaction) publication else BookPackageFiles.publication(sql, id)
            BookPackageFiles.resolveOriginal(directory, id, revision, selected) { ensureActive(); work() }
        }
    }

    private fun BookEntity.packageRevision() = BookPackageFiles.Revision(packageSha256, packageBytes)
    private suspend fun verifiedPackage(row: BookEntity, work: () -> Unit = {}) =
        resolvePackage(row.bookId, row.packageRevision(), work)
    private suspend fun availablePackage(row: BookEntity): File? = verifiedPackage(row)?.file

    internal data class PreparedBinaryPackage(
        val assembly: BookPackageFiles.Original,
        val currentRevision: BookPackageFiles.Revision,
        val currentOriginal: BookPackageFiles.Original?,
        val shadow: BinaryPackageShadow? = null
    )

    internal data class BinaryPackageShadow(
        val header: BinaryBookPackage.Header,
        val records: File,
        val stateJson: String,
        val retain: Boolean
    )

    internal suspend fun prepareBinaryPackage(
        id: String, assembly: BookPackageFiles.Original, work: () -> Unit,
        scratch: File? = null, sourceState: JSONObject? = null
    ): PreparedBinaryPackage {
        val row = dao.get(id) ?: throw B2Exception("DEPENDENCIES_PENDING")
        val current = verifiedPackage(row, work)
        val shadow = if (scratch == null) null
            else prepareBinaryShadow(id, row, assembly.file, current, scratch, requireNotNull(sourceState), work)
        return PreparedBinaryPackage(assembly, row.packageRevision(), current, shadow)
    }

    private suspend fun prepareBinaryShadow(id: String, row: BookEntity, original: File,
        current: BookPackageFiles.Original?, scratch: File, sourceState: JSONObject, check: () -> Unit): BinaryPackageShadow {
        val state = JSONObject(row.stateJson)
        val count = state.getInt("chapterCount")
        val retain = state.getBoolean("deleted") || BookSync.compareLifecycle(state, sourceState) > 0 ||
            current != null && dao.binaryPresentChapters(id) == count
        val content = contentStore as? FileArticleContentStore ?: throw B2Exception("LOCAL_IO")
        val records = File(scratch, "chapters.jsonl")
        val claimedArticles = mutableSetOf<String>()
        lateinit var header: BinaryBookPackage.Header
        val fingerprint = records.bufferedWriter().use { journal ->
            BinaryBookPackage.read(original, scratch, check, start = {
                require(it.count == count && it.instance == state.opt("instanceId"))
                require(BookSync.binaryId(state.getString("fingerprint"), it.instance) == id)
                header = it
            }) { book, index, chapter ->
                if (!retain) {
                    val candidate = binaryChapterArticle(id, index, book, chapter.url)
                    val article = if (candidate in claimedArticles) BookSync.articleId(id, index) else candidate
                    if (!claimedArticles.add(article)) throw B2Exception("BUSINESS_REJECTED")
                    val old = articles.getBodyMetadataById(article)
                    val text = if (old == null || !chapterBodyPresent(content, articles.getContentTextChunk(article, 1, 512)))
                        content.publishTextFile("$article-text", chapter.text) else null
                    val html = chapter.html?.takeIf { old == null || !chapterBodyPresent(content, articles.getContentHtmlChunk(article, 1, 512)) }
                        ?.let { content.publishTextFile("$article-html", it) }
                    journal.write(JSONObject().put("articleId", article).put("title", chapter.title).put("url", chapter.url)
                        .put("hash", chapter.contentHash).put("text", text ?: JSONObject.NULL).put("html", html ?: JSONObject.NULL)
                        .put("textWitness", text?.let { markerWitness(content, it) } ?: JSONObject.NULL)
                        .put("htmlWitness", html?.let { markerWitness(content, it) } ?: JSONObject.NULL).toString())
                    journal.newLine()
                }
            }
        }
        if (fingerprint != state.getString("fingerprint")) throw B2Exception("HASH_MISMATCH")
        return BinaryPackageShadow(header, records, row.stateJson, retain)
    }

    private fun markerWitness(content: FileArticleContentStore, marker: String): String {
        val markerFile = content.openTextFile(marker) ?: throw B2Exception("SOURCE_CHANGED")
        return BookPackageFiles.fileIdentity(markerFile)?.toString() ?: throw B2Exception("SOURCE_CHANGED")
    }

    private suspend fun binaryChapterArticle(id: String, index: Int, header: BinaryBookPackage.Header, url: String): String {
        val mapped = dao.chapterAt(id, index)?.articleId
        val candidate = if (mapped != null || header.instance != null) mapped else dao.binaryArticleAtUrl(url)
        return candidate?.takeIf { existing ->
            val owner = dao.chapterByArticle(existing)
            owner == null || owner.bookId == id && owner.chapterIndex == index
        } ?: BookSync.articleId(id, index)
    }

    internal suspend fun packageForSync(id: String): Pair<File, String>? = dao.get(id)?.let { row ->
        availablePackage(row)?.let { it to row.packageSha256 }
    }
    suspend fun packageFile(id: String): File? = packageForSync(id)?.first
    suspend fun packageHash(id:String)=requireNotNull(dao.get(id)).packageSha256
    suspend fun merge(remote:JSONObject,checkOwner:()->Unit={},forceProjection:Boolean=false,binary:Boolean=false):Int=db.withTransaction {
        checkOwner()
        val id=remote.getString("bookId");var previous=dao.get(id)
        val before=previous?.let{JSONObject(it.stateJson).also(if (binary) BookSync::validateBinary else BookSync::validate)}
        var state=validateBookPackage{BookSync.merge(before,remote,binary)};var json=BookSync.canonical(state)
        if(previous?.stateJson==json && !forceProjection)return@withTransaction 0
        readingJournal.initialize()
        captureDirtyReading(collect = !binary)
        // Dirty capture can add a local deletion. Rebase before writing remote metadata.
        previous=dao.get(id)
        val captured=previous?.let{JSONObject(it.stateJson).also(if (binary) BookSync::validateBinary else BookSync::validate)}
        state=validateBookPackage{BookSync.merge(captured,remote,binary)};json=BookSync.canonical(state)
        var changed=0
        if(previous?.stateJson!=json){
            val before=previous?.stateJson?.let(::JSONObject)
            val fields=(state.keys().asSequence().toSet()+before?.keys()?.asSequence().orEmpty().toSet()).filter {
                com.lightningstudio.watchrss.phone.cloud.PhoneCloudRecordCodec.canonical(before?.opt(it))!=com.lightningstudio.watchrss.phone.cloud.PhoneCloudRecordCodec.canonical(state.opt(it))
            }
            diagnostic("book-merge:id=${id.take(8)}:fields=${fields.sorted().joinToString(",")}")
            dao.put(previous?.copy(stateJson=json)?:BookEntity(id,json));changed++
        }
        changed + if (binary) applyBinaryDeletions(state, checkOwner) else applyDeletions(state)
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
    private suspend fun applyBinaryDeletions(state: JSONObject, work: () -> Unit): Int {
        val id = state.getString("bookId")
        val all = state.getBoolean("deleted")
        val clock = maxOf(state.getLong("deletionClock"), state.getLong("deletedAt"))
        var changed = 0

        suspend fun clearReading(rows: List<BookReadingEntity>) {
            for (reading in rows) {
                val value = JSONObject(reading.stateJson)
                for (prefix in listOf("favorite", "watchLater")) {
                    if (value.has("${prefix}Saved")) value.put("${prefix}Saved", false)
                }
                if (readingJournal.record(value)) changed++
            }
        }

        suspend fun markChapters(mappings: List<BookChapterEntity>) {
            val metadata = articles.bookMetadata(mappings.map { it.articleId }).associateBy { it.articleId }
            for (mapping in mappings) {
                val meta = metadata[mapping.articleId] ?: continue
                if (!meta.deleted || meta.deletedAt < clock) {
                    val row = meta
                    val next = row.copy(deleted = true, deletedAt = maxOf(row.deletedAt, clock),
                        favoriteSaved = false, watchLaterSaved = false, independentSaved = false)
                    db.b2ArticleDao().updateBinaryArticleKeepingBody(next.copy(syncMetadataHash = ""))
                    db.bookReadingDao().cleanProjection(next.articleId)
                    logArticle(next)
                    changed++
                }
            }
        }

        if (all) {
            var cursor = 0
            while (true) {
                val page = dao.binaryReadingPage(id, cursor)
                if (page.isEmpty()) break
                clearReading(page)
                cursor = page.last().chapterIndex
                work()
            }
            cursor = 0
            while (true) {
                val page = dao.binaryChapterPage(id, cursor)
                if (page.isEmpty()) break
                markChapters(page)
                cursor = page.last().chapterIndex
                work()
            }
        } else {
            val deleted = state.getJSONArray("deletedChapters")
            for (offset in 0 until deleted.length() step 100) {
                val indices = (offset until minOf(deleted.length(), offset + 100)).map(deleted::getInt)
                clearReading(dao.readingBatch(id, indices))
                markChapters(dao.chapterBatch(id, indices))
                work()
            }
        }
        val sourceUrl=state.optString("sourceUrl")
        val source = db.phoneRssSourceDao().getByUrl(sourceUrl) ?: dao.binaryChapterPage(id, 0).firstOrNull()
            ?.let { articles.getBodyMetadataById(it.articleId)?.rssSourceUrl }
            ?.let { db.phoneRssSourceDao().getByUrl(it) }
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
            val clock = if (BuildConfig.B2_ENABLED) {
                maxOf(state.getLong("deletionClock"), rows.maxOfOrNull { (_, row) ->
                    if (row.deleted) row.deletedAt else 0L
                } ?: 0L)
            } else {
                maxOf(state.getLong("deletionClock"), rows.maxOfOrNull { it.second.deletedAt } ?: 0L, source?.deletedAt ?: 0L)
            }
            if(source!=null && source.url!=ImportedContentIds.ROOT_SOURCE_URL && source.updatedAt>state.getLong("updatedAt"))state.put("title",source.title).put("updatedAt",source.updatedAt).put("sourceDeviceId",source.sourceDeviceId)
            state.put("deletedChapters",JSONArray(deleted.sorted())).put("deletionClock",clock)
            if (source?.deleted == true) {
                val wholeClock = if (BuildConfig.B2_ENABLED) source.deletedAt else clock
                state.put("deleted", true).put("deletedAt", maxOf(wholeClock, state.getLong("deletedAt")))
            }
            if (BookSync.canonical(state) != book.stateJson) {
                val binary = BookSync.usesBinaryCapture(state)
                // B2-only instances have no v1 identity on a build with B2 disabled.
                if (binary || book.bookId == BookSync.id(state.getString("fingerprint"))) merge(state, binary = binary)
            }
        }
        if(changedSources)db.bookReadingDao().putRegistration(requireNotNull(db.bookReadingDao().registration()).copy(sourceStateJson=sourceState))
        diagnostic("capture:items=${changedRows.size}:ms=${(System.nanoTime()-started)/1_000_000}")
    }
    /** The transaction is the dirty revision witness: local writers cannot race projection cleanup. */
    private suspend fun captureDirtyReading(collect: Boolean = true):List<Pair<BookChapterEntity,PhoneArticleEntity>> {
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
                    if (collect) changed+=mapping to row
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
    suspend fun acknowledgeReading(peer: String, local: BookReadingSummary, remote: BookReadingSummary, root: String,
        checkWindow: () -> Unit = {}, admitCommit: () -> Unit = {}) = db.withTransaction {
        checkWindow()
        readingJournal.initialize()
        readingJournal.acknowledge(peer, local, remote, root)
        checkWindow()
        admitCommit()
    }
    suspend fun mergeReading(remote:JSONObject,checkOwner:()->Unit={}):Int=mergeReadingBatch(listOf(remote),checkOwner)
    suspend fun mergeReadingBatch(values:List<JSONObject>,checkOwner:()->Unit={},binary:Boolean=false):Int=db.withTransaction {
        require(values.size<=100)
        checkOwner();values.forEach(BookSync::validateReading)
        readingJournal.initialize();captureDirtyReading(collect = !binary)
        var changed=0
        val books = if (binary) values.map { it.getString("bookId") }.distinct().mapNotNull { dao.get(it) }.associateBy { it.bookId }
            else dao.all().associateBy{it.bookId}
        val states = books.mapValues { JSONObject(it.value.stateJson) }
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
            val state = states[id]
            if (state != null) require(index <= state.getInt("chapterCount"))
            val merged=BookSync.mergeReading(previous[id to index],remote)
            if (state != null) {
                val deleted = state.getJSONArray("deletedChapters")
                var lo = 0
                var hi = deleted.length()
                while (lo < hi) {
                    val mid = (lo + hi) / 2
                    if (deleted.getInt(mid) < index) lo = mid + 1 else hi = mid
                }
                val chapterDeleted = if (binary) lo < deleted.length() && deleted.getInt(lo) == index
                    else index in BookSync.deletions(state)
                if(state.getBoolean("deleted") || chapterDeleted)
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
    /** Called by the B2 FULL writer transaction, including the package APPLIED receipt. */
    internal suspend fun installBinaryPackage(id: String, fingerprint: String,
        sourceState: JSONObject, prepared: PreparedBinaryPackage, scratch: File, check: () -> Unit): String {
        val content = contentStore as? FileArticleContentStore ?: throw B2Exception("LOCAL_IO")
        val current = dao.get(id) ?: throw B2Exception("DEPENDENCIES_PENDING")
        val assembly = prepared.assembly
        val combined = assembly.file
        val (hash, bytes) = assembly.revision
        if (current.packageRevision() != prepared.currentRevision || prepared.currentOriginal?.isCurrent() == false ||
            !assembly.isCurrent()) throw B2Exception("SOURCE_CHANGED")
        val state = JSONObject(current.stateJson)
        val retired = state.getBoolean("deleted") || BookSync.compareLifecycle(state, sourceState) > 0
        // A cloud writer can restore the registered bytes without updating the Room row.
        // Reuse a positive file witness and resolve a missed original again under the writer.
        val currentOriginal = prepared.currentOriginal ?: if (retired) null else verifiedPackage(current, check)
        val present = currentOriginal != null && dao.binaryPresentChapters(id) == state.getInt("chapterCount")
        val retain = retired || present
        val deleted = java.util.BitSet(state.getInt("chapterCount") + 1)
        val tombstones = state.getJSONArray("deletedChapters")
        for (i in 0 until tombstones.length()) deleted.set(tombstones.getInt(i))
        suspend fun registerReading(articleId: String, index: Int) {
            dao.putChapters(listOf(BookChapterEntity(id, index, articleId)))
            val reading = dao.reading(id, index)
            if (reading != null) mergeReadingBatch(listOf(JSONObject(reading.stateJson)), check, binary = true)
        }
        val shadow = prepared.shadow
        val verified = if (shadow != null) {
            if (current.stateJson != shadow.stateJson || (shadow.retain && !retain)) throw B2Exception("SOURCE_CHANGED")
            if (!retain) shadow.records.bufferedReader().use { journal ->
                for (index in 1..shadow.header.count) {
                    check()
                    val chapter = JSONObject(journal.readLine() ?: throw B2Exception("LOCAL_IO"))
                    val url = chapter.getString("url")
                    val article = binaryChapterArticle(id, index, shadow.header, url)
                    if (article != chapter.getString("articleId")) throw B2Exception("SOURCE_CHANGED")
                    fun marker(field: String): String? {
                        if (chapter.isNull(field)) return null
                        val value = chapter.getString(field)
                        if (markerWitness(content, value) != chapter.getString(field + "Witness")) throw B2Exception("SOURCE_CHANGED")
                        return value
                    }
                    applyBinaryChapter(state, shadow.header, chapter.getString("title"), url, chapter.getString("hash"),
                        article, deleted[index], articles.getBodyMetadataById(article), marker("text"), marker("html"))
                    registerReading(article, index)
                }
                if (journal.readLine() != null) throw B2Exception("LOCAL_IO")
            }
            state.getString("fingerprint")
        } else BinaryBookPackage.read(combined, scratch, check, start = { header ->
            require(header.count == state.getInt("chapterCount") && header.instance == state.opt("instanceId"))
            require(BookSync.binaryId(fingerprint, header.instance) == id)
        }) { header, index, chapter ->
            if (!retain) {
                val articleId = binaryChapterArticle(id, index, header, chapter.url)
                installBinaryChapter(content, state, header, chapter, articleId, deleted[index])
                registerReading(articleId, index)
            }
        }
        if (verified != fingerprint || state.getString("fingerprint") != fingerprint) throw B2Exception("HASH_MISMATCH")
        if (!assembly.isCurrent()) throw B2Exception("SOURCE_CHANGED")
        if (retain) return if (!retired && current.packageSha256 == hash && current.packageBytes == bytes)
            "alreadyPresent" else "retainedLocal"
        check()
        if (combined != file(id)) BookPackageFiles.publish(db.openHelper.writableDatabase, directory, id, assembly)
        dao.put(current.copy(packageSha256 = hash, packageBytes = bytes))
        return "merged"
    }

    /** Repair derived reader text from the current verified original before cloud fallback. */
    internal suspend fun restoreChapterBodyForReading(articleId: String): Boolean = withContext(Dispatchers.IO) {
        val content = contentStore as? FileArticleContentStore ?: return@withContext false
        val mapping = dao.chapterByArticle(articleId) ?: return@withContext false
        val article = articles.getBodyMetadataById(articleId) ?: return@withContext false
        if (article.deleted || chapterBodyPresent(content, articles.getContentTextChunk(articleId, 1, 512)))
            return@withContext false
        val row = dao.get(mapping.bookId) ?: return@withContext false
        val state = JSONObject(row.stateJson)
        if (state.getBoolean("deleted") || mapping.chapterIndex in BookSync.deletions(state)) return@withContext false
        val original = availablePackage(row) ?: return@withContext false
        val modified = original.lastModified()
        val check = { ensureActive() }
        var text: String? = null
        var html: String? = null
        var hash = ""
        val fingerprint = try {
            B2Scratch.open(directory, B2Scratch.BOOK_IMPORT).use { scratch ->
                BinaryBookPackage.read(original, scratch.file, check, start = { header ->
                    require(header.count == state.getInt("chapterCount") && header.instance == state.opt("instanceId"))
                }) { _, index, chapter ->
                    if (index == mapping.chapterIndex) {
                        require(chapter.url == article.url)
                        text = content.publishTextFile("$articleId-text", chapter.text)
                        html = chapter.html?.let { content.publishTextFile("$articleId-html", it) }
                        hash = chapter.contentHash
                    }
                }
            }
        } catch (_: IOException) {
            return@withContext false
        } catch (_: IllegalArgumentException) {
            return@withContext false
        }
        if (text == null || fingerprint != state.getString("fingerprint")) return@withContext false
        db.withTransaction {
            // Hashing and parsing are outside the writer transaction. Recheck the book,
            // chapter, and cached body to avoid undoing a concurrent update or deletion.
            if (dao.chapterByArticle(articleId) != mapping || dao.get(mapping.bookId) != row ||
                original.length() != row.packageBytes || original.lastModified() != modified) return@withTransaction false
            val current = articles.getBodyMetadataById(articleId) ?: return@withTransaction false
            if (current.deleted || current.url != article.url || chapterBodyPresent(content, articles.getContentTextChunk(articleId, 1, 512)))
                return@withTransaction false
            if (chapterBodyPresent(content, articles.getContentHtmlChunk(articleId, 1, 512))) html = null
            check()
            dao.repairBinaryChapter(articleId, text, html, hash)
            db.bookReadingDao().cleanProjection(articleId)
            true
        }
    }

    private fun chapterBodyPresent(content: FileArticleContentStore, prefix: ByteArray?): Boolean {
        val value = prefix?.toString(Charsets.UTF_8).orEmpty()
        return value.isNotBlank() && (!content.isMarker(value) || content.openTextFile(value)?.length()?.let { it > 0 } == true)
    }

    private suspend fun installBinaryChapter(content: FileArticleContentStore, state: JSONObject,
        header: BinaryBookPackage.Header, chapter: BinaryBookPackage.Chapter, id: String, deleted: Boolean) {
        val previous = articles.getBodyMetadataById(id)
        val text = if (previous == null || !chapterBodyPresent(content, articles.getContentTextChunk(id, 1, 512)))
            content.publishTextFile("$id-text", chapter.text) else null
        val html = chapter.html?.takeIf { previous == null || !chapterBodyPresent(content, articles.getContentHtmlChunk(id, 1, 512)) }
            ?.let { content.publishTextFile("$id-html", it) }
        applyBinaryChapter(state, header, chapter.title, chapter.url, chapter.contentHash, id, deleted, previous, text, html)
    }

    private suspend fun applyBinaryChapter(state: JSONObject, header: BinaryBookPackage.Header,
        title: String, url: String, contentHash: String, id: String, deleted: Boolean,
        previous: PhoneArticleEntity?, text: String?, html: String?) {
        val goneAt = if (deleted) maxOf(state.getLong("deletedAt"), state.getLong("deletionClock")) else 0L
        if (previous != null) {
            val next = previous.copy(deleted = previous.deleted || deleted, deletedAt = maxOf(previous.deletedAt, goneAt),
                favoriteSaved = previous.favoriteSaved && !deleted, watchLaterSaved = previous.watchLaterSaved && !deleted,
                independentSaved = previous.independentSaved && !deleted)
            if (next != previous) db.b2ArticleDao().updateBinaryArticleKeepingBody(next.copy(syncMetadataHash = ""))
            if (text != null || html != null) dao.repairBinaryChapter(id, text, html, contentHash)
            if (next != previous || text != null || html != null) logArticle(next)
        } else {
            val single = ImportedContentIds.isImportedTextArticleUrl(url)
            val source = if (single) ImportedContentIds.ROOT_SOURCE_URL else header.source
            val sourceTitle = if (single) ImportedContentIds.ROOT_SOURCE_TITLE else header.title
            val device = state.getString("sourceDeviceId")
            writeArticle(PhoneArticleEntity(id, device, url, title, header.title, "", html,
                requireNotNull(text), null, contentHash, 1, 1, false, 0, 0, source, sourceTitle, false, 0, 0,
                false, 0, 0, deleted, goneAt))
            if (db.phoneRssSourceDao().getByUrl(source) == null) {
                db.phoneRssSourceDao().upsert(PhoneRssSourceEntity(source, device, sourceTitle, "", null, null,
                    1, state.getLong("updatedAt"), 1, false, false, 0))
            }
        }
        db.bookReadingDao().cleanProjection(id)
    }

    internal fun binaryImportDirectory(): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw B2Exception("LOCAL_IO")
        return directory
    }

    /** Validate the package before publishing its original reference and chapter mappings. */
    suspend fun receivePackage(id: String, input: File, checkOwner: () -> Unit = {}): Int =
        receivePackage(id, input, null, {}, checkOwner)

    internal suspend fun receiveVerifiedPackage(id: String, input: File, revision: BookPackageFiles.Revision,
        admitCommit: () -> Unit = {}, commitOwner: (suspend (suspend () -> Int) -> Int)? = null,
        checkOwner: () -> Unit): Int = verifiedReceiveLock.withLock {
        receivePackage(id, input, revision, admitCommit, checkOwner, commitOwner)
    }

    private suspend fun receivePackage(id: String, input: File, expected: BookPackageFiles.Revision?,
        admitCommit: () -> Unit, checkOwner: () -> Unit,
        commitOwner: (suspend (suspend () -> Int) -> Int)? = null): Int = withContext(Dispatchers.IO) {
        checkOwner()
        val staged = temporary("received-")
        try {
            input.copyTo(staged, true)
            val original = BookPackageFiles.Revision(sha(staged), staged.length())
            if (expected != null && expected != original) throw InvalidBookPackageException("package_digest_changed")
            applyStagedPackage(id, staged, original, expected != null, admitCommit, checkOwner, commitOwner)
        } finally { staged.delete() }
    }

    private suspend fun applyStagedPackage(id: String, staged: File, original: BookPackageFiles.Revision,
        bluetooth: Boolean, admitCommit: () -> Unit, checkOwner: () -> Unit,
        commitOwner: (suspend (suspend () -> Int) -> Int)?): Int {
        var header:JSONObject?=null;val hashes=mutableListOf<String>()
        val fingerprint=validateBookPackage {
            require(id.matches(Regex("[0-9a-f]{64}")))
            staged.bufferedReader(Charsets.UTF_8).use { r ->
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
        if(existing!=null && availablePackage(existing) != null) {
            val mappings=dao.chapters(id);val present=articles.getAllIds().toHashSet()
            if(mappings.size==hashes.size && mappings.all{it.articleId in present}) {
                return db.withTransaction {
                    val changed = merge(JSONObject(existing.stateJson), checkOwner, forceProjection = true)
                    checkOwner()
                    admitCommit()
                    changed
                }
            }
        }
        val h=requireNotNull(header);val state=existing?.let{JSONObject(it.stateJson)}?:JSONObject().put("bookId",id).put("fingerprint",fingerprint).put("title",h.getString("title")).put("chapterCount",hashes.size).put("sourceUrl",h.getString("sourceUrl")).put("kind",h.getString("kind")).put("deletedChapters",JSONArray()).put("deletionClock",0).put("deleted",false).put("deletedAt",0).put("updatedAt",0).put("sourceDeviceId",deviceId)
        require(ImportedContentIds.isImportedContentUrl(h.getString("sourceUrl")))
        val owner = currentCoroutineContext()
        fun authorize() { owner.ensureActive(); checkOwner() }
        suspend fun commit(): Int {
            var published: ConditionalBodyFile.PublishedBook? = null
            try {
                authorize()
                directory.mkdirs()
                com.lightningstudio.watchrss.phone.data.local.CloudExportFileWitness.changing(file(id)) {
                    if (bluetooth) ConditionalBodyFile.publishNewBook(file(id), staged, original.sha256, ::authorize) { published = it }
                    else ConditionalBodyFile.installPrivateBook(file(id), staged, original.sha256, ::authorize)
                }
                suspend fun writePackage(): Int {
                    var changed=0
                    authorize()
                    val current=dao.get(id);val merged=BookSync.merge(current?.let{JSONObject(it.stateJson)},state)
                    if(merged.optString("sourceUrl").isBlank())merged.put("sourceUrl",h.getString("sourceUrl"))
                    val mappings=dao.chapters(id).associateBy{it.chapterIndex};val allOld=articles.getAllMetadataForSync().associateBy{it.url}
                    staged.bufferedReader(Charsets.UTF_8).use {r->r.readLine();repeat(hashes.size){i->authorize();val c=JSONObject(requireNotNull(r.readLine()));val index=i+1
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
                    val next=BookEntity(id,BookSync.canonical(merged),original.sha256,original.bytes);if(current!=next){dao.put(next);changed++}
                    val singleText=ImportedContentIds.isImportedTextArticleUrl(h.getString("sourceUrl"))
                    val source=if(singleText)ImportedContentIds.ROOT_SOURCE_URL else h.getString("sourceUrl")
                    if(db.phoneRssSourceDao().getByUrl(source)==null)db.phoneRssSourceDao().upsert(PhoneRssSourceEntity(source,merged.getString("sourceDeviceId"),if(singleText)ImportedContentIds.ROOT_SOURCE_TITLE else merged.getString("title"),"",null,null,1,merged.getLong("updatedAt"),1,false,!singleText&&merged.getBoolean("deleted"),if(singleText)0 else merged.getLong("deletedAt")))
                    for(reading in dao.reading().filter{it.bookId==id})mergeReading(JSONObject(reading.stateJson))
                    authorize()
                    admitCommit()
                    return changed
                }
                return if (commitOwner == null) db.withTransaction { writePackage() } else commitOwner(::writePackage)
            } catch (failure: Throwable) {
                try { published?.withdraw() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }
        // Await the actual SQLite outcome before withdrawing a file. The original job and
        // account still gate admission after the writer queue and before committing.
        return if (bluetooth) withContext(NonCancellable) { commit() } else commit()
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
    suspend fun clear() {
        db.withTransaction {
            dao.clear()
            readingJournal.clear()
            BookPackageFiles.clearPublications(db.openHelper.writableDatabase)
        }
        directory.listFiles()?.filter { it.isFile }?.forEach { file ->
            val expected = ConditionalBodyFile.digest(file)
            ConditionalBodyFile.delete(file, expected)
        }
        directory.delete()
        migrationWarnings.clear()
    }
    private fun sha(file:File):String {val digest=MessageDigest.getInstance("SHA-256");file.inputStream().buffered().use{input->val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)}};return digest.digest().joinToString(""){"%02x".format(it)}}
}
