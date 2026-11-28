package com.lightningstudio.watchrss.phone.syncv2

import androidx.sqlite.db.SupportSQLiteDatabase
import com.lightningstudio.watchrss.phone.connection.bluetooth.B2BodyHandoff
import com.lightningstudio.watchrss.phone.data.book.BookPackageFiles
import com.lightningstudio.watchrss.phone.data.local.ConditionalBodyFile
import com.lightningstudio.watchrss.phone.data.book.BookSync
import com.lightningstudio.watchrss.phone.data.note.NoteAssetFiles
import com.lightningstudio.watchrss.phone.data.book.SMALL_BOOK_JSON
import com.lightningstudio.watchrss.phone.data.book.readStoredBookJson
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.NoSuchFileException

/** Original bytes are the offline queue. Downloaded SHA values never change business clocks. */
internal class SyncV2BodyRepository(private val db:SupportSQLiteDatabase,private val files:File,private val beforeInstall:(File)->Unit={}) {
    private val bookDirectory = File(files, "imported_books")

    fun pendingBluetooth(kind: String, id: String, scope: String?): Boolean =
        scope != null && B2BodyHandoff.hasPending(files, scope, kind, id)

    fun pendingBluetooth(binding: SyncV2BodyCodec.Binding, scope: String, defer: Boolean = false): Boolean =
        B2BodyHandoff.hasPending(files, scope, binding.kind, binding.logicalId, defer) {
            SyncV2BodyCodec.revision(binding.kind, binding.entityKey, it) == binding.bodyRevision
        }

    /** Destination for a verified cloud download. Local reads use the current package revision. */
    fun file(kind: String, id: String): File {
        SyncV2BodyCodec.name(kind, id)
        return if (kind == "asset") NoteAssetFiles.current(db, files, id) else BookPackageFiles.legacy(bookDirectory, id)
    }

    fun read(kind: String, id: String): ByteArray? {
        val selected = if (kind == "book") {
            db.controlQuery("SELECT stateJson FROM imported_books WHERE bookId=?", arrayOf(id)).use {
                if (!it.moveToFirst()) return null
                JSONObject(it.getString(0))
            }
        } else JSONObject().put("assetId", id).put("deleted", false)
        return original(kind, id, selected)?.raw
    }
    internal sealed interface Reference
    private data class BookReference(val revision: BookPackageFiles.Revision, val identity: String,
        val publication: BookPackageFiles.Publication?) : Reference
    private data class AssetReference(val owners: Set<String>, val revisions: Set<BookPackageFiles.Revision>,
        val published: NoteAssetFiles.Reference?) : Reference

    internal class Original(val raw: ByteArray, val sha256: String, private val reference: Reference,
        private val stillCurrent: () -> Boolean) {
        val hasRegisteredDigest = reference is BookReference || reference is AssetReference && (reference.published != null || reference.revisions.isNotEmpty())
        fun matches(other: Original?): Boolean =
            other != null && reference == other.reference && sha256 == other.sha256 && raw.size == other.raw.size
        fun requireCurrent() {
            if (!stillCurrent()) SyncV2Codec.fail("body_revision_changed")
        }
    }

    private data class FileIdentity(val size: Long, val mtime: FileTime, val inode: Any?, val ctime: FileTime?)

    private fun identity(file: File): FileIdentity? {
        val path = file.toPath()
        return try {
            val basic = Files.readAttributes(path, BasicFileAttributes::class.java)
            if (!basic.isRegularFile) SyncV2Codec.fail("body_revision_changed")
            val ctime = try { Files.getAttribute(path, "unix:ctime") as? FileTime }
                catch (_: UnsupportedOperationException) { null }
            FileIdentity(basic.size(), basic.lastModifiedTime(), basic.fileKey(), ctime)
        } catch (_: NoSuchFileException) {
            null
        }
    }

