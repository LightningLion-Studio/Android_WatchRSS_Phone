package com.lightningstudio.watchrss.phone.data.note

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class NoteRepositoryTest {
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

    private class FakeNoteDao : NoteDao {
        val notes = linkedMapOf<String, NoteEntity>()
        val assets = linkedMapOf<String, NoteAssetEntity>()
        private val noteFlow = MutableStateFlow<List<NoteEntity>>(emptyList())

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
