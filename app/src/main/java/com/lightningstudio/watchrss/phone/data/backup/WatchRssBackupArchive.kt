package com.lightningstudio.watchrss.phone.data.backup

import com.lightningstudio.watchrss.phone.data.db.AppMetaEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneSavedItemEntity
import com.lightningstudio.watchrss.phone.connection.bluetooth.ReaderPresetSyncPayload
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetSnapshot
import com.lightningstudio.watchrss.phone.data.reader.ReaderResourceStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal object WatchRssBackupArchive {
    private const val FORMAT = "watchrss-library-backup"

    /**
     * 备份数据结构版本。每次备份数据结构发生变化时递增。
     * - v1: 初始版本（sources, articles, saved_items, bodies）
     * - v2: 历史分支分别加入阅读器资源或 app_meta
     * - v3: 同时包含阅读器资源与 app_meta
     */
    const val CURRENT_DATA_STRUCTURE_VERSION = 3

    /** 最低支持的备份版本（含），低于此版本的备份文件将被拒绝。 */
    private const val MIN_SUPPORTED_VERSION = 1
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val SOURCES_ENTRY = "sources.json"
    private const val ARTICLES_ENTRY = "articles.json"
    private const val SAVED_ITEMS_ENTRY = "saved_items.json"
    private const val READER_ENTRY = "reader/manifest.json"
    private const val APP_META_ENTRY = "app_meta.json"
    private const val MAX_ENTRY_COUNT = 100_000
    private const val MAX_ENTRY_BYTES = 512L * 1024L * 1024L
    private const val MAX_EXPANDED_BYTES = 4L * 1024L * 1024L * 1024L
    private val BOOK_ENTRY_PATTERN=Regex("books/[0-9a-f]{64}\\.jsonl")
    private val BODY_ENTRY_PATTERN = Regex("""bodies/[0-9a-f]{64}\.(html|txt)""")
    private val READER_RESOURCE_PATTERN =
        Regex("""reader/(fonts|backgrounds|variants)/[0-9a-f]{64}\.bin""")

    /** 所有版本都必须包含的条目。 */
    private val REQUIRED_METADATA_ENTRIES = setOf(
        MANIFEST_ENTRY,
        SOURCES_ENTRY,
        ARTICLES_ENTRY,
        SAVED_ITEMS_ENTRY
    )

    /** v2+ 必须包含、v1 中可能缺失的条目。 */
    private val OPTIONAL_METADATA_ENTRIES = setOf(APP_META_ENTRY, READER_ENTRY,"books.json")

    /** 所有已知的元数据条目名（用于条目名白名单校验）。 */
    private val ALL_KNOWN_METADATA_ENTRIES = REQUIRED_METADATA_ENTRIES + OPTIONAL_METADATA_ENTRIES

    fun write(snapshot: WatchRssBackupSnapshot, output: OutputStream) {
        validateSnapshot(snapshot)
        val articleMetadata = JSONArray()
        snapshot.articles.forEach { article ->
            val textEntry = bodyTextEntry(article.articleId)
            val htmlEntry = article.contentHtml?.let { bodyHtmlEntry(article.articleId) }
            articleMetadata.put(article.toBackupJson(textEntry, htmlEntry))
        }

        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            zip.writeUtf8Entry(MANIFEST_ENTRY, snapshot.manifestJson().toString())
            zip.writeUtf8Entry(SOURCES_ENTRY, snapshot.sources.toJsonArray { it.toBackupJson() }.toString())
            zip.writeUtf8Entry(SAVED_ITEMS_ENTRY, snapshot.savedItems.toJsonArray { it.toBackupJson() }.toString())
            zip.writeUtf8Entry(ARTICLES_ENTRY, articleMetadata.toString())
            snapshot.readerSnapshot?.let {
                val reader = ReaderPresetSyncPayload.snapshotJson(it)
                zip.writeUtf8Entry(READER_ENTRY, if (snapshot.cloudContentOnly) com.lightningstudio.watchrss.phone.cloud.PhoneCloudRecordCodec.canonical(reader) else reader.toString())
            }
            zip.writeUtf8Entry(APP_META_ENTRY, snapshot.appMeta.toJsonArray { it.toBackupJson() }.toString())
            snapshot.articles.forEach { article ->
                zip.writeUtf8Entry(bodyTextEntry(article.articleId), article.contentText)
                article.contentHtml?.let { html ->
                    zip.writeUtf8Entry(bodyHtmlEntry(article.articleId), html)
                }
            }
            if(snapshot.bookPackages.isNotEmpty()) {
                zip.writeUtf8Entry("books.json",com.lightningstudio.watchrss.phone.cloud.PhoneCloudRecordCodec.canonical(JSONArray(snapshot.bookPackages.sortedBy{it.metadata.getString("bookId")}.map{it.metadata})))
                snapshot.bookPackages.sortedBy{it.metadata.getString("bookId")}.forEach {zip.writeFileEntry(it.metadata.getString("packagePath"),it.file)}
            }
            snapshot.readerResources.sortedWith(compareBy({ it.kind }, { it.fileName })).forEach { resource ->
                require(resource.file.isFile && resource.file.length() == resource.byteCount) {
                    "阅读器备份资源无效：${resource.fileName}"
                }
                zip.writeFileEntry(resource.entryName(), resource.file)
            }
        }
    }

    fun read(
        input: InputStream,
        readerResourceStore: ReaderResourceStore? = null,
        tolerateCloudEntries: Boolean = false
    ): WatchRssBackupSnapshot {
        val (entries,bookFiles) = runCatching { readEntries(input,tolerateCloudEntries) }
            .getOrElse { throwable ->
                if (throwable is IllegalArgumentException) throw throwable
                throw IllegalArgumentException("WRSS 文件损坏或无法读取", throwable)
            }
        return runCatching { parseEntries(entries, readerResourceStore, tolerateCloudEntries,bookFiles) }
            .getOrElse { throwable ->
                bookFiles.values.forEach {it.delete()}
                if (throwable is IllegalArgumentException) throw throwable
                if (throwable is BackupVersionTooHighException) throw throwable
                throw IllegalArgumentException("WRSS 数据格式无效", throwable)
            }
    }

    private fun isKnownEntry(name:String)=name in ALL_KNOWN_METADATA_ENTRIES||BODY_ENTRY_PATTERN.matches(name)||READER_RESOURCE_PATTERN.matches(name)||BOOK_ENTRY_PATTERN.matches(name)

    /** A real edit to a legacy WRSS object may replace known entries, never future entries. */
    internal fun preserveUnknownEntries(original:ByteArray,replacement:File) {
        val temporary=File.createTempFile("wrss-compatible-",".part",replacement.parentFile)
        val seen=hashSetOf<String>();var total=0L
        fun copy(zip:ZipInputStream,out:ZipOutputStream,entry:ZipEntry,emit:Boolean) {
            require(isSafeEntryName(entry.name)&&!entry.isDirectory)
            if(emit){require(seen.add(entry.name)&&seen.size<=MAX_ENTRY_COUNT);out.putNextEntry(ZipEntry(entry.name).apply{time=0})}
            val buffer=ByteArray(8192);var length=0L
            while(true){val count=zip.read(buffer);if(count<0)break;length+=count;total+=count
                require(length<=MAX_ENTRY_BYTES&&total<=MAX_EXPANDED_BYTES);if(emit)out.write(buffer,0,count)}
            zip.closeEntry();if(emit)out.closeEntry()
        }
        try {
            ZipOutputStream(temporary.outputStream().buffered()).use{out->
                ZipInputStream(replacement.inputStream().buffered()).use{zip->while(true){val e=zip.nextEntry?:break;copy(zip,out,e,true)}}
                ZipInputStream(original.inputStream()).use{zip->val originalSeen=hashSetOf<String>();while(true){val e=zip.nextEntry?:break;require(originalSeen.add(e.name))
                    if(!isKnownEntry(e.name)&&e.name in seen)throw com.lightningstudio.watchrss.phone.cloud.CloudFormatUpgradeRequired("archive_unknown_entry_conflict")
                    copy(zip,out,e,!isKnownEntry(e.name))}}
            }
            java.nio.file.Files.move(temporary.toPath(),replacement.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }finally{temporary.delete()}
    }

    private fun readEntries(input: InputStream, allowUnknown:Boolean=false): Pair<Map<String, ByteArray>,Map<String,File>> {
        val bookFiles=linkedMapOf<String,File>()
        val entries = linkedMapOf<String, ByteArray>()
        var expandedBytes = 0L
        val seen=hashSetOf<String>()
        try { ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory) { "WRSS 中不允许目录条目" }
                val name = entry.name
                require(isSafeEntryName(name)) { "WRSS 包含不安全路径：$name" }
                val known=isKnownEntry(name)
                require(known || allowUnknown) {
                    "WRSS 包含未知条目：$name"
                }
                require(seen.add(name)) { "WRSS 包含重复条目：$name" }
                require(seen.size < MAX_ENTRY_COUNT) { "WRSS 条目数量过多" }

                val bookFile=if(BOOK_ENTRY_PATTERN.matches(name))File.createTempFile("wrss-book-",".jsonl").also{bookFiles[name]=it} else null
                val output:OutputStream=if(!known)object:OutputStream(){override fun write(b:Int)=Unit;override fun write(b:ByteArray,off:Int,len:Int)=Unit}else bookFile?.outputStream()?:ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var entryBytes = 0L
                output.use { while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    entryBytes += read
                    expandedBytes += read
                    require(entryBytes <= MAX_ENTRY_BYTES) { "WRSS 单个条目过大：$name" }
                    require(expandedBytes <= MAX_EXPANDED_BYTES) { "WRSS 解压后内容过大" }
                    output.write(buffer, 0, read)
                } }
                if(output is ByteArrayOutputStream)entries[name] = output.toByteArray()
                zip.closeEntry()
            }
        }
        } catch(error:Throwable) {bookFiles.values.forEach{it.delete()};throw error}
        require(entries.isNotEmpty()) { "不是有效的 WRSS 备份" }
        return entries to bookFiles
    }

    private fun parseEntries(
        entries: Map<String, ByteArray>,
        readerResourceStore: ReaderResourceStore?,
        tolerateCloudEntries: Boolean,
        bookFiles:Map<String,File>
    ): WatchRssBackupSnapshot {
        REQUIRED_METADATA_ENTRIES.forEach { name ->
            require(name in entries) { "WRSS 缺少必要条目：$name" }
        }
        val manifest = JSONObject(entries.getValue(MANIFEST_ENTRY).toString(Charsets.UTF_8))
        require(manifest.getString("format") == FORMAT) { "不是腕上RSS 资料库备份" }
        val version = manifest.getInt("version")
        require(version >= MIN_SUPPORTED_VERSION) { "WRSS 备份版本无效：$version" }
        if (version > CURRENT_DATA_STRUCTURE_VERSION) {
            throw BackupVersionTooHighException(
                backupVersion = version,
                currentVersion = CURRENT_DATA_STRUCTURE_VERSION
            )
        }

        val sources = JSONArray(entries.getValue(SOURCES_ENTRY).toString(Charsets.UTF_8))
            .mapObjects(::sourceFromBackupJson)
        val savedItems = JSONArray(entries.getValue(SAVED_ITEMS_ENTRY).toString(Charsets.UTF_8))
            .mapObjects(::savedItemFromBackupJson)
        val expectedBodyEntries = linkedSetOf<String>()
        val cloudContentOnly=manifest.optBoolean("cloudContentOnly")
        val tolerateRows=cloudContentOnly || tolerateCloudEntries
        val readWarnings=mutableListOf<CloudRestoreWarning>()
        val articleRows=JSONArray(entries.getValue(ARTICLES_ENTRY).toString(Charsets.UTF_8))
        val articles=buildList {
            for (index in 0 until articleRows.length()) {
                var articleId=""
                try {
                    val json=articleRows.getJSONObject(index)
                    articleId=if (json.isNull("articleId")) "" else json.optString("articleId")
                    if (tolerateRows) require(listOf("articleId","url","title","contentHash").all { json.has(it) && !json.isNull(it) }) { "missing_private_metadata" }
                    val textEntry=json.getString("contentTextEntry")
                    val htmlEntry=json.nullableString("contentHtmlEntry")
                    require(textEntry==bodyTextEntry(articleId)) { "WRSS 文章正文路径不匹配" }
                    require(htmlEntry==null || htmlEntry==bodyHtmlEntry(articleId)) { "WRSS 文章 HTML 路径不匹配" }
                    expectedBodyEntries += textEntry; htmlEntry?.let(expectedBodyEntries::add)
                    val text=entries[textEntry]?.toString(Charsets.UTF_8)
                    val html=htmlEntry?.let { entries[it]?.toString(Charsets.UTF_8) }
                    if (!tolerateRows) require(text!=null && (htmlEntry==null || html!=null)) { "WRSS 缺少文章正文" }
                    if (tolerateRows && (text==null || (htmlEntry!=null && html==null))) {
                        readWarnings += CloudRestoreWarning(articleId,"missing_body",permanent=true)
                        continue
                    }
                    add(articleFromBackupJson(json,html,requireNotNull(text)))
                } catch (error: Exception) {
                    if (!tolerateRows || (error !is IllegalArgumentException && error !is org.json.JSONException)) throw error
                    // Paths remain constrained by article identity even for a quarantined metadata row.
                    if (articleId.isNotBlank()) {
                        expectedBodyEntries += bodyTextEntry(articleId)
                        expectedBodyEntries += bodyHtmlEntry(articleId)
                    }
                    readWarnings += CloudRestoreWarning(articleId.ifBlank { "articles.json#$index" },"invalid_metadata",permanent=true)
                }
            }
        }

        // v2+ 备份包含 app_meta.json；v1 备份可能没有，此时视为空列表
        val appMeta = entries[APP_META_ENTRY]
            ?.let { JSONArray(it.toString(Charsets.UTF_8)).mapObjects(::appMetaFromBackupJson) }
            ?: emptyList()

        val actualBodyEntries = entries.keys.filterTo(linkedSetOf()) { BODY_ENTRY_PATTERN.matches(it) }
        if (tolerateRows) {
            // An unidentifiable metadata row may leave a safe, hashed body path orphaned.
            // Quarantine that body instead of discarding all healthy rows in the archive.
            (actualBodyEntries-expectedBodyEntries).forEach { name ->
                readWarnings += CloudRestoreWarning(name,"missing_metadata",permanent=true)
            }
        } else require(actualBodyEntries == expectedBodyEntries) { "WRSS 包含未引用或缺失的正文条目" }
        require(manifest.getInt("sourceCount") == sources.size) { "WRSS 的 RSS 源数量不匹配" }
        require(manifest.getInt("articleCount") == articleRows.length()) { "WRSS 的文章数量不匹配" }
        require(manifest.getInt("savedItemCount") == savedItems.size) { "WRSS 的保存项数量不匹配" }
        if (version >= CURRENT_DATA_STRUCTURE_VERSION) {
            require(manifest.getInt("appMetaCount") == appMeta.size) { "WRSS 的元数据条目数量不匹配" }
        }

        val readerSnapshot = entries[READER_ENTRY]?.let { readerEntry ->
            val readerJson = readerEntry.toString(Charsets.UTF_8)
            val json = JSONObject(readerJson)
            ReaderPresetSnapshot(
                presets = ReaderPresetSyncPayload.parsePresets(json.optJSONArray("presets")),
                fonts = ReaderPresetSyncPayload.parseFonts(json.optJSONArray("fonts")),
                backgrounds = ReaderPresetSyncPayload.parseBackgrounds(json.optJSONArray("backgrounds")),
                deletions = ReaderPresetSyncPayload.parseDeletions(json.optJSONArray("deletions"))
            ).also { reader ->
                validateAndInstallReaderResources(entries, reader, readerResourceStore)
            }
        }

        val books=mutableListOf<BookArchiveContent>()
        val seenBooks=hashSetOf<String>()
        val bookRows=entries["books.json"]?.let{JSONArray(it.toString(Charsets.UTF_8))}?:JSONArray()
        for(i in 0 until bookRows.length()) {
            val row=bookRows.optJSONObject(i)
            try {
                requireNotNull(row);val id=row.getString("bookId")
                require(id==com.lightningstudio.watchrss.phone.data.book.BookSync.id(row.getString("fingerprint")) && id.matches(Regex("[0-9a-f]{64}")))
                require(row.getString("packagePath")=="books/$id.jsonl" && seenBooks.add(id))
                val packageFile=requireNotNull(bookFiles[row.getString("packagePath")])
                val header=packageFile.bufferedReader().use{JSONObject(requireNotNull(it.readLine()))}
                require(com.lightningstudio.watchrss.phone.data.book.BookSync.integer(row,"chapterCount")==com.lightningstudio.watchrss.phone.data.book.BookSync.integer(header,"chapterCount"))
                books+=BookArchiveContent(row,requireNotNull(bookFiles[row.getString("packagePath")]))
            }catch(error:Exception) {if(!tolerateRows)throw error;readWarnings+=CloudRestoreWarning(row?.optString("bookId")?:"books.json#$i","missing_book_body",permanent=true)}
        }
        bookFiles.values.filterNot{f->books.any{it.file==f}}.forEach{it.delete()}

        return WatchRssBackupSnapshot(
            exportedAt = manifest.getLong("exportedAt"),
            appVersion = manifest.optString("appVersion"),
            dataStructureVersion = version,
            sources = sources,
            articles = articles,
            savedItems = savedItems,
            readerSnapshot = readerSnapshot,
            appMeta = appMeta,
            cloudContentOnly = cloudContentOnly,
            readWarnings = readWarnings,
            bookPackages = books
        ).also(::validateSnapshot)
    }

    private fun validateSnapshot(snapshot: WatchRssBackupSnapshot) {
        require(snapshot.exportedAt > 0L) { "WRSS 导出时间无效" }
        require(snapshot.sources.none { it.url.isBlank() }) { "WRSS 包含无效 RSS 源" }
        require(snapshot.sources.map { it.url }.distinct().size == snapshot.sources.size) {
            "WRSS 包含重复 RSS 源"
        }
        require(snapshot.articles.none { it.articleId.isBlank() }) { "WRSS 包含无效文章" }
        require(snapshot.articles.map { it.articleId }.distinct().size == snapshot.articles.size) {
            "WRSS 包含重复文章"
        }
        require(snapshot.savedItems.map { it.type to it.stableKey }.distinct().size == snapshot.savedItems.size) {
            "WRSS 包含重复保存项"
        }
        require(snapshot.appMeta.map { it.key }.distinct().size == snapshot.appMeta.size) {
            "WRSS 包含重复元数据键"
        }
    }

    private fun WatchRssBackupSnapshot.manifestJson(): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        put("version", CURRENT_DATA_STRUCTURE_VERSION)
        put("exportedAt", exportedAt)
        if (cloudContentOnly) put("cloudContentOnly", true) else put("appVersion", appVersion)
        put("sourceCount", sources.size)
        put("articleCount", articles.size)
        put("savedItemCount", savedItems.size)
        put("readerPresetCount", readerSnapshot?.presets?.size ?: 0)
        put("readerFontCount", readerSnapshot?.fonts?.size ?: 0)
        put("readerBackgroundCount", readerSnapshot?.backgrounds?.size ?: 0)
        put("appMetaCount", appMeta.size)
    }

    private fun PhoneRssSourceEntity.toBackupJson(): JSONObject = JSONObject().apply {
        put("url", url)
        put("title", title)
        put("description", description)
        putNullable("siteUrl", siteUrl)
        putNullable("imageUrl", imageUrl)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("sortOrder", sortOrder)
        put("isPinned", isPinned)
        put("deleted", deleted)
        put("deletedAt", deletedAt)
        put("useOriginalContent", useOriginalContent)
        put("continuePlaybackInBackground", continuePlaybackInBackground)
    }

    private fun PhoneArticleEntity.toBackupJson(textEntry: String, htmlEntry: String?): JSONObject =
        JSONObject().apply {
            put("articleId", articleId)
            put("url", url)
            put("title", title)
            put("siteName", siteName)
            put("excerpt", excerpt)
            putNullable("imageUrl", imageUrl)
            put("contentHash", contentHash)
            put("importedAt", importedAt)
            put("updatedAt", updatedAt)
            put("independentSaved", independentSaved)
            put("independentChangedAt", independentChangedAt)
            put("independentSortOrder", independentSortOrder)
            putNullable("rssSourceUrl", rssSourceUrl)
            putNullable("rssSourceTitle", rssSourceTitle)
            put("favoriteSaved", favoriteSaved)
            put("favoriteChangedAt", favoriteChangedAt)
            put("favoriteSortOrder", favoriteSortOrder)
            put("watchLaterSaved", watchLaterSaved)
            put("watchLaterChangedAt", watchLaterChangedAt)
            put("watchLaterSortOrder", watchLaterSortOrder)
            put("deleted", deleted)
            put("deletedAt", deletedAt)
            put("readingProgress", readingProgress.toDouble())
            put("readingPositionBytes", readingPositionBytes)
            put("readingPositionContentHash", readingPositionContentHash)
            put("readingPositionChangedAt", readingPositionChangedAt)
            put("isRead", isRead)
            put("contentTextEntry", textEntry)
            putNullable("contentHtmlEntry", htmlEntry)
        }

    private fun PhoneSavedItemEntity.toBackupJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("stableKey", stableKey)
        put("remoteId", remoteId)
        put("title", title)
        put("link", link)
        put("summary", summary)
        put("channelTitle", channelTitle)
        put("pubDate", pubDate)
        put("syncedAt", syncedAt)
    }

    private fun AppMetaEntity.toBackupJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("value", value)
    }

    private fun sourceFromBackupJson(json: JSONObject): PhoneRssSourceEntity =
        PhoneRssSourceEntity(
            url = json.getString("url"),
            sourceDeviceId = "",
            title = json.getString("title"),
            description = json.getString("description"),
            siteUrl = json.nullableString("siteUrl"),
            imageUrl = json.nullableString("imageUrl"),
            createdAt = json.getLong("createdAt"),
            updatedAt = json.getLong("updatedAt"),
            sortOrder = json.getLong("sortOrder"),
            isPinned = json.getBoolean("isPinned"),
            deleted = json.getBoolean("deleted"),
            deletedAt = json.getLong("deletedAt"),
            useOriginalContent = json.optBoolean("useOriginalContent"),
            continuePlaybackInBackground =
                json.optBoolean("continuePlaybackInBackground")
        )

    private fun articleFromBackupJson(
        json: JSONObject,
        contentHtml: String?,
        contentText: String
    ): PhoneArticleEntity = PhoneArticleEntity(
        articleId = json.getString("articleId"),
        sourceDeviceId = "",
        url = json.getString("url"),
        title = json.getString("title"),
        siteName = json.getString("siteName"),
        excerpt = json.getString("excerpt"),
        contentHtml = contentHtml,
        contentText = contentText,
        imageUrl = json.nullableString("imageUrl"),
        contentHash = json.getString("contentHash"),
        importedAt = json.getLong("importedAt"),
        updatedAt = json.getLong("updatedAt"),
        independentSaved = json.getBoolean("independentSaved"),
        independentChangedAt = json.getLong("independentChangedAt"),
        independentSortOrder = json.getLong("independentSortOrder"),
        rssSourceUrl = json.nullableString("rssSourceUrl"),
        rssSourceTitle = json.nullableString("rssSourceTitle"),
        favoriteSaved = json.getBoolean("favoriteSaved"),
        favoriteChangedAt = json.getLong("favoriteChangedAt"),
        favoriteSortOrder = json.getLong("favoriteSortOrder"),
        watchLaterSaved = json.getBoolean("watchLaterSaved"),
        watchLaterChangedAt = json.getLong("watchLaterChangedAt"),
        watchLaterSortOrder = json.getLong("watchLaterSortOrder"),
        deleted = json.getBoolean("deleted"),
        deletedAt = json.getLong("deletedAt"),
        readingProgress = json.optDouble("readingProgress").toFloat().coerceIn(0f, 1f),
        readingPositionBytes = json.optLong("readingPositionBytes").coerceAtLeast(0L),
        readingPositionContentHash = json.optString("readingPositionContentHash"),
        readingPositionChangedAt = json.optLong("readingPositionChangedAt").coerceAtLeast(0L),
        isRead = json.optBoolean("isRead")
    )

    private fun savedItemFromBackupJson(json: JSONObject): PhoneSavedItemEntity =
        PhoneSavedItemEntity(
            type = json.getString("type"),
            stableKey = json.getString("stableKey"),
            remoteId = json.getLong("remoteId"),
            title = json.getString("title"),
            link = json.getString("link"),
            summary = json.getString("summary"),
            channelTitle = json.getString("channelTitle"),
            pubDate = json.getString("pubDate"),
            syncedAt = json.getLong("syncedAt")
        )

    private fun appMetaFromBackupJson(json: JSONObject): AppMetaEntity =
        AppMetaEntity(
            key = json.getString("key"),
            value = json.getString("value")
        )

    private fun bodyTextEntry(articleId: String): String = "bodies/${bodyKey(articleId)}.txt"

    private fun bodyHtmlEntry(articleId: String): String = "bodies/${bodyKey(articleId)}.html"

    private fun bodyKey(articleId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(articleId.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun isSafeEntryName(name: String): Boolean {
        return name.isNotBlank() &&
            !name.startsWith('/') &&
            '\\' !in name &&
            name.split('/').none { it == ".." || it.isBlank() }
    }

    private fun ZipOutputStream.writeUtf8Entry(name: String, value: String) {
        putNextEntry(ZipEntry(name).apply { time = 0L })
        val bytes = value.toByteArray(Charsets.UTF_8)
        write(bytes)
        closeEntry()
    }

    private fun ZipOutputStream.writeFileEntry(name: String, file: File) {
        putNextEntry(ZipEntry(name).apply { time = 0L })
        file.inputStream().buffered().use { it.copyTo(this) }
        closeEntry()
    }

    private fun ReaderBackupResource.entryName(): String {
        val directory = when (kind) {
            "font" -> "fonts"
            "background" -> "backgrounds"
            "variant" -> "variants"
            else -> error("未知阅读器资源类型")
        }
        return "reader/$directory/$sha256.bin"
    }

    private fun validateAndInstallReaderResources(
        entries: Map<String, ByteArray>,
        snapshot: ReaderPresetSnapshot,
        store: ReaderResourceStore?
    ) {
        val expected = buildList {
            snapshot.fonts.filterNot { it.deleted }.forEach {
                add(Triple("font", it.fileName, it.sha256) to it.byteCount)
            }
            snapshot.backgrounds.filterNot { it.deleted }.forEach {
                add(Triple("background", it.masterFileName, it.sha256) to it.byteCount)
                val variants = runCatching { JSONObject(it.variantsJson) }.getOrNull()
                listOf("watch", "watchPoster").forEach { key ->
                    variants?.optJSONObject(key)?.let { variant ->
                        val fileName = variant.optString("fileName")
                        val hash = variant.optString("sha256")
                        val bytes = variant.optLong("byteCount")
                        if (fileName.isNotBlank() && hash.length == 64 && bytes >= 0L) {
                            add(Triple("variant", fileName, hash) to bytes)
                        }
                    }
                }
            }
        }
        expected.forEach { (identity, byteCount) ->
            val (kind, fileName, hash) = identity
            val directory = when (kind) {
                "font" -> "fonts"
                "variant" -> "variants"
                else -> "backgrounds"
            }
            val entryName = "reader/$directory/$hash.bin"
            val bytes = entries[entryName]
                ?: throw IllegalArgumentException("WRSS 缺少阅读器资源：$fileName")
            require(bytes.size.toLong() == byteCount && sha256(bytes) == hash) {
                "WRSS 阅读器资源校验失败：$fileName"
            }
            val target = when (kind) {
                "font" -> store?.targetFontFile(fileName)
                "variant" -> store?.targetVariantFile(fileName)
                else -> store?.targetBackgroundFile(fileName)
            }
            if (target != null && (!target.exists() || target.length() != byteCount)) {
                val partial = File(target.parentFile, "${target.name}.restore")
                partial.outputStream().buffered().use { it.write(bytes) }
                if (target.exists()) target.delete()
                require(partial.renameTo(target)) { "WRSS 阅读器资源恢复失败" }
            }
        }
        val expectedNames = expected.mapTo(linkedSetOf()) { (identity, _) ->
            val (kind, _, hash) = identity
            val directory = when (kind) {
                "font" -> "fonts"
                "variant" -> "variants"
                else -> "backgrounds"
            }
            "reader/$directory/$hash.bin"
        }
        val actualNames = entries.keys.filterTo(linkedSetOf()) { READER_RESOURCE_PATTERN.matches(it) }
        require(actualNames == expectedNames) { "WRSS 包含未引用或缺失的阅读器资源" }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun JSONObject.putNullable(name: String, value: String?) {
        put(name, value ?: JSONObject.NULL)
    }

    private fun JSONObject.nullableString(name: String): String? {
        return if (!has(name) || isNull(name)) null else getString(name)
    }

    private fun <T> List<T>.toJsonArray(transform: (T) -> JSONObject): JSONArray =
        JSONArray().also { array -> forEach { item -> array.put(transform(item)) } }

    private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
        buildList {
            for (index in 0 until length()) {
                add(transform(getJSONObject(index)))
            }
        }
}