    private fun sameFile(file: File, observed: FileIdentity?, sha256: String?): Boolean {
        if (identity(file) != observed) return false
        if (observed == null || observed.inode != null && observed.ctime != null) return true
        return ConditionalBodyFile.digest(file) == sha256 &&
            identity(file) == observed
    }

    internal class RepairTarget(val expectedSha256: String?, private val stillCurrent: () -> Boolean,
        private val sameIdentity: () -> Boolean) {
        fun requireCurrent() {
            if (!stillCurrent()) SyncV2Codec.fail("body_revision_changed")
        }
        fun requireSameIdentity() {
            if (!sameIdentity()) SyncV2Codec.fail("body_revision_changed")
        }
    }

    fun repairTarget(kind: String, id: String, selected: JSONObject): RepairTarget {
        val target = file(kind, id)
        if (target.canonicalFile.parentFile != target.parentFile!!.canonicalFile) SyncV2Codec.fail("body_revision_changed")
        val before = reference(kind, id, selected)
        val observed = identity(target)
        if (observed != null && observed.size > SyncV2BodyCodec.limit(kind)) SyncV2Codec.fail("object_too_large")
        val sha = ConditionalBodyFile.digest(target)
        if (identity(target) != observed || reference(kind, id, selected) != before) SyncV2Codec.fail("body_revision_changed")
        return RepairTarget(sha,
            stillCurrent = { file(kind, id) == target && reference(kind, id, selected) == before &&
                sameFile(target, observed, sha) },
            sameIdentity = { identity(target) == observed })
    }

    private fun bookIdentity(value: JSONObject): String = SyncV2Codec.canonical(org.json.JSONArray().apply {
        for (field in listOf("bookId", "instanceId", "fingerprint", "chapterCount", "lifecycle")) {
            put(value.opt(field) ?: JSONObject.NULL)
        }
    })

    private fun bookReference(id: String, selected: JSONObject): BookReference? =
        db.controlQuery(
            "SELECT packageSha256,packageBytes,$SMALL_BOOK_JSON FROM imported_books WHERE bookId=?",
            arrayOf(id)
        ).use { row ->
            if (!row.moveToFirst()) return@use null
            val state = JSONObject(readStoredBookJson({ sql, args -> db.controlQuery(sql, args) }, id, row.getString(2)))
            if (state.opt("deleted") != false && state.opt("deleted") != 0) return@use null
            val current = bookIdentity(state)
            if (current != bookIdentity(selected)) SyncV2Codec.fail("body_revision_changed")
            BookReference(BookPackageFiles.Revision(row.getString(0), row.getLong(1)), current,
                BookPackageFiles.publication(db, id))
        }

    private fun resolveBook(id: String, current: BookReference, onRead: (Int) -> Unit = {}): BookPackageFiles.Original? =
        BookPackageFiles.resolveOriginal(bookDirectory, id, current.revision, current.publication, onRead)

    /** Recovery and cloud proof use the same current Room identity and physical original. */
    fun committedBook(id: String, selected: JSONObject, maxBytes: Long? = null,
        onRead: (Int) -> Unit = {}): BookPackageFiles.Original? {
        val current = bookReference(id, selected) ?: return null
        if (maxBytes != null && current.revision.bytes > maxBytes) SyncV2Codec.fail("quota_exceeded")
        val original = resolveBook(id, current, onRead) ?: return null
        return original.takeIf { bookReference(id, selected) == current && it.isCurrent() }
    }

    private fun assetReference(id: String): AssetReference? {
        val owners = SyncV2NoteAssetReferences.owners(db, id)
        if (owners.isEmpty()) return null
        val metadata = SyncV2NoteAssetNames.metadataSource(db)
        val revisions = db.controlQuery("SELECT sha256,byteCount,deleted FROM $metadata WHERE storageKey=?", arrayOf(id)).use { row ->
            var registered = false
            var live = false
            val found = buildSet {
                while (row.moveToNext()) {
                    registered = true
                    if (row.getInt(2) != 0) continue
                    live = true
                    val hash = row.getString(0)
                    if (rawSha.matches(hash)) add(BookPackageFiles.Revision(hash, row.getLong(1)))
                }
            }
            if (registered && !live) return null
            found
        }
        return AssetReference(owners, revisions, NoteAssetFiles.selection(db, id))
    }

