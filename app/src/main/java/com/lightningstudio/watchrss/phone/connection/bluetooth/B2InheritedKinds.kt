package com.lightningstudio.watchrss.phone.connection.bluetooth

import androidx.room.withTransaction
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.*
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal class B2InheritedKinds(
    private val db: PhoneCompanionDatabase,
    private val repository: PhoneCompanionRepository,
    private val identity: BluetoothArticleIdentityProjection,
    private val noteAssets: B2NoteAssetBusiness,
    override val kinds: Set<String>,
    private val excludeBooks: Boolean,
    private val authorize: () -> Unit,
    private val canonicalIds: Boolean = false,
    private val books: B2BookBusiness? = null
) : B2BusinessPort {
    private val binary = db.b2ArticleDao()
    fun bookImportRoot(): File? = repository.books?.binaryImportDirectory()
    suspend fun committedBookPackages(): JSONArray = books?.committedPackages() ?: JSONArray()
    @Volatile var applyWork: () -> Unit = {}

    suspend fun freeze(sendKinds: Set<String>, scratch: File, work: () -> Unit,
        allowBook: (JSONObject) -> Boolean = { true }, warn: (BookSyncIssue) -> Unit = {},
        omitPackage: (JSONObject) -> Boolean = { false },
        add: (JSONObject, File) -> Unit) {
        books?.freeze(scratch, work, allowBook, warn, omitPackage, add)
        val exclusions = repository.books?.takeIf { excludeBooks }?.bluetoothExclusions()
        val empty = File(scratch, "empty").apply { createNewFile() }
        val emptyHashes = binaryFileHashes(empty)
        val dependencies = B2RssDependencies()
        if (B2_RSS_SOURCE in sendKinds) {
            for (source in repository.getRssSourcesForSync()) {
                authorize()
                if (source.url in exclusions?.second.orEmpty() || excludeBooks &&
                    source.url == com.lightningstudio.watchrss.phone.data.model.ImportedContentIds.ROOT_SOURCE_URL) continue
                val descriptor = binaryDescriptor(B2_RSS_SOURCE, source.url,
                    LibrarySyncPayload.binaryRssMetadata(source), emptyHashes)
                dependencies.addSource(descriptor)
                add(descriptor, empty)
                work()
            }
        }
        if (B2_ARTICLE_METADATA !in sendKinds) return
        var after: String? = null
        while (true) {
            val ids = binary.binaryArticleIds(after)
            if (ids.isEmpty()) return
            for (id in ids) {
                authorize()
                if (id in exclusions?.first.orEmpty()) continue
                val encoded = File.createTempFile("article-", ".gzip", scratch)
                val article = db.withTransaction {
                    repository.freezeBinaryArticle(id, scratch, encoded, B2_ARTICLE_BODY in sendKinds, work)
                }
                if (article == null) {
                    encoded.delete()
                    continue
                }
                val (row, hashes) = article
                val wire = identity.outgoing(row)
                val body = hashes?.let { ArticleBodyMetadata(it.sha256, it.size, ArticleSyncBody.CHUNK_SIZE_BYTES,
                    it.legacyChunks, ArticleSyncBody.metadataHashFor(wire)) }
                val metadata = LibrarySyncPayload.binaryArticleMetadata(wire, body, body == null, identity.outgoingAliases(listOf(wire)))
                val kind = if (body == null) B2_ARTICLE_METADATA else B2_ARTICLE_BODY
                val descriptor = binaryDescriptor(kind, wire.articleId, metadata, hashes ?: emptyHashes,
                    dependencies.forArticle(metadata))
                add(descriptor, if (body == null) empty else encoded)
                if (body == null) encoded.delete()
                work()
            }
            after = ids.last()
        }
    }

    override suspend fun receiptWitness(
        context: B2ApplyContext?, descriptor: JSONObject, metadata: JSONObject, prior: JSONObject?
    ): JSONObject? = when (descriptor.s("kind")) {
        in B2Wire.bookKinds -> books?.receiptWitness(context, descriptor, metadata, prior)
        B2_NOTE_ASSET -> noteAssets.receiptWitness(context, descriptor, metadata, prior)
        else -> null
    }

    override fun validatePlan(objects: List<JSONObject>) = validateBinaryRssDependencies(objects)

    override fun applicationScratchBytes(objects: List<JSONObject>): Long =
        (if (objects.any { it.getString("kind") == B2_ARTICLE_BODY }) 32L * 1024 * 1024 else 0L) +
            (books?.scratchBytes(objects) ?: 0L)

    override suspend fun prepareFile(context: B2ApplyContext, descriptor: JSONObject, metadata: JSONObject) {
        if (descriptor.getString("kind") == B2Wire.BOOK_COMMIT) {
            (books ?: throw B2Exception("UNSUPPORTED_KIND")).prepareCommit(context, metadata, applyWork)
        }
        if (descriptor.s("kind") == B2_NOTE_ASSET) noteAssets.prepareFile(context, descriptor, metadata)
    }

    override suspend fun applyFile(context: B2ApplyContext, descriptor: JSONObject, metadata: JSONObject,
        staged: B2FileRef): String {
        val kind = descriptor.getString("kind")
        if (kind in B2Wire.bookKinds) {
            return (books ?: throw B2Exception("UNSUPPORTED_KIND")).apply(context, descriptor, metadata, staged, applyWork)
        }
        if (kind == B2_NOTE_ASSET) {
            return try {
                noteAssets.applyFile(context, descriptor, metadata, staged)
            } catch (_: android.database.SQLException) {
                throw B2Exception("LOCAL_IO")
            }
        }
        return super.applyFile(context, descriptor, metadata, staged)
    }

    override suspend fun apply(descriptor: JSONObject, metadata: JSONObject, staged: File): String = try {
        authorize()
        when (descriptor.getString("kind")) {
            B2_NOTE_ASSET -> noteAssets.apply(descriptor, metadata, staged)
            B2_RSS_SOURCE -> {
                validateBinarySource(descriptor, metadata)
                val source = LibrarySyncPayload.parseRssSources(JSONArray().put(metadata)).singleOrNull()
                    ?: throw B2Exception("BUSINESS_REJECTED")
                if (source.url != descriptor.getString("businessId")) throw B2Exception("BUSINESS_REJECTED")
                repository.applyBinaryRssSource(binary, source)
            }
            B2_ARTICLE_BODY, B2_ARTICLE_METADATA -> applyArticle(descriptor, metadata, staged)
            else -> throw B2Exception("BUSINESS_REJECTED")
        }
    } catch (_: java.io.IOException) {
        throw B2Exception("LOCAL_IO")
    } catch (_: android.database.SQLException) {
        throw B2Exception("LOCAL_IO")
    }

    private suspend fun applyArticle(descriptor: JSONObject, metadata: JSONObject, staged: File): String {
        validateBinaryArticle(descriptor, metadata)
        val payload = try {
            // The legacy parser treats a missing optional string as empty.
            for (key in listOf("imageUrl", "rssSourceUrl", "rssSourceTitle")) {
                if (metadata.isNull(key)) metadata.remove(key)
            }
            LibrarySyncPayload.parseChunkedArticles(JSONArray().put(metadata)).single()
        } catch (_: IllegalArgumentException) {
            throw B2Exception("BUSINESS_REJECTED")
        } catch (_: NoSuchElementException) {
            throw B2Exception("BUSINESS_REJECTED")
        }
        if (payload.article.articleId != descriptor.getString("businessId")) throw B2Exception("BUSINESS_REJECTED")
        val aliases = metadata.optJSONArray("legacyArticleIds")
        suspend fun apply(html: File?, text: File?): String {
            if (canonicalIds && aliases != null) {
                repository.acceptBinaryArticleAlias(binary, payload.article, (0 until aliases.length()).map(aliases::getString))
            }
            val projected = identity.incoming(payload)
            return repository.applyBinaryArticle(binary, projected, html, text)
        }
        if (payload.metadataOnly) return apply(null, null)

        val hashes = binaryFileHashes(staged, applyWork)
        if (hashes.size != payload.bodyByteCount || hashes.sha256 != payload.bodyHash ||
            hashes.legacyChunks != payload.chunkHashes) throw B2Exception("HASH_MISMATCH")
        val scratch = java.nio.file.Files.createTempDirectory(staged.parentFile!!.toPath(), ".decode-").toFile()
        try {
            val html = File(scratch, "html")
            val text = File(scratch, "text")
            val hasHtml = try {
                ArticleSyncBody.decodeFileBody(staged, html, text, applyWork)
            } catch (_: IllegalArgumentException) {
                throw B2Exception("BUSINESS_REJECTED")
            } catch (_: java.nio.charset.CharacterCodingException) {
                throw B2Exception("BUSINESS_REJECTED")
            } catch (_: java.util.zip.ZipException) {
                throw B2Exception("BUSINESS_REJECTED")
            } catch (_: java.io.EOFException) {
                throw B2Exception("BUSINESS_REJECTED")
            }
            authorize()
            return apply(html.takeIf { hasHtml }, text)
        } finally {
            scratch.deleteRecursively()
        }
    }
}
