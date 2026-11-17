package com.lightningstudio.watchrss.phone

import com.lightningstudio.watchrss.phone.data.note.MarkdownMergeResult
import com.lightningstudio.watchrss.phone.data.note.MarkdownNoteCodec
import com.lightningstudio.watchrss.phone.data.note.MarkdownThreeWayMerge
import com.lightningstudio.watchrss.phone.data.note.NoteRepository
import com.lightningstudio.watchrss.phone.data.note.NoteRepositoryTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteEditorR4MergeTest {
    @Test fun sourceLineDeletionShouldAutoMergeWithAdjacentPeerEdit() = kotlinx.coroutines.runBlocking {
        fun edit(base: String, text: String) = NoteEditorDraft(base).also {
            assertTrue(it.sourceMode)
            it.editSource(text)
        }.storageMarkup()
        val base = "6. prefix\nplain\nend"
        val local = edit(base, "plain\nend")
        val peer = edit(base, "6. prefix\nPLAIN\nend")
        val dao = NoteRepositoryTest.FakeNoteDao()
        var clock = 100L
        val repository = NoteRepository(dao, "local", now = { clock++ })
        val baseNote = repository.save("r3-test", "Explicit", base)
        repository.save(baseNote.noteId, baseNote.title, local, editorSourceMode = true)
        val remote = baseNote.copy(markdown = peer, plainText = MarkdownNoteCodec.toPlainText(peer),
            contentHash = MarkdownNoteCodec.sha256(peer), updatedAt = 200, modifiedBy = "remote")
        val result = repository.applyRemote(remote, "remote")
        val stored = dao.notes.getValue(baseNote.noteId).markdown
        if (result == MarkdownMergeResult.Conflict) assertEquals(local, stored)
        println("r3 adjacent source edit: result=$result localPreserved=${stored == local}")
        assertTrue("Independent source deletion and adjacent edit must auto-merge: $result", result is MarkdownMergeResult.Merged)
        assertEquals("PLAIN\nend", NoteEditorDraft(stored, repository.editorSourceMode(baseNote.noteId)).sourceText)
    }

    @Test fun deletingASourceLineKeepsUntouchedLinesAndLineEndings() {
        val untouched = "plain  \r\n\t末行\r"
        val base = "6. prefix\n$untouched"
        val draft = NoteEditorDraft(base)
        draft.editSource(untouched)
        val stored = draft.storageMarkup()
        assertEquals(untouched, stored)
        assertEquals(untouched, NoteEditorDraft(stored, preferredSourceMode = true).sourceText)
    }

    @Test fun theFormerModeFooterIsAnOrdinaryLineDuringMerge() {
        val base = "plain\nend"
        val literal = "<!--watchrss:source-mode:v2-->"
        val local = "$base\n$literal"
        val remote = "PLAIN\nend"
        val expected = MarkdownMergeResult.Merged("$remote\n$literal")
        assertEquals(expected, MarkdownThreeWayMerge.merge(base, local, remote))
        assertEquals(expected, MarkdownThreeWayMerge.merge(base, remote, local))
    }
}