    private fun stableRead(file: File, observed: FileIdentity, limit: Int): ByteArray? {
        if (observed.size > limit) SyncV2Codec.fail("object_too_large")
        return try {
            val raw = ByteArray(observed.size.toInt())
            DataInputStream(file.inputStream()).use { input ->
                input.readFully(raw)
                if (input.read() != -1) return null
            }
            raw.takeIf { identity(file) == observed }
        } catch (_: IOException) {
            null
        }
    }

    private fun reference(kind: String, id: String, selected: JSONObject): Reference? =
        if (kind == "book") bookReference(id, selected) else assetReference(id)

    /** The reference is the committed local business selection; the digest always comes from readable bytes. */
    fun original(kind: String, id: String, selected: JSONObject): Original? {
        val target = file(kind, id)
        val before = reference(kind, id, selected) ?: return null
        val limit = SyncV2BodyCodec.limit(kind)
        val source = if (before is BookReference) {
            if (before.revision.bytes > limit) SyncV2Codec.fail("object_too_large")
            resolveBook(id, before)?.file ?: return null
        } else {
            if (target.canonicalFile.parentFile != target.parentFile!!.canonicalFile) return null
            target
        }
        val observed = identity(source) ?: return null
        val raw = stableRead(source, observed, limit) ?: return null
        val hash = SyncV2BodyCodec.hash(raw)
        val actual = BookPackageFiles.Revision(hash, raw.size.toLong())
        when (before) {
            is BookReference -> if (before.revision != actual) return null
            is AssetReference -> if ((before.published != null && before.published.revision != actual) ||
                (before.revisions.isNotEmpty() && before.revisions != setOf(actual))) return null
        }
        validate(kind, id, selected, raw)
        val original = Original(raw, hash, before) {
            reference(kind, id, selected) == before && sameFile(source, observed, hash) &&
                (kind != "asset" || NoteAssetFiles.current(db, files, id) == source)
        }
        original.requireCurrent()
        return original
    }

    internal class NoteAssetRegistration(val storageKey: String, val sha256: String, val byteCount: Int,
        private val stillCurrent: () -> Boolean) {
        fun requireCurrent() {
            if (!stillCurrent()) SyncV2Codec.fail("closure_incomplete")
        }
    }

    fun prepareNoteAssetRegistration(id: String): NoteAssetRegistration {
        val selected = NoteAssetFiles.selection(db, id)
        val target = file("asset", id)
        if (!target.isFile || target.canonicalFile.parentFile != target.parentFile!!.canonicalFile) SyncV2Codec.fail("closure_incomplete")
        val observed = identity(target) ?: SyncV2Codec.fail("closure_incomplete")
        val raw = stableRead(target, observed, SyncV2BodyCodec.limit("asset")) ?: SyncV2Codec.fail("closure_incomplete")
        val sha = SyncV2BodyCodec.hash(raw)
        if (selected != null && selected.revision != BookPackageFiles.Revision(sha, raw.size.toLong())) {
            SyncV2Codec.fail("closure_incomplete")
        }
        val registration = NoteAssetRegistration(id, sha, raw.size) {
            NoteAssetFiles.selection(db, id) == selected && file("asset", id) == target &&
                target.canonicalFile.parentFile == target.parentFile!!.canonicalFile &&
                (selected == null || SyncV2NoteAssetReferences.owners(db, id, liveOnly = true).isNotEmpty()) &&
                sameFile(target, observed, sha)
        }
        registration.requireCurrent()
        return registration
    }

