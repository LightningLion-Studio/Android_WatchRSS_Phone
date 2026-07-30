package com.lightningstudio.watchrss.phone.data.backup

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
    private const val VERSION = 2
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val SOURCES_ENTRY = "sources.json"
    private const val ARTICLES_ENTRY = "articles.json"
    private const val SAVED_ITEMS_ENTRY = "saved_items.json"
    private const val READER_ENTRY = "reader/manifest.json"
    private const val MAX_ENTRY_COUNT = 100_000
    private const val MAX_ENTRY_BYTES = 512L * 1024L * 1024L
    private const val MAX_EXPANDED_BYTES = 4L * 1024L * 1024L * 1024L
    private val BODY_ENTRY_PATTERN = Regex("""bodies/[0-9a-f]{64}\.(html|txt)""")
    private val READER_RESOURCE_PATTERN =
        Regex("""reader/(fonts|backgrounds|variants)/[0-9a-f]{64}\.bin""")
    private val METADATA_ENTRIES = setOf(
        MANIFEST_ENTRY,
        SOURCES_ENTRY,
        ARTICLES_ENTRY,
        SAVED_ITEMS_ENTRY
    )

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
                zip.writeUtf8Entry(READER_ENTRY, ReaderPresetSyncPayload.snapshotJson(it).toString())
            }
            snapshot.articles.forEach { article ->
                zip.writeUtf8Entry(bodyTextEntry(article.articleId), article.contentText)
                article.contentHtml?.let { html ->
                    zip.writeUtf8Entry(bodyHtmlEntry(article.articleId), html)
                }
            }
            snapshot.readerResources.forEach { resource ->
                require(resource.file.isFile && resource.file.length() == resource.byteCount) {
                    "阅读器备份资源无效：${resource.fileName}"
                }
                zip.writeFileEntry(resource.entryName(), resource.file)
            }
        }
    }

    fun read(
        input: InputStream,
        readerResourceStore: ReaderResourceStore? = null
    ): WatchRssBackupSnapshot {
        val entries = runCatching { readEntries(input) }
            .getOrElse { throwable ->
                if (throwable is IllegalArgumentException) throw throwable
                throw IllegalArgumentException("WRSS 文件损坏或无法读取", throwable)
            }
        return runCatching { parseEntries(entries, readerResourceStore) }
            .getOrElse { throwable ->
                if (throwable is IllegalArgumentException) throw throwable
                throw IllegalArgumentException("WRSS 数据格式无效", throwable)
            }
    }

    private fun readEntries(input: InputStream): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        var expandedBytes = 0L
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory) { "WRSS 中不允许目录条目" }
                val name = entry.name
                require(isSafeEntryName(name)) { "WRSS 包含不安全路径：$name" }
                require(
                    name in METADATA_ENTRIES ||
                        name == READER_ENTRY ||
                        BODY_ENTRY_PATTERN.matches(name) ||
                        READER_RESOURCE_PATTERN.matches(name)
                ) {
                    "WRSS 包含未知条目：$name"
                }
                require(name !in entries) { "WRSS 包含重复条目：$name" }
                require(entries.size < MAX_ENTRY_COUNT) { "WRSS 条目数量过多" }

                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var entryBytes = 0L
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    entryBytes += read
                    expandedBytes += read
                    require(entryBytes <= MAX_ENTRY_BYTES) { "WRSS 单个条目过大：$name" }
                    require(expandedBytes <= MAX_EXPANDED_BYTES) { "WRSS 解压后内容过大" }
                    output.write(buffer, 0, read)
                }
                entries[name] = output.toByteArray()
                zip.closeEntry()
            }
        }
        require(entries.isNotEmpty()) { "不是有效的 WRSS 备份" }
        return entries
    }

    private fun parseEntries(
        entries: Map<String, ByteArray>,
        readerResourceStore: ReaderResourceStore?
    ): WatchRssBackupSnapshot {
        METADATA_ENTRIES.forEach { name ->
            require(name in entries) { "WRSS 缺少必要条目：$name" }
        }
        val manifest = JSONObject(entries.getValue(MANIFEST_ENTRY).toString(Charsets.UTF_8))
        require(manifest.getString("format") == FORMAT) { "不是腕上RSS 资料库备份" }
        val version = manifest.getInt("version")
        require(version in 1..VERSION) {
            if (version > VERSION) "不支持的 WRSS 备份版本：$version" else "WRSS 备份版本无效：$version"
        }

        val sources = JSONArray(entries.getValue(SOURCES_ENTRY).toString(Charsets.UTF_8))
            .mapObjects(::sourceFromBackupJson)
        val savedItems = JSONArray(entries.getValue(SAVED_ITEMS_ENTRY).toString(Charsets.UTF_8))
            .mapObjects(::savedItemFromBackupJson)
        val expectedBodyEntries = linkedSetOf<String>()
        val articles = JSONArray(entries.getValue(ARTICLES_ENTRY).toString(Charsets.UTF_8))
            .mapObjects { json ->
                val articleId = json.getString("articleId")
                val textEntry = json.getString("contentTextEntry")
                val htmlEntry = json.nullableString("contentHtmlEntry")
                require(textEntry == bodyTextEntry(articleId)) { "WRSS 文章正文路径不匹配" }
                require(htmlEntry == null || htmlEntry == bodyHtmlEntry(articleId)) { "WRSS 文章 HTML 路径不匹配" }
                expectedBodyEntries += textEntry
                htmlEntry?.let(expectedBodyEntries::add)
                val contentText = entries[textEntry]?.toString(Charsets.UTF_8)
                    ?: throw IllegalArgumentException("WRSS 缺少文章正文：$articleId")
                val contentHtml = htmlEntry?.let { entryName ->
                    entries[entryName]?.toString(Charsets.UTF_8)
                        ?: throw IllegalArgumentException("WRSS 缺少文章 HTML：$articleId")
                }
                articleFromBackupJson(json, contentHtml, contentText)
            }

        val actualBodyEntries = entries.keys.filterTo(linkedSetOf()) { BODY_ENTRY_PATTERN.matches(it) }
        require(actualBodyEntries == expectedBodyEntries) { "WRSS 包含未引用或缺失的正文条目" }
        require(manifest.getInt("sourceCount") == sources.size) { "WRSS 的 RSS 源数量不匹配" }
        require(manifest.getInt("articleCount") == articles.size) { "WRSS 的文章数量不匹配" }
        require(manifest.getInt("savedItemCount") == savedItems.size) { "WRSS 的保存项数量不匹配" }

        val readerSnapshot = if (version >= 2) {
            val readerJson = entries[READER_ENTRY]?.toString(Charsets.UTF_8)
                ?: throw IllegalArgumentException("WRSS v2 缺少阅读器清单")
            val json = JSONObject(readerJson)
            ReaderPresetSnapshot(
                presets = ReaderPresetSyncPayload.parsePresets(json.optJSONArray("presets")),
                fonts = ReaderPresetSyncPayload.parseFonts(json.optJSONArray("fonts")),
                backgrounds = ReaderPresetSyncPayload.parseBackgrounds(json.optJSONArray("backgrounds")),
                deletions = ReaderPresetSyncPayload.parseDeletions(json.optJSONArray("deletions"))
            ).also { reader ->
                validateAndInstallReaderResources(entries, reader, readerResourceStore)
            }
        } else {
            null
        }

        return WatchRssBackupSnapshot(
            exportedAt = manifest.getLong("exportedAt"),
            appVersion = manifest.optString("appVersion"),
            sources = sources,
            articles = articles,
            savedItems = savedItems,
            readerSnapshot = readerSnapshot
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
    }

    private fun WatchRssBackupSnapshot.manifestJson(): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        put("version", if (readerSnapshot == null) 1 else VERSION)
        put("exportedAt", exportedAt)
        put("appVersion", appVersion)
        put("sourceCount", sources.size)
        put("articleCount", articles.size)
        put("savedItemCount", savedItems.size)
        put("readerPresetCount", readerSnapshot?.presets?.size ?: 0)
        put("readerFontCount", readerSnapshot?.fonts?.size ?: 0)
        put("readerBackgroundCount", readerSnapshot?.backgrounds?.size ?: 0)
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
        putNextEntry(ZipEntry(name))
        val bytes = value.toByteArray(Charsets.UTF_8)
        write(bytes)
        closeEntry()
    }

    private fun ZipOutputStream.writeFileEntry(name: String, file: File) {
        putNextEntry(ZipEntry(name))
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
