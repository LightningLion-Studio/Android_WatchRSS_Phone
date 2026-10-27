package com.lightningstudio.watchrss.phone.data.backup

import android.content.Context
import com.lightningstudio.watchrss.phone.cloud.CloudRssSourceIdentity
import com.lightningstudio.watchrss.phone.cloud.CloudContentPolicy
import com.lightningstudio.watchrss.phone.cloud.CloudSnapshotCodec
import android.net.Uri
import androidx.room.withTransaction
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneSavedItemEntity
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import com.lightningstudio.watchrss.phone.connection.bluetooth.LibrarySyncPayload
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WatchRssBackupService(
    context: Context,
    private val database: PhoneCompanionDatabase,
    private val repository: PhoneCompanionRepository,
    private val readerPresetRepository: ReaderPresetRepository,
    private val deviceId: String
) {
    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver

    // ── 专有格式备份（WRSS）──

    suspend fun exportTo(uriString: String): BackupSummary = withContext(Dispatchers.IO) {
        val uri = parseUri(uriString)
        val output = contentResolver.openOutputStream(uri, "w")
            ?: error("无法创建 WRSS 文件")
        output.use { exportTo(it) }
    }

    suspend fun exportTo(output: OutputStream): BackupSummary = withContext(Dispatchers.IO) {
        val snapshot = collectSnapshot()
        WatchRssBackupArchive.write(snapshot, output)
        snapshot.summary()
    }

    /** Actual private bytes/configuration only; read/favorite/progress clocks travel in RSS state. */
    internal suspend fun privateCloudFingerprint(): String = withContext(Dispatchers.IO) {
        val bookVersions=repository.books?.availableBooks().orEmpty().associate{it.first.getString("bookId") to it.first.getString("fingerprint")}
        val state = JSONObject().put("books",JSONObject(bookVersions))
            .put("versions", JSONObject(repository.privateCloudVersions(layoutV3 = true)))
            .put("reader", com.lightningstudio.watchrss.phone.connection.bluetooth.ReaderPresetSyncPayload.snapshotJson(CloudPrivateArchive.reader(readerPresetRepository.exportSnapshot())))
            .put("appMeta", JSONObject(database.appMetaDao().getAll().filter { CloudContentPolicy.privateAppMeta(it.key) }.associate { it.key to it.value }))
        com.lightningstudio.watchrss.phone.cloud.CloudSnapshotCodec.sha256(
            com.lightningstudio.watchrss.phone.cloud.PhoneCloudRecordCodec.canonical(state).toByteArray())
    }

    internal suspend fun canReuseCloudArchive(bytes: ByteArray, reason: (String) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        com.lightningstudio.watchrss.phone.cloud.CloudArchiveReuse.matches(bytes, repository.privateCloudVersions(),
            com.lightningstudio.watchrss.phone.connection.bluetooth.ReaderPresetSyncPayload.snapshotJson(readerPresetRepository.exportSnapshot()),
            database.appMetaDao().getAll().associate { it.key to it.value }, reason)
    }

    internal suspend fun privateCloudBodyIds(): Set<String> {
        val ids=repository.getPrivateArticlesForCloudArchive().filter { CloudContentPolicy.privateBody(it) && (it.contentText.isNotBlank() || !it.contentHtml.isNullOrBlank()) }.mapTo(hashSetOf()){it.articleId}
        val store=repository.books
        if(store!=null) for((state,_) in store.availableBooks())ids+=database.bookDao().chapters(state.getString("bookId")).map{it.articleId}
        return ids
    }

    suspend fun createCloudPrivateArchive(): ByteArray = withContext(Dispatchers.IO) {
        val file=createCloudPrivateArchiveFile()
        try { file.readBytes() } finally { file.delete() }
    }

    suspend fun createCloudPrivateArchiveFile(): File = createCloudPrivateArchiveExport().file

    internal data class PrivateArchiveExport(val file: File, val articleIds: Set<String>, val bookIds: Set<String> = emptySet())
    internal suspend fun createCloudPrivateArchiveExport(): PrivateArchiveExport = withContext(Dispatchers.IO) {
        // Real snapshot time lives in the authenticated cloud manifest. Do not churn body chunks.
        val allArticles = repository.getPrivateArticlesForCloudArchive()
        val exportedAt = 1L
        val privateArticles = allArticles.filter(CloudContentPolicy::privateBody)
        val privateSnapshot = WatchRssBackupSnapshot(
            exportedAt = exportedAt,
            appVersion = "",
            dataStructureVersion = WatchRssBackupArchive.CURRENT_DATA_STRUCTURE_VERSION,
            sources = emptyList(),
            articles = privateArticles,
            savedItems = emptyList(),
            readerSnapshot = readerPresetRepository.exportSnapshot(),
            appMeta = database.appMetaDao().getAll().sortedBy { it.key },
            bookPackages = repository.books?.availableBooks().orEmpty().map{(state,file)->BookArchiveContent(
                JSONObject().put("bookId",state.getString("bookId")).put("fingerprint",state.getString("fingerprint")).put("title",state.getString("title"))
                    .put("chapterCount",state.getInt("chapterCount")).put("sourceUrl",state.optString("sourceUrl")).put("kind",state.optString("kind")).put("packagePath","books/${state.getString("bookId")}.jsonl"),file)}
        ).let {
            it.copy(readerResources = readerResources(requireNotNull(it.readerSnapshot)))
        }
        val directory = File(appContext.cacheDir, "cloud-staging").apply { mkdirs() }
        val archive = File.createTempFile("private-library-", ".wrss", directory)
        val projected=CloudPrivateArchive.project(privateSnapshot)
        archive.outputStream().buffered().use { output ->
            WatchRssBackupArchive.write(projected, output)
        }
        PrivateArchiveExport(archive, projected.articles.mapTo(hashSetOf()){it.articleId},
            projected.bookPackages.mapTo(hashSetOf()){it.metadata.getString("bookId")})
    }

    internal data class PrivateOverlayExport(val objects:List<com.lightningstudio.watchrss.phone.cloud.CloudLogicalObject>,
        val entries:List<com.lightningstudio.watchrss.phone.cloud.CloudPrivateOverlayEntry>,val articleIds:Set<String>,val bookIds:Set<String>)

    /** Compare originals, never a DB/encoder version. The old backing is not rewritten for an article edit. */
    internal suspend fun createPrivateOverlayExport(backing:ByteArray,oldEntries:List<com.lightningstudio.watchrss.phone.cloud.CloudPrivateOverlayEntry>,
        oldObjects:Map<String,ByteArray>,clock:Long=System.currentTimeMillis()):PrivateOverlayExport = withContext(Dispatchers.IO) {
        val baseline=WatchRssBackupArchive.read(backing.inputStream(),tolerateCloudEntries=true)
        try {
            val effective=baseline.articles.associateBy{it.articleId}.toMutableMap()
            val rawArticles=CloudPrivateOverlayArchive.rawArticles(backing).toMutableMap()
            val readerNow=com.lightningstudio.watchrss.phone.connection.bluetooth.ReaderPresetSyncPayload.snapshotJson(CloudPrivateArchive.reader(readerPresetRepository.exportSnapshot()))
            val readerThen=baseline.readerSnapshot?.let{com.lightningstudio.watchrss.phone.connection.bluetooth.ReaderPresetSyncPayload.snapshotJson(CloudPrivateArchive.reader(it))}
            val metaNow=database.appMetaDao().getAll().filter{CloudContentPolicy.privateAppMeta(it.key)}.associate{it.key to it.value}
            val metaThen=baseline.appMeta.associate{it.key to it.value}
            if((readerThen!=null && com.lightningstudio.watchrss.phone.cloud.PhoneCloudRecordCodec.canonical(readerThen)!=com.lightningstudio.watchrss.phone.cloud.PhoneCloudRecordCodec.canonical(readerNow)) || metaNow!=metaThen)
                throw com.lightningstudio.watchrss.phone.cloud.CloudFormatUpgradeRequired("overlay_config_edit_pending")
            for(entry in oldEntries.filter{it.kind=="article"}) {
                if(entry.objectName==null)effective.remove(entry.id)
                else {
                    val bytes=requireNotNull(oldObjects[entry.objectName])
                    effective[entry.id]=CloudPrivateOverlayArchive.validate(bytes,entry.id).articles.single()
                    rawArticles[entry.id]=CloudPrivateOverlayArchive.rawArticles(bytes).getValue(entry.id)
                }
            }
            val candidates=repository.getPrivateArticlesForCloudArchive().filter(CloudContentPolicy::privateBody)
            if(candidates.any{it.contentText.isBlank() && it.contentHtml.isNullOrBlank()})
                throw com.lightningstudio.watchrss.phone.cloud.CloudFormatUpgradeRequired("overlay_original_missing")
            val current=candidates.associateBy{it.articleId}
            val entries=oldEntries.associateBy{it.key}.toMutableMap()
            val objects=mutableListOf<com.lightningstudio.watchrss.phone.cloud.CloudLogicalObject>()
            for(id in (effective.keys+current.keys).sorted()) {
                val before=effective[id];val after=current[id]
                if(before!=null && after!=null && CloudPrivateOverlayArchive.signature(before)==CloudPrivateOverlayArchive.signature(after))continue
                if(before==null && after==null)continue
                if(after==null) {
                    // Absence of a cache row is not a deletion. Require an explicit local membership change.
                    val local=repository.getArticle(id)
                    if(local==null || CloudContentPolicy.privateBody(local))continue
                }
                val previous=entries["article" to id]
                val changed=maxOf(clock,(previous?.changedAt ?: 0)+1,after?.updatedAt ?: 0)
                val name=after?.let{com.lightningstudio.watchrss.phone.cloud.CloudPrivateOverlay.articleName(id)}
                entries["article" to id]=com.lightningstudio.watchrss.phone.cloud.CloudPrivateOverlayEntry("article",id,name,changed,deviceId)
                if(after!=null)objects+=com.lightningstudio.watchrss.phone.cloud.CloudLogicalObject(requireNotNull(name),CloudPrivateOverlayArchive.encode(after,rawArticles[id]),false)
            }
            val currentBooks=repository.books?.availableBooks().orEmpty()
            val baseBooks=baseline.bookPackages.map{it.metadata.getString("bookId")}.toMutableSet()
            for(entry in oldEntries.filter{it.kind=="book"})if(entry.objectName==null)baseBooks.remove(entry.id)else baseBooks.add(entry.id)
            val states=repository.books?.states().orEmpty().associateBy{it.getString("bookId")}
            val nowBooks=states.values.filterNot{it.getBoolean("deleted")}.map{it.getString("bookId")}.toSet()
            for(id in (baseBooks+nowBooks).sorted()) {
                if(id in baseBooks && id in nowBooks)continue
                val previous=entries["book" to id]
                if(id !in nowBooks && states[id]?.getBoolean("deleted")!=true)continue
                val pair=currentBooks.firstOrNull{it.first.getString("bookId")==id}
                if(id in nowBooks && pair==null)throw com.lightningstudio.watchrss.phone.cloud.CloudFormatUpgradeRequired("book_original_missing")
                val metadata=pair?.first?.let{state->JSONObject().put("bookId",id).put("fingerprint",state.getString("fingerprint")).put("title",state.getString("title")).put("chapterCount",state.getInt("chapterCount")).put("sourceUrl",state.optString("sourceUrl")).put("kind",state.optString("kind")).put("packagePath","books/$id.jsonl")}
                entries["book" to id]=com.lightningstudio.watchrss.phone.cloud.CloudPrivateOverlayEntry("book",id,metadata?.getString("packagePath"),maxOf(clock,(previous?.changedAt ?: 0)+1),deviceId)
                if(pair!=null)objects+=com.lightningstudio.watchrss.phone.cloud.CloudLogicalObject("books/$id.jsonl",pair.second,false).copy(privateBook=metadata)
            }
            val merged=entries.values.sortedWith(compareBy({it.kind},{it.id}))
            // Validate the bound before creating a reservation. Never trim tombstones to fit.
            com.lightningstudio.watchrss.phone.cloud.CloudPrivateOverlay.encode(merged)
            PrivateOverlayExport(objects,merged,current.keys,nowBooks)
        } finally {baseline.bookPackages.forEach{it.file.delete()}}
    }

    internal fun inspectPrivateOverlayCount(objects:Map<String,ByteArray>):Int {
        val backing=objects["private-library.wrss"]?.let{WatchRssBackupArchive.read(it.inputStream(),tolerateCloudEntries=true)}
        try {
            val ids=backing?.articles.orEmpty().mapTo(mutableSetOf()){it.articleId}
            val entries=com.lightningstudio.watchrss.phone.cloud.CloudPrivateOverlay.decode(objects.getValue(com.lightningstudio.watchrss.phone.cloud.CloudPrivateOverlay.NAME))
            for(entry in entries.filter{it.kind=="article"}) {
                if(entry.objectName==null)ids.remove(entry.id)
                else {CloudPrivateOverlayArchive.validate(objects[entry.objectName] ?: throw java.io.IOException("私有覆盖正文暂不可用"),entry.id);ids+=entry.id}
            }
            return ids.size
        } finally {backing?.bookPackages?.forEach{it.file.delete()}}
    }

    internal fun validatePrivateOverlayArticle(bytes:ByteArray,id:String) {CloudPrivateOverlayArchive.validate(bytes,id)}

    /** Optional pool warming only. Never resolve removed subscriptions or gate records on a fetch UUID. */
    internal suspend fun ensureCloudRssSourceIds(resolve: suspend (String) -> String): List<String> {
        val failed = mutableListOf<String>()
        for (source in database.phoneRssSourceDao().getAllForSync().filterNot { it.deleted || ImportedContentIds.isImportedContentUrl(it.url) || CloudRssSourceIdentity.isLocalOnly(it.url) }) {
            if (repository.publicRssSourceId(source.url) != null) continue
            try { repository.rememberPublicRssSource(source.url,resolve(source.url)) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: com.lightningstudio.watchrss.phone.cloud.CloudTrustException) { throw e }
            catch (_: Exception) { failed += source.url }
        }
        return failed
    }

    internal val unresolvedRssStateUrls = mutableSetOf<String>()

    /** Keyset pages contain metadata only; never hydrate article bodies. */
    internal suspend fun cloudRecordPages(consume: suspend (String, JSONObject) -> Unit) = withContext(Dispatchers.IO) {
        unresolvedRssStateUrls.clear()
        val bookStore=repository.books
        val bookIds=bookStore?.chapterIds().orEmpty();val bookSources=bookStore?.sourceUrls().orEmpty()
        bookStore?.states()?.forEach {consume("book",it)}
        bookStore?.readingStates()?.forEach {consume("book-reading",it)}
        var after = ""
        while (true) {
            val page = database.phoneRssSourceDao().cloudRecordPage(after, 25)
            if (page.isEmpty()) break
            val json = JSONObject(CloudRssStateCodec.encode(emptyList(), 0, page).toString(Charsets.UTF_8)).getJSONArray("sources")
            for (i in 0 until json.length()) {
                val value = json.getJSONObject(i)
                if(value.getString("url") in bookSources || (bookStore!=null && value.getString("url")==ImportedContentIds.ROOT_SOURCE_URL))continue
                if (ImportedContentIds.isImportedContentUrl(value.getString("url"))) { consume("source",value); continue }
                val fields = listOf("url", "title", "sortOrder", "isPinned", "updatedAt", "deleted", "deletedAt", "useOriginalContent", "continuePlaybackInBackground")
                val subscription = JSONObject()
                if (!CloudRssSourceIdentity.isLocalOnly(value.getString("url"))) repository.publicRssSourceId(value.getString("url"))?.let { subscription.put("sourceId",it) }
                fields.forEach { subscription.put(it, value.get(it)) }
                consume("source", subscription)
            }
            after = page.last().url
        }
        after = ""
        while (true) {
            val page = database.phoneArticleDao().cloudRecordPage(after, 25)
            if (page.isEmpty()) break
            val json = JSONObject(CloudRssStateCodec.encode(page, 0).toString(Charsets.UTF_8)).getJSONArray("articles")
            for (i in 0 until json.length()) {
                val value = json.getJSONObject(i)
                if(value.getString("articleId") in bookIds)continue
                if (!CloudContentPolicy.publicRss(value)) consume("article", value)
                else {
                    if (CloudContentPolicy.interacted(value)) {
                        val url = value.getString("rssSourceUrl")
                        if (CloudRssSourceIdentity.isLocalOnly(url)) continue
                        val key = runCatching { CloudRssSourceIdentity.sourceKey(url) }.getOrNull()
                        if (key == null) unresolvedRssStateUrls += url else consume("rss-state",CloudContentPolicy.rssState(value,key))
                    }
                }
            }
            after = page.last().articleId
        }
    }

    internal suspend fun applyCloudRecord(kind: String, value: JSONObject, checkOwner: () -> Unit = {}) = withContext(Dispatchers.IO) {
        checkOwner()
        if(kind=="book") {requireNotNull(repository.books).merge(value,checkOwner);return@withContext}
        if(kind=="book-reading") {requireNotNull(repository.books).mergeReading(value,checkOwner);return@withContext}
        if(kind=="article" && value.optBoolean("bookIndexRetired"))return@withContext
        if (kind == "rss-state") { applyRssInteraction(value, checkOwner); return@withContext }
        if (kind == "article" && CloudContentPolicy.publicRss(value) && CloudRssSourceIdentity.isLocalOnly(value.getString("rssSourceUrl"))) return@withContext
        if (kind == "source" && value.optString("sourceId").isNotBlank()) repository.rememberPublicRssSource(value.getString("url"), value.getString("sourceId"))
        val root = JSONObject().put("format", "watchrss-rss-state").put("version", 1)
            .put("articles", JSONArray()).put("sources", JSONArray())
        root.getJSONArray(if (kind == "article") "articles" else "sources").put(value)
        if (kind == "source") {
            repository.mergeRssSourcesFromSync(CloudRssStateCodec.decodeSources(root.toString().toByteArray()))
        } else {
            val decoded = CloudRssStateCodec.decode(root.toString().toByteArray()).single()
            val incoming = repository.books?.bookForArticle(decoded.articleId,decoded.url)?.let{decoded.copy(articleId=it.articleId)}?:decoded
            database.withTransaction {
                val current = database.phoneArticleDao().getById(incoming.articleId)
                val merged = current?.mergeCloudState(incoming)
                    ?: LibrarySyncPayload.parseArticles(root).single().copy(imageUrl = incoming.imageUrl,
                        rssSourceUrl = incoming.rssSourceUrl, rssSourceTitle = incoming.rssSourceTitle)
                checkOwner()
                database.phoneArticleDao().upsert(repository.books?.constrainDeletion(merged) ?: merged)
            }
        }
    }

    internal suspend fun restorePrivateBook(value:JSONObject,bytes:ByteArray,checkOwner:()->Unit={}) {
        checkOwner();val id=value.getString("bookId")
        require(id==com.lightningstudio.watchrss.phone.data.book.BookSync.id(value.getString("fingerprint")) && value.getString("packagePath")=="books/$id.jsonl")
        val temp=File.createTempFile("book-object-",".jsonl",appContext.cacheDir)
        try{temp.writeBytes(bytes)
            val header=temp.bufferedReader().use {JSONObject(requireNotNull(it.readLine()))}
            require(com.lightningstudio.watchrss.phone.data.book.BookSync.integer(value,"chapterCount")==com.lightningstudio.watchrss.phone.data.book.BookSync.integer(header,"chapterCount"))
            requireNotNull(repository.books).receivePackage(id,temp,checkOwner)}finally{temp.delete()}
        checkOwner()
    }

    internal suspend fun bookRecordForLegacyArticle(value:JSONObject):Pair<JSONObject,JSONObject?>? {
        val store=repository.books?:return null
        if(!ImportedContentIds.isImportedContentUrl(value.optString("url")))return null
        val mapping=store.bookForArticle(value.getString("articleId"),value.optString("url"))?:return null
        val state=database.bookDao().get(mapping.bookId)?.let{JSONObject(it.stateJson)}?:return null
        val reading=database.bookDao().reading(mapping.bookId,mapping.chapterIndex)?.let{JSONObject(it.stateJson)}
        return state to reading
    }

    internal suspend fun canonicalCloudArticleId(value: JSONObject): String = repository.canonicalRssArticleId(value.getString("articleId"))
    internal suspend fun keepCloudArticle(value: JSONObject): Boolean =
        (database.phoneArticleDao().getById(canonicalCloudArticleId(value)) ?: database.phoneArticleDao().getById(value.getString("articleId")))?.let(CloudContentPolicy::privateBody) ?: false

    internal suspend fun applyRssInteraction(value: JSONObject, checkOwner: () -> Unit = {}) = applyRssInteractions(listOf(value),checkOwner)

    internal suspend fun applyRssInteractions(values: List<JSONObject>, checkOwner: () -> Unit = {}) {
        if (values.isEmpty()) return
        val aliases=repository.legacyRssArticleIds()
        val byIdentity = values.associateBy { it.getString("sourceId") + ":" + it.getString("articleIdHash") }
        val sources = mutableMapOf<String,String?>()
        var after = ""
        while (true) {
            checkOwner()
            val rows = database.phoneArticleDao().cloudRecordPage(after, 25)
            if (rows.isEmpty()) return
            for (row in rows) {
                val url = row.rssSourceUrl ?: continue
                if (CloudRssSourceIdentity.isLocalOnly(url)) continue
                val sourceId = if (sources.containsKey(url)) sources[url] else repository.publicRssSourceId(url).also { sources[url] = it }
                val key = runCatching { CloudRssSourceIdentity.sourceKey(url) }.getOrNull()
                val candidates=(listOf(row.articleId)+aliases[row.articleId].orEmpty()).flatMap { id ->
                    val suffix=":"+CloudSnapshotCodec.sha256(id.toByteArray())
                    listOfNotNull(byIdentity[key+suffix],byIdentity[sourceId+suffix])
                }
                val value=candidates.reduceOrNull(com.lightningstudio.watchrss.phone.cloud.CloudRssInteraction::merge) ?: continue
                // rss-state has an explicit interaction clock. Public discovery time must
                // never act as the legacy article-record read/unread fallback clock.
                database.withTransaction {
                    checkOwner()
                    val current=database.phoneArticleDao().getById(row.articleId) ?: return@withTransaction
                    val metadata=JSONObject(CloudRssStateCodec.encode(listOf(current),0).toString(Charsets.UTF_8)).getJSONArray("articles").getJSONObject(0)
                    val merged=com.lightningstudio.watchrss.phone.cloud.CloudRssInteraction.merge(
                        CloudContentPolicy.rssState(metadata,value.getString("sourceId")),value)
                    val updated=current.copy(
                        sourceDeviceId=merged.optString("sourceDeviceId",current.sourceDeviceId),
                        isRead=merged.getBoolean("isRead"),readingProgress=merged.getDouble("readingProgress").toFloat(),
                        readingPositionBytes=merged.getLong("readingPositionBytes"),
                        readingPositionContentHash=merged.getString("readingPositionContentHash"),
                        readingPositionChangedAt=merged.getLong("readingPositionChangedAt"),
                        favoriteSaved=merged.getBoolean("favoriteSaved"),favoriteChangedAt=merged.getLong("favoriteChangedAt"),favoriteSortOrder=merged.getLong("favoriteSortOrder"),
                        watchLaterSaved=merged.getBoolean("watchLaterSaved"),watchLaterChangedAt=merged.getLong("watchLaterChangedAt"),watchLaterSortOrder=merged.getLong("watchLaterSortOrder"),
                        deleted=merged.getBoolean("deleted"),deletedAt=merged.getLong("deletedAt"))
                    if (updated!=current) database.phoneArticleDao().upsert(updated)
                }
            }
            after = rows.last().articleId
        }
    }

    internal suspend fun restorePrivateReaderArticle(metadata: JSONObject, text: String, checkOwner: () -> Unit): CloudRestoreWarning? {
        checkOwner()
        val id=metadata.optString("articleId")
        if (metadata.optBoolean("deleted")) return null
        if (listOf("articleId","url","title","contentHash").any { !metadata.has(it) || metadata.isNull(it) || metadata.optString(it).isBlank() }) return CloudRestoreWarning(id,"missing_metadata",permanent=true)
        val value=JSONObject(metadata.toString())
        if (!value.has("independentSaved")) value.put("independentSaved",!CloudContentPolicy.publicRss(value))
        val incoming=LibrarySyncPayload.parseArticles(JSONObject().put("articles",JSONArray().put(value))).single().copy(contentText=text,contentHtml=null,
            rssSourceUrl=if (!value.has("rssSourceUrl") || value.isNull("rssSourceUrl")) null else value.getString("rssSourceUrl").takeIf { it.isNotBlank() },
            rssSourceTitle=if (!value.has("rssSourceTitle") || value.isNull("rssSourceTitle")) null else value.getString("rssSourceTitle").takeIf { it.isNotBlank() })
        val current=repository.getArticle(id)
        val restored=restoreCloudBody(incoming,current) ?: return CloudRestoreWarning(id,if (text.isBlank()) "missing_body" else "version_mismatch",permanent=text.isBlank())
        checkOwner(); repository.mergeArticlesFromBackup(listOf(restored))
        return null
    }

    /** Empty remote originals must never erase a valid local version. */
    private fun restoreCloudBody(body: com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity,
        current: com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity?): com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity? {
        if (body.contentText.isBlank() && body.contentHtml.isNullOrBlank()) return null
        if (body.deleted) return current
        if (current == null) return if (!CloudContentPolicy.publicRss(JSONObject().put("url",body.url).put("rssSourceUrl",body.rssSourceUrl)) && !body.independentSaved && !body.favoriteSaved && !body.watchLaterSaved) body.copy(independentSaved=true) else body
        val public=CloudContentPolicy.publicRss(JSONObject().put("url",body.url).put("rssSourceUrl",body.rssSourceUrl))
        if (!public && current.contentHash.isNotBlank() && current.contentHash != body.contentHash)
            return current.takeIf { it.contentText.isNotBlank() || !it.contentHtml.isNullOrBlank() }
        return current.copy(url=body.url,title=body.title,rssSourceUrl=body.rssSourceUrl,contentHash=body.contentHash,
            importedAt=body.importedAt,
            contentText=body.contentText.ifBlank { if (body.contentHash==current.contentHash) current.contentText else "" },
            contentHtml=body.contentHtml ?: current.contentHtml?.takeIf { body.contentHash==current.contentHash })
    }

    suspend fun restoreCloudRelayLibrary(
        payload: ByteArray,
        checkOwner: () -> Unit = {},
        beforeRow: suspend () -> Unit = {}
    ): Int = withContext(Dispatchers.IO) {
        var changed = 0
        CloudRelayStream.read(ByteArrayInputStream(payload), article = { article ->
            beforeRow(); checkOwner()
            changed += repository.mergeArticlesFromSync(listOf(article))
        }, source = { source ->
            beforeRow(); checkOwner()
            changed += repository.mergeRssSourcesFromSync(listOf(source))
        })
        changed
    }

    suspend fun inspectCloudPayload(payload: CloudBackupPayload): CloudRestorePreview =
        withContext(Dispatchers.IO) {
            val privateSnapshot = WatchRssBackupArchive.read(
                ByteArrayInputStream(payload.privateLibraryArchive)
            )
            try { CloudRestorePreview(
                sourceCount = privateSnapshot.sources.size,
                privateArticleCount = privateSnapshot.articles.size,
                rssStateCount = CloudRssStateCodec.decode(payload.rssStateJson).size,
                exportedAt = privateSnapshot.exportedAt
            ) } finally { privateSnapshot.bookPackages.forEach {it.file.delete()} }
        }

    fun inspectCloudRssState(payload: ByteArray): Pair<Int, Int> =
        CloudRssStateCodec.decodeSources(payload).size to CloudRssStateCodec.decode(payload).size

    fun inspectCloudPrivateLibrary(payload: ByteArray): BackupPreview =
        WatchRssBackupArchive.read(ByteArrayInputStream(payload),tolerateCloudEntries=true).let{snapshot->try{snapshot.preview()}finally{snapshot.bookPackages.forEach{it.file.delete()}}}

    fun inspectCloudRelayLibrary(payload: ByteArray): Pair<Int, Int> {
        val json = org.json.JSONObject(payload.toString(Charsets.UTF_8))
        return LibrarySyncPayload.parseRssSources(json).size to
            LibrarySyncPayload.parseArticles(json).size
    }

    suspend fun restoreCloudPayload(
        payload: CloudBackupPayload,
        mode: BackupImportMode = BackupImportMode.MERGE
    ): CloudRestoreResult = withContext(Dispatchers.IO) {
        val privateImport = restorePrivateLibrary(payload.privateLibraryArchive, mode)
        val (applied, pending) = applyCloudRssState(payload.rssStateJson)
        CloudRestoreResult(
            privateImport = privateImport,
            appliedRssStates = applied,
            pendingRssStates = pending
        )
    }

    suspend fun restorePrivateLibrary(
        archive: ByteArray,
        mode: BackupImportMode = BackupImportMode.MERGE,
        excludedCloudArticles:Set<String> = emptySet(),
        excludedCloudBooks:Set<String> = emptySet(),
        checkOwner: () -> Unit = {}
    ): BackupImportResult = withContext(Dispatchers.IO) {
        if (mode == BackupImportMode.REPLACE) createSafetySnapshot()
        importFrom(
            ByteArrayInputStream(archive),
            mode,
            resetSyncHistory = false,
            cloudRestore = true,
            checkOwner = checkOwner,
            excludedCloudArticles=excludedCloudArticles, excludedCloudBooks=excludedCloudBooks
        )
    }

    suspend fun applyCloudRssState(
        stateJson: ByteArray,
        restoreAsNewChange: Boolean = false,
        createMissing: Boolean = false,
        checkOwner: () -> Unit = {}
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val states = CloudRssStateCodec.decode(stateJson).filterNot { it.rssSourceUrl?.let(CloudRssSourceIdentity::isLocalOnly) == true }
        val sources = CloudRssStateCodec.decodeSources(stateJson)
        val restoredArticles = if (restoreAsNewChange) LibrarySyncPayload.parseArticles(JSONObject(stateJson.toString(Charsets.UTF_8))).associateBy { it.articleId } else emptyMap()
        val currentSources = if (restoreAsNewChange) database.phoneRssSourceDao().getAllForSync().associateBy { it.url } else emptyMap()
        repository.mergeRssSourcesFromSync(sources.map { incoming ->
            if (!restoreAsNewChange) incoming else {
                val current = currentSources[incoming.url]
                val clock = maxOf(System.currentTimeMillis(), incoming.updatedAt, incoming.deletedAt,
                    current?.updatedAt ?: 0L, current?.deletedAt ?: 0L) + 1
                incoming.copy(updatedAt = clock, deletedAt = clock).also {
                    it.syncedSettingsIncluded = incoming.syncedSettingsIncluded
                }
            }
        })
        var applied = 0
        database.withTransaction {
            checkOwner()
            states.forEach { original ->
                val current = database.phoneArticleDao().getById(original.articleId)
                    ?: restoredArticles[original.articleId]
                    ?: if (createMissing) original.toMetadataArticle() else null
                    ?: return@forEach
                val incoming = if (!restoreAsNewChange) original else {
                    val clock = listOf(System.currentTimeMillis(), original.updatedAt,
                        original.favoriteChangedAt, original.watchLaterChangedAt, original.independentChangedAt,
                        original.readingPositionChangedAt, original.deletedAt, current.updatedAt,
                        current.favoriteChangedAt, current.watchLaterChangedAt, current.independentChangedAt,
                        current.readingPositionChangedAt, current.deletedAt).max() + 1
                    original.copy(updatedAt = clock, favoriteChangedAt = clock, watchLaterChangedAt = clock,
                        independentChangedAt = clock, readingPositionChangedAt = clock, deletedAt = clock)
                }
                val merged = current.mergeCloudState(incoming)
                database.phoneArticleDao().upsert(if (restoreAsNewChange) merged.copy(isRead = incoming.isRead) else merged)
                applied += 1
            }
            checkOwner()
        }
        applied to (states.size - applied)
    }

    suspend fun createSafetySnapshot(): File = withContext(Dispatchers.IO) {
        val directory = File(appContext.filesDir, "cloud-safety").apply { mkdirs() }
        SafetySnapshotRetention.prune(directory)
        val outputFile = File(directory, "before-restore-${System.currentTimeMillis()}.wrss")
        val temporary = File(directory, "${outputFile.name}.part")
        try {
            temporary.outputStream().use { exportTo(it) }
            SafetySnapshotRetention.commit(temporary, outputFile)
        } finally {
            temporary.delete()
        }
        SafetySnapshotRetention.prune(directory)
        outputFile
    }

    suspend fun inspect(uriString: String): BackupPreview = withContext(Dispatchers.IO) {
        val uri = parseUri(uriString)
        val input = contentResolver.openInputStream(uri)
            ?: error("无法读取 WRSS 文件")
        input.use(::inspect)
    }

    fun inspect(input: InputStream): BackupPreview = WatchRssBackupArchive.read(input).let{snapshot->try{snapshot.preview()}finally{snapshot.bookPackages.forEach{it.file.delete()}}}

    suspend fun importFrom(uriString: String, mode: BackupImportMode): BackupImportResult =
        withContext(Dispatchers.IO) {
            val uri = parseUri(uriString)
            val input = contentResolver.openInputStream(uri)
                ?: error("无法读取 WRSS 文件")
            input.use { importFrom(it, mode) }
        }

    suspend fun importFrom(
        input: InputStream,
        mode: BackupImportMode,
        resetSyncHistory: Boolean = true,
        cloudRestore: Boolean = false,
        excludedCloudArticles:Set<String> = emptySet(),
        excludedCloudBooks:Set<String> = emptySet(),
        checkOwner: () -> Unit = {}
    ): BackupImportResult =
        withContext(Dispatchers.IO) {
            val snapshot = WatchRssBackupArchive.read(
                input,
                readerPresetRepository.resourceStore,
                tolerateCloudEntries=cloudRestore
            )
            try {
            val warnings=snapshot.readWarnings.toMutableList()
            val restoredArticles = if (!snapshot.cloudContentOnly && !cloudRestore) snapshot.articles else snapshot.articles.mapNotNull { body ->
                if (body.deleted || body.articleId in excludedCloudArticles) return@mapNotNull null
                if (body.url.isBlank() || body.title.isBlank() || body.contentHash.isBlank()) {
                    warnings += CloudRestoreWarning(body.articleId,"missing_metadata",permanent=true); return@mapNotNull null
                }
                val current=repository.getArticle(body.articleId)
                val restored=restoreCloudBody(body,current)
                if (restored == null) warnings += CloudRestoreWarning(body.articleId,if (body.contentText.isBlank() && body.contentHtml.isNullOrBlank()) "missing_body" else "version_mismatch",permanent=body.contentText.isBlank() && body.contentHtml.isNullOrBlank())
                restored
            }
            val normalizedSources = snapshot.sources.map { source ->
                source.copy(sourceDeviceId = deviceId)
            }
            var changedSourceCount = 0
            var changedArticleCount = 0
            var changedSavedItemCount = 0
            var changedAppMetaCount = 0

            database.withTransaction {
                checkOwner()
                when (mode) {
                    BackupImportMode.REPLACE -> {
                        database.phoneRssSourceDao().deleteAll()
                        database.phoneSavedItemDao().deleteAll()
                        val sourceMappings = database.appMetaDao().getAll().filter { it.key.startsWith("public_rss_source:") }
                        database.appMetaDao().deleteAll()
                        sourceMappings.forEach { database.appMetaDao().set(it) }
                        if (normalizedSources.isNotEmpty()) {
                            database.phoneRssSourceDao().upsertAll(normalizedSources)
                        }
                        changedSourceCount = normalizedSources.size
                        changedArticleCount = if (warnings.isEmpty()) repository.replaceArticlesFromBackup(restoredArticles) else repository.mergeArticlesFromBackup(restoredArticles)
                        if (snapshot.savedItems.isNotEmpty()) {
                            database.phoneSavedItemDao().upsertAll(snapshot.savedItems)
                        }
                        changedSavedItemCount = snapshot.savedItems.size
                        if (snapshot.appMeta.isNotEmpty()) {
                            snapshot.appMeta.forEach { database.appMetaDao().set(it) }
                        }
                        changedAppMetaCount = snapshot.appMeta.size
                    }

                    BackupImportMode.MERGE -> {
                        changedSourceCount = mergeSources(normalizedSources)
                        changedArticleCount = repository.mergeArticlesFromBackup(restoredArticles)
                        changedSavedItemCount = mergeSavedItems(snapshot.savedItems)
                        // 对于元数据，合并模式使用 setIfAbsent，保留本地已有的值
                        snapshot.appMeta.forEach { meta ->
                            database.appMetaDao().setIfAbsent(meta)
                            changedAppMetaCount++
                        }
                    }
                }
                checkOwner()
                if (resetSyncHistory) {
                    database.syncChangeLogDao().deleteAll()
                    database.syncPeerStateDao().deleteAll()
                }
            }
            snapshot.readerSnapshot?.let { readerPresetRepository.mergeRemote(
                presets = it.presets,
                fonts = it.fonts,
                backgrounds = it.backgrounds,
                deletions = it.deletions
            ) }
            for(book in snapshot.bookPackages.filter{it.metadata.getString("bookId") !in excludedCloudBooks})try {
                checkOwner()
                val header=book.file.bufferedReader().use{JSONObject(requireNotNull(it.readLine()))}
                require(com.lightningstudio.watchrss.phone.data.book.BookSync.integer(book.metadata,"chapterCount")==com.lightningstudio.watchrss.phone.data.book.BookSync.integer(header,"chapterCount"))
                changedArticleCount+=requireNotNull(repository.books).receivePackage(book.metadata.getString("bookId"),book.file,checkOwner)
            }catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(e:IllegalArgumentException){checkOwner();warnings+=CloudRestoreWarning(book.metadata.getString("bookId"),"invalid_book_package",permanent=true)}
            catch(e:org.json.JSONException){checkOwner();warnings+=CloudRestoreWarning(book.metadata.getString("bookId"),"invalid_book_package",permanent=true)}
            finally {book.file.delete()}
            repository.books?.ensureRegistered()
            repository.pruneUnreferencedArticleContent()

            BackupImportResult(
                mode = mode,
                sourceCount = snapshot.sources.size,
                articleCount = snapshot.articles.size,
                savedItemCount = snapshot.savedItems.size,
                appMetaCount = snapshot.appMeta.size,
                changedSourceCount = changedSourceCount,
                changedArticleCount = changedArticleCount,
                changedSavedItemCount = changedSavedItemCount,
                changedAppMetaCount = changedAppMetaCount,
                warnings = warnings
            )
            } finally {snapshot.bookPackages.forEach{it.file.delete()}}
        }

    private suspend fun readerResources(
        snapshot: com.lightningstudio.watchrss.phone.data.reader.ReaderPresetSnapshot
    ): List<ReaderBackupResource> = buildList {
        snapshot.fonts.filterNot { it.deleted }.forEach { font ->
            readerPresetRepository.resourceStore.fontFile(font.fileName)?.let { file ->
                add(ReaderBackupResource("font", font.fileName, font.sha256, font.byteCount, file))
            }
        }
        snapshot.backgrounds.filterNot { it.deleted }.forEach { background ->
            readerPresetRepository.resourceStore.backgroundFile(background.masterFileName)?.let { file ->
                add(
                    ReaderBackupResource(
                        "background",
                        background.masterFileName,
                        background.sha256,
                        background.byteCount,
                        file
                    )
                )
            }
            val variants = runCatching { org.json.JSONObject(background.variantsJson) }.getOrNull()
            listOf("watch", "watchPoster").forEach { key ->
                variants?.optJSONObject(key)?.let { variant ->
                    val fileName = variant.optString("fileName")
                    val hash = variant.optString("sha256")
                    val byteCount = variant.optLong("byteCount")
                    readerPresetRepository.resourceStore.variantFile(fileName)?.let { file ->
                        if (hash.length == 64 && file.length() == byteCount) {
                            add(ReaderBackupResource("variant", fileName, hash, byteCount, file))
                        }
                    }
                }
            }
        }
    }

    // ── 人类可读格式导出（JSON）──

    suspend fun exportHumanReadable(uriString: String): BackupSummary = withContext(Dispatchers.IO) {
        val uri = parseUri(uriString)
        val output = contentResolver.openOutputStream(uri, "w")
            ?: error("无法创建导出文件")
        output.use { exportHumanReadable(it) }
    }

    suspend fun exportHumanReadable(output: OutputStream): BackupSummary = withContext(Dispatchers.IO) {
        val snapshot = collectSnapshot()
        val json = buildHumanReadableJson(snapshot)
        output.write(json.toString(2).toByteArray(Charsets.UTF_8))
        snapshot.summary()
    }

    // ── 内部方法 ──

    private suspend fun collectSnapshot(): WatchRssBackupSnapshot {
        val readerSnapshot = readerPresetRepository.exportSnapshot()
        return WatchRssBackupSnapshot(
            exportedAt = System.currentTimeMillis(),
            appVersion = currentAppVersion(),
            dataStructureVersion = WatchRssBackupArchive.CURRENT_DATA_STRUCTURE_VERSION,
            sources = database.phoneRssSourceDao().getAllForSync(),
            articles = repository.getArticlesForBackup(),
            savedItems = database.phoneSavedItemDao().getAll(),
            readerSnapshot = readerSnapshot,
            readerResources = readerResources(readerSnapshot),
            appMeta = database.appMetaDao().getAll()
        )
    }

    private fun buildHumanReadableJson(snapshot: WatchRssBackupSnapshot): JSONObject {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        return JSONObject().apply {
            put("format", "watchrss-human-readable-export")
            put("exportedAt", dateFormat.format(Date(snapshot.exportedAt)))
            put("appVersion", snapshot.appVersion)
            put("dataStructureVersion", snapshot.dataStructureVersion)

            // 元数据
            val metaArray = JSONArray()
            snapshot.appMeta.forEach { meta ->
                metaArray.put(JSONObject().apply {
                    put("key", meta.key)
                    put("value", meta.value)
                    if (meta.key == "first_use_at") {
                        meta.value.toLongOrNull()?.let {
                            put("valueReadable", dateFormat.format(Date(it)))
                        }
                    }
                })
            }
            put("appMeta", metaArray)

            // RSS 源
            val sourcesArray = JSONArray()
            snapshot.sources.forEach { source ->
                sourcesArray.put(JSONObject().apply {
                    put("title", source.title)
                    put("url", source.url)
                    put("description", source.description)
                    putNullable("siteUrl", source.siteUrl)
                    putNullable("imageUrl", source.imageUrl)
                    put("isPinned", source.isPinned)
                    put("createdAt", dateFormat.format(Date(source.createdAt)))
                    put("articleCount", snapshot.articles.count { it.rssSourceUrl == source.url })
                })
            }
            put("rssSources", sourcesArray)

            // 收藏
            val favoritesArray = JSONArray()
            snapshot.articles.filter { it.favoriteSaved && !it.deleted }.forEach { article ->
                favoritesArray.put(articleToHumanReadableJson(article, dateFormat))
            }
            put("favorites", favoritesArray)

            // 稍后阅读
            val watchLaterArray = JSONArray()
            snapshot.articles.filter { it.watchLaterSaved && !it.deleted }.forEach { article ->
                watchLaterArray.put(articleToHumanReadableJson(article, dateFormat))
            }
            put("watchLater", watchLaterArray)

            // 独立文章（非 RSS、非收藏、非稍后阅读）
            val independentArray = JSONArray()
            snapshot.articles.filter {
                it.independentSaved && !it.deleted &&
                    !it.favoriteSaved && !it.watchLaterSaved
            }.forEach { article ->
                independentArray.put(articleToHumanReadableJson(article, dateFormat))
            }
            put("independentArticles", independentArray)

            // 全部文章（不含正文，仅元数据摘要）
            val allArticlesArray = JSONArray()
            snapshot.articles.filter { !it.deleted }.forEach { article ->
                allArticlesArray.put(JSONObject().apply {
                    put("title", article.title)
                    put("url", article.url)
                    put("siteName", article.siteName)
                    put("excerpt", article.excerpt)
                    putNullable("imageUrl", article.imageUrl)
                    put("importedAt", dateFormat.format(Date(article.importedAt)))
                    put("isFavorite", article.favoriteSaved)
                    put("isWatchLater", article.watchLaterSaved)
                    put("readingProgress", (article.readingProgress * 100).toInt())
                    put("contentLength", article.contentText.length)
                })
            }
            put("allArticles", allArticlesArray)

            // 保存项（从手表同步的收藏/稍后阅读）
            val savedItemsArray = JSONArray()
            snapshot.savedItems.forEach { item ->
                savedItemsArray.put(JSONObject().apply {
                    put("type", item.type)
                    put("title", item.title)
                    put("link", item.link)
                    put("summary", item.summary)
                    put("channelTitle", item.channelTitle)
                    put("pubDate", item.pubDate)
                    put("syncedAt", dateFormat.format(Date(item.syncedAt)))
                })
            }
            put("savedItems", savedItemsArray)

            // 统计信息
            put("statistics", JSONObject().apply {
                put("totalSources", snapshot.sources.size)
                put("totalArticles", snapshot.articles.count { !it.deleted })
                put("totalFavorites", snapshot.articles.count { it.favoriteSaved && !it.deleted })
                put("totalWatchLater", snapshot.articles.count { it.watchLaterSaved && !it.deleted })
                put("totalSavedItems", snapshot.savedItems.size)
            })
        }
    }

    private fun articleToHumanReadableJson(
        article: com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity,
        dateFormat: SimpleDateFormat
    ): JSONObject = JSONObject().apply {
        put("title", article.title)
        put("url", article.url)
        put("siteName", article.siteName)
        put("excerpt", article.excerpt)
        putNullable("imageUrl", article.imageUrl)
        put("importedAt", dateFormat.format(Date(article.importedAt)))
        put("contentText", article.contentText)
        article.contentHtml?.let { put("contentHtml", it) }
        put("readingProgress", (article.readingProgress * 100).toInt())
    }

    private suspend fun mergeSources(incoming: List<PhoneRssSourceEntity>): Int {
        var changed = 0
        incoming.forEach { backup ->
            val current = database.phoneRssSourceDao().getByUrl(backup.url)
            val backupChangedAt = maxOf(backup.updatedAt, backup.deletedAt)
            val currentChangedAt = current?.let { maxOf(it.updatedAt, it.deletedAt) } ?: Long.MIN_VALUE
            if (current == null || backupChangedAt > currentChangedAt) {
                database.phoneRssSourceDao().upsert(backup)
                changed += 1
            }
        }
        return changed
    }

    private suspend fun mergeSavedItems(incoming: List<PhoneSavedItemEntity>): Int {
        val currentByKey = database.phoneSavedItemDao().getAll()
            .associateBy { it.type to it.stableKey }
        val changed = incoming.filter { backup ->
            val current = currentByKey[backup.type to backup.stableKey]
            current == null || backup.syncedAt > current.syncedAt
        }
        if (changed.isNotEmpty()) {
            database.phoneSavedItemDao().upsertAll(changed)
        }
        return changed.size
    }

    private fun parseUri(uriString: String): Uri {
        require(uriString.isNotBlank()) { "文件地址无效" }
        return Uri.parse(uriString)
    }

    private fun currentAppVersion(): String {
        return runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
    }

    private fun JSONObject.putNullable(name: String, value: String?) {
        put(name, value ?: JSONObject.NULL)
    }
}