    fun registerNoteAsset(asset: NoteAssetRegistration, now: Long, stage: SyncV2UnconfirmedWrite? = null): String {
        check(db.inTransaction())
        asset.requireCurrent()
        return try {
            registerNoteAssetByDigest(asset.storageKey, asset.sha256, asset.byteCount, now,stage)
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
            SyncV2Codec.fail("closure_incomplete")
        }
    }

    private fun registerNoteAssetByDigest(id: String, sha256: String, byteCount: Int, now: Long,
        stage: SyncV2UnconfirmedWrite?): String {
        val existing = db.controlQuery("SELECT storageKey FROM note_assets WHERE sha256=? AND deleted=0", arrayOf(sha256)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        if (existing == id) return id
        val mime=when(id.substringAfterLast('.',"").lowercase()){ "png"->"image/png";"jpg","jpeg"->"image/jpeg";"webp"->"image/webp";"md"->"text/markdown";else->"application/octet-stream" }
        val value = JSONObject().put("noteId","")
            .put("storageKey",id).put("mimeType",mime).put("displayName",id).put("sha256",sha256).put("byteCount",byteCount)
            .put("isOriginal",false).put("createdAt",now).put("deleted",false).put("deletedAt",0)
        if (existing != null) {
            stage?.let { value.put("registration_txn",it.id) }
            SyncV2NoteAssetNames.saveAlias(db, value)
            return existing
        }
        val assetId = java.util.UUID.randomUUID().toString()
        SyncV2PhoneApply(db).upsert("note_assets",listOf("assetId"),value.put("assetId",assetId))
        stage?.recordNoteAsset(assetId)
        return id
    }

    /** Discover referenced originals omitted by older private-UUID metadata. Absence never deletes. */
    fun refreshReferences(now:Long,scope:String?=null,bluetoothScope:String?=null,heldNotes:Set<String> = emptySet(),checkOwner:()->Unit):List<String> {
        SyncV2UnconfirmedWrite.recover(db)
        val errors=linkedSetOf<String>()
        fun onlyHeldOwners(id: String): Boolean {
            if (heldNotes.isEmpty()) return false
            return db.controlQuery("SELECT DISTINCT noteId FROM note_asset_refs WHERE storageKey=?", arrayOf(id)).use { rows ->
                if (!rows.moveToFirst()) return@use false
                do {
                    if (rows.getString(0) !in heldNotes) return@use false
                } while (rows.moveToNext())
                true
            }
        }
        for(id in references(db))try {
            checkOwner()
            if (SyncV2NoteAssetNames.exists(db, id) || pendingBluetooth("asset", id, bluetoothScope)) continue
            val target = file("asset", id)
            if (!target.isFile) SyncV2Codec.fail("cloud_note_asset_missing")
            val asset = prepareNoteAssetRegistration(id)
            val stage = scope?.let { SyncV2UnconfirmedWrite(db,it,SyncV2Codec.hex(SyncV2Codec.random(16))) }
            val write = {
                SyncV2UnconfirmedWrite.transaction(db) {
                    checkOwner()
                    stage?.begin()
                    if (!SyncV2NoteAssetNames.exists(db, id) && !pendingBluetooth("asset", id, bluetoothScope)) {
                         registerNoteAsset(asset, now, stage)
                     }
                    checkOwner()
                }
            }
            if (stage == null) write() else stage.verify(checkOwner,write)
        } catch (e: Exception) {
            checkOwner()
            val code = SyncV2BodyChannel.code(e)
            if (code !in setOf("cloud_note_asset_missing", "closure_incomplete") || !onlyHeldOwners(id))
                errors.add(code)
        }
        return errors.toList()
    }
    fun validate(kind:String,id:String,value:JSONObject,raw:ByteArray) {
        SyncV2BodyCodec.name(kind,id);if(raw.size>SyncV2BodyCodec.limit(kind))SyncV2Codec.fail("object_too_large")
        if(value.getBoolean("deleted"))SyncV2Codec.fail("body_revision_changed")
        if(kind=="asset") {if(value.getString("assetId")!=id)SyncV2Codec.fail("source_identity_missing");return}
        val text=try{Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(raw)).toString()}catch(_:java.nio.charset.CharacterCodingException){SyncV2Codec.fail("book_package_invalid")}
        if(text.startsWith('\uFEFF') || !text.endsWith('\n') || text.contains("\r\n"))SyncV2Codec.fail("book_package_invalid")
        val lines=text.reader().buffered()
        try {
            val h=JSONObject(lines.readLine()?:SyncV2Codec.fail("book_package_invalid"))
            if(h.getString("format")!="watchrss-book" || h.get("version")!=1 || h.get("kind") !in setOf("txt","epub") || h.get("title") !is String || !bookUrl(h.getString("sourceUrl")))SyncV2Codec.fail("book_package_invalid")
            val count=SyncV2Wire.uint(h,"chapterCount",100000).toInt();if(count==0 || value.getInt("chapterCount")!=count)SyncV2Codec.fail("book_package_invalid")
            if(h.has("instanceId") && h.get("instanceId") !is String)SyncV2Codec.fail("book_package_invalid")
            val instance=h.optString("instanceId","");if(h.has("instanceId"))SyncV2Codec.unhex(instance,16)
            if(instance!=value.optString("instanceId",""))SyncV2Codec.fail("book_package_invalid")
            val hashes=ArrayList<String>(count)
            repeat(count){index->val c=JSONObject(lines.readLine()?:SyncV2Codec.fail("book_package_invalid"))
                if(SyncV2Wire.uint(c,"chapterIndex")!=index+1L || c.get("title") !is String || c.get("contentText") !is String || !bookUrl(c.getString("url")) || c.has("contentHtml")&&!c.isNull("contentHtml")&&c.get("contentHtml") !is String)SyncV2Codec.fail("book_package_invalid")
                hashes.add(BookSync.chapterHash(index+1,c.getString("title"),c.getString("contentText")))
            }
            if(lines.readLine()!=null)SyncV2Codec.fail("book_package_invalid")
            val fingerprint=BookSync.fingerprint(hashes)
            val bookId=if(instance.isEmpty())BookSync.id(fingerprint)else SyncV2BodyCodec.hash(SyncV2Codec.lp(SyncV2Codec.utf8("watchrss/book/instance-id/v1"),SyncV2Codec.utf8(fingerprint),SyncV2Codec.utf8(instance)))
            if(fingerprint!=value.getString("fingerprint") || id!=bookId || id!=value.getString("bookId"))SyncV2Codec.fail("book_package_invalid")
        } catch(e:org.json.JSONException){SyncV2Codec.fail("book_package_invalid")}finally{lines.close()}
    }
    /** Call under the account lifetime and projection writer, after full validation.
     * A crash can leave a verified original with an old cache marker; the next round repairs it. */
    fun install(kind: String, id: String, raw: ByteArray, repair: RepairTarget? = null, checkWindow: () -> Unit = {}) {
        check(db.inTransaction())
        val current = file(kind, id)
        val hash = SyncV2BodyCodec.hash(raw)
        val published = if (kind == "asset") NoteAssetFiles.selection(db, id) else null
        val newReference = published?.let {
            NoteAssetFiles.ensureCandidateDirectory(files)
            NoteAssetFiles.replacement(BookPackageFiles.Revision(hash, raw.size.toLong()))
        }
        val target = if (newReference == null) current else NoteAssetFiles.candidate(files, newReference)
        val expected = if (target == current) {
            if (repair == null) hash else repair.expectedSha256
        } else hash
        val bookState=if(kind=="book")db.controlQuery("SELECT stateJson FROM imported_books WHERE bookId=?",arrayOf(id)).use{c->if(!c.moveToFirst())SyncV2Codec.fail("body_revision_changed");JSONObject(c.getString(0))}else null
        if(bookState!=null)validate("book",id,bookState,raw)
        try {
            ConditionalBodyFile.installGuarded(
                target, raw, expected,
                beforeStagingCommit = { beforeInstall(target) },
                beforePublish = {
                    repair?.requireCurrent()
                    checkWindow()
                    repair?.requireSameIdentity()
                },
                privateBook = kind == "book"
            )
        } catch (changed: ConditionalBodyFile.RevisionChanged) {
            throw SyncV2ProtocolError("body_revision_changed", changed)
        }
        if(kind=="asset") {
            if (newReference != null) {
                NoteAssetFiles.publish(db, id, newReference)
            }
            SyncV2NoteAssetNames.recordDownload(db, id, hash, raw.size)
        } else {
            materializeBook(id,raw,bookState!!)
            db.execSQL("UPDATE imported_books SET packageSha256=?,packageBytes=? WHERE bookId=? AND (packageSha256 IS NOT ? OR packageBytes IS NOT ?)",arrayOf<Any>(hash,raw.size,id,hash,raw.size))
        }
    }
    /** Derived chapter bodies use current Room state, never save/reading fields from the carrier. */
    private fun materializeBook(id:String,raw:ByteArray,state:JSONObject) {
        val deleted=state.optJSONArray("deletedChapters")?.let{a->(0 until a.length()).map{a.getInt(it)}.toSet()}?:emptySet()
        val content=com.lightningstudio.watchrss.phone.data.local.FileArticleContentStore(File(files,"imported_text"))
        raw.inputStream().bufferedReader(Charsets.UTF_8).use{lines->val header=JSONObject(lines.readLine()!!)
            repeat(state.getInt("chapterCount")){i->val chapter=JSONObject(lines.readLine()!!);val index=i+1
                val oldId=db.controlQuery("SELECT articleId FROM imported_book_chapters WHERE bookId=? AND chapterIndex=?",arrayOf<Any>(id,index)).use{c->if(c.moveToFirst())c.getString(0)else null}
                    ?:db.controlQuery("SELECT articleId FROM phone_articles WHERE url=? ORDER BY articleId LIMIT 1",arrayOf(chapter.getString("url"))).use{c->if(c.moveToFirst())c.getString(0)else null}
                val article=oldId?:BookSync.articleId(id,index)
                val old=db.controlQuery("SELECT substr(contentText,1,256),length(contentText) FROM phone_articles WHERE articleId=?",arrayOf(article)).use{c->if(c.moveToFirst())c.getString(0) to c.getLong(1)else null}
                val marker=old?.first?.let{com.lightningstudio.watchrss.phone.data.local.articleContentMarkerFileName(it)}
                val missing=old==null || old.second==0L || marker!=null&&!File(files,"imported_text/$marker").isFile
                val row=JSONObject().put("articleId",article)
                if(old==null) {
                    val tombstone=state.optBoolean("deleted") || index in deleted
                    row.put("sourceDeviceId",state.optString("sourceDeviceId")).put("url",chapter.getString("url")).put("title",chapter.getString("title"))
                        .put("siteName",state.optString("title",header.getString("title"))).put("rssSourceUrl",header.getString("sourceUrl")).put("rssSourceTitle",state.optString("title",header.getString("title")))
                        .put("importedAt",1).put("updatedAt",1).put("deleted",tombstone).put("deletedAt",if(tombstone)maxOf(state.optLong("deletedAt"),state.optLong("deletionClock"))else 0)
                    val reading=db.controlQuery("SELECT stateJson FROM imported_book_reading WHERE bookId=? AND chapterIndex=?",arrayOf<Any>(id,index)).use{c->if(c.moveToFirst())JSONObject(c.getString(0))else null}
                    if(reading!=null)for(field in listOf("isRead","readingProgress","readingPositionBytes","readingPositionContentHash","readingPositionChangedAt","favoriteSaved","favoriteChangedAt","favoriteSortOrder","watchLaterSaved","watchLaterChangedAt","watchLaterSortOrder"))if(reading.has(field))row.put(field,reading.get(field))
                }
                if(missing) {
                    val text=chapter.getString("contentText")
                    val textBytes=text.toByteArray(Charsets.UTF_8)
                    val stored=if(text.length>50000) {
                        // Hydration is a cache write: an immutable private marker must not bump the edit witness.
                        val marker=content.markerFor("syncv2-body-${SyncV2BodyCodec.hash(textBytes)}")
                        val target=File(files,"imported_text/${com.lightningstudio.watchrss.phone.data.local.articleContentMarkerFileName(marker)!!}")
                        ConditionalBodyFile.install(target,textBytes)

                        marker
                    }else text
                    val missingHtml=old==null || db.controlQuery("SELECT contentHtml IS NULL OR length(contentHtml)=0 FROM phone_articles WHERE articleId=?",arrayOf(article)).use{it.moveToFirst() && it.getInt(0)!=0}
                    if(missingHtml)row.put("contentHtml",chapter.opt("contentHtml")?:JSONObject.NULL)
                    row.put("contentText",stored).put("contentHash",SyncV2BodyCodec.hash(textBytes))
                        .put("syncBodyHash","").put("syncBodyByteCount",0).put("syncChunkSize",0).put("syncChunkHashesJson","").put("syncMetadataHash","")
                }
                if(old==null || missing)SyncV2PhoneApply(db).upsert("phone_articles",listOf("articleId"),row)
                db.execSQL("INSERT INTO imported_book_chapters(bookId,chapterIndex,articleId) VALUES(?,?,?) ON CONFLICT(bookId,chapterIndex) DO UPDATE SET articleId=excluded.articleId WHERE articleId IS NOT excluded.articleId",arrayOf<Any>(id,index,article))
            }
        }
    }
    companion object {
        private val rawSha = Regex("[0-9a-f]{64}")
        private fun bookUrl(url:String)=Regex("(?i)^https://watchrss\\.local/(?:import-txt-novel(?:/|$)|import-epub(?:/|$)|import-content/txt/)").containsMatchIn(url) ||
            Regex("(?i)watchrss://import/online-v1/[0-9a-f]{64}").matches(url) || Regex("watchrss://import/instance-v1/[a-f0-9]{32}(?:/|$)").find(url)?.range?.first==0
        /** Same literal link/image/HTML mapping as PodJS notes/cloud-assets.ts. */
        private val reference=Regex("""<(?:img|a)\b[^>]*\b(?:src|href)\s*=\s*(?:"assets/([^"<>]+)"|'assets/([^'<>]+)'|assets/([^\s<>]+))[^>]*>|!?\[[^\]]*\]\(assets/([^\s)]+)(?:\s+["'][^"']*["'])?\)""",RegexOption.IGNORE_CASE)
        fun assetKeys(markdown:String):Set<String> = reference.findAll(markdown).mapNotNull{m->
            val id=m.groupValues.drop(1).firstOrNull{it.isNotEmpty()}
            id?.takeIf{Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,254}").matches(it)}
        }.toCollection(linkedSetOf())
        fun noteReferences(value:JSONObject):Set<String> {
            val refs=linkedSetOf<String>()
            for(k in listOf("markdown","baseMarkdown"))value.optString(k).let{refs.addAll(assetKeys(it))}
            value.optJSONObject("noteConflicts")?.optJSONArray("records")?.let{a->for(i in 0 until a.length())for(k in listOf("baseMarkdown","localMarkdown","remoteMarkdown"))refs.addAll(assetKeys(a.getJSONObject(i).optString(k)))}
            return refs
        }
        fun references(db: SupportSQLiteDatabase): Set<String> = SyncV2NoteAssetReferences.keys(db)

    }
}
