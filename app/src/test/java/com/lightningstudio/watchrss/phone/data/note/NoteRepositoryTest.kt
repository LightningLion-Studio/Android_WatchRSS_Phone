package com.lightningstudio.watchrss.phone.data.note

import com.lightningstudio.watchrss.phone.NoteEditorDraft
import com.lightningstudio.watchrss.phone.NoteLosslessSamples
import com.lightningstudio.watchrss.phone.connection.bluetooth.NoteSyncPayload
import org.junit.Assert.assertArrayEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class NoteRepositoryTest {
    @Test fun `derived repository title preserves complete Unicode and shared words`() = runBlocking {
        val repository = NoteRepository(FakeNoteDao(), deviceId = "phone", now = { 123L })
        assertEquals(".".repeat(79) + "😀", repository.save(title = "", markdown = ".".repeat(79) + "😀").title)
        assertEquals("one two three", repository.save(title = "", markdown = "one two three four").title)
        assertEquals("a".repeat(79), repository.save(title = "", markdown = "a".repeat(79) + "e\u0301").title)
    }

    @Test fun `ordinary markdown uses source filename as title`() = runBlocking {
        val dao = FakeNoteDao()
        val repository = NoteRepository(dao, deviceId = "phone", now = { 123L })

        val imported = repository.importMarkdown("正文内容", fallbackTitle = "第一导入")

        assertEquals("第一导入", imported.title)
        assertEquals("正文内容", imported.markdown)
        assertEquals(imported, dao.notes.getValue(imported.noteId))
    }

    @Test fun `watchrss envelope keeps content derived title instead of archive uuid`() = runBlocking {
        val dao = FakeNoteDao()
        val repository = NoteRepository(dao, deviceId = "phone", now = { 123L })
        val envelope = """
            ---
            watchrss-note: 1
            id: stable-id
            ---
            # 原标题
        """.trimIndent()

        val imported = repository.importMarkdown(envelope, fallbackTitle = "stable-id")

        assertEquals("原标题", imported.title)
    }

    @Test fun `duplicate image registration returns canonical stored asset`() = runBlocking {
        val dao = FakeNoteDao()
        val repository = NoteRepository(dao, deviceId = "phone", now = { 123L })
        val canonical = noteAsset("first", "first.jpg")
        val duplicate = noteAsset("second", "second.jpg")

        assertSame(canonical, repository.registerAsset(canonical))
        assertSame(canonical, repository.registerAsset(duplicate))
        assertEquals(listOf(canonical), dao.assets.values.toList())
    }

    @Test fun `management updates preserve content and create sync tombstone`() = runBlocking {
        var instant = 100L
        val dao = FakeNoteDao()
        val repository = NoteRepository(dao, deviceId = "phone", now = { instant })
        val original = repository.save(title = "标题", markdown = "正文")

        instant = 200L
        val moved = repository.move(original.noteId, "folder-1")
        instant = 300L
        val pinned = repository.setPinned(original.noteId, true)
        instant = 400L
        val deleted = repository.delete(original.noteId)

        assertEquals("folder-1", moved.folderId)
        assertEquals(original.markdown, moved.markdown)
        assertEquals(original.baseMarkdown, moved.baseMarkdown)
        assertEquals(true, pinned.pinned)
        assertEquals(true, deleted.deleted)
        assertEquals(400L, deleted.deletedAt)
        assertEquals("phone", deleted.modifiedBy)
    }

    @Test fun `six title pin and sync cycles preserve all source bytes and hashes`() = runBlocking {
        for ((name, body) in NoteLosslessSamples.all) {
            val dao = FakeNoteDao()
            val repository = NoteRepository(dao, deviceId = "phone", now = { 123L })
            var saved = repository.save(title = "手填标题", markdown = body)
            repeat(6) { round ->
                val draft = NoteEditorDraft(saved.markdown)
                saved = repository.save(saved.noteId, "改标题 $round", draft.storageMarkup {
                    error("Metadata-only save exported: $name")
                })
                saved = repository.setPinned(saved.noteId, round % 2 == 0)
                val remote = NoteCloudStateCodec.decode(NoteCloudStateCodec.encode(listOf(saved))).single()
                val bluetooth = NoteSyncPayload.decodeNotes(NoteSyncPayload.manifest("phone", listOf(remote))).single()
                repository.applyRemote(bluetooth, "peer")
                saved = dao.notes.getValue(saved.noteId)
                assertArrayEquals(name, body.toByteArray(), saved.markdown.toByteArray())
                assertArrayEquals(name, body.toByteArray(), saved.baseMarkdown.toByteArray())
                assertEquals(name, MarkdownNoteCodec.sha256(body), saved.contentHash)
                assertEquals(name, MarkdownNoteCodec.sha256(body), saved.baseContentHash)
                assertEquals("改标题 $round", saved.title)
            }
        }
    }

    @Test fun `verbatim user vocabulary survives six unedited open save and sync cycles`() = runBlocking {
        val originalBytes = NoteLosslessSamples.userVocabBytes()
        val body = originalBytes.toString(Charsets.UTF_8)
        assertEquals(1122, originalBytes.size)
        assertEquals("fa21ba9f806652452ee0739d85ca68ab8e2817b08a3ec7033d426efb8ac73e4e",
            MarkdownNoteCodec.sha256(body))
        assertEquals(7, body.count { it == '\n' })
        assertEquals(4, body.lines().count { it.endsWith(" ") })
        assertEquals(true, body.endsWith(" \n"))

        val dao = FakeNoteDao()
        val repository = NoteRepository(dao, deviceId = "phone", now = { 123L })
        var saved = repository.save(title = "用户原文", markdown = body)
        repeat(6) { round ->
            val draft = NoteEditorDraft(saved.markdown)
            assertEquals("Source mode on open $round", true, draft.sourceMode)
            assertEquals(false, draft.bodyEdited)
            val untouched = draft.storageMarkup { error("Unedited user vocabulary exported at $round") }
            assertArrayEquals(originalBytes, untouched.toByteArray(Charsets.UTF_8))
            saved = repository.save(saved.noteId, saved.title, untouched, saved.folderId, saved.pinned)
            assertArrayEquals(originalBytes, saved.markdown.toByteArray(Charsets.UTF_8))
            val wire = org.json.JSONObject(NoteSyncPayload.manifest("phone", listOf(saved)).toString())
            val bluetooth = NoteSyncPayload.decodeNotes(wire).single()
            assertArrayEquals(originalBytes, bluetooth.markdown.toByteArray(Charsets.UTF_8))
            val cloud = NoteCloudStateCodec.decode(NoteCloudStateCodec.encode(listOf(bluetooth))).single()
            assertArrayEquals(originalBytes, cloud.markdown.toByteArray(Charsets.UTF_8))
            repository.applyRemote(cloud, "peer")
            saved = dao.notes.getValue(saved.noteId)
            assertArrayEquals(originalBytes, saved.markdown.toByteArray(Charsets.UTF_8))
            assertArrayEquals(originalBytes, saved.baseMarkdown.toByteArray(Charsets.UTF_8))
            assertEquals("用户原文", saved.title)
            assertEquals(true, saved.markdown.contains("postpone3."))
            assertEquals(true, saved.markdown.contains("container11."))
            val secondBlock = saved.markdown.split("\n\n")[1]
            val markers = Regex("""(?<!\d)(\d+)\.""").findAll(secondBlock)
                .map { it.groupValues[1].toInt() }.toList()
            assertEquals("Literal 1..27 markers after round $round", (1..27).toList(), markers)
        }
    }

    private fun noteAsset(id: String, key: String) = NoteAssetEntity(
        assetId = id,
        noteId = "note",
        sha256 = "same-hash",
        displayName = key,
        mimeType = "image/jpeg",
        byteCount = 10,
        storageKey = key,
        isOriginal = false,
        createdAt = 1L
    )

    internal class FakeNoteDao : NoteDao {
        val editorModes = mutableMapOf<String, Pair<String, Boolean>>()
        override suspend fun editorSourceMode(noteId: String, installationId: String): Boolean? =
            editorModes[noteId]?.takeIf { it.first == installationId }?.second
        override suspend fun writeEditorSourceMode(noteId: String, installationId: String, sourceMode: Boolean) {
            editorModes[noteId] = installationId to sourceMode
        }
        override suspend fun clearEditorPreferences() = editorModes.clear()

        val notes = linkedMapOf<String, NoteEntity>()
        val assets = linkedMapOf<String, NoteAssetEntity>()
        private val receipts = mutableSetOf<String>()
        override suspend fun hasRemoteReceipt(hash: String) = hash in receipts
        override suspend fun insertRemoteReceipt(receipt: NoteRemoteReceiptEntity) { receipts.add(receipt.snapshotHash) }
        override suspend fun clearRemoteReceipts() { receipts.clear() }
        override suspend fun allConflicts() = emptyList<NoteConflictEntity>()
        override suspend fun conflictsFor(noteId: String) = emptyList<NoteConflictEntity>()
        override suspend fun hasAny() = notes.isNotEmpty()
        private val noteFlow = MutableStateFlow<List<NoteEntity>>(emptyList())

        override suspend fun clearConflicts() = Unit
        override suspend fun clearAssets() { assets.clear() }
        override suspend fun clearNotes() { notes.clear(); noteFlow.value = emptyList() }
        override suspend fun clearFolders() = Unit
        override fun observeFolders(): Flow<List<NoteFolderEntity>> = MutableStateFlow(emptyList())
        override fun observeNotes(): Flow<List<NoteEntity>> = noteFlow
        override suspend fun note(noteId: String): NoteEntity? = notes[noteId]
        override suspend fun search(query: String): List<NoteEntity> = notes.values.filter {
            it.title.contains(query) || it.plainText.contains(query)
        }
        override suspend fun allNotes(): List<NoteEntity> = notes.values.toList()
        override suspend fun assets(noteId: String): List<NoteAssetEntity> =
            assets.values.filter { it.noteId == noteId && !it.deleted }
        override suspend fun assetByHash(sha256: String): NoteAssetEntity? =
            assets.values.firstOrNull { it.sha256 == sha256 && !it.deleted }
        override fun observeUnresolvedConflicts(): Flow<List<NoteConflictEntity>> = MutableStateFlow(emptyList())
        override suspend fun upsertFolders(items: List<NoteFolderEntity>) = Unit
        override suspend fun upsertNotes(items: List<NoteEntity>) {
            items.forEach { notes[it.noteId] = it }
            noteFlow.value = notes.values.toList()
        }
        override suspend fun upsertAssets(items: List<NoteAssetEntity>) {
            items.forEach { assets[it.assetId] = it }
        }
        override suspend fun upsertConflicts(items: List<NoteConflictEntity>) = Unit
        override suspend fun resolveConflict(conflictId: String, resolvedAt: Long) = Unit
    }
}
